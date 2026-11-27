package com.dsh.wechatshot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.wechatshot.adb.ConnectionUiState
import com.dsh.wechatshot.export.ExportMode
import com.dsh.wechatshot.export.ExportBus
import com.dsh.wechatshot.export.ExportRepository
import com.dsh.wechatshot.export.ExportRequest
import com.dsh.wechatshot.export.ExportService
import com.dsh.wechatshot.export.ExportUiState
import java.time.LocalDate

/** 导出页：联系人 → 时间范围 → 导出选项 → 开始/停止 → 进度与结果。 */
@Composable
fun ExportPane(
    connection: ConnectionUiState,
    state: ExportUiState,
    onOpenConnection: () -> Unit,
    contactSeed: String = "",
) {
    val context = LocalContext.current
    val colors = LocalIosColors.current
    var contact by rememberSaveable { mutableStateOf(contactSeed) }
    var allMode by rememberSaveable { mutableStateOf(true) }
    var startDay by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(7).toEpochDay()) }
    var endDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var makeZip by rememberSaveable { mutableStateOf(true) }
    var saveToGallery by rememberSaveable { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(ExportRepository.recentContacts(context)) }

    LaunchedEffect(contactSeed) {
        if (contactSeed.isNotBlank()) contact = contactSeed
    }
    LaunchedEffect(state.running, state.finished) {
        if (!state.running) recent = ExportRepository.recentContacts(context)
    }

    val startDate = LocalDate.ofEpochDay(startDay)
    val endDate = LocalDate.ofEpochDay(endDay)
    val busy = state.running || state.stopping

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!connection.connected) {
            IosBanner(
                text = "还没有连接 ADB。先到「连接设备」用无线调试配对，连接成功后才能导出。",
                kind = IosBannerKind.Info,
                actionText = "去连接",
                onAction = onOpenConnection,
            )
        }

        IosSectionHeader(text = "1. 选择聊天对象")
        IosCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "填写微信通讯录里显示的联系人名称（备注名也可以）。App 会自动打开微信搜索并进入聊天。",
                    style = IosType.footnote,
                    color = colors.textSecondary,
                )
                IosTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    placeholder = "联系人名称，例如 张三",
                    enabled = !busy,
                    leadingIcon = Icons.Filled.Search,
                )
                if (recent.isNotEmpty()) {
                    Text(
                        text = "最近使用",
                        style = IosType.caption.copy(fontWeight = FontWeight.Medium),
                        color = colors.textSecondary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recent.forEach { name ->
                            Text(
                                text = name,
                                style = IosType.footnote,
                                color = if (busy) colors.textTertiary else colors.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(colors.accentSoft)
                                    .iosClickable(enabled = !busy) { contact = name }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        IosSectionHeader(text = "2. 选择时间范围")
        IosCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IosSegmentedControl(
                    options = listOf("全部记录", "日期区间"),
                    selected = if (allMode) 0 else 1,
                    onSelected = { index ->
                        if (!busy) allMode = index == 0
                    },
                )
                if (allMode) {
                    Text(
                        text = "从当前最新消息一直向前翻到聊天顶部；聊天很长时可能产生几百张截图，建议先试日期区间。",
                        style = IosType.footnote,
                        color = colors.textSecondary,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DateField(
                            label = "开始日期",
                            date = startDate,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                showDatePicker(context, startDate) { picked ->
                                    startDay = picked.toEpochDay()
                                }
                            },
                        )
                        DateField(
                            label = "结束日期",
                            date = endDate,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                showDatePicker(context, endDate) { picked ->
                                    endDay = picked.toEpochDay()
                                }
                            },
                        )
                    }
                    Text(
                        text = "包含起止日期。截图之间保留少量重叠（约 10%～25%，视机型而自动调整），输出时自动去重，确保跨页消息不被截断。",
                        style = IosType.footnote,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        IosSectionHeader(text = "3. 导出选项")
        IosCard {
            IosSwitchRow(
                title = "完成后打包 ZIP",
                subtitle = "方便一次性分享全部截图",
                checked = makeZip,
                enabled = !busy,
                onCheckedChange = { makeZip = it },
            )
            Hairline()
            IosSwitchRow(
                title = "同时保存到相册",
                subtitle = "复制到系统 Pictures/WeChatShots，可在相册查看",
                checked = saveToGallery,
                enabled = !busy,
                onCheckedChange = { saveToGallery = it },
            )
            Hairline()
            IosCheckRow(
                title = "裁剪状态栏和输入框",
                subtitle = "拼接时固定只保留聊天内容区域",
            )
        }

        formError?.let { message ->
            IosBanner(text = message, kind = IosBannerKind.Error)
        }

        if (busy) {
            IosSecondaryButton(
                text = if (state.stopping) "正在停止…" else "停止导出",
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.stopping,
                tint = colors.red,
                onClick = { ExportService.stop(context) },
            )
            IosFooterNote(text = "导出期间微信会保持在前台；也可以下拉通知栏点「停止导出」。")
        } else {
            IosPrimaryButton(
                text = "开始导出",
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.PlayArrow,
                onClick = {
                    val name = contact.trim()
                    when {
                        name.isEmpty() -> formError = "请输入微信联系人名称"
                        !connection.connected -> formError = "ADB 尚未连接，请先到「连接设备」页连接"
                        !allMode && endDate.isBefore(startDate) -> formError = "结束日期不能早于开始日期"
                        else -> {
                            formError = null
                            ExportRepository.rememberContact(context, name)
                            recent = ExportRepository.recentContacts(context)
                            val request = ExportRequest(
                                contact = name,
                                mode = if (allMode) ExportMode.ALL else ExportMode.RANGE,
                                startDate = if (allMode) null else startDate,
                                endDate = if (allMode) null else endDate,
                                cropStatusAndInput = true,
                                makeZip = makeZip,
                                saveToGallery = saveToGallery,
                            )
                            runCatching {
                                ExportService.start(context, request)
                            }.onFailure { error ->
                                formError = "无法启动导出服务：" +
                                    (error.message ?: error.javaClass.simpleName)
                            }
                        }
                    }
                },
            )
        }

        if (state.running || state.finished || state.error != null || state.log.isNotEmpty()) {
            ExportProgressCard(
                state = state,
                onStop = { ExportService.stop(context) },
                onShare = { shareResult(context, state) },
            )
        }

        IosFooterNote(
            text = "截图保存在 App 专属目录（Android/data/com.dsh.wechatshot/files/Pictures/WeChatShots），" +
                "卸载 App 会一起删除。需要长期保存时请用「分享结果」导出 ZIP，或打开「同时保存到相册」。"
        )
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun DateField(
    label: String,
    date: LocalDate,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalIosColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fill)
            .iosClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = label, style = IosType.caption, color = colors.textSecondary)
        Spacer(modifier = Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = null,
                tint = if (enabled) colors.accent else colors.textTertiary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = date.format(DateFormatter),
                style = IosType.callout.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun ExportProgressCard(
    state: ExportUiState,
    onStop: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalIosColors.current
    IosCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.running) "导出进行中" else "最近一次导出",
                        style = IosType.headline,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = state.contact + " · " + state.modeLabel,
                        style = IosType.footnote,
                        color = colors.textSecondary,
                    )
                }
                if (state.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                }
            }

            if (state.running) {
                IosProgressBar()
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IosStatTile(
                    label = "已扫描",
                    value = state.pagesScanned.toString(),
                    modifier = Modifier.weight(1f),
                )
                IosStatTile(
                    label = "已保存",
                    value = state.imagesSaved.toString(),
                    modifier = Modifier.weight(1f),
                )
                IosStatTile(
                    label = "当前日期",
                    value = state.currentDate.ifBlank { "-" },
                    modifier = Modifier.weight(1.2f),
                )
            }

            if (state.message.isNotBlank()) {
                Text(text = state.message, style = IosType.footnote, color = colors.textSecondary)
            }
            state.error?.let { message ->
                IosBanner(text = message, kind = IosBannerKind.Error)
            }
            if (state.warnings.isNotEmpty()) {
                IosBanner(
                    text = state.warnings.take(3).joinToString("\n"),
                    kind = IosBannerKind.Warning,
                )
            }
            state.resultDir?.let { path ->
                Hairline(startIndent = 0.dp)
                Text(
                    text = "保存位置",
                    style = IosType.caption.copy(fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                )
                SelectionContainer {
                    Text(
                        text = path,
                        style = IosType.caption.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textSecondary,
                    )
                }
            }
            if (state.log.isNotEmpty()) {
                Hairline(startIndent = 0.dp)
                Text(
                    text = "运行日志",
                    style = IosType.caption.copy(fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                )
                IosCodeBlock(text = state.log.takeLast(15).joinToString("\n"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.running) {
                    IosSecondaryButton(
                        text = if (state.stopping) "正在停止…" else "停止",
                        modifier = Modifier.weight(1f),
                        enabled = !state.stopping,
                        tint = colors.red,
                        onClick = onStop,
                    )
                }
                if (state.resultDir != null) {
                    IosPrimaryButton(
                        text = "分享结果",
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Share,
                        onClick = onShare,
                    )
                }
            }
        }
    }
}
