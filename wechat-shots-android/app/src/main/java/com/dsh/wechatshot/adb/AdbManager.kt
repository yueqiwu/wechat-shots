package com.dsh.wechatshot.adb

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class ConnectionUiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val status: String = "未连接",
    val device: String = "",
    val error: String? = null,
)

data class PairingDiscoveryState(
    val discovering: Boolean = false,
    val host: String? = null,
    val port: Int = 0,
    val message: String = "",
)

/**
 * ADB 连接、配对和 mDNS 发现的应用级单例。
 */
object AdbManager {
    private const val TAG = "AdbManager"
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
    private const val DISCOVERY_TIMEOUT_MS = 90_000L

    /** Android 17（API 36）起的本地网络访问运行时权限；常量字符串避免旧 SDK 编译期找不到。 */
    const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    private lateinit var appContext: Context
    private var controllerRef: AdbController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null
    private var mdns: AdbMdns? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _connection = MutableStateFlow(ConnectionUiState())
    val connection: StateFlow<ConnectionUiState> = _connection.asStateFlow()

    private val _pairing = MutableStateFlow(PairingDiscoveryState())
    val pairing: StateFlow<PairingDiscoveryState> = _pairing.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    @Synchronized
    fun controller(): AdbController {
        val existing = controllerRef
        if (existing != null) return existing
        return AdbController(appContext).also { controllerRef = it }
    }

    fun connectAuto() {
        if (_connection.value.busy || _connection.value.connected) return
        scope.launch {
            _connection.update {
                it.copy(busy = true, error = null, status = "正在发现无线调试服务…")
            }
            try {
                val adb = controller()
                val ok = adb.connectTlsNow(DEFAULT_CONNECT_TIMEOUT_MS)
                if (!ok) {
                    fail("连接未建立，请确认无线调试已开启")
                    return@launch
                }
                verifyAndPublish(adb)
            } catch (e: Exception) {
                fail(friendly(e, connecting = true))
            }
        }
    }

    fun connectManual(host: String, portText: String) {
        if (_connection.value.busy) return
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            _connection.update { it.copy(error = "端口号应为 1–65535") }
            return
        }
        if (host.isBlank()) {
            _connection.update { it.copy(error = "主机地址不能为空") }
            return
        }
        scope.launch {
            _connection.update { it.copy(busy = true, error = null, status = "正在连接 " + host + ":" + port + "…") }
            try {
                val adb = controller()
                adb.disconnectQuietly()
                val ok = adb.connectManual(host.trim(), port)
                if (!ok) {
                    fail("连接失败：设备拒绝或端口未开放")
                    return@launch
                }
                verifyAndPublish(adb)
            } catch (e: Exception) {
                fail(friendly(e, connecting = true))
            }
        }
    }

    fun disconnect() {
        scope.launch {
            runCatching { controllerRef?.disconnectQuietly() }
            _connection.value = ConnectionUiState(status = "已断开")
        }
    }

    private fun verifyAndPublish(adb: AdbController): Boolean {
        if (!adb.warmUpShell()) {
            fail("已连上 ADB，但执行测试命令失败")
            return false
        }
        val summary = adb.deviceSummary()
        _connection.value = ConnectionUiState(
            connected = true,
            busy = false,
            status = "已连接，可以开始导出",
            device = summary,
        )
        return true
    }

    private fun fail(message: String) {
        val withHint = message + localNetworkHint()
        _connection.update {
            it.copy(busy = false, connected = false, status = "连接失败", error = withHint)
        }
    }

    /**
     * Android 17（API 36）起新增「本地网络访问」运行时权限（ACCESS_LOCAL_NETWORK），
     * 未授予时 mDNS 发现 / TLS 连接 / 配对 socket 都会被系统拦截，表现为超时或 SocketException。
     * 这里在失败文案后追加提示，避免用户只看到「连接超时」不知道要授权。
     */
    private fun localNetworkHint(): String {
        if (Build.VERSION.SDK_INT < 36) return ""
        val granted = runCatching {
            ::appContext.isInitialized && appContext.checkSelfPermission(ACCESS_LOCAL_NETWORK_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true)
        if (granted) return ""
        return "\n提示：Android 17 起需要「本地网络访问」权限，请允许系统弹窗，或到 系统设置 → 应用 → 权限 中打开后重试。"
    }

    @SuppressLint("MissingPermission")
    fun startPairingDiscovery() {
        if (_pairing.value.discovering) return
        stopPairingDiscovery(clear = true)
        val wifi = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("wechatshot-mdns")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        _pairing.value = PairingDiscoveryState(discovering = true, message = "正在搜索配对端口…")
        mdns = AdbMdns(appContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (port > 0) {
                _pairing.value = PairingDiscoveryState(
                    discovering = true,
                    host = host?.hostAddress,
                    port = port,
                    message = "已发现配对端口 " + port + "，请输入手机上的 6 位配对码",
                )
            }
        }.also {
            runCatching { it.start() }.onFailure { e ->
                Log.w(TAG, "mDNS 启动失败", e)
                _pairing.update { p ->
                    p.copy(discovering = false, message = "自动发现不可用，请手动输入配对端口")
                }
            }
        }
        discoveryJob = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (_pairing.value.discovering && _pairing.value.port <= 0) {
                _pairing.update {
                    it.copy(
                        discovering = false,
                        message = "没有自动发现端口，请在无线调试配对页面查看端口并手动输入",
                    )
                }
            }
            stopMdnsOnly()
        }
    }

    fun stopPairingDiscovery(clear: Boolean = false) {
        discoveryJob?.cancel()
        discoveryJob = null
        stopMdnsOnly()
        if (clear) {
            _pairing.value = PairingDiscoveryState()
        } else {
            _pairing.update { it.copy(discovering = false) }
        }
    }

    private fun stopMdnsOnly() {
        runCatching { mdns?.stop() }
        mdns = null
        runCatching {
            multicastLock?.let { if (it.isHeld) it.release() }
        }
        multicastLock = null
    }

    /**
     * 用配对码配对并建立 TLS 连接。
     *
     * @param pairingCode 手机「使用配对码配对设备」弹窗里的 6 位配对码（OCR 结果会先过滤掉非数字字符）
     * @param manualPort 手动输入的配对端口；留空则用 mDNS 发现的端口
     * @param onSuccess 配对 + TLS 连接都成功后的回调（在 IO 线程调用；页面应以 [connection] StateFlow 为准）
     * @param hostOverride 截图里 OCR 出的手机 IP（IPv4:port 形式时可用）；为空则 mDNS host -> 127.0.0.1
     */
    fun pair(
        pairingCode: String,
        manualPort: String,
        onSuccess: (() -> Unit)? = null,
        hostOverride: String? = null,
    ) {
        if (_connection.value.busy) return
        val code = pairingCode.filter { it.isDigit() }
        if (code.length != 6) {
            _connection.update { it.copy(busy = false, error = "配对码是 6 位数字，请对照手机弹窗输入") }
            return
        }
        val discovered = _pairing.value
        val port = manualPort.filter { it.isDigit() }.toIntOrNull()
            ?: discovered.port.takeIf { it > 0 }
        if (port == null || port !in 1..65535) {
            _connection.update {
                it.copy(busy = false, error = "缺少配对端口：请把弹窗里的“IP 地址和端口”一起截图识别，或手动输入端口")
            }
            return
        }
        // host 优先级：截图 OCR 出的 IP -> mDNS 发现 -> 127.0.0.1（手机自身回环，Android 11+ 有效）
        val host = hostOverride?.trim()?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: discovered.host?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: "127.0.0.1"
        stopPairingDiscovery(clear = false)
        scope.launch {
            _connection.update {
                it.copy(
                    busy = true,
                    connected = false,
                    error = null,
                    status = "正在与 " + host + ":" + port + " 配对…",
                )
            }
            try {
                val adb = controller()
                val paired = adb.pair(host, port, code)
                if (!paired) {
                    fail("配对失败：配对码不正确或配对弹窗已关闭。请重新打开“使用配对码配对设备”再截图")
                    return@launch
                }
                _connection.update { it.copy(status = "配对成功，正在建立 ADB 连接…") }
                var connected = false
                // 配对成功后系统可能需要一点时间才开放 TLS 端口，重试 3 次
                for (attempt in 0..2) {
                    if (attempt > 0) delay(1_200L * attempt)
                    connected = runCatching { adb.connectTlsNow(DEFAULT_CONNECT_TIMEOUT_MS) }.getOrDefault(false)
                    if (connected) break
                    if (attempt < 2) {
                        _connection.update {
                            it.copy(status = "配对成功，正在重试连接（第 " + (attempt + 2) + "/3 次）…")
                        }
                    }
                }
                if (!connected) {
                    fail("配对成功，但自动连接失败。请保持无线调试开启，然后点“重新连接”")
                    return@launch
                }
                val published = verifyAndPublish(adb)
                if (published) onSuccess?.invoke()
            } catch (e: Exception) {
                fail(friendly(e, connecting = false))
            }
        }
    }

    private fun friendly(e: Exception, connecting: Boolean): String {
        Log.w(TAG, "ADB 操作失败", e)
        return when (e) {
            is AdbPairingRequiredException ->
                "设备要求配对：请打开“无线调试 → 使用配对码配对设备”，重新配对"
            is AdbAuthenticationFailedException ->
                "ADB 授权被拒绝：请在手机弹窗点“允许”，或重新配对"
            is InterruptedException ->
                "连接超时：请确认“无线调试”已开启，且没有锁屏"
            is IOException -> {
                val raw = e.message.orEmpty()
                when {
                    raw.contains("Could not find any valid host", true) ->
                        "没有发现无线调试服务：请打开“无线调试”，并保持本 App 在前台"
                    raw.contains("Connection refused", true) ->
                        "连接被拒绝：端口可能已变化，请重新打开无线调试"
                    raw.contains("Stream closed", true) ->
                        "ADB 连接被关闭，请重试"
                    raw.contains("timeout", true) || raw.contains("timed out", true) ->
                        "连接超时：请确认无线调试已开启"
                    else -> if (connecting) "连接失败：" + raw else "配对失败：" + raw
                }
            }
            else -> e.message ?: "操作失败，请重试"
        }
    }
}
