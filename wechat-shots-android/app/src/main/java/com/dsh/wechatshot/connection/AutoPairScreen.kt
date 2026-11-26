package com.dsh.wechatshot.connection

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.dsh.wechatshot.adb.AdbManager
import com.dsh.wechatshot.adb.PairingCodeParser
import com.dsh.wechatshot.adb.ScreenshotPairWatcher
import com.dsh.wechatshot.automation.OcrEngine
import com.dsh.wechatshot.ui.Hairline
import com.dsh.wechatshot.ui.IosBanner
import com.dsh.wechatshot.ui.IosBannerKind
import com.dsh.wechatshot.ui.IosCard
import com.dsh.wechatshot.ui.IosCodeBlock
import com.dsh.wechatshot.ui.IosFooterNote
import com.dsh.wechatshot.ui.IosLargeTitle
import com.dsh.wechatshot.ui.IosNavBar
import com.dsh.wechatshot.ui.IosPage
import com.dsh.wechatshot.ui.IosPrimaryButton
import com.dsh.wechatshot.ui.IosProgressBar
import com.dsh.wechatshot.ui.IosSecondaryButton
import com.dsh.wechatshot.ui.IosSectionHeader
import com.dsh.wechatshot.ui.IosTagChip
import com.dsh.wechatshot.ui.IosTextButton
import com.dsh.wechatshot.ui.IosTextField
import com.dsh.wechatshot.ui.IosType
import com.dsh.wechatshot.ui.LocalIosColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自动配对页：用户按「电源键 + 音量下」截一张「使用配对码配对设备」弹窗的图，
 * 页面监听系统相册里新增的截图 -> ML Kit OCR -> [PairingCodeParser] 解析出 6 位配对码和端口
 * -> [AdbManager.pair] 完成 adb pair + TLS 连接 -> [onConnected]。
 *
 * 关于 Android 版本：
 * - Android 11（API 30）起才有「无线调试 / 配对码」这套机制；
 * - Android 14（API 34）起系统只提供「截屏通知」回调（Activity.ScreenCaptureCallback，拿不到图），
 *   所以真正取图的主路径是 MediaStore 截图目录监听 + 回到前台时的主动轮询，任何版本都一样；
 * - 页面同时提供手动输入配对码/端口作为兜底（例如相册权限被拒绝、截图目录不标准时）。
 */
@Composable
fun AutoPairScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalIosColors.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    val isDebugBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val appLabel = remember(context) {
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }
            .getOrDefault("本应用")
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val conn by AdbManager.connection.collectAsState()
    val discovery by AdbManager.pairing.collectAsState()

    var stage by remember {
        mutableStateOf(
            if (ScreenshotPairWatcher.hasPermission(context)) PairStage.WaitingScreenshot
            else PairStage.Idle,
        )
    }
    var headline by remember { mutableStateOf("等待截图") }
    var statusText by remember {
        mutableStateOf("先按下面 4 步打开配对弹窗并截屏，然后切回本 App，这里会自动识别。")
    }
    var detailText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var parsed by remember { mutableStateOf<PairingCodeParser.Result?>(null) }
    var ocrText by remember { mutableStateOf("") }
    var showOcr by remember { mutableStateOf(false) }
    var debugLog by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(ScreenshotPairWatcher.hasPermission(context)) }
    var permissionDeniedOnce by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }
    var manualHint by remember { mutableStateOf("") }
    var pairStarted by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    var selfTestMode by remember { mutableStateOf(false) }

    // -----------------------------------------------------------------------------------
    // 基础工具
    // -----------------------------------------------------------------------------------

    fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        debugLog = ("[$time] $message\n" + debugLog).take(5_000)
        Log.d(TAG, message)
    }

    fun finishSuccess() {
        if (finished) return
        finished = true
        stage = PairStage.Done
        headline = "已连接"
        statusText = "无线 ADB 连接成功，正在进入下一步…"
        detailText = ""
        onConnected()
    }

    // -----------------------------------------------------------------------------------
    // 本地网络访问权限（Android 17 / API 36 起新增，无线 ADB 的 mDNS / TLS / 配对都依赖它）
    // -----------------------------------------------------------------------------------

    fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 36 ||
            context.checkSelfPermission(AdbManager.ACCESS_LOCAL_NETWORK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    var localNetGranted by remember { mutableStateOf(hasLocalNetworkPermission()) }
    var localNetDenied by remember { mutableStateOf(false) }
    // 用户点「开始配对」时若缺权限，先请求，授权成功后继续刚才的动作
    var pendingLocalNetAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val localNetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetGranted = granted
        localNetDenied = !granted
        if (granted) {
            log("已获得「本地网络访问」权限")
            if (stage == PairStage.WaitingScreenshot) {
                statusText = "已获得本地网络访问权限，可以开始配对。"
            }
            pendingLocalNetAction?.invoke()
        } else {
            statusText = "没有「本地网络访问」权限：Android 17 会拦截无线 ADB 的发现和连接。"
        }
        pendingLocalNetAction = null
    }

    /** 需要时请求本地网络权限；已授权则立刻执行 [onReady]，否则授权成功后执行。 */
    fun withLocalNetworkPermission(onReady: () -> Unit) {
        when {
            Build.VERSION.SDK_INT < 36 -> onReady()
            hasLocalNetworkPermission() -> {
                localNetGranted = true
                onReady()
            }
            else -> {
                localNetGranted = false
                pendingLocalNetAction = onReady
                statusText = "无线 ADB 需要「本地网络访问」权限，请在系统弹窗里点允许…"
                localNetLauncher.launch(AdbManager.ACCESS_LOCAL_NETWORK_PERMISSION)
            }
        }
    }

    // 进入页面时先申请（Android 17+）；拒绝后不再自动弹，只显示横幅让用户手动点
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 36 && !hasLocalNetworkPermission() && !localNetDenied) {
            delay(500L)
            localNetLauncher.launch(AdbManager.ACCESS_LOCAL_NETWORK_PERMISSION)
        }
    }

    // -----------------------------------------------------------------------------------
    // OCR + 解析 + 配对
    // -----------------------------------------------------------------------------------

    suspend fun beginPair(code: String, portText: String?, host: String?, connectPort: Int? = null) {
        if (finished) return
        if (AdbManager.connection.value.busy) {
            statusText = "上一次连接还在进行中，请稍候…"
            return
        }
        // Android 17 起，没「本地网络访问」权限时 socket 会被系统直接拦截，先确保授权
        if (Build.VERSION.SDK_INT >= 36 && !hasLocalNetworkPermission()) {
            headline = "需要本地网络权限"
            statusText = "Android 17 的无线 ADB 需要「本地网络访问」权限，授予后会自动继续配对…"
            withLocalNetworkPermission { scope.launch { beginPair(code, portText, host, connectPort) } }
            return
        }
        pairStarted = true
        manualCode = code
        stage = PairStage.Pairing
        headline = "正在配对"
        detailText = ""

        if (portText.isNullOrBlank()) {
            statusText = "已识别配对码 $code，正在通过 mDNS 自动发现配对端口…"
            AdbManager.startPairingDiscovery()
            var discoveredPort = 0
            var attempts = 0
            while (discoveredPort <= 0 && attempts < 12 && !finished) {
                delay(600L)
                attempts++
                discoveredPort = AdbManager.pairing.value.port
            }
            if (discoveredPort <= 0) {
                AdbManager.stopPairingDiscovery()
                stage = PairStage.Recognized
                headline = "还差一个配对端口"
                statusText = "识别到配对码 $code，但没有发现配对端口"
                detailText = "请把弹窗里的「IP 地址和端口」一起截进图里；" +
                    "或在手动配对里填写端口后点「开始配对」。"
                return
            }
            manualPort = discoveredPort.toString()
            statusText = "已发现配对端口 $discoveredPort，正在配对…"
            log("发起配对：code=$code port=$discoveredPort host=${host ?: "自动"}")
            AdbManager.pair(code, discoveredPort.toString(), hostOverride = host, connectPort = connectPort)
        } else {
            manualPort = portText
            statusText = "正在用配对码 $code 连接（端口 $portText）…"
            log("发起配对：code=$code port=$portText host=${host ?: "自动"}")
            AdbManager.pair(code, portText, hostOverride = host, connectPort = connectPort)
        }
    }

    suspend fun recognizeAndPair(bitmap: Bitmap, sourceLabel: String, allowAutoPair: Boolean) {
        stage = PairStage.Recognizing
        headline = "正在识别截图"
        statusText = "正在识别截图里的配对码和端口…"
        detailText = ""
        manualHint = ""

        val lines = try {
            OcrEngine.recognize(bitmap)
        } catch (t: Throwable) {
            log("OCR 失败：${t.javaClass.simpleName}: ${t.message}")
            stage = PairStage.Failed
            headline = "截图识别失败"
            statusText = "无法解析这张截图"
            detailText = "可以重试一次，或直接使用下面的手动配对。"
            return
        }

        val textLines = lines.map { PairingCodeParser.TextLine(it.text, it.height) }
        ocrText = textLines.joinToString("\n") { it.text }
        val result = PairingCodeParser.parse(textLines)
        parsed = result
        log(
            "收到截图（$sourceLabel），OCR 行数=${lines.size}，" +
                "解析 code=${result.code} port=${result.port} host=${result.host}",
        )

        val code = result.code
        val port = result.port
        when {
            code != null && port != null -> {
                headline = "已识别到配对信息"
                statusText = "配对码 $code，配对端口 $port"
                stage = PairStage.Recognized
                manualCode = code
                manualPort = port.toString()
                if (allowAutoPair) beginPair(code, port.toString(), result.host, result.connectPort)
            }

            code != null -> {
                headline = "已识别到配对码"
                statusText = "配对码 $code，正在自动查找配对端口…"
                stage = PairStage.Recognized
                manualCode = code
                if (allowAutoPair) beginPair(code, null, result.host, result.connectPort)
            }

            else -> {
                stage = PairStage.WaitingScreenshot
                headline = "没找到配对码"
                statusText = "这张截图里没有识别出 6 位配对码"
                detailText = "请确认截到的是「使用配对码配对设备」弹窗（含 6 位配对码和「IP 地址和端口」），" +
                    "或把配对码/端口直接填到下面的手动配对里。"
                log("解析失败：codeEvidence=${result.codeEvidence} portEvidence=${result.portEvidence}")
            }
        }
    }

    fun handleScreenshot(bitmap: Bitmap, source: ScreenshotPairWatcher.Source) {
        if (finished) return
        if (stage == PairStage.Pairing) {
            log("配对进行中，忽略新截图（${source.name}）")
            return
        }
        if (recognizing) {
            log("上一张截图还在识别，忽略新截图（${source.name}）")
            return
        }
        preview = bitmap.asImageBitmap()
        val test = selfTestMode
        log("收到新截图（${source.name}）${bitmap.width}x${bitmap.height}${if (test) "（自检）" else ""}")
        scope.launch {
            recognizing = true
            try {
                recognizeAndPair(bitmap, source.name, allowAutoPair = !test)
            } finally {
                recognizing = false
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // 截图监听（MediaStore + 回到前台轮询 + API 34+ 截屏回调信号）
    // -----------------------------------------------------------------------------------

    val watcher = remember {
        ScreenshotPairWatcher(
            context = context.applicationContext,
            onScreenshot = { bitmap, source -> handleScreenshot(bitmap, source) },
            onStatus = { message ->
                Log.d(TAG, "watcher: $message")
                if (selfTestMode) log("watcher: $message")
            },
            onPermissionMissing = {
                permissionGranted = false
                if (stage == PairStage.WaitingScreenshot) {
                    statusText = "没有相册权限，无法自动读取截图；可以授权或使用手动配对。"
                }
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permissionDeniedOnce = !granted
        if (granted) {
            stage = PairStage.WaitingScreenshot
            statusText = "已获得相册权限，正在查找最新截图…"
            watcher.pollLatest(force = true)
        } else {
            stage = PairStage.Idle
            headline = "没有相册权限"
            statusText = "无法读取系统截图"
            detailText = "可以点「去应用设置」手动打开相册权限，或拒绝权限直接用下面的手动配对。"
        }
    }

    DisposableEffect(Unit) {
        watcher.start(activity)
        onDispose { watcher.stop() }
    }

    DisposableEffect(activity) {
        val lifecycleOwner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ScreenshotPairWatcher.hasPermission(context)
                localNetGranted = hasLocalNetworkPermission()
                if (permissionGranted && !finished && stage != PairStage.Pairing) {
                    // 用户很可能刚在「设置」里截了图再切回来：回前台立刻查一次最新截图
                    watcher.pollLatest(force = false)
                }
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    // 与 AdbManager 的全局连接状态保持同步
    LaunchedEffect(conn) {
        if (conn.connected && pairStarted && !finished) {
            finishSuccess()
        } else if (conn.busy && pairStarted) {
            stage = PairStage.Pairing
            headline = "正在配对"
            statusText = conn.status
        } else if (pairStarted && !conn.busy && !conn.error.isNullOrBlank() && !finished) {
            stage = PairStage.Failed
            headline = "配对/连接失败"
            statusText = "这次没有连接成功"
            detailText = conn.error.orEmpty()
        }
    }

    // -----------------------------------------------------------------------------------
    // 手动兜底
    // -----------------------------------------------------------------------------------

    fun submitManualPair() {
        val code = manualCode.filter { it.isDigit() }
        val port = manualPort.filter { it.isDigit() }
        when {
            AdbManager.connection.value.busy -> manualHint = "正在连接中，请稍候…"
            code.length != 6 -> manualHint = "配对码需要 6 位数字"
            port.isEmpty() -> manualHint = "请填写配对端口（弹窗里 IP 地址后面的数字）"
            else -> {
                manualHint = ""
                // Android 17 起，无线 ADB 的 socket 需要「本地网络访问」权限
                if (Build.VERSION.SDK_INT >= 36 && !hasLocalNetworkPermission()) {
                    headline = "需要本地网络权限"
                    statusText = "Android 17 的无线 ADB 需要「本地网络访问」权限，授予后会继续配对…"
                    withLocalNetworkPermission { submitManualPair() }
                    return
                }
                pairStarted = true
                stage = PairStage.Pairing
                headline = "正在配对"
                statusText = "正在使用配对码 $code 连接（端口 $port）…"
                detailText = ""
                log("手动配对：code=$code port=$port")
                AdbManager.pair(
                    pairingCode = code,
                    manualPort = port,
                    onSuccess = { mainHandler.post { log("AdbManager 回调：配对 + TLS 连接成功") } },
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // 页面 UI（iOS 风格）
    // -----------------------------------------------------------------------------------

    IosPage {
        IosNavBar(title = "自动配对无线 ADB", onBack = onBack)
        IosLargeTitle(
            title = "截一下屏，自动配对",
            subtitle = "看到「使用配对码配对设备」弹窗时按电源键+音量下截屏，回到这里自动识别并连接",
        )

        // 实时状态卡
        IosCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(stage)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = headline, style = IosType.headline, color = colors.textPrimary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = statusText, style = IosType.footnote, color = colors.textSecondary)
                    }
                }

                if (detailText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = detailText, style = IosType.footnote, color = colors.red)
                }

                val code = parsed?.code
                val port = parsed?.port
                val host = parsed?.host
                if (code != null || port != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (code != null) IosTagChip("配对码 $code", colors.accent)
                        if (port != null) IosTagChip("配对端口 $port", colors.green)
                        if (host != null) IosTagChip("IP $host", colors.orange)
                    }
                }

                if (discovery.discovering) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = discovery.message.ifBlank { "正在通过 mDNS 发现配对端口…" },
                        style = IosType.footnote,
                        color = colors.orange,
                    )
                }

                if (stage == PairStage.Recognizing || stage == PairStage.Pairing) {
                    Spacer(modifier = Modifier.height(12.dp))
                    IosProgressBar()
                }
            }
        }

        // 权限横幅
        if (!permissionGranted) {
            IosBanner(
                text = "读取系统截图需要「" +
                    (if (Build.VERSION.SDK_INT >= 33) "照片和视频" else "存储") +
                    "」权限；只在本机识别，不会上传。也可以拒绝后手动输入。",
                kind = IosBannerKind.Warning,
                actionText = "去授权",
                onAction = { permissionLauncher.launch(ScreenshotPairWatcher.permissionName()) },
            )
            if (permissionDeniedOnce) {
                IosSecondaryButton(
                    text = "去应用设置里打开相册权限",
                    modifier = Modifier.fillMaxWidth(),
                ) { openAppSettings(context) }
            }
        }

        // 本地网络访问权限横幅（Android 17 / API 36 起）
        if (Build.VERSION.SDK_INT >= 36 && !localNetGranted) {
            IosBanner(
                text = "Android 17 起，无线 ADB 需要「本地网络访问」权限，否则 mDNS 发现和配对会被系统拦截。" +
                    (if (localNetDenied) "刚才没有授权，可以再点一次「授权」，或去系统设置里打开。" else ""),
                kind = if (localNetDenied) IosBannerKind.Error else IosBannerKind.Warning,
                actionText = "授权",
                onAction = {
                    pendingLocalNetAction = null
                    localNetLauncher.launch(AdbManager.ACCESS_LOCAL_NETWORK_PERMISSION)
                },
            )
            if (localNetDenied) {
                IosSecondaryButton(
                    text = "去应用设置里打开本地网络权限",
                    modifier = Modifier.fillMaxWidth(),
                ) { openAppSettings(context) }
            }
        }

        // 主操作
        IosPrimaryButton(
            text = if (permissionGranted) "我已截图，开始识别" else "授权并识别最新截图",
            modifier = Modifier.fillMaxWidth(),
            loading = stage == PairStage.Recognizing,
            icon = Icons.Filled.Refresh,
            onClick = {
                if (!permissionGranted) {
                    permissionLauncher.launch(ScreenshotPairWatcher.permissionName())
                } else {
                    stage = PairStage.Recognizing
                    headline = "正在识别截图"
                    statusText = "正在查找最新截图…"
                    detailText = ""
                    watcher.pollLatest(force = true)
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IosSecondaryButton(
                text = "打开无线调试设置",
                modifier = Modifier.weight(1f),
            ) { openDeveloperOptions(context) }
            IosSecondaryButton(
                text = if (conn.connected) "已连接，重新连接" else "直接尝试连接",
                modifier = Modifier.weight(1f),
            ) {
                withLocalNetworkPermission {
                    pairStarted = true
                    stage = PairStage.Pairing
                    headline = "正在连接"
                    statusText = "正在尝试直接连接已配对的设备…"
                    val cp = parsed?.connectPort
                    val ph = parsed?.host
                    if (cp != null && cp in 1..65535 && ph != null) {
                        log("直接连接：$ph:$cp（来自截图识别）")
                        AdbManager.connectManual(ph, cp.toString())
                    } else {
                        AdbManager.connectAuto()
                    }
                }
            }
        }

        // 最近截图预览 + OCR 原文
        preview?.let { image ->
            IosCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "最近识别的截图",
                        style = IosType.footnote.copy(fontWeight = FontWeight.Medium),
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(
                        bitmap = image,
                        contentDescription = "最近识别的截图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.fill),
                    )
                    if (ocrText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        IosTextButton(
                            text = if (showOcr) "收起识别文本" else "查看识别出的文本",
                        ) { showOcr = !showOcr }
                        if (showOcr) {
                            Spacer(modifier = Modifier.height(6.dp))
                            IosCodeBlock(text = ocrText.take(1_200))
                        }
                    }
                }
            }
        }

        // 手动兜底
        IosSectionHeader("手动配对（兜底）")
        IosCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "自动识别不成功时，可以直接照着配对弹窗填写（不需要相册权限）。",
                    style = IosType.footnote,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(10.dp))
                IosTextField(
                    value = manualCode,
                    onValueChange = { input -> manualCode = input.filter { it.isDigit() }.take(6) },
                    placeholder = "6 位配对码",
                    leadingIcon = Icons.Filled.Lock,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(modifier = Modifier.height(8.dp))
                IosTextField(
                    value = manualPort,
                    onValueChange = { input -> manualPort = input.filter { it.isDigit() }.take(5) },
                    placeholder = "配对端口（弹窗 IP 地址后面的数字）",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (manualHint.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = manualHint, style = IosType.footnote, color = colors.red)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IosSecondaryButton(
                        text = if (discovery.discovering) "发现中…" else "发现端口",
                        modifier = Modifier.weight(1f),
                        loading = discovery.discovering,
                    ) {
                        AdbManager.startPairingDiscovery()
                        statusText = "正在通过 mDNS 发现同一 WiFi 下的配对端口…"
                    }
                    IosPrimaryButton(
                        text = "开始配对",
                        modifier = Modifier.weight(1f),
                        enabled = manualCode.length == 6 && stage != PairStage.Pairing,
                    ) { submitManualPair() }
                }
            }
        }

        // 4 步引导
        IosSectionHeader("怎么操作（4 步）")
        IosCard {
            Column {
                GuideStep(
                    index = 1,
                    title = "打开「无线调试」",
                    subtitle = "设置 → 开发者选项 → 无线调试。手机和本机需要连同一个 WiFi。" +
                        "第一次使用要先在开发者选项里开启无线调试。",
                )
                Hairline(startIndent = 54.dp)
                GuideStep(
                    index = 2,
                    title = "点「使用配对码配对设备」",
                    subtitle = "弹窗会显示 6 位配对码和「IP 地址和端口」，请让它们完整出现在一屏内，且不要关闭弹窗。",
                )
                Hairline(startIndent = 54.dp)
                GuideStep(
                    index = 3,
                    title = "截屏",
                    subtitle = "同时按 电源键 + 音量减（或下拉控制中心点截屏）。系统会把截图存进相册。",
                )
                Hairline(startIndent = 54.dp)
                GuideStep(
                    index = 4,
                    title = "切回本 App",
                    subtitle = "回到这里会自动识别并配对；如果没反应，点上面的「我已截图，开始识别」。",
                )
            }
        }

        IosBanner(
            text = "需要 Android 11 及以上、手机与本机同一 WiFi，并且配对弹窗保持打开（配对码只能用一次）。",
            kind = IosBannerKind.Info,
        )

        // 常见问题
        IosSectionHeader("常见问题")
        IosCard {
            Column {
                FaqRow(
                    question = "为什么一定要截图？",
                    answer = "Android 不允许应用读取其它界面。截图是你主动给出的唯一入口，" +
                        "App 只读取相册里刚产生的新截图，并且只在本机做文字识别。",
                )
                Hairline(startIndent = 16.dp)
                FaqRow(
                    question = "配对码识别不出来？",
                    answer = "保证弹窗完整入镜、字迹清晰；或直接用手动配对填写。" +
                        "重新打开弹窗后配对码会变化，记得重新截图。",
                )
                Hairline(startIndent = 16.dp)
                FaqRow(
                    question = "提示配对失败？",
                    answer = "配对码已过期（弹窗被关了会失效），请重新打开「使用配对码配对设备」再截图；" +
                        "同时确认手机和本机在同一 WiFi。",
                )
                Hairline(startIndent = 16.dp)
                FaqRow(
                    question = "为什么提示没有权限？",
                    answer = "首次使用需要授予「照片和视频 / 存储」权限才能读系统截图；" +
                        "也可以拒绝后用上面的手动配对。",
                )
                Hairline(startIndent = 16.dp)
                FaqRow(
                    question = "Android 17 提示缺少「本地网络访问」？",
                    answer = "Android 17 起无线 ADB 的发现和连接需要系统「本地网络访问」权限。" +
                        "弹出系统授权时点「允许」；如果已经拒绝，点页面上方横幅的「授权」，" +
                        "或到 系统设置 → 应用 → " + appLabel + " → 权限 里打开。",
                )
                Hairline(startIndent = 16.dp)
                FaqRow(
                    question = "Android 10 及以下能用吗？",
                    answer = "不能。无线调试的配对码是 Android 11（API 30）起才有；" +
                        "本 App 最低支持 Android 11 的这套流程（系统版本再低请用 USB 或旧版无线 ADB）。",
                )
            }
        }

        // debug 自检：模拟器上无法真正打开无线调试，用内置图片验证 OCR/解析/截图监听链路
        if (isDebugBuild) {
            IosSectionHeader("开发者自检（debug）")
            IosCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "模拟器/无真机时，用下面两项验证识别链路（不会真的发起配对）。",
                        style = IosType.footnote,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    IosSecondaryButton(
                        text = "自检 1：内置配对弹窗图 → OCR + 解析",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        scope.launch {
                            val bitmap = buildFakePairingDialogBitmap()
                            preview = bitmap.asImageBitmap()
                            selfTestMode = true
                            log("自检1：生成假配对弹窗 ${bitmap.width}x${bitmap.height}")
                            try {
                                recognizeAndPair(bitmap, "内置假图", allowAutoPair = false)
                            } finally {
                                selfTestMode = false
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    IosSecondaryButton(
                        text = "自检 2：写入假截图 → 走截图监听",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        scope.launch {
                            selfTestMode = true
                            stage = PairStage.Recognizing
                            headline = "自检中"
                            statusText = "正在往相册写入一张假截图…"
                            val written = writeFakeScreenshotToMediaStore(context)
                            log(if (written) "自检2：写入假截图成功，等待监听" else "自检2：写入假截图失败")
                            if (written) {
                                delay(1_200L)
                                statusText = "等待 ContentObserver / 回前台轮询发现新截图…"
                                watcher.pollLatest(force = true)
                                delay(2_500L)
                                stage = PairStage.WaitingScreenshot
                                headline = "自检完成"
                                statusText = "看下方日志确认是否收到截图；自检不会真的配对。"
                            } else {
                                stage = PairStage.Failed
                                headline = "自检失败"
                                detailText = "写入 MediaStore 失败（可能没有存储权限或空间不足）。"
                            }
                            selfTestMode = false
                        }
                    }
                    if (debugLog.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        IosCodeBlock(text = debugLog)
                        Spacer(modifier = Modifier.height(6.dp))
                        IosTextButton(text = "清空日志") { debugLog = "" }
                    }
                }
            }
        }

        IosFooterNote(
            "首次使用需要在系统弹窗里允许「$appLabel」读取照片/视频；" +
                "截图只在本机做文字识别，不会上传到任何服务器。",
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------------------
// 小组件
// ---------------------------------------------------------------------------------------

private enum class PairStage { Idle, WaitingScreenshot, Recognizing, Recognized, Pairing, Done, Failed }

@Composable
private fun StatusBadge(stage: PairStage) {
    val colors = LocalIosColors.current
    val icon: ImageVector
    val foreground: Color
    val background: Color
    when (stage) {
        PairStage.Idle, PairStage.WaitingScreenshot -> {
            icon = Icons.Filled.Info
            foreground = colors.accent
            background = colors.accentSoft
        }
        PairStage.Recognizing, PairStage.Pairing -> {
            icon = Icons.Filled.Refresh
            foreground = colors.orange
            background = colors.orangeSoft
        }
        PairStage.Recognized -> {
            icon = Icons.Filled.CheckCircle
            foreground = colors.green
            background = colors.greenSoft
        }
        PairStage.Done -> {
            icon = Icons.Filled.CheckCircle
            foreground = colors.green
            background = colors.greenSoft
        }
        PairStage.Failed -> {
            icon = Icons.Filled.Warning
            foreground = colors.red
            background = colors.redSoft
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = foreground, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun GuideStep(index: Int, title: String, subtitle: String) {
    val colors = LocalIosColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                style = IosType.footnote.copy(fontWeight = FontWeight.Bold),
                color = colors.accent,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = IosType.body.copy(fontWeight = FontWeight.Medium),
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, style = IosType.footnote, color = colors.textSecondary)
        }
    }
}

@Composable
private fun FaqRow(question: String, answer: String) {
    val colors = LocalIosColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = question,
            style = IosType.body.copy(fontWeight = FontWeight.Medium),
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(text = answer, style = IosType.footnote, color = colors.textSecondary)
    }
}

// ---------------------------------------------------------------------------------------
// 系统跳转
// ---------------------------------------------------------------------------------------

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openDeveloperOptions(context: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Throwable) {
            // 尝试下一个
        }
    }
}

private fun openAppSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Throwable) {
        openDeveloperOptions(context)
    }
}

// ---------------------------------------------------------------------------------------
// 自检用：内存里画一张假的「使用配对码配对设备」弹窗
// ---------------------------------------------------------------------------------------

private fun buildFakePairingDialogBitmap(): Bitmap {
    val width = 1080
    val height = 1600
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(AndroidColor.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        typeface = Typeface.DEFAULT
    }
    paint.textSize = 40f
    canvas.drawText("设置", 48f, 96f, paint)

    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = 54f
    canvas.drawText("使用配对码配对设备", 48f, 250f, paint)

    paint.typeface = Typeface.DEFAULT
    paint.textSize = 40f
    canvas.drawText("配对码", 48f, 410f, paint)

    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = 130f
    canvas.drawText("123456", 48f, 570f, paint)

    paint.typeface = Typeface.DEFAULT
    paint.textSize = 40f
    canvas.drawText("IP 地址和端口", 48f, 760f, paint)
    paint.textSize = 58f
    canvas.drawText("192.168.1.23:41234", 48f, 860f, paint)

    paint.textSize = 34f
    canvas.drawText("在设备上输入此配对码以配对。", 48f, 1_000f, paint)
    canvas.drawText("此代码只能使用一次。", 48f, 1_052f, paint)

    paint.textSize = 40f
    canvas.drawText("取消", 700f, 1_200f, paint)
    paint.typeface = Typeface.DEFAULT_BOLD
    canvas.drawText("配对", 880f, 1_200f, paint)
    return bitmap
}

/** 自检：把一张假配对弹窗图插进 MediaStore 的 Screenshots 目录，验证「截图监听」全链路。 */
private suspend fun writeFakeScreenshotToMediaStore(context: Context): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "Screenshot_self_test_${System.currentTimeMillis()}.png",
                )
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Screenshots",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            val bitmap = buildFakePairingDialogBitmap()
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            true
        }.getOrDefault(false)
    }

private const val TAG = "AutoPairScreen"
