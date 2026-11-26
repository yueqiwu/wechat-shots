package com.dsh.wechatshot.stitch

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** 把任意 Bitmap 缩放并转成分析用灰度图（默认宽度 144，与网页版 ANALYSIS_WIDTH 一致）。 */
fun Bitmap.toAnalysisGray(targetWidth: Int = 144): ByteArray {
    val tw = maxOf(8, targetWidth)
    val th = maxOf(1, (height.toDouble() * tw / maxOf(1, width)).roundToInt())
    val scaled = if (width == tw && height == th) this else Bitmap.createScaledBitmap(this, tw, th, true)
    val px = IntArray(tw * th)
    scaled.getPixels(px, 0, tw, 0, 0, tw, th)
    if (scaled !== this) scaled.recycle()
    return StitchCore.grayFromArgb(px, tw, th)
}

/** 从 Bitmap 取一块区域的灰度（用于视频方案按用户裁剪区分析）。区域坐标为像素。 */
fun Bitmap.regionGray(left: Int, top: Int, right: Int, bottom: Int, targetWidth: Int): ByteArray {
    val l = left.coerceIn(0, width - 1)
    val t = top.coerceIn(0, height - 1)
    val r = right.coerceIn(l + 1, width)
    val b = bottom.coerceIn(t + 1, height)
    return Bitmap.createBitmap(this, l, t, r - l, b - t).toAnalysisGray(targetWidth)
}
