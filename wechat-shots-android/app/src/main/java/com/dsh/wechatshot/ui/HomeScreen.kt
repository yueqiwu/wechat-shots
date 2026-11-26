package com.dsh.wechatshot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/** 首页：顶部品牌 + 大标题 + 两张方案卡片 + 选择说明。 */
@Composable
fun HomeScreen(
    connected: Boolean,
    deviceName: String?,
    onOpenAdb: (step: Int) -> Unit,
    onOpenVideo: () -> Unit,
    modifier: Modifier = Modifier,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomPadding: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val colors = LocalIosColors.current
    IosPage(modifier = modifier, topPadding = topPadding, bottomPadding = bottomPadding) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(colors.green, colors.accent))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Smartphone,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = "聊天截图导出",
                style = IosType.headline,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (connected) colors.greenSoft else colors.fill
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (connected) "已连接" else "未连接",
                    style = IosType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    color = if (connected) colors.green else colors.textSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        IosLargeTitle(
            title = "导出聊天记录",
            subtitle = "自动翻页截图，或上传一段屏幕录制，两种方式都行。",
        )

        Spacer(modifier = Modifier.height(6.dp))
        IosSectionHeader(text = "选择导出方案")

        IosChoiceCard(
            title = "无线 ADB 截图导出",
            subtitle = "自动翻页截图 · 现有方案",
            description = "App 通过无线 ADB 连接手机或模拟器，自动滚动聊天列表并逐页截图，再拼接成长图。",
            tags = listOf("自动翻页", "逐页截图", "长图拼接"),
            icon = Icons.Filled.Smartphone,
            gradient = listOf(colors.accent, colors.accent2),
            footnote = "需要开启无线调试并完成配对",
            onClick = { onOpenAdb(0) },
        )

        IosChoiceCard(
            title = "录屏转截图",
            subtitle = "上传屏幕录制 · 新增方案",
            description = "先录一段滑动聊天记录的屏幕视频，再上传到 App，自动切页、去重并拼接输出长图。",
            tags = listOf("录屏切割", "自动去重", "长图拼接"),
            icon = Icons.Filled.Movie,
            gradient = listOf(colors.orange, colors.orange2),
            footnote = "无需 ADB，适合不方便连接调试的设备",
            onClick = onOpenVideo,
        )

        if (connected) {
            IosCard(
                onClick = { onOpenAdb(1) },
            ) {
                IosRow(
                    title = "设备已连接，可以直接导出",
                    subtitle = deviceName ?: "无线 ADB 已就绪",
                    icon = Icons.Filled.CheckCircle,
                    iconTint = colors.green,
                    iconBackground = colors.greenSoft,
                    chevron = true,
                )
            }
        } else {
            IosBanner(
                text = "还没有连接设备。进入「无线 ADB 截图导出」后会自动发现设备，也可以手动配对。",
                kind = IosBannerKind.Info,
            )
        }

        IosSectionHeader(text = "两种方案怎么选")
        IosCard {
            IosRow(
                title = "想完全自动",
                subtitle = "选无线 ADB 截图：连接一次，自动翻页到底。",
                icon = Icons.Filled.Smartphone,
                iconTint = colors.accent,
                iconBackground = colors.accentSoft,
            )
            Hairline()
            IosRow(
                title = "不方便连接调试",
                subtitle = "选录屏转截图：手动录屏后上传，离线完成切割与拼接。",
                icon = Icons.Filled.Movie,
                iconTint = colors.orange,
                iconBackground = colors.orangeSoft,
            )
        }

        IosFooterNote(
            text = "拼接时会固定裁掉状态栏和输入栏，保证页面干净；导出结果统一保存在应用文档目录，可在「记录」中查看、分享或删除。"
        )
        Spacer(modifier = Modifier.height(2.dp))
    }
}
