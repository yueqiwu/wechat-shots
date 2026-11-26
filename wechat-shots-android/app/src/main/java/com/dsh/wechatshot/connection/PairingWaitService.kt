package com.dsh.wechatshot.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dsh.wechatshot.MainActivity
import com.dsh.wechatshot.R
import com.dsh.wechatshot.adb.AdbManager
import com.dsh.wechatshot.adb.PairingCodeParser
import com.dsh.wechatshot.adb.ScreenshotPairWatcher
import com.dsh.wechatshot.automation.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台等待「配对弹窗截图」的前台服务。
 *
 * 为什么需要它（真机实测结论）：
 * 一加 Ace 6T / ColorOS（Android 17）上，把本 App 切到前台时，「与设备配对」弹窗会被系统立刻关闭，
 * 弹窗里的配对端口随之失效（实测：弹窗在前台时 PC 能连上该端口，按 Home 后 2 秒即连接被拒）。
 * 因此「截图 → 切回 App → 自动配对」必然失败。
 *
 * 本服务在用户点「打开无线调试设置」时启动（此时 App 还在前台，允许启动前台服务），
 * 让进程在设置界面保持前台服务级别活跃：用户截图后，服务侧 ContentObserver 立即收到新截图
 * → ML Kit OCR → [PairingCodeParser] 解析配对码/配对端口/连接端口 → 立即 [AdbManager.pair]。
 * 此时配对弹窗仍开在前台，端口可用，配对成功后在通知栏提示「配对成功」，并把结果写入
 * [PairingBridge]，用户回到 App 时页面直接显示已连接。
 */
class PairingWaitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val handling = AtomicBoolean(false)
    private var watcher: ScreenshotPairWatcher? = null
    private var watchJob: Job? = null
    private var startedAtMs = 0L
    private var finishedNotified = false
    private var lastPollAtMs = 0L

    /** 后台轮询兜底：OEM 后台可能延迟/丢弃 ContentObserver 回调，定时主动查一遍 MediaStore 最新截图。 */
    private val pollRunnable = object : Runnable {
        private var ticks = 0
        override fun run() {
            if (finishedNotified) return
            val now = System.currentTimeMillis()
            // 服务被系统冻结后恢复时，runnable 可能一次性堆积；限制最小间隔
            if (now - lastPollAtMs >= 600L) {
                lastPollAtMs = now
                ticks++
                if (ticks % 10 == 1) debug("轮询心跳 tick=$ticks")
                watcher?.pollLatest(force = false)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val timeoutRunnable = Runnable {
        Log.d(TAG, "等待超时，停止服务")
        updateNotification("配对等待已超时", "可以回到 App 重新点「打开无线调试设置」再试一次")
        handler.postDelayed({ stopSelf() }, 1_500L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "收到取消等待指令")
            stopSelf()
            return START_NOT_STICKY
        }
        startedAtMs = System.currentTimeMillis()
        finishedNotified = false
        ensureChannel()
        startForegroundCompat(buildNotification(
            title = "等待配对截图",
            text = "打开「无线调试 → 输入配对码配对设备」，按电源键+音量下截屏，本应用会在后台自动完成配对",
            ongoing = true,
        ))
        startWatching()
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, MAX_RUN_MS)
        handler.removeCallbacks(pollRunnable)
        lastPollAtMs = 0L
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        debug("前台配对服务已启动")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        debug("前台配对服务停止")
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(pollRunnable)
        stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // 截图监听 + OCR + 配对
    // ---------------------------------------------------------------------------------------

    private fun startWatching() {
        if (watcher != null) return
        val w = ScreenshotPairWatcher(
            context = applicationContext,
            onScreenshot = { bitmap, source -> handleScreenshot(bitmap, source) },
            onStatus = { message ->
                Log.d(TAG, "watcher: $message")
                debug("watcher: $message")
            },
            onPermissionMissing = {
                updateNotification(
                    title = "缺少相册权限",
                    text = "无法读取系统截图：请回到 App 授予「照片和视频」权限后，再点「打开无线调试设置」",
                )
            },
            initialLookbackSec = 15L,
        )
        w.start(null)
        watcher = w
        watchJob = scope.launch {
            AdbManager.connection.collect { state ->
                val elapsed = System.currentTimeMillis() - startedAtMs
                when {
                    state.connected -> onPaired(state.device)
                    // 服务刚启动时可能带着上一次的旧错误状态，忽略前 2 秒内的错误
                    !state.busy && !state.error.isNullOrBlank() && elapsed > 2_000L ->
                        onPairFailed(state.error.orEmpty())
                }
            }
        }
    }

    private fun stopWatching() {
        watcher?.stop()
        watcher = null
        watchJob?.cancel()
        watchJob = null
    }

    private fun handleScreenshot(bitmap: Bitmap, source: ScreenshotPairWatcher.Source) {
        if (finishedNotified) {
            bitmap.recycle()
            return
        }
        if (!handling.compareAndSet(false, true)) {
            // 上一张还在识别/配对中，丢弃这张，避免重复配对
            bitmap.recycle()
            return
        }
        scope.launch {
            try {
                debug("收到截图 source=${source.name} ${bitmap.width}x${bitmap.height}，开始 OCR")
                val lines = OcrEngine.recognize(bitmap)
                val textLines = lines.map { PairingCodeParser.TextLine(it.text, it.height) }
                val parsed = PairingCodeParser.parse(textLines)
                val detail = "OCR=${lines.size}行 code=${parsed.code} pairPort=${parsed.port} " +
                    "connectPort=${parsed.connectPort} host=${parsed.host}"
                Log.d(TAG, "收到截图(${source.name}) $detail")
                debug("识别结果 $detail")
                val code = parsed.code
                if (code == null) {
                    updateNotification(
                        title = "没有识别到配对码",
                        text = "请确认截图包含「WLAN 配对码」和「IP 地址和端口」，重新截屏即可自动重试",
                    )
                    return@launch
                }
                updateNotification(
                    title = "正在配对…",
                    text = "配对码 $code · 配对端口 ${parsed.port ?: "自动发现"} · 正在建立无线 ADB 连接",
                )
                debug("发起配对 code=$code port=${parsed.port} host=${parsed.host}")
                AdbManager.pair(
                    pairingCode = code,
                    manualPort = parsed.port?.toString().orEmpty(),
                    hostOverride = parsed.host,
                    connectPort = parsed.connectPort,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "处理截图失败", t)
                debug("处理截图失败 ${t.javaClass.simpleName}: ${t.message}")
                updateNotification("识别失败", "截图处理失败：${t.javaClass.simpleName}")
            } finally {
                bitmap.recycle()
                handling.set(false)
            }
        }
    }

    private fun onPaired(device: String) {
        if (finishedNotified) return
        finishedNotified = true
        val detail = if (device.isNotBlank()) "已连接：$device" else "已通过无线 ADB 连接"
        Log.d(TAG, "配对成功，$detail")
        debug("配对成功，$detail")
        handler.removeCallbacks(pollRunnable)
        PairingBridge.publish(PairingBridge.Result(true, detail))
        stopWatching()
        handler.removeCallbacks(timeoutRunnable)
        handler.post {
            notifyResult("配对成功", "$detail，回到 App 即可开始导出")
        }
        handler.postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 2_000L)
    }

    private fun onPairFailed(error: String) {
        if (finishedNotified) return
        Log.d(TAG, "配对失败：$error")
        debug("配对失败：$error")
        PairingBridge.publish(PairingBridge.Result(false, error))
        updateNotification(
            title = "配对未成功",
            text = "$error。可重新打开配对弹窗再截一张新截图重试",
        )
    }

    // ---------------------------------------------------------------------------------------
    // 诊断日志（后台时 logcat 可能被 OEM 丢弃，写到外部文件方便用户回传）
    // ---------------------------------------------------------------------------------------

    private fun debug(message: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = java.io.File(dir, DEBUG_LOG_NAME)
            if (file.exists() && file.length() > MAX_DEBUG_LOG_BYTES) {
                file.delete()
            }
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("$stamp $message\n")
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // 通知
    // ---------------------------------------------------------------------------------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动配对等待",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "在后台等待配对弹窗截图并自动完成无线 ADB 配对"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PairingWaitService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "取消等待", stopIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, text: String) {
        if (finishedNotified) return
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            buildNotification(title, text, ongoing = true),
        )
    }

    /** 配对结束后单独发一条普通通知，前台通知移除后它仍留在通知栏。 */
    private fun notifyResult(title: String, text: String) {
        NotificationManagerCompat.from(this).notify(
            RESULT_NOTIFICATION_ID,
            buildNotification(title, text, ongoing = false),
        )
    }

    companion object {
        private const val TAG = "PairingWaitService"
        private const val CHANNEL_ID = "wechat_shots_pairing"
        private const val NOTIFICATION_ID = 0x5754
        private const val RESULT_NOTIFICATION_ID = 0x5755
        private const val ACTION_STOP = "com.dsh.wechatshot.action.STOP_PAIRING_WAIT"
        private const val MAX_RUN_MS = 10 * 60_000L
        private const val POLL_INTERVAL_MS = 1_000L
        private const val DEBUG_LOG_NAME = "pairing_debug.log"
        private const val MAX_DEBUG_LOG_BYTES = 512 * 1024L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PairingWaitService::class.java),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "启动前台配对服务失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, PairingWaitService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
