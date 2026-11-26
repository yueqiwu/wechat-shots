package com.dsh.wechatshot.connection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.dsh.wechatshot.MainActivity
import com.dsh.wechatshot.R
import com.dsh.wechatshot.adb.AdbManager
import com.dsh.wechatshot.adb.PairingCodeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 无障碍配对助手：读取系统「与设备配对」弹窗的文本，自动取配对码/配对端口并在弹窗仍打开时完成配对。
 *
 * 这是真机上唯一可靠的路径：ColorOS 会冻结后台应用的主线程定时器，截图监听方案在后台不可用；
 * 而配对弹窗一旦切走就会关闭、配对端口失效。无障碍服务由系统绑定并保持运行，可以在设置界面
 * 前台时读取窗口内容并发起 adb pair。
 */
class PairingAssistAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var finished = false
    private var lastAttemptAt = 0L
    private var lastAttemptedCode: String? = null
    private var lastClickAt = 0L
    private var inspecting = false

    // 从「无线调试」主页缓存连接端口：配对弹窗是独立窗口，rootInActiveWindow 里看不到背后的主页，
    // 所以配对成功后要连的端口（主页 IP 地址和端口）需要提前缓存下来。
    private var lastConnectPort: Int? = null
    private var lastHost: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            packageNames = arrayOf(
                "com.android.settings",
                "com.coloros.settings",
                "com.oplus.settings",
            )
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 80
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        PairingAssist.log(this, "无障碍配对助手已连接")

        // 配对成功后由 AdbManager 的连接状态收尾。
        // 注意：必须同时满足「确实有一次自动配对请求」，否则无障碍服务重启时（系统会不定时重绑）
        // 会立刻看到 connected=true 并强行拉起 MainActivity，把微信从前台抢走，导致自动化导出失败。
        scope.launch {
            AdbManager.connection.collect { conn ->
                if (finished) return@collect
                if (conn.connected && PairingAssist.isAssistRequested(this@PairingAssistAccessibilityService)) {
                    finished = true
                    onPaired()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (finished) return
        val pkg = ev.packageName?.toString().orEmpty()
        if (!pkg.contains("settings")) return
        if (!PairingAssist.isAssistRequested(this)) return
        // 窗口内容变化很频繁，做个轻量防抖
        handler.removeCallbacks(inspectRunnable)
        handler.postDelayed(inspectRunnable, 260L)
    }

    private val inspectRunnable = Runnable { inspectWindow() }

    private val ipPortRegex = Regex("""(\d{1,3}(?:\.\d{1,3}){3})\s*[:：]\s*(\d{2,5})""")

    /**
     * 在「无线调试」主页缓存 IP:端口（连接端口）。配对弹窗是独立窗口，读不到背后的主页，
     * 所以要在打开配对弹窗之前把连接端口记下来，配对成功后直接连它，避免依赖 mDNS。
     */
    private fun cacheConnectEndpoint(texts: List<String>) {
        for (text in texts) {
            val match = ipPortRegex.find(text) ?: continue
            val port = match.groupValues[2].toIntOrNull() ?: continue
            if (port !in 1..65535) continue
            lastConnectPort = port
            lastHost = match.groupValues[1]
            return
        }
    }

    private fun inspectWindow() {
        if (finished || inspecting) return
        inspecting = true
        try {
            val root = rootInActiveWindow ?: return
            val entries = ArrayList<PairingCodeParser.TextLine>()
            val texts = ArrayList<String>()
            collectTexts(root, entries, texts, depth = 0)
            if (entries.isEmpty()) return
            PairingAssist.log(this, "读到窗口文本(${entries.size}): " + texts.joinToString(" | ").take(360))

            val parsed = PairingCodeParser.parse(entries)
            val now = System.currentTimeMillis()
            if (parsed.code != null) {
                val changed = parsed.code != lastAttemptedCode
                if (changed || now - lastAttemptAt > 4_000L) {
                    lastAttemptAt = now
                    lastAttemptedCode = parsed.code
                    val effectiveConnectPort = parsed.connectPort ?: lastConnectPort
                    val effectiveHost = parsed.host ?: lastHost
                    PairingAssist.log(
                        this,
                        "解析配对码=${parsed.code} 配对端口=${parsed.port} 连接端口=$effectiveConnectPort host=$effectiveHost",
                    )
                    if (!AdbManager.connection.value.busy) {
                        AdbManager.pair(
                            pairingCode = parsed.code,
                            manualPort = parsed.port?.toString().orEmpty(),
                            hostOverride = effectiveHost,
                            connectPort = effectiveConnectPort,
                            onSuccess = { PairingAssist.log(this@PairingAssistAccessibilityService, "配对 + TLS 连接成功") },
                        )
                    }
                }
                return
            }

            // 没有配对码 = 正停在「无线调试」主页：缓存连接端口（主页的 IP 地址和端口）
            cacheConnectEndpoint(texts)

            // 没有配对码时：若正停在「无线调试」页，自动点开「使用配对码配对设备」入口
            if (now - lastClickAt > 2_500L) {
                val target = findNode(root) { text ->
                    text.contains("配对码配对设备") || text.contains("使用配对码") ||
                        text.contains("Pair device with pairing code", ignoreCase = true)
                }
                if (target != null) {
                    lastClickAt = now
                    val clickable = findClickableAncestor(target) ?: target
                    if (clickable.isClickable) {
                        val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        PairingAssist.log(this, "自动点击「使用配对码配对设备」结果=$ok")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "inspectWindow 失败", t)
        } finally {
            inspecting = false
        }
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo?,
        out: MutableList<PairingCodeParser.TextLine>,
        texts: MutableList<String>,
        depth: Int,
    ) {
        if (node == null || depth > 24) return
        val text = node.text?.toString()?.trim().orEmpty()
            .ifBlank { node.contentDescription?.toString()?.trim().orEmpty() }
        if (text.isNotBlank()) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            out.add(PairingCodeParser.TextLine(text = text, height = rect.height()))
            texts.add(text)
        }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out, texts, depth + 1)
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        predicate: (String) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val text = root.text?.toString().orEmpty()
        if (text.isNotBlank() && predicate(text)) return root
        for (i in 0 until root.childCount) {
            val found = findNode(root.getChild(i), predicate)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var guard = 0
        while (current != null && guard < 5) {
            if (current.isClickable) return current
            current = current.parent
            guard++
        }
        return null
    }

    private fun onPaired() {
        PairingAssist.clearRequest(this)
        PairingBridge.publish(PairingBridge.Result(true, "配对成功，已连接无线 ADB"))
        notify("配对成功", "无线 ADB 已连接，点此回到应用开始导出")
        handler.postDelayed({
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
            }.onFailure { PairingAssist.log(this, "回到应用失败：${it.message}") }
        }, 1_000L)
    }

    private fun notify(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "无线 ADB 自动配对",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(inspectRunnable)
        runCatching { scope.cancel() }
        PairingAssist.log(this, "无障碍配对助手已销毁")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PairingAssistService"
        private const val CHANNEL_ID = "wechat_shots_pairing_assist"
        private const val NOTIFICATION_ID = 0x5756
    }
}
