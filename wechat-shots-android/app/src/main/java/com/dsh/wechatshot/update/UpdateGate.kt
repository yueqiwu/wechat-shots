package com.dsh.wechatshot.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.dsh.wechatshot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 启动时检查新版（GET https://qipaclub.top/wechat-shots-app/version.json），
 * 有新版则弹窗提示并下载 APK 安装。
 *
 * 契约（由 UI 组在主界面根部调用一次 `UpdateGate()`，无需参数）。
 *
 * 行为约定：
 *  - version.json 请求 5 秒超时，任何失败都静默忽略，不打扰用户；
 *  - 只有 versionCode 大于当前 BuildConfig.VERSION_CODE 才弹窗；
 *  - APK 下载到 cacheDir/update/update.apk，通过 FileProvider 交给系统安装器；
 *  - 未允许「安装未知来源应用」时引导用户去系统设置授权。
 */
@Composable
fun UpdateGate() {
    UpdateGateImpl()
}

private const val TAG = "UpdateGate"
private const val VERSION_URL = "https://qipaclub.top/wechat-shots-app/version.json"
private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 5_000

/** 进程内只自动检查一次，避免配置变更/重组时重复弹窗。 */
@Volatile
private var checkedThisProcess = false

private data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: List<String>,
    val size: Long,
)

private sealed interface UpdateUiState {
    data class Prompt(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val progress: Float, val downloaded: Long, val total: Long) : UpdateUiState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateUiState
    data class Failed(val info: UpdateInfo, val message: String) : UpdateUiState
}

@Composable
private fun UpdateGateImpl() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<UpdateUiState?>(null) }

    LaunchedEffect(Unit) {
        if (checkedThisProcess) return@LaunchedEffect
        checkedThisProcess = true
        val remote = withContext(Dispatchers.IO) { fetchVersionInfo() } ?: return@LaunchedEffect
        if (remote.versionCode > BuildConfig.VERSION_CODE) {
            Log.i(TAG, "发现新版本 ${remote.versionName}(${remote.versionCode})，当前 ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            ui = UpdateUiState.Prompt(remote)
        }
    }

    val current = ui ?: return

    val info = when (current) {
        is UpdateUiState.Prompt -> current.info
        is UpdateUiState.Downloading -> current.info
        is UpdateUiState.Ready -> current.info
        is UpdateUiState.Failed -> current.info
    }

    val onDismiss: () -> Unit = { ui = null }

    val startDownload: () -> Unit = {
        ui = UpdateUiState.Downloading(info, 0f, 0L, info.size)
        scope.launch {
            try {
                val file = downloadApk(context, info) { progress, done, total ->
                    ui = UpdateUiState.Downloading(info, progress, done, total)
                }
                ui = UpdateUiState.Ready(info, file)
            } catch (e: Exception) {
                Log.w(TAG, "APK 下载失败", e)
                ui = UpdateUiState.Failed(info, e.message ?: "网络异常")
            }
        }
    }

    // 下载完成自动拉起系统安装器（若已允许未知来源；否则引导去设置）
    val ready = current as? UpdateUiState.Ready
    LaunchedEffect(ready?.file) {
        if (ready != null) {
            installApk(context, ready.file)
        }
    }

    UpdateDialog(
        state = current,
        onDismiss = onDismiss,
        onDownload = startDownload,
        onInstall = {
            val f = (ui as? UpdateUiState.Ready)?.file
            if (f != null) installApk(context, f) else startDownload()
        },
        onRetry = startDownload,
        onOpenUnknownSources = {
            openUnknownSourcesSettings(context)
        },
    )
}

// ---------------------------------------------------------------------------
// 网络：版本检查与 APK 下载
// ---------------------------------------------------------------------------

private fun fetchVersionInfo(): UpdateInfo? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }
        if (conn.responseCode != 200) {
            Log.d(TAG, "version.json HTTP ${conn.responseCode}")
            return null
        }
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val obj = JSONObject(text)
        val code = obj.optInt("versionCode", 0)
        val name = obj.optString("versionName", "")
        val apk = obj.optString("apk", "")
        if (code <= 0 || name.isBlank() || apk.isBlank()) {
            Log.d(TAG, "version.json 字段不完整")
            return null
        }
        // notes 可能是真正的换行，也可能是命令行里传进来的字面 "\n"/"\r\n"，都兼容
        val notes = obj.optString("notes", "")
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .split('\n', '\r')
            .map { it.trim().removePrefix("- ").removePrefix("• ").removePrefix("* ").trim() }
            .filter { it.isNotEmpty() }
        UpdateInfo(
            versionCode = code,
            versionName = name,
            apkUrl = apk,
            notes = notes,
            size = obj.optLong("size", 0L),
        )
    } catch (e: Exception) {
        Log.d(TAG, "检查更新失败（静默）: ${e.message}")
        null
    } finally {
        conn?.disconnect()
    }
}

private suspend fun downloadApk(
    context: Context,
    info: UpdateInfo,
    onProgress: (Float, Long, Long) -> Unit,
): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "update").apply { mkdirs() }
    val target = File(dir, "update.apk")
    var conn: HttpURLConnection? = null
    try {
        conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        if (conn.responseCode != 200) throw IOException("下载失败 HTTP ${conn.responseCode}")
        val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
        conn.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        val progress = if (pct >= 0) pct / 100f else 0f
                        withContext(Dispatchers.Main) { onProgress(progress, done, total) }
                    }
                }
                output.flush()
            }
        }
        if (info.size > 0 && target.length() != info.size) {
            throw IOException("安装包大小不一致：${target.length()} / ${info.size}")
        }
        Log.i(TAG, "APK 下载完成: ${target.absolutePath} (${target.length()} bytes)")
        target
    } finally {
        conn?.disconnect()
    }
}

// ---------------------------------------------------------------------------
// 安装
// ---------------------------------------------------------------------------

/** 拉起系统安装器；未授权「安装未知来源」时跳设置页。返回是否已拉起安装器。 */
private fun installApk(context: Context, file: File): Boolean {
    return try {
        if (!context.packageManager.canRequestPackageInstalls()) {
            openUnknownSourcesSettings(context)
            return false
        }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "拉起安装器失败", e)
        false
    }
}

private fun openUnknownSourcesSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w(TAG, "打开未知来源设置失败", e)
    }
}

// ---------------------------------------------------------------------------
// UI（iOS 风格卡片弹窗，不依赖 Material 主题色）
// ---------------------------------------------------------------------------

@Composable
private fun UpdateDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onOpenUnknownSources: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val surface = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val titleColor = if (dark) Color(0xFFF2F2F7) else Color(0xFF1C1C1E)
    val bodyColor = if (dark) Color(0xFFAEAEB2) else Color(0xFF6E6E73)
    val trackColor = if (dark) Color(0xFF2C2C2E) else Color(0xFFEEF0F3)
    val accentStart = Color(0xFF34C759)
    val accentEnd = Color(0xFF0A84FF)
    val gradient = Brush.horizontalGradient(listOf(accentStart, accentEnd))

    val info = when (state) {
        is UpdateUiState.Prompt -> state.info
        is UpdateUiState.Downloading -> state.info
        is UpdateUiState.Ready -> state.info
        is UpdateUiState.Failed -> state.info
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(surface)
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 图标
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(gradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state is UpdateUiState.Ready) "✓" else "↑",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = when (state) {
                        is UpdateUiState.Ready -> "下载完成"
                        is UpdateUiState.Failed -> "更新失败"
                        else -> "发现新版本"
                    },
                    color = titleColor,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "v${info.versionName}  ·  当前 v${BuildConfig.VERSION_NAME}",
                    color = bodyColor,
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(18.dp))

                // 更新说明
                if (info.notes.isNotEmpty() && state is UpdateUiState.Prompt) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 168.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        info.notes.forEach { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 7.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(accentStart),
                                )
                                Spacer(Modifier.size(9.dp))
                                Text(
                                    text = line,
                                    color = bodyColor,
                                    fontSize = 13.5.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                when (state) {
                    is UpdateUiState.Downloading -> {
                        DownloadProgressBlock(state, gradient, trackColor, bodyColor)
                        Spacer(Modifier.height(18.dp))
                        SecondaryButton("后台下载", enabled = false, color = bodyColor, onClick = {})
                        Spacer(Modifier.height(8.dp))
                        TextButtonLink("取消", bodyColor, onDismiss)
                    }

                    is UpdateUiState.Failed -> {
                        Text(
                            text = "下载失败：${state.message}\n请检查网络后重试",
                            color = Color(0xFFFF453A),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(18.dp))
                        PrimaryButton("重试", gradient, onRetry)
                        Spacer(Modifier.height(10.dp))
                        TextButtonLink("稍后", bodyColor, onDismiss)
                    }

                    is UpdateUiState.Ready -> {
                        Text(
                            text = "安装包 ${fmtSize(info.size)} 已下载完成。\n如果安装被系统拦截，请先允许「安装未知来源应用」。",
                            color = bodyColor,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(18.dp))
                        PrimaryButton("安装更新", gradient, onInstall)
                        Spacer(Modifier.height(10.dp))
                        TextButtonLink("允许安装未知来源", bodyColor, onOpenUnknownSources)
                        Spacer(Modifier.height(4.dp))
                        TextButtonLink("稍后", bodyColor, onDismiss)
                    }

                    is UpdateUiState.Prompt -> {
                        PrimaryButton("立即更新", gradient, onDownload)
                        Spacer(Modifier.height(10.dp))
                        TextButtonLink("稍后再说", bodyColor, onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressBlock(
    state: UpdateUiState.Downloading,
    gradient: Brush,
    trackColor: Color,
    bodyColor: Color,
) {
    val ratio = state.progress.coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(trackColor),
        ) {
            if (ratio > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(gradient),
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = buildString {
                append("正在下载 ")
                append((ratio * 100).toInt())
                append("%")
                if (state.total > 0) {
                    append("  ·  ")
                    append(fmtSize(state.downloaded))
                    append(" / ")
                    append(fmtSize(state.total))
                }
            },
            color = bodyColor,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PrimaryButton(text: String, gradient: Brush, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(gradient)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) color else color.copy(alpha = 0.45f), fontSize = 14.sp)
    }
}

@Composable
private fun TextButtonLink(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        textAlign = TextAlign.Center,
    )
}

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / 1048576.0
    return String.format(Locale.CHINA, "%.1f MB", mb)
}
