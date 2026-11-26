package com.dsh.wechatshot.automation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一行 OCR 文本及其在屏幕上的像素位置。 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top

    fun offset(dx: Int, dy: Int): OcrLine =
        OcrLine(text, left + dx, top + dy, right + dx, bottom + dy)
}

data class OcrScreen(
    val width: Int,
    val height: Int,
    val lines: List<OcrLine>,
)

/**
 * ML Kit 中文识别。模型随 APK 打包（bundled），不依赖 Google Play 服务在线下载。
 */
object OcrEngine {
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                if (!continuation.isActive) return@addOnSuccessListener
                val lines = ArrayList<OcrLine>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val text = line.text.trim()
                        if (text.isNotEmpty()) {
                            lines.add(OcrLine(text, box.left, box.top, box.right, box.bottom))
                        }
                    }
                }
                lines.sortWith(compareBy({ it.top }, { it.left }))
                continuation.resume(lines)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    suspend fun recognizePng(png: ByteArray): OcrScreen {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IOException("截图解码失败")
        try {
            return OcrScreen(bitmap.width, bitmap.height, recognize(bitmap))
        } finally {
            bitmap.recycle()
        }
    }
}
