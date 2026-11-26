package com.dsh.wechatshot.stitch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * 渲染时的整帧来源。两套导出方案各实现一个：
 *  - 无线 ADB：按帧号解码已保存的裁剪后截图 PNG；
 *  - 录屏：按帧时间戳用 MediaMetadataRetriever 取原始帧并按 cropTopPx/cropBotPx 裁剪。
 * 返回的 Bitmap 宽必须等于 [width]，高 >= plan.cropHpx。
 */
interface FrameImageSource {
    val width: Int
    fun frameImage(frameIndex: Int): Bitmap?
}

/**
 * 把 [MasterPlan] 渲染成互不重叠、上下连续的整页截图。
 * ops 里 src 是「裁剪区第几行」，所以源图取 plan.topPx + src 起。
 */
class StitchTiles(
    private val plan: MasterPlan,
    private val source: FrameImageSource,
) {
    val count: Int get() = plan.cuts.size - 1

    fun render(index: Int): Bitmap {
        require(index in 0 until count) { "页码越界: $index / $count" }
        val y0 = plan.cuts[index]
        val y1 = plan.cuts[index + 1]
        val tileH = maxOf(1, y1 - y0)
        val out = Bitmap.createBitmap(source.width, tileH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val dst = Rect()
        val src = Rect()
        var lastFrame = -1
        var lastBmp: Bitmap? = null
        for (op in plan.ops) {
            if (op.y1 <= y0) continue
            if (op.y0 >= y1) break
            val from = maxOf(op.y0, y0)
            val to = minOf(op.y1, y1)
            val bmp = when {
                op.frame == lastFrame -> lastBmp
                else -> source.frameImage(op.frame).also {
                    lastFrame = op.frame
                    lastBmp = it
                }
            } ?: continue
            val len = to - from
            val srcY = (plan.topPx + op.src + (from - op.y0)).coerceIn(0, maxOf(0, bmp.height - len))
            src.set(0, srcY, source.width, srcY + len)
            dst.set(0, from - y0, source.width, from - y0 + len)
            canvas.drawBitmap(bmp, src, dst, paint)
        }
        return out
    }

    /** 逐页渲染并把位图交给调用方写文件（调用方负责 recycle）。 */
    fun renderAll(onTile: (index: Int, bitmap: Bitmap) -> Unit) {
        for (i in 0 until count) onTile(i, render(i))
    }
}
