package com.dsh.wechatshot.connection

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 无障碍配对助手（自动配对的主路径）。
 *
 * 背景：真机上（ColorOS / Android 17）一旦把应用切到后台，即使有 dataSync 前台服务，
 * 主线程定时器也会被系统冻结——MediaStore 截图监听和轮询都不可靠；而配对弹窗一旦被切走就会关闭，
 * 配对端口随之失效。因此改为用无障碍服务直接读取「与设备配对」弹窗的窗口文本，在弹窗还开着的时候
 * 完成配对，全程不需要用户截图、也不需要切回应用。
 *
 * 该助手需要用户一次性在「设置 → 无障碍」里启用。
 */
object PairingAssist {

    const val TAG = "PairingAssist"

    private const val PREFS = "pairing_assist"
    private const val KEY_REQUESTED_AT = "requested_at"

    /** 用户在自动配对页点了「打开无线调试设置」后，5 分钟内的配对弹窗都会自动处理。 */
    private const val VALID_WINDOW_MS = 5 * 60 * 1000L

    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context, PairingAssistAccessibilityService::class.java)

    /** 无障碍服务是否已启用（无需绑定，直接读系统设置）。 */
    fun isEnabled(context: Context): Boolean {
        return runCatching {
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
            if (!enabled) return false
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val target = serviceComponent(context).flattenToString()
            flat.split(':').any { it.equals(target, ignoreCase = true) }
        }.getOrDefault(false)
    }

    fun requestAssist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_REQUESTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun isAssistRequested(context: Context): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_REQUESTED_AT, 0L)
        return at > 0 && System.currentTimeMillis() - at <= VALID_WINDOW_MS
    }

    fun clearRequest(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_REQUESTED_AT, 0L)
            .apply()
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "无法打开无障碍设置", it) }
    }

    /** 写文件日志（真机后台 logcat 会被 OEM 吞掉，只能靠文件排查）。 */
    fun log(context: Context, message: String) {
        Log.d(TAG, message)
        runCatching {
            val dir: File = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "pairing_assist.log")
            if (file.exists() && file.length() > 512 * 1024) file.delete()
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$time $message\n")
        }
    }
}
