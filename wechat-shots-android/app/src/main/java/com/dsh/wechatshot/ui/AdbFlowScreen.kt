package com.dsh.wechatshot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.wechatshot.adb.ConnectionUiState
import com.dsh.wechatshot.adb.PairingDiscoveryState
import com.dsh.wechatshot.export.ExportUiState

/** 方案 A 的流程页：导航返回 + 「连接设备 / 导出截图」分段切换。 */
@Composable
fun AdbFlowScreen(
    connection: ConnectionUiState,
    pairing: PairingDiscoveryState,
    exportState: ExportUiState,
    step: Int,
    onStepChange: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenAutoPair: () -> Unit,
    contactSeed: String = "",
    localNetworkGranted: Boolean = true,
    localNetworkDenied: Boolean = false,
    onRequestLocalNetwork: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    IosGradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            IosNavBar(
                title = "无线 ADB 截图导出",
                modifier = Modifier.padding(horizontal = 12.dp),
                onBack = onBack,
            )
            IosSegmentedControl(
                options = listOf("连接设备", "导出截图"),
                selected = step,
                modifier = Modifier.padding(horizontal = 20.dp),
                onSelected = onStepChange,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (step == 0) {
                    ConnectionPane(
                        connection = connection,
                        pairing = pairing,
                        onOpenAutoPair = onOpenAutoPair,
                        localNetworkGranted = localNetworkGranted,
                        localNetworkDenied = localNetworkDenied,
                        onRequestLocalNetwork = onRequestLocalNetwork,
                        onOpenAppSettings = onOpenAppSettings,
                    )
                } else {
                    ExportPane(
                        connection = connection,
                        state = exportState,
                        onOpenConnection = { onStepChange(0) },
                        contactSeed = contactSeed,
                    )
                }
            }
        }
    }
}
