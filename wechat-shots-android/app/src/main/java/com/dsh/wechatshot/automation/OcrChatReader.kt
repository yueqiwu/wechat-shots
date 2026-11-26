package com.dsh.wechatshot.automation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.time.LocalDate

data class OcrPageAnalysis(
    val document: UiDocument,
    val dates: List<LocalDate>,
    val signature: String,
)

/**
 * 把聊天区域的截图转换成与 uiautomator 类似的 UiDocument，复用现有的日期判断和翻页逻辑。
 */
object OcrChatReader {
    suspend fun analyze(png: ByteArray, layout: ChatLayout): OcrPageAnalysis {
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IOException("截图解码失败")
        try {
            val x = layout.left.coerceIn(0, (source.width - 1).coerceAtLeast(0))
            val y = layout.top.coerceIn(0, (source.height - 1).coerceAtLeast(0))
            val width = layout.width.coerceAtMost(source.width - x)
            val height = layout.height.coerceAtMost(source.height - y)
            if (width <= 0 || height <= 0) throw IOException("聊天区域尺寸无效")

            val crop = Bitmap.createBitmap(source, x, y, width, height)
            try {
                val lines = OcrEngine.recognize(crop).map { it.offset(x, y) }
                val doc = UiDocument.fromOcr(source.width, source.height, lines)
                val dates = DateTags.datesIn(doc)
                val signature = lines.asSequence()
                    .filter { it.bottom > layout.top && it.top < layout.bottom }
                    .joinToString("\n") { it.text.trim() }
                return OcrPageAnalysis(doc, dates, signature)
            } finally {
                if (crop !== source) crop.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
