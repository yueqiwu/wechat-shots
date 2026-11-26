package com.dsh.wechatshot.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dsh.wechatshot.ui.Hairline
import com.dsh.wechatshot.ui.IosBanner
import com.dsh.wechatshot.ui.IosBannerKind
import com.dsh.wechatshot.ui.IosCard
import com.dsh.wechatshot.ui.IosFooterNote
import com.dsh.wechatshot.ui.IosGradientBackground
import com.dsh.wechatshot.ui.IosLargeTitle
import com.dsh.wechatshot.ui.IosNavBar
import com.dsh.wechatshot.ui.IosPrimaryButton
import com.dsh.wechatshot.ui.IosRow
import com.dsh.wechatshot.ui.IosSecondaryButton
import com.dsh.wechatshot.ui.IosSectionHeader
import com.dsh.wechatshot.ui.IosSegmentedControl
import com.dsh.wechatshot.ui.IosStatTile
import com.dsh.wechatshot.ui.IosSwitchRow
import com.dsh.wechatshot.ui.IosTextButton
import com.dsh.wechatshot.ui.IosType
import com.dsh.wechatshot.ui.LocalIosColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 方案二「录屏转截图」：选录屏 → 分析滚动位移 → 重建长图 → 切成互不重叠的连续聊天截图。
 *
 * 整条流程（选择、处理进度、结果、保存/分享）都在本 Composable 内完成，只通过 [onBack] 返回上一级。
 * 签名保持不变（UI 组在 AppRoot 里直接调用）；调试入口见 debug 源集里的 VideoDebugActivity。
 * 视觉统一走 com.dsh.wechatshot.ui 里的 iOS 组件，自带状态栏留白，宿主不要再额外加 insets。
 */

/** 调试入口 intent extra：视频绝对路径或 content:// uri（仅 debug 包生效）。 */
private const val EXTRA_VIDEO_PATH = "dsh_video_path"

private sealed interface Stage {
    data object Idle : Stage
    data object Processing : Stage
    data class Done(val result: VideoPipeline.Result) : Stage
    data class Error(val message: String) : Stage
}

@Composable
fun VideoExportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val outputRoot = remember(appContext) {
        appContext.getExternalFilesDir(null) ?: appContext.filesDir
    }

    var stage by remember { mutableStateOf<Stage>(Stage.Idle) }
    var autoCrop by remember { mutableStateOf(true) }
    var tileFactor by remember { mutableStateOf(1.0) }
    var lastSource by remember { mutableStateOf<VideoPipeline.VideoSource?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var startedAt by remember { mutableStateOf(0L) }
    var elapsedSec by remember { mutableStateOf(0.0) }
    var cancelling by remember { mutableStateOf(false) }

    val progressFlow = remember { MutableStateFlow(VideoPipeline.StageProgress(0f, "准备中")) }
    val progress by progressFlow.collectAsState()

    // 处理中每 100ms 刷新一次计时器
    val processing = stage is Stage.Processing
    LaunchedEffect(processing) {
        while (processing) {
            elapsedSec = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            delay(100)
        }
    }

    val start: (VideoPipeline.VideoSource) -> Unit = { source ->
        if (stage is Stage.Processing) {
            // 已经在跑，忽略重复触发
        } else {
            job?.cancel()
            lastSource = source
            cancelling = false
            startedAt = SystemClock.elapsedRealtime()
            elapsedSec = 0.0
            progressFlow.value = VideoPipeline.StageProgress(0f, "准备中")
            stage = Stage.Processing
            job = scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        VideoPipeline.export(
                            context = appContext,
                            source = source,
                            options = VideoPipeline.Options(
                                autoCrop = autoCrop,
                                tileFactor = tileFactor,
                                outputRoot = outputRoot,
                            ),
                        ) { p ->
                            progressFlow.value = p
                        }
                    }
                    stage = Stage.Done(result)
                } catch (ce: CancellationException) {
                    stage = Stage.Idle
                } catch (e: Exception) {
                    Log.e(VideoPipeline.TAG, "UI export failed", e)
                    stage = Stage.Error(e.message ?: "导出失败，请重试")
                } finally {
                    cancelling = false
                }
            }
        }
    }

    val cancel: () -> Unit = {
        if (stage is Stage.Processing && !cancelling) {
            cancelling = true
            job?.cancel()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // 有些 provider 不支持持久授权，本次会话内仍然可读
            }
            start(VideoPipeline.VideoSource.Content(uri, uri.lastPathSegment ?: "录屏视频"))
        }
    }

    // debug 包：允许 adb 通过 intent extra 直接带一个视频路径跑完整流程（自动化冒烟用）
    LaunchedEffect(Unit) {
        if (isDebuggable(appContext)) {
            val path = debugAutoPath(context)
            if (!path.isNullOrBlank()) {
                Log.i(VideoPipeline.TAG, "DEBUG auto start: $path")
                start(sourceFromPath(path))
            }
        }
    }

    BackHandler(enabled = stage !is Stage.Idle) {
        when (stage) {
            is Stage.Processing -> cancel()
            else -> stage = Stage.Idle
        }
    }

    IosGradientBackground(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            IosNavBar(
                title = when (stage) {
                    is Stage.Idle -> null
                    is Stage.Processing -> "正在生成截图"
                    is Stage.Done -> "生成完成"
                    is Stage.Error -> "出错了"
                },
                onBack = { if (stage is Stage.Processing) cancel() else onBack() },
            )
            Box(modifier = Modifier.weight(1f)) {
                when (val s = stage) {
                    is Stage.Idle -> IdleContent(
                        autoCrop = autoCrop,
                        tileFactor = tileFactor,
                        onAutoCropChange = { autoCrop = it },
                        onTileFactorChange = { tileFactor = it },
                        onPick = { picker.launch(arrayOf("video/*")) },
                    )

                    is Stage.Processing -> ProcessingContent(
                        progress = progress,
                        elapsedSec = elapsedSec,
                        cancelling = cancelling,
                        onCancel = cancel,
                    )

                    is Stage.Done -> DoneContent(
                        result = s.result,
                        onRestart = { lastSource?.let { src -> start(src) } ?: run { stage = Stage.Idle } },
                        onPickOther = { stage = Stage.Idle },
                        onBack = onBack,
                    )

                    is Stage.Error -> ErrorContent(
                        message = s.message,
                        canRetry = lastSource != null,
                        onRetry = { lastSource?.let { src -> start(src) } ?: run { stage = Stage.Idle } },
                        onPick = { stage = Stage.Idle },
                        onBack = onBack,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// 各状态页面
// ------------------------------------------------------------------

@Composable
private fun IdleContent(
    autoCrop: Boolean,
    tileFactor: Double,
    onAutoCropChange: (Boolean) -> Unit,
    onTileFactorChange: (Double) -> Unit,
    onPick: () -> Unit,
) {
    val colors = LocalIosColors.current
    val factors = listOf(1.0, 0.8, 1.2)
    val selected = factors.indexOfFirst { abs(it - tileFactor) < 0.01 }.coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosLargeTitle(
            title = "录屏转截图",
            subtitle = "上传手机屏幕录制，自动重建长图并切成连续截图",
        )
        Spacer(Modifier.height(2.dp))

        IosCard(contentPadding = PaddingValues(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("三步生成", style = IosType.headline, color = colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "完全在本机处理，视频不会上传",
                        style = IosType.footnote,
                        color = colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            StepLine("1", "选择一段微信聊天向上滚动的录屏")
            StepLine("2", "自动分析滚动位移、重建完整长图")
            StepLine("3", "切成互不重叠的连续截图页，可导出 ZIP")
        }

        Spacer(Modifier.height(2.dp))
        IosSectionHeader("导出设置")
        IosCard {
            IosSwitchRow(
                title = "自动裁掉状态栏 / 输入栏",
                subtitle = "只保留聊天内容区",
                checked = autoCrop,
                onCheckedChange = onAutoCropChange,
            )
            Hairline()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("每页高度", style = IosType.body, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                IosSegmentedControl(
                    options = listOf("标准", "更短", "更长"),
                    selected = selected,
                    onSelected = { onTileFactorChange(factors[it]) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        IosPrimaryButton(
            text = "选择录屏视频",
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Filled.Movie,
            onClick = onPick,
        )
        IosFooterNote(
            "视频只在本机处理，不会上传。1 分钟以内的录屏通常 1~2 分钟完成。",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StepLine(index: String, text: String) {
    val colors = LocalIosColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                index,
                style = IosType.caption.copy(fontWeight = FontWeight.Bold),
                color = colors.accent,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = IosType.callout, color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ProcessingContent(
    progress: VideoPipeline.StageProgress,
    elapsedSec: Double,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    val colors = LocalIosColors.current
    val shown by animateFloatAsState(
        targetValue = progress.fraction,
        animationSpec = tween(durationMillis = 350, easing = LinearEasing),
        label = "progress",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosCard(contentPadding = PaddingValues(22.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 4.dp,
                    color = colors.accent,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    if (cancelling) "正在取消…" else progress.stage,
                    style = IosType.headline,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (cancelling) "马上就好" else progress.detail,
                    style = IosType.footnote,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = colors.accent,
                    trackColor = colors.fill,
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${(shown * 100).toInt()}%",
                        style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.accent,
                    )
                    Text(
                        "已用时 ${formatSeconds(elapsedSec)}",
                        style = IosType.footnote,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        IosCard(contentPadding = PaddingValues(16.dp)) {
            Text("处理完全在本机进行", style = IosType.footnote.copy(fontWeight = FontWeight.Medium), color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "会逐帧分析录屏的滚动位移、重建长图再切页。页数多时需要几分钟，请保持屏幕常亮。",
                style = IosType.footnote,
                color = colors.textSecondary,
            )
        }

        Spacer(Modifier.height(4.dp))
        IosSecondaryButton(
            text = if (cancelling) "正在取消…" else "取消",
            modifier = Modifier.fillMaxWidth(),
            enabled = !cancelling,
            onClick = onCancel,
        )
    }
}

@Composable
private fun DoneContent(
    result: VideoPipeline.Result,
    onRestart: () -> Unit,
    onPickOther: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalIosColors.current
    var previews by remember(result) { mutableStateOf<List<Bitmap>>(emptyList()) }
    LaunchedEffect(result) {
        previews = withContext(Dispatchers.IO) {
            result.pageFiles.take(2).mapNotNull { file ->
                try {
                    decodeSampled(file, 900)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosCard(contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "已生成 ${result.pages} 张截图",
                    style = IosType.cardTitle,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "内容总高 ${result.masterH}px · 耗时 ${VideoMath.formatElapsed(result.elapsedMs)}",
                    style = IosType.footnote,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IosStatTile("页数", "${result.pages}", Modifier.weight(1f))
                    IosStatTile("每页", "${result.pageWidth}×${result.cropHpx}", Modifier.weight(1f))
                    IosStatTile("分析帧", "${result.analysisFrames}", Modifier.weight(1f))
                }
            }
        }

        if (previews.isNotEmpty()) {
            IosCard(contentPadding = PaddingValues(16.dp)) {
                Text("预览", style = IosType.headline, color = colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    "前 2 页，从上到下连续、互不重叠",
                    style = IosType.footnote,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    previews.forEach { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "截图预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(210.dp)
                                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                                .clip(RoundedCornerShape(8.dp))
                                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }

        IosCard {
            IosRow(
                title = "保存位置",
                subtitle = result.outDir.absolutePath,
                icon = null,
            )
            Hairline()
            IosRow(
                title = "PNG + ZIP",
                subtitle = "${result.pages} 个 PNG · ZIP ${VideoMath.formatBytes(result.zipFile.length())}",
            )
            Hairline()
            IosRow(
                title = "裁剪范围",
                subtitle = "top ${result.cropTopPx}px / bottom ${result.cropBotPx}px（原视频 ${result.videoWidth}×${result.videoHeight}）",
            )
            Hairline()
            IosRow(
                title = "分析情况",
                subtitle = "${result.analysisFrames} 帧" +
                    (if (result.refinedFrames > 0) "（补采 ${result.refinedFrames} 帧）" else "") +
                    " · 对齐失败 ${result.badPairs} 处",
            )
        }

        if (result.missingPx > 0) {
            IosBanner(
                text = "有约 ${result.missingPx}px 内容缺少画面（滚动太快），已用相邻帧补齐，可能出现少量重复或错位。",
                kind = IosBannerKind.Warning,
            )
        }

        Spacer(Modifier.height(4.dp))
        IosPrimaryButton(
            text = "分享 ZIP",
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Filled.Share,
            onClick = { shareResult(context, result) },
        )
        IosSecondaryButton(
            text = "重新导出这段录屏",
            modifier = Modifier.fillMaxWidth(),
            onClick = onRestart,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IosTextButton("选择其他视频", onClick = onPickOther)
            IosTextButton("返回", onClick = onBack)
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onPick: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalIosColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosCard(contentPadding = PaddingValues(22.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = colors.red,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("没能生成截图", style = IosType.headline, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = IosType.callout,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        if (canRetry) {
            IosPrimaryButton(
                text = "重试",
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Refresh,
                onClick = onRetry,
            )
        }
        IosSecondaryButton(
            text = "重新选择视频",
            modifier = Modifier.fillMaxWidth(),
            onClick = onPick,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IosTextButton("返回", onClick = onBack)
        }
    }
}

// ------------------------------------------------------------------
// 工具函数
// ------------------------------------------------------------------

private fun formatSeconds(sec: Double): String =
    String.format(Locale.US, "%.1f 秒", if (sec.isFinite()) sec else 0.0)

private fun isDebuggable(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** 从宿主 Activity 的 intent 里取出调试用的视频路径（只取一次）。 */
private fun debugAutoPath(context: Context): String? {
    val activity = context.findActivity() ?: return null
    val intent = activity.intent ?: return null
    val path = intent.getStringExtra(EXTRA_VIDEO_PATH) ?: return null
    intent.removeExtra(EXTRA_VIDEO_PATH)
    return path
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun sourceFromPath(raw: String): VideoPipeline.VideoSource {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("content://") || trimmed.startsWith("file://") ->
            VideoPipeline.VideoSource.Content(Uri.parse(trimmed))
        else -> VideoPipeline.VideoSource.LocalPath(trimmed)
    }
}

private fun decodeSampled(file: File, targetHeight: Int): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

private fun shareResult(context: Context, result: VideoPipeline.Result) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            VideoPipeline.FILE_PROVIDER_AUTHORITY,
            result.zipFile,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "聊天截图（${result.pages} 张）")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享聊天截图"))
    } catch (e: Exception) {
        Log.e(VideoPipeline.TAG, "share failed", e)
        Toast.makeText(context, "分享失败：${e.message ?: "无法打开分享面板"}", Toast.LENGTH_SHORT).show()
    }
}
