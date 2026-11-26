package com.dsh.wechatshot.stitch

import com.dsh.wechatshot.export.StitchQualityCheck
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 纯 JVM 单测：用合成灰度数据验证「截图 → 拼长图 → 按空行切页」的流程。
 *
 * 合成聊天长图：144 宽，每 200 行一个消息周期（150 行内容 + 50 行纯白空行），
 * 按 50% 重叠切成 9 张 1200 高的「截图」，验证：
 *  - 重建长图高度 ≈ 原始长图高度（内容不重复、不丢失）；
 *  - 切页边界落在空行上，不会把一条消息劈成两半；
 *  - 按 ops 重建的像素与原始长图差异极小；
 *  - 质量检查在正常数据上通过、在「没滚动」的异常数据上拒绝拼接。
 *
 * 这里不复用 StitchTiles（依赖 android.graphics.Bitmap），而是按同样的 ops 规则
 * 用 IntArray 复刻一遍像素映射，保证单测不依赖 Android 运行时。
 */
class StitchPlannerTest {

    private val width = 144
    private val period = 200
    private val messageH = 150
    private val gapH = period - messageH
    private val frameH = 1200

    /**
     * 构造一张合成聊天长图（灰度值 0..255）。
     *
     * 刻意不用固定周期：每条消息高度、气泡宽度/位置、文字行都随机，
     * 只留消息之间的纯白空行。否则固定的周期性内容会让「第一对帧」的窄窗匹配
     * 撞上错位峰值（周期混叠），测试就测不出真实数据下的行为了。
     */
    private fun buildChatImage(totalH: Int, seed: Int = 7): ByteArray {
        val pixels = ByteArray(totalH * width) { 255.toByte() }
        val random = Random(seed)
        var y = random.nextInt(20)
        var block = 0
        while (y < totalH) {
            val gap = 24 + random.nextInt(56)          // 24..79 行纯白空行
            val messageH = 60 + random.nextInt(160)    // 60..219 行消息
            val messageTop = y + gap
            val messageBot = minOf(totalH, messageTop + messageH)
            if (messageTop >= totalH) break
            if (messageBot > messageTop) {
                val incoming = block % 2 == 0
                if (incoming) {
                    // 左侧头像
                    val avatarW = 22 + random.nextInt(8)
                    fillRect(pixels, totalH, 4, 4 + avatarW, messageTop + 4, messageBot - 4, 30 + random.nextInt(40))
                }
                // 气泡：宽度和横向位置随机，左右交替
                val bubbleW = 46 + random.nextInt(72)
                val bubbleX = if (incoming) 34 + random.nextInt(10) else width - bubbleW - 12 - random.nextInt(10)
                val bubbleTop = messageTop + 2
                val bubbleBot = messageBot - 2
                fillRect(pixels, totalH, bubbleX, bubbleX + bubbleW, bubbleTop, bubbleBot, 70 + random.nextInt(100))
                // 气泡内文字行：宽窄、灰度、行距随机
                var ty = bubbleTop + 6 + random.nextInt(6)
                while (ty + 4 < bubbleBot - 4) {
                    val tx = bubbleX + 5 + random.nextInt(6)
                    val tw = 16 + random.nextInt(maxOf(1, bubbleW - 14))
                    val tx1 = minOf(bubbleX + bubbleW - 3, tx + tw)
                    fillRect(pixels, totalH, tx, tx1, ty, ty + 3 + random.nextInt(4), random.nextInt(80))
                    ty += 10 + random.nextInt(8)
                }
            }
            y = messageBot + random.nextInt(10)
            block++
        }
        return pixels
    }

    private fun fillRect(
        pixels: ByteArray,
        totalH: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        value: Int,
    ) {
        val v = value.coerceIn(0, 255).toByte()
        for (yy in y0.coerceAtLeast(0) until y1.coerceAtMost(totalH)) {
            val base = yy * width
            for (xx in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) {
                pixels[base + xx] = v
            }
        }
    }

    /** 从长图按 top 起截 frameH 行作为一张「截图」。 */
    private fun cropFrame(longImage: ByteArray, totalH: Int, top: Int): ByteArray {
        val frame = ByteArray(frameH * width)
        for (row in 0 until frameH) {
            val src = (top + row) * width
            System.arraycopy(longImage, src, frame, row * width, width)
        }
        return frame
    }

    private fun framesOf(longImage: ByteArray, totalH: Int, step: Int): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var top = 0
        while (top + frameH <= totalH) {
            frames.add(cropFrame(longImage, totalH, top))
            top += step
        }
        return frames
    }

    private fun samplesOf(frames: List<ByteArray>): List<GraySample> =
        frames.mapIndexed { index, gray -> GraySample(timeUs = index.toLong() * 1_000_000L, gray = gray) }

    /**
     * 按 StitchTiles.render 的 ops 规则，把帧像素重建成主图（返回灰度 IntArray）。
     * 与 StitchTiles 的区别只是 Bitmap → IntArray，映射公式保持一致。
     */
    private fun renderMasterFromPlan(plan: MasterPlan, frames: List<ByteArray>): IntArray {
        val w = width
        val master = IntArray(plan.masterH * w) { 255 }
        for (op in plan.ops) {
            val frame = frames.getOrNull(op.frame) ?: continue
            val frameRows = frame.size / w
            val len = op.y1 - op.y0
            for (dy in 0 until len) {
                val srcRow = op.src + dy
                if (srcRow < 0 || srcRow >= frameRows) continue
                val dstBase = (op.y0 + dy) * w
                val srcBase = srcRow * w
                for (x in 0 until w) master[dstBase + x] = frame[srcBase + x].toInt() and 0xFF
            }
        }
        return master
    }

    // ------------------------------------------------------------------

    @Test
    fun `overlapping screenshots rebuild the long image`() {
        val totalH = 6000
        val longImage = buildChatImage(totalH)
        val frames = framesOf(longImage, totalH, step = frameH / 2)
        assertEquals("测试用例应切成 9 张 50% 重叠的截图", 9, frames.size)

        val analysis = StitchPlanner.analyzeStatic(
            samplesOf(frames),
            aw = width,
            ah = frameH,
            imageWidth = width,
            imageHeight = frameH,
            band = StitchCore.Band(0, frameH),
        )
        val plan = StitchPlanner.planMaster(analysis, cropTopPxIn = 0, cropBotPxIn = frameH, tileH = frameH)

        // 1) 重建长图高度应 ≈ 原始长图高度（不重复、不丢失）
        assertEquals("重建长图高度应接近原始长图", totalH.toDouble(), plan.masterH.toDouble(), 40.0)
        assertEquals("正常数据不应有对齐失败的相邻帧", 0, plan.badCount)
        assertEquals("正常数据不应缺失像素", 0, plan.missingPx)

        // 2) 质量检查应通过
        val quality = StitchQualityCheck.evaluate(analysis, plan, frameH)
        assertTrue("质量检查应通过，实际原因：" + quality.reasons, quality.ok)

        // 3) 切页：每页高度在 0.75~1.15 倍目标高度之间，页数合理
        val cuts = plan.cuts
        assertEquals(0, cuts.first())
        assertEquals(plan.masterH, cuts.last())
        assertTrue("页数应大约 5 页，实际 " + (cuts.size - 1), cuts.size - 1 in 4..7)
        for (i in 1 until cuts.size) {
            assertTrue("切点必须严格递增", cuts[i] > cuts[i - 1])
            val pageH = cuts[i] - cuts[i - 1]
            assertTrue("页高 $pageH 超出允许范围", pageH >= frameH * 0.7 && pageH <= frameH * 1.2)
        }

        // 4) 每个内部切点都落在原始长图的纯白空行上（不劈字、不劈气泡）
        for (i in 1 until cuts.size - 1) {
            val cut = cuts[i]
            for (dy in -2..2) {
                val row = (cut + dy).coerceIn(0, totalH - 1)
                val base = row * width
                for (x in 0 until width) {
                    assertEquals(
                        "切点 $cut（行 $row 第 $x 列）落在消息内容上，会劈开消息",
                        255.toByte(),
                        longImage[base + x],
                    )
                }
            }
        }

        // 5) 按 ops 重建的像素与原始长图差异极小
        val master = renderMasterFromPlan(plan, frames)
        var diffSum = 0L
        var badRows = 0
        val rows = minOf(plan.masterH, totalH)
        for (y in 0 until rows) {
            var rowDiff = 0L
            for (x in 0 until width) {
                rowDiff += abs(master[y * width + x] - (longImage[y * width + x].toInt() and 0xFF))
            }
            diffSum += rowDiff
            if (rowDiff / width > 20) badRows++
        }
        val meanDiff = diffSum.toDouble() / (rows.toDouble() * width)
        assertTrue("重建长图与原始长图平均像素差过大：$meanDiff", meanDiff < 3.0)
        assertTrue("重建长图有 $badRows 行内容与原始长图不一致", badRows <= 2)

        // 6) 按切点把主图切成页、再竖直拼回来，应与主图完全一致（无重复/缺失）
        val pages = ArrayList<IntArray>()
        for (i in 0 until cuts.size - 1) {
            val pageH = cuts[i + 1] - cuts[i]
            pages.add(master.copyOfRange(cuts[i] * width, cuts[i + 1] * width))
            assertEquals(pageH * width, pages.last().size)
        }
        val joined = IntArray(cuts.last() * width)
        var offset = 0
        for (page in pages) {
            System.arraycopy(page, 0, joined, offset, page.size)
            offset += page.size
        }
        assertEquals("拼回的页总高度应等于主图", cuts.last() * width, joined.size)
        assertArrayEquals("切页再拼回应与主图像素一致", master, joined)
    }

    @Test
    fun `cutPoints keeps messages intact and pages strictly increasing`() {
        // 每 200 行一个「消息」：150 行活跃 + 50 行安静空行。
        val total = 5000
        val tileH = 1000
        val act = FloatArray(total) { y ->
            if (y % period < gapH) 0f else 30f
        }
        val cuts = StitchCore.cutPoints(act, total, tileH, win = 100)
        assertEquals("total/tileH=5 页应有 6 个切点", 6, cuts.size)
        assertEquals(0, cuts[0])
        assertEquals(total, cuts[cuts.size - 1])
        for (i in 1 until cuts.size - 1) {
            assertTrue("切点必须严格递增", cuts[i] > cuts[i - 1])
            val pageH = cuts[i] - cuts[i - 1]
            assertTrue("页高 $pageH 超出允许比例", pageH >= 750 && pageH <= 1150)
            assertEquals("切点 ${cuts[i]} 应落在安静空行", 0f, act[cuts[i]], 0.0001f)
        }
        // 切点应贴近标称位置 1000/2000/3000/4000
        val nominal = intArrayOf(1000, 2000, 3000, 4000)
        for (i in nominal.indices) {
            assertTrue("第 ${i + 1} 个切点 ${cuts[i + 1]} 偏离标称位置太远", abs(cuts[i + 1] - nominal[i]) <= 100)
        }
    }

    @Test
    fun `cutPoints falls back to nominal positions on blank pages`() {
        val total = 5000
        val cuts = StitchCore.cutPoints(FloatArray(total), total, 1000, win = 100)
        assertArrayEquals(intArrayOf(0, 1000, 2000, 3000, 4000, 5000), cuts)
    }

    @Test
    fun `quality check rejects screenshots that never scrolled`() {
        // 4 张完全相同的截图：没有任何滚动，拼接不可信，必须回退。
        val totalH = 6000
        val longImage = buildChatImage(totalH)
        val same = cropFrame(longImage, totalH, top = totalH - frameH)
        val frames = List(4) { same }
        val analysis = StitchPlanner.analyzeStatic(
            samplesOf(frames),
            aw = width,
            ah = frameH,
            imageWidth = width,
            imageHeight = frameH,
            band = StitchCore.Band(0, frameH),
        )
        val plan = StitchPlanner.planMaster(analysis, 0, frameH, frameH)
        val quality = StitchQualityCheck.evaluate(analysis, plan, frameH)
        assertFalse("没有滚动时应拒绝拼接并回退原始截图页", quality.ok)
        assertTrue("应给出拒绝原因", quality.reasons.isNotEmpty())
    }
}
