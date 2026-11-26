package com.dsh.wechatshot.export

import android.content.Context
import android.os.Environment
import java.io.File

data class ExportRecord(
    val directory: File,
    val name: String,
    val imageCount: Int,
    val zipFile: File?,
    val modifiedAt: Long,
)

object ExportRepository {
    private const val PREFS = "wechat_shots"
    private const val KEY_RECENT = "recent_contacts"
    private const val DELIMITER = "\u0001"

    fun root(context: Context): File {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "Pictures")
        return File(pictures, "WeChatShots")
    }

    fun list(context: Context): List<ExportRecord> {
        val root = root(context)
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val images = dir.listFiles { file ->
                    file.isFile && file.extension.equals("png", true)
                }.orEmpty()
                if (images.isEmpty()) return@mapNotNull null
                val zip = File(root, dir.name + ".zip").takeIf { it.isFile }
                ExportRecord(
                    directory = dir,
                    name = dir.name,
                    imageCount = images.size,
                    zipFile = zip,
                    modifiedAt = dir.lastModified(),
                )
            }
            .sortedByDescending { it.modifiedAt }
    }

    fun recentContacts(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(DELIMITER).filter { it.isNotBlank() }.take(8)
    }

    fun rememberContact(context: Context, contact: String) {
        val name = contact.trim()
        if (name.isEmpty()) return
        val updated = (listOf(name) + recentContacts(context))
            .distinct()
            .take(8)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT, updated.joinToString(DELIMITER))
            .apply()
    }
}
