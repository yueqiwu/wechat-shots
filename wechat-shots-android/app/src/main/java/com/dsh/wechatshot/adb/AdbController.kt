package com.dsh.wechatshot.adb

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 本机自连 ADB 的薄封装。所有命令都通过 ADB shell / exec 执行。
 */
class AdbController(context: Context) : AbsAdbConnectionManager() {
    private val appContext = context.applicationContext
    private val identity = AdbCrypto.loadOrCreate(appContext)
    private val ioPool = Executors.newCachedThreadPool()
    @Volatile private var cachedWidth = 0
    @Volatile private var cachedHeight = 0

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(60, TimeUnit.SECONDS)
        // 首次连接时 adbd 一定会先拒绝签名，然后要求客户端发送公钥并在手机上弹授权框。
        // 若这里设为 true，LibADB 会在第一次签名被拒时直接抛异常，永远不会触发授权弹窗。
        setThrowOnUnauthorised(false)
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = AdbCrypto.deviceName()

    @Synchronized
    fun connectTlsNow(timeoutMillis: Long = 15_000): Boolean {
        if (isConnected()) return true
        return connectTls(appContext, timeoutMillis)
    }

    @Synchronized
    fun connectManual(host: String, port: Int): Boolean {
        if (isConnected()) return true
        setHostAddress(host)
        setThrowOnUnauthorised(false)
        return connect(host, port)
    }

    @Synchronized
    fun disconnectQuietly() {
        runCatching { disconnect() }
    }

    @Synchronized
    fun shell(command: String, timeoutMillis: Long = 30_000): String {
        if (!isConnected()) throw IOException("尚未连接 ADB")
        // LibADB 的 shell 流在部分命令上收不到 EOF，read 会一直阻塞到超时。
        // 追加一个唯一结束标记，读到标记就主动结束，不再依赖 EOF。
        val marker = "__WS_END_" + System.nanoTime() + "__"
        val stream = try {
            openStream("shell:" + command + "; echo " + marker)
        } catch (e: Exception) {
            throw IOException("打开 ADB shell 失败：" + e.message, e)
        }
        return try {
            val raw = String(readUntil(stream, marker, timeoutMillis), Charsets.UTF_8)
            val end = raw.indexOf(marker)
            (if (end >= 0) raw.substring(0, end) else raw).replace("\r\n", "\n").trimEnd()
        } finally {
            runCatching { stream.close() }
        }
    }

    @Synchronized
    fun execBytes(command: String, timeoutMillis: Long = 30_000): ByteArray {
        if (!isConnected()) throw IOException("尚未连接 ADB")
        val stream = try {
            openStream("exec:$command")
        } catch (e: Exception) {
            throw IOException("执行 ADB 命令失败：" + e.message, e)
        }
        return try {
            readAll(stream, timeoutMillis)
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * 只读取指定字节数就结束，不等待流 EOF。读取图片/截图这类大文件时
     * LibADB 的 exec 流常常迟迟不关闭，等 EOF 会白等几十秒。
     */
    @Synchronized
    fun execBytesExact(command: String, expected: Int, timeoutMillis: Long = 20_000): ByteArray {
        if (!isConnected()) throw IOException("尚未连接 ADB")
        if (expected <= 0) throw IOException("文件长度无效")
        val stream = try {
            openStream("exec:" + command)
        } catch (e: Exception) {
            throw IOException("执行 ADB 命令失败：" + e.message, e)
        }
        return try {
            readExactly(stream, expected, timeoutMillis)
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun readUntil(stream: AdbStream, marker: String, timeoutMillis: Long): ByteArray {
        val future = ioPool.submit<ByteArray> {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = stream.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) {
                        SystemClock.sleep(5)
                        continue
                    }
                    out.write(buffer, 0, n)
                    if (out.toString("UTF-8").contains(marker)) break
                }
            } catch (e: IOException) {
                val normalEof = e.message?.contains("Stream closed", ignoreCase = true) == true
                if (out.size() == 0 && !normalEof) throw e
                Log.d("AdbController", "流结束：" + e.message)
            }
            out.toByteArray()
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            runCatching { stream.close() }
            future.cancel(true)
            throw IOException("ADB 命令超时（" + (timeoutMillis / 1000) + " 秒）")
        } catch (e: InterruptedException) {
            runCatching { stream.close() }
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("ADB 命令被中断", e)
        } catch (e: Exception) {
            throw if (e is IOException) e else IOException("读取 ADB 输出失败：" + e.message, e)
        }
    }

    private fun readExactly(stream: AdbStream, expected: Int, timeoutMillis: Long): ByteArray {
        val future = ioPool.submit<ByteArray> {
            val out = ByteArrayOutputStream(expected.coerceAtLeast(64 * 1024))
            val buffer = ByteArray(64 * 1024)
            try {
                while (out.size() < expected) {
                    val want = minOf(buffer.size, expected - out.size())
                    val n = stream.read(buffer, 0, want)
                    if (n < 0) break
                    if (n == 0) {
                        SystemClock.sleep(5)
                        continue
                    }
                    out.write(buffer, 0, n)
                }
            } catch (e: IOException) {
                Log.d("AdbController", "按长度读取结束：" + e.message)
            }
            out.toByteArray()
        }
        return try {
            val bytes = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (bytes.size < expected) {
                throw IOException("读取 ADB 文件不完整：" + bytes.size + "/" + expected + " 字节")
            }
            bytes.copyOf(expected)
        } catch (e: TimeoutException) {
            runCatching { stream.close() }
            future.cancel(true)
            throw IOException("读取 ADB 文件超时（" + (timeoutMillis / 1000) + " 秒）")
        } catch (e: InterruptedException) {
            runCatching { stream.close() }
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("读取 ADB 文件被中断", e)
        } catch (e: Exception) {
            throw if (e is IOException) e else IOException("读取 ADB 文件失败：" + e.message, e)
        }
    }

    private fun readAll(stream: AdbStream, timeoutMillis: Long): ByteArray {
        val future = ioPool.submit<ByteArray> {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = stream.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) {
                        SystemClock.sleep(5)
                        continue
                    }
                    out.write(buffer, 0, n)
                }
            } catch (e: IOException) {
                // LibADB 在流正常结束时可能抛 "Stream closed."，而不是返回 -1。
                // 对 input / am 这类没有输出的命令，空输出 + Stream closed 就是正常结束。
                val normalEof = e.message?.contains("Stream closed", ignoreCase = true) == true
                if (out.size() == 0 && !normalEof) throw e
                Log.d("AdbController", "流结束：" + e.message)
            }
            out.toByteArray()
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            runCatching { stream.close() }
            future.cancel(true)
            throw IOException("ADB 命令超时（" + (timeoutMillis / 1000) + " 秒）")
        } catch (e: InterruptedException) {
            runCatching { stream.close() }
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("ADB 命令被中断", e)
        } catch (e: Exception) {
            throw if (e is IOException) e else IOException("读取 ADB 输出失败：" + e.message, e)
        }
    }

    fun dumpUi(): String {
        // uiautomator 偶尔会卡住不退出（实测 30 秒以上），并且可能在设备上留下
        // 仍持有 UiAutomation 的僵尸进程，导致后续 dump 继续失败。
        // 因此每次先清理僵尸进程，再用设备端 timeout 限制单次 dump 时长。
        val paths = listOf("/data/local/tmp/wechatshot_ui.xml", "/sdcard/wechatshot_ui.xml")
        var lastError: Exception? = null
        for (path in paths) {
            val started = SystemClock.elapsedRealtime()
            try {
                val output = shell(
                    "pkill -9 -x uiautomator 2>/dev/null; rm -f " + path +
                        "; timeout 8 uiautomator dump " + path + " >/dev/null 2>&1; cat " + path,
                    12_000
                )
                val elapsed = SystemClock.elapsedRealtime() - started
                Log.d("AdbController", "dumpUi " + path + " 用时 " + elapsed + "ms，长度 " + output.length)
                if (output.contains("<hierarchy")) {
                    return output.substring(output.indexOf("<hierarchy"))
                }
                lastError = IOException("uiautomator 返回内容为空（" + path + "）")
            } catch (e: Exception) {
                lastError = e
                Log.w("AdbController", "dumpUi " + path + " 失败：" + e.message)
            }
        }
        throw IOException("读取微信界面失败：" + (lastError?.message ?: "未知错误"))
    }

    fun screencapPng(): ByteArray {
        val started = SystemClock.elapsedRealtime()
        val remote = "/data/local/tmp/wechatshot_screen.png"
        // 先让设备把 PNG 落盘并取得精确长度，再按长度读取。
        // 直接 exec:screencap -p 在真机上经常拿不到 EOF，会卡满 20 秒。
        val sizeOutput = shell("screencap -p " + remote + "; wc -c < " + remote, 20_000)
        val size = Regex("\\d+").findAll(sizeOutput).lastOrNull()?.value?.toIntOrNull()
            ?: throw IOException("截图失败：无法读取文件长度（" + sizeOutput.trim() + "）")
        val bytes = try {
            execBytesExact("cat " + remote, size, 20_000)
        } finally {
            runCatching { shell("rm -f " + remote, 5_000) }
        }
        Log.d(
            "AdbController",
            "screencapPng 用时 " + (SystemClock.elapsedRealtime() - started) + "ms，长度 " + bytes.size,
        )
        if (!isPng(bytes)) throw IOException("截图失败：没有得到有效 PNG")
        return bytes
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature.indices.all { bytes[it] == signature[it] }
    }

    data class Screen(val width: Int, val height: Int)

    fun screenSize(): Screen {
        if (cachedWidth > 0 && cachedHeight > 0) return Screen(cachedWidth, cachedHeight)
        val output = shell("wm size", 10_000)
        val matches = Regex("(\\d+)x(\\d+)").findAll(output).toList()
        val chosen = matches.lastOrNull() ?: throw IOException("无法读取屏幕分辨率")
        val width = chosen.groupValues[1].toInt()
        val height = chosen.groupValues[2].toInt()
        cachedWidth = width
        cachedHeight = height
        return Screen(width, height)
    }

    fun isScreenOn(): Boolean {
        val output = runCatching { shell("dumpsys power", 15_000) }.getOrDefault("")
        return output.contains("mWakefulness=Awake") || output.contains("Display Power: state=ON")
    }

    fun wakeAndUnlock() {
        if (!isScreenOn()) {
            shell("input keyevent 224", 5_000)
            SystemClock.sleep(400)
        }
        runCatching { shell("wm dismiss-keyguard", 5_000) }
        SystemClock.sleep(300)
    }

    fun isPackageInstalled(packageName: String): Boolean {
        val output = runCatching { shell("pm path " + packageName, 10_000) }.getOrDefault("")
        return output.contains("package:")
    }

    fun launchWeChat() {
        // 注意：不要在真机上 force-stop 微信。
        // ColorOS 在微信被强杀后会把之前的任务栈恢复出来，导致我们的 App 被重新拉到前台，
        // 自动化截图就会拍到「自家界面」而不是微信（真机实测踩过）。
        // 直接拉起/前置微信任务即可，冷启动慢的问题交给调用方的等待逻辑。
        var output = runCatching {
            shell("am start -W -n com.tencent.mm/.ui.LauncherUI", 25_000)
        }.getOrDefault("")
        if (!output.contains("Status: ok") && !output.contains("LaunchState")) {
            runCatching { shell("input keyevent 3", 5_000) }
            output = runCatching {
                shell("monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1", 20_000)
            }.getOrDefault(output)
        }
        if (output.contains("No activities found") || output.contains("does not exist")) {
            throw IOException("未安装微信（com.tencent.mm）")
        }
        SystemClock.sleep(1_200)
    }

    /** 当前前台 Activity 的包名；读不到时返回空字符串。 */
    fun topPackage(): String {
        val output = runCatching {
            shell(
                "dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'",
                30_000,
            )
        }.getOrDefault("")
        return Regex("""([A-Za-z0-9_.]+)/""").find(output)?.groupValues?.get(1).orEmpty()
    }

    fun isWeChatForeground(): Boolean = topPackage() == "com.tencent.mm"

    fun tap(x: Int, y: Int) {
        shell("input tap " + x + " " + y, 10_000)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        shell("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durationMs, 15_000)
    }

    fun keyEvent(keyCode: Int) {
        shell("input keyevent " + keyCode, 10_000)
    }

    fun isKeyboardShown(): Boolean {
        val output = runCatching { shell("dumpsys input_method", 15_000) }.getOrDefault("")
        return output.contains("mInputShown=true") || output.contains("mIsInputViewShown=true")
    }

    fun hideKeyboardIfShown() {
        if (isKeyboardShown()) {
            keyEvent(4)
            SystemClock.sleep(400)
        }
    }

    fun getProp(name: String): String =
        runCatching { shell("getprop " + name, 8_000).trim() }.getOrDefault("")

    fun deviceSummary(): String {
        val model = getProp("ro.product.model")
        val release = getProp("ro.build.version.release")
        return listOf(model, release).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun ping(timeoutMillis: Long = 8_000): Boolean {
        val output = runCatching { shell("echo wechatshot_ping", timeoutMillis) }.getOrDefault("")
        return output.contains("wechatshot_ping")
    }

    /**
     * 刚完成 ADB 握手时，前两个 shell 流偶尔会丢数据或一直等到超时（adbd 尚未准备好）。
     * 这里串行做几次短命令预热，确认 shell 通道真正可用后再继续。
     */
    fun warmUpShell(): Boolean {
        repeat(4) { attempt ->
            SystemClock.sleep(if (attempt == 0) 900 else 350)
            if (ping(4_000)) return true
        }
        return false
    }
}