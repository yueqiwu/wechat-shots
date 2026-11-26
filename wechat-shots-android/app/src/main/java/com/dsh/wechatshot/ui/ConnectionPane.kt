package com.dsh.wechatshot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.wechatshot.adb.AdbManager
import com.dsh.wechatshot.adb.ConnectionUiState
import com.dsh.wechatshot.adb.PairingDiscoveryState

/** 无线 ADB 连接页：状态卡 + 自动配对入口 + 配对码连接 + 手动连接 + 帮助。 */
@Composable
fun ConnectionPane(
    connection: ConnectionUiState,
    pairing: PairingDiscoveryState,
    onOpenAutoPair: () -> Unit,
    localNetworkGranted: Boolean = true,
    localNetworkDenied: Boolean = false,
    onRequestLocalNetwork: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    val colors = LocalIosColors.current
    var code by rememberSaveable { mutableStateOf("") }
    var manualPairPort by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("127.0.0.1") }
    var port by rememberSaveable { mutableStateOf("5555") }

    // Android 17（API 36+）本地网络权限门禁：未授权时先请求或跳系统设置，不发起连接
    val ensureLocalNetwork: () -> Boolean = {
        if (localNetworkGranted) {
            true
        } else {
            if (localNetworkDenied) onOpenAppSettings() else onRequestLocalNetwork()
            false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 连接状态卡
        IosCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (connection.connected) colors.greenSoft else colors.fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = if (connection.connected) colors.green else colors.textSecondary,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "无线 ADB 连接",
                        style = IosType.headline,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = connection.status,
                        style = IosType.footnote,
                        color = if (connection.connected) colors.green else colors.textSecondary,
                    )
                }
                if (connection.connected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = colors.green,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            if (connection.device.isNotBlank()) {
                Hairline()
                Text(
                    text = connection.device,
                    style = IosType.caption.copy(fontFamily = FontFamily.Monospace),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            connection.error?.let { message ->
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    IosBanner(text = message, kind = IosBannerKind.Error)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IosPrimaryButton(
                    text = when {
                        connection.connected -> "已连接"
                        connection.busy -> "连接中…"
                        else -> "自动连接"
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !connection.busy && !connection.connected,
                    loading = connection.busy,
                    onClick = { if (ensureLocalNetwork()) AdbManager.connectAuto() },
                )
                IosSecondaryButton(
                    text = "断开",
                    modifier = Modifier.weight(1f),
                    enabled = connection.connected || connection.busy,
                    tint = if (connection.connected) colors.red else colors.textSecondary,
                    onClick = { AdbManager.disconnect() },
                )
            }
        }

        // Android 17 起访问本地网络需要运行时权限
        if (!localNetworkGranted) {
            IosBanner(
                text = "Android 17 起，发现无线调试设备需要「本地网络访问」权限。" +
                    if (localNetworkDenied) {
                        "当前未授权，请到系统设置中开启后再连接。"
                    } else {
                        "授权后才能自动发现设备。"
                    },
                kind = IosBannerKind.Warning,
                actionText = if (localNetworkDenied) "去设置" else "去授权",
                onAction = if (localNetworkDenied) onOpenAppSettings else onRequestLocalNetwork,
            )
            if (!localNetworkDenied) {
                IosTextButton(
                    text = "打开系统设置手动开启",
                    modifier = Modifier.padding(start = 4.dp),
                    onClick = onOpenAppSettings,
                )
            }
        }

        // 突出的自动配对入口
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(colors.orange, colors.orange2)))
                .iosClickable(onClick = onOpenAutoPair)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "截图自动配对",
                        style = IosType.headline,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "配对助手自动发现端口并完成连接，推荐首选",
                        style = IosType.footnote,
                        color = Color.White.copy(alpha = 0.88f),
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color.White.copy(alpha = 0.20f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "打开配对助手",
                    style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }

        IosSectionHeader(text = "首次使用：用配对码连接")
        IosCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "1. 在手机上打开「设置 → 开发者选项 → 无线调试」。\n" +
                        "2. 点「使用配对码配对设备」，把手机上的 6 位配对码填到下面。\n" +
                        "3. 保持该配对页面打开，点「发现配对端口」；发现失败可手动输入页面上的端口。\n" +
                        "4. 点「配对并连接」。首次连接时手机上可能弹出授权提示，请点「允许」。",
                    style = IosType.footnote,
                    color = colors.textSecondary,
                )
                IosTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) },
                    placeholder = "6 位配对码（例如 123456）",
                    enabled = !connection.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    trailing = {
                        Text(
                            text = code.length.toString() + "/6",
                            style = IosType.caption,
                            color = colors.textSecondary,
                        )
                    },
                )
                IosTextField(
                    value = manualPairPort,
                    onValueChange = { value -> manualPairPort = value.filter { it.isDigit() }.take(5) },
                    placeholder = "配对端口（可选，自动发现失败时填）",
                    enabled = !connection.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (pairing.discovering || pairing.port > 0 || pairing.message.isNotBlank()) {
                    val text = buildString {
                        if (pairing.port > 0) {
                            append("已发现配对端口：")
                            append(pairing.host ?: "本机")
                            append(":")
                            append(pairing.port)
                            if (pairing.message.isNotBlank()) append("\n")
                        }
                        if (pairing.message.isNotBlank()) append(pairing.message)
                    }
                    IosBanner(text = text, kind = IosBannerKind.Info)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IosSecondaryButton(
                        text = if (pairing.discovering) "停止发现" else "发现配对端口",
                        modifier = Modifier.weight(1f),
                        enabled = !connection.busy,
                        loading = pairing.discovering,
                        onClick = {
                            if (ensureLocalNetwork()) {
                                if (pairing.discovering) {
                                    AdbManager.stopPairingDiscovery()
                                } else {
                                    AdbManager.startPairingDiscovery()
                                }
                            }
                        },
                    )
                    IosPrimaryButton(
                        text = "配对并连接",
                        modifier = Modifier.weight(1f),
                        enabled = code.length == 6 && !connection.busy,
                        loading = connection.busy,
                        onClick = { if (ensureLocalNetwork()) AdbManager.pair(code, manualPairPort) },
                    )
                }
                IosFooterNote(
                    text = "配对页面关闭后端口会失效。若提示「配对码不正确」，请重新打开配对页面再试。"
                )
            }
        }

        IosTextButton(
            text = if (advanced) "收起手动连接" else "手动连接（模拟器 / 固定端口）",
            modifier = Modifier.padding(start = 4.dp),
        ) {
            advanced = !advanced
        }

        if (advanced) {
            IosCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "适用于模拟器，或已通过 adb tcpip 5555 固定端口的设备。手机连接自己时主机填 127.0.0.1。",
                        style = IosType.footnote,
                        color = colors.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IosTextField(
                            value = host,
                            onValueChange = { host = it },
                            placeholder = "主机",
                            modifier = Modifier.weight(1.4f),
                            enabled = !connection.busy,
                        )
                        IosTextField(
                            value = port,
                            onValueChange = { value -> port = value.filter { it.isDigit() }.take(5) },
                            placeholder = "端口",
                            modifier = Modifier.weight(1f),
                            enabled = !connection.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    IosPrimaryButton(
                        text = "连接该地址",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connection.busy,
                        loading = connection.busy,
                        onClick = { if (ensureLocalNetwork()) AdbManager.connectManual(host, port) },
                    )
                }
            }
        }

        IosCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "连接不上时",
                        style = IosType.headline,
                        color = colors.textPrimary,
                    )
                }
                Text(
                    text = "· 确认手机和本机在同一 Wi-Fi，且「无线调试」开关保持打开。\n" +
                        "· 部分系统在开启无线调试后需要保持屏幕解锁。\n" +
                        "· 若之前配过对但连接失败，先到系统无线调试页面「取消配对」，再按上面步骤重新配对。\n" +
                        "· 小米/OPPO/vivo 等系统可能需要在开发者选项里允许「USB 调试（安全设置）」或关闭权限监控。",
                    style = IosType.footnote,
                    color = colors.textSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
