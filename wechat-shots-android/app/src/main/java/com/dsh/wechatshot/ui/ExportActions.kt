package com.dsh.wechatshot.ui

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dsh.wechatshot.export.ExportRecord
import com.dsh.wechatshot.export.ExportUiState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val DateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

internal fun formatTime(millis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

internal fun showDatePicker(
    context: Context,
    initial: LocalDate,
    onPicked: (LocalDate) -> Unit,
) {
    val dialog = DatePickerDialog(
        context,
        { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    )
    dialog.datePicker.maxDate = System.currentTimeMillis()
    dialog.show()
}

internal fun shareRecord(context: Context, record: ExportRecord) {
    val zip = record.zipFile
    if (zip != null && zip.isFile) {
        shareFile(context, zip)
        return
    }
    val images = record.directory.listFiles { file ->
        file.isFile && file.extension.equals("png", true)
    }?.sortedBy { it.name }.orEmpty()
    if (images.isEmpty()) {
        Toast.makeText(context, "没有可分享的截图", Toast.LENGTH_SHORT).show()
        return
    }
    shareFile(context, images.first())
}

internal fun shareResult(context: Context, state: ExportUiState) {
    val zip = state.zipPath?.let { File(it) }
    if (zip != null && zip.isFile) {
        shareFile(context, zip)
        return
    }
    val dir = state.resultDir?.let { File(it) }
    val images = dir?.listFiles { file ->
        file.isFile && file.extension.equals("png", true)
    }?.sortedBy { it.name }.orEmpty()
    if (images.isEmpty()) {
        Toast.makeText(context, "还没有生成可分享的文件", Toast.LENGTH_SHORT).show()
        return
    }
    shareFile(context, images.first())
}

internal fun shareFile(context: Context, file: File) {
    if (!file.isFile) {
        Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val mime = when (file.extension.lowercase()) {
            "zip" -> "application/zip"
            "png" -> "image/png"
            else -> "*/*"
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "export", uri)
        }
        context.startActivity(Intent.createChooser(send, "分享导出结果"))
    }.onFailure { error ->
        Toast.makeText(
            context,
            "分享失败：" + (error.message ?: error.javaClass.simpleName),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
