package com.dsh.wechatshot.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Android 17（API 36+）起，访问本地网络（无线 ADB、mDNS 发现、局域网 socket）需要运行时权限。
 * 用字符串常量而不是 Build.VERSION_CODES / Manifest.permission 常量，避免低版本编译问题。
 */
internal const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** 该权限只在 API 36+ 存在；更低版本视为已具备（无需授权）。 */
internal fun localNetworkPermissionRequired(): Boolean = Build.VERSION.SDK_INT >= 36

internal fun hasLocalNetworkPermission(context: Context): Boolean {
    if (!localNetworkPermissionRequired()) return true
    return ContextCompat.checkSelfPermission(
        context,
        PERMISSION_ACCESS_LOCAL_NETWORK,
    ) == PackageManager.PERMISSION_GRANTED
}

/** 跳转到本应用的系统详情页，方便用户手动开启「本地网络访问」权限。 */
internal fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
