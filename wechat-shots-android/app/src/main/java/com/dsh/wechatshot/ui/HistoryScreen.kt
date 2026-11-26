package com.dsh.wechatshot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.wechatshot.export.ExportRecord
import com.dsh.wechatshot.export.ExportRepository
import com.dsh.wechatshot.export.ExportUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 记录页：iOS 分组卡片列表 + 分享 / 删除。 */
@Composable
fun HistoryScreen(state: ExportUiState) {
    val context = LocalContext.current
    val colors = LocalIosColors.current
    var records by remember { mutableStateOf<List<ExportRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ExportRecord?>(null) }

    LaunchedEffect(refresh, state.finished, state.imagesSaved) {
        loading = true
        records = withContext(Dispatchers.IO) { ExportRepository.list(context) }
        loading = false
    }

    IosGradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "导出记录",
                                style = IosType.largeTitle,
                                color = colors.textPrimary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "保存在 " + ExportRepository.root(context).absolutePath,
                                style = IosType.caption,
                                color = colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IosTextButton(text = "刷新") { refresh++ }
                    }
                }

                if (loading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.5.dp,
                                color = colors.accent,
                            )
                        }
                    }
                } else if (records.isEmpty()) {
                    item {
                        IosCard {
                            IosEmptyState(
                                icon = Icons.Filled.Folder,
                                title = "还没有导出记录",
                                subtitle = "完成一次导出后，这里会按时间列出结果，可直接分享。",
                            )
                        }
                    }
                } else {
                    items(records, key = { it.directory.absolutePath }) { record ->
                        RecordCard(
                            record = record,
                            onShare = { shareRecord(context, record) },
                            onDelete = { pendingDelete = record },
                        )
                    }
                }

                item {
                    IosFooterNote(
                        text = "记录保存在 App 专属目录，卸载 App 会一起删除；需要长期保留请先分享 ZIP 或保存到相册。"
                    )
                }
            }
        }
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除导出记录？") },
            text = { Text("将删除目录 " + record.name + " 以及对应 ZIP，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        runCatching {
                            record.directory.deleteRecursively()
                            record.zipFile?.delete()
                        }
                        refresh++
                    },
                ) {
                    Text("删除", color = colors.red)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun RecordCard(
    record: ExportRecord,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalIosColors.current
    IosCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.name,
                        style = IosType.body.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = record.imageCount.toString() + " 张 · " +
                            formatTime(record.modifiedAt) +
                            if (record.zipFile != null) " · 已打包 ZIP" else "",
                        style = IosType.caption,
                        color = colors.textSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Hairline(startIndent = 0.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IosTextButton(text = "分享", onClick = onShare)
                IosTextButton(text = "删除", color = colors.red, onClick = onDelete)
            }
        }
    }
}
