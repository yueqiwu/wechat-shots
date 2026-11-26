package com.dsh.wechatshot.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.wechatshot.adb.AdbManager
import com.dsh.wechatshot.adb.ConnectionUiState

/** 设置页：连接、导出偏好、通知、关于。 */
@Composable
fun SettingsScreen(
    connection: ConnectionUiState,
    notificationsGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenConnection: (step: Int) -> Unit,
    onOpenAutoPair: () -> Unit,
    localNetworkGranted: Boolean = true,
    localNetworkDenied: Boolean = false,
    onRequestLocalNetwork: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val colors = LocalIosColors.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    IosPage(topPadding = 14.dp, bottomPadding = 28.dp) {
        IosLargeTitle(
            title = "设置",
            subtitle = "连接、导出偏好与关于信息都在这里。",
        )

        IosSectionHeader(text = "设备与连接")
        IosCard {
            IosRow(
                title = "无线 ADB 连接",
                subtitle = connection.status,
                value = if (connection.connected) "已连接" else "未连接",
                icon = Icons.Filled.Wifi,
                iconTint = if (connection.connected) colors.green else colors.textSecondary,
                iconBackground = if (connection.connected) colors.greenSoft else colors.fill,
                chevron = true,
                onClick = { onOpenConnection(0) },
            )
            Hairline()
            IosRow(
                title = "截图自动配对",
                subtitle = "配对助手自动发现端口并连接",
                icon = Icons.Filled.Link,
                iconTint = colors.orange,
                iconBackground = colors.orangeSoft,
                chevron = true,
                onClick = onOpenAutoPair,
            )
            Hairline()
            IosRow(
                title = "本地网络访问权限",
                subtitle = when {
                    localNetworkGranted -> "无线调试与自动发现设备所需，已授权"
                    localNetworkDenied -> "未授权，无法发现设备；点此前往系统设置开启"
                    else -> "未授权时无法发现设备和连接无线调试"
                },
                value = if (localNetworkGranted) "已允许" else "未允许",
                icon = Icons.Filled.Wifi,
                iconTint = if (localNetworkGranted) colors.green else colors.orange,
                iconBackground = if (localNetworkGranted) colors.greenSoft else colors.orangeSoft,
                chevron = !localNetworkGranted,
                onClick = when {
                    localNetworkGranted -> null
                    localNetworkDenied -> onOpenAppSettings
                    else -> onRequestLocalNetwork
                },
            )
            if (connection.connected || connection.busy) {
                Hairline()
                IosRow(
                    title = "断开连接",
                    titleColor = colors.red,
                    icon = Icons.Filled.Wifi,
                    iconTint = colors.red,
                    iconBackground = colors.redSoft,
                    onClick = { AdbManager.disconnect() },
                )
            }
        }

        IosSectionHeader(text = "导出偏好")
        IosCard {
            IosCheckRow(
                title = "固定裁剪状态栏和输入框",
                subtitle = "拼接只保留聊天内容区域，保证页面干净",
            )
            Hairline()
            IosRow(
                title = "完成后打包 ZIP",
                subtitle = "导出页默认开启，可按次调整",
                value = "默认开启",
            )
            Hairline()
            IosRow(
                title = "同时保存到相册",
                subtitle = "导出页默认关闭，可按次调整",
                value = "默认关闭",
            )
        }

        IosSectionHeader(text = "通知")
        IosCard {
            IosRow(
                title = "通知权限",
                subtitle = if (notificationsGranted) {
                    "导出进度与完成结果会通过通知提醒"
                } else {
                    "未授权时无法在通知栏查看进度或停止导出"
                },
                value = if (notificationsGranted) "已允许" else "未允许",
                icon = Icons.Filled.Notifications,
                iconTint = if (notificationsGranted) colors.green else colors.orange,
                iconBackground = if (notificationsGranted) colors.greenSoft else colors.orangeSoft,
                chevron = !notificationsGranted,
                onClick = if (notificationsGranted) null else onRequestNotifications,
            )
        }

        IosSectionHeader(text = "关于")
        IosCard {
            IosRow(
                title = "应用名称",
                value = "聊天截图导出",
                icon = Icons.Filled.Info,
                iconTint = colors.accent,
                iconBackground = colors.accentSoft,
            )
            Hairline()
            IosRow(
                title = "版本",
                value = if (versionName.isBlank()) "1.0.1" else versionName,
            )
            Hairline()
            IosRow(
                title = "更新",
                subtitle = "启动时会自动检查新版本并提示",
                value = "自动",
            )
        }

        IosFooterNote(
            text = "聊天记录只在本机处理，不会上传到服务器；录屏文件与截图均保存在 App 私有目录。"
        )
        Spacer(modifier = Modifier.height(6.dp))
    }
}
