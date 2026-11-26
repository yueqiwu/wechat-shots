package com.dsh.wechatshot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dsh.wechatshot.adb.AdbManager
import com.dsh.wechatshot.connection.AutoPairScreen
import com.dsh.wechatshot.export.ExportBus
import com.dsh.wechatshot.update.UpdateGate
import com.dsh.wechatshot.video.VideoExportScreen

internal const val ROUTE_ADB = "adb"
internal const val ROUTE_VIDEO = "video"
internal const val ROUTE_AUTOPAIR = "autopair"

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

/**
 * App 根部：iOS Tab（首页 / 记录 / 设置）+ 全屏子路由（ADB 流程 / 录屏流程 / 自动配对）。
 * 不使用 navigation 依赖，单 BackHandler 统一处理返回栈。
 * 同时负责 Android 17（API 36+）「本地网络访问」运行时权限的请求与状态展示。
 */
@Composable
fun WeChatShotsRoot(
    incomingContact: String = "",
    resumeTick: Int = 0,
) {
    val context = LocalContext.current
    val colors = LocalIosColors.current

    val connection by AdbManager.connection.collectAsState()
    val pairing by AdbManager.pairing.collectAsState()
    val exportState by ExportBus.state.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var route by rememberSaveable { mutableStateOf("") }
    var adbStep by rememberSaveable { mutableIntStateOf(0) }
    var contactSeed by rememberSaveable { mutableStateOf("") }

    // ---------- 通知权限 ----------
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }
    LaunchedEffect(Unit) {
        if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------- 本地网络访问权限（Android 17 / API 36+） ----------
    var localNetworkGranted by remember { mutableStateOf(hasLocalNetworkPermission(context)) }
    var localNetworkRequested by rememberSaveable { mutableStateOf(false) }
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetworkGranted = granted
        localNetworkRequested = true
    }
    val requestLocalNetwork: () -> Unit = {
        if (localNetworkPermissionRequired() && !localNetworkGranted) {
            localNetworkRequested = true
            localNetworkLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
        }
    }
    val localNetworkDenied = localNetworkRequested && !localNetworkGranted

    // 进入「无线 ADB / 自动配对」流程时自动申请一次本地网络权限
    LaunchedEffect(route) {
        if (route == ROUTE_ADB || route == ROUTE_AUTOPAIR) {
            localNetworkGranted = hasLocalNetworkPermission(context)
            if (!localNetworkGranted && !localNetworkRequested && localNetworkPermissionRequired()) {
                localNetworkRequested = true
                localNetworkLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
            }
        }
    }

    // 回到前台（例如从系统设置页返回）时刷新权限状态
    LaunchedEffect(resumeTick) {
        notificationsGranted = hasNotificationPermission(context)
        localNetworkGranted = hasLocalNetworkPermission(context)
    }

    // 外部入口（通知 / 分享等带联系人进来）直接进入导出步骤
    LaunchedEffect(incomingContact) {
        if (incomingContact.isNotBlank()) {
            contactSeed = incomingContact
            route = ROUTE_ADB
            adbStep = 1
        }
    }

    // 连接成功事件发生时，若正停在连接步骤则自动切到导出步骤
    var wasConnected by remember { mutableStateOf(connection.connected) }
    LaunchedEffect(connection.connected) {
        if (connection.connected && !wasConnected && route == ROUTE_ADB && adbStep == 0) {
            adbStep = 1
        }
        wasConnected = connection.connected
    }

    BackHandler(enabled = route.isNotEmpty() || tab != 0) {
        when {
            route == ROUTE_AUTOPAIR -> {
                route = ROUTE_ADB
                adbStep = 0
            }
            route == ROUTE_ADB && adbStep == 1 -> adbStep = 0
            route.isNotEmpty() -> route = ""
            tab != 0 -> tab = 0
        }
    }

    // 版本更新入口：根部调用一次
    UpdateGate()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (route) {
                ROUTE_ADB -> AdbFlowScreen(
                    connection = connection,
                    pairing = pairing,
                    exportState = exportState,
                    step = adbStep,
                    onStepChange = { adbStep = it },
                    onBack = { route = "" },
                    onOpenAutoPair = { route = ROUTE_AUTOPAIR },
                    contactSeed = contactSeed,
                    localNetworkGranted = localNetworkGranted,
                    localNetworkDenied = localNetworkDenied,
                    onRequestLocalNetwork = requestLocalNetwork,
                    onOpenAppSettings = { openAppSettings(context) },
                )

                ROUTE_VIDEO -> VideoExportScreen(onBack = { route = "" })

                ROUTE_AUTOPAIR -> AutoPairScreen(
                    onBack = {
                        route = ROUTE_ADB
                        adbStep = 0
                    },
                    onConnected = {
                        route = ROUTE_ADB
                        adbStep = 1
                    },
                )

                else -> when (tab) {
                    0 -> HomeScreen(
                        connected = connection.connected,
                        deviceName = connection.device,
                        onOpenAdb = { requestedStep ->
                            route = ROUTE_ADB
                            adbStep = if (connection.connected) 1 else requestedStep
                        },
                        onOpenVideo = { route = ROUTE_VIDEO },
                        topPadding = 10.dp,
                        bottomPadding = 24.dp,
                    )

                    1 -> HistoryScreen(state = exportState)

                    else -> SettingsScreen(
                        connection = connection,
                        notificationsGranted = notificationsGranted,
                        onRequestNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onOpenConnection = { step ->
                            route = ROUTE_ADB
                            adbStep = step
                        },
                        onOpenAutoPair = { route = ROUTE_AUTOPAIR },
                        localNetworkGranted = localNetworkGranted,
                        localNetworkDenied = localNetworkDenied,
                        onRequestLocalNetwork = requestLocalNetwork,
                        onOpenAppSettings = { openAppSettings(context) },
                    )
                }
            }
        }

        if (route.isEmpty()) {
            IosTabBar(
                items = listOf(
                    IosTabItem("首页", Icons.Filled.Home),
                    IosTabItem("记录", Icons.Filled.History),
                    IosTabItem("设置", Icons.Filled.Settings),
                ),
                selected = tab,
                onSelected = { tab = it },
            )
        }
    }
}
