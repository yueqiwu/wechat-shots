package com.dsh.wechatshot.video

import com.dsh.wechatshot.stitch.AnalyzeOptions
import com.dsh.wechatshot.stitch.FrameSampler
import com.dsh.wechatshot.stitch.GraySample
import com.dsh.wechatshot.stitch.StitchPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 用一张「虚拟聊天长图」+ 一条匀速滚动轨迹模拟录屏，直接喂给 lead 移植的
 * [StitchPlanner.analyze] / [StitchPlanner.planMaster]，在纯 JVM 上验证：
 *  - 位移轨迹能否被准确恢复（每帧误差 <= 3 行）；
 *  - 主图总高、切页边界、ops 是否连续覆盖整张主图；
 *  - 由 ops 反推的渲染源行在页与页之间是否连续（StitchTiles 的渲染语义）。
 * 这层验证不依赖模拟器/解码器，是设备冒烟失败时的兜底证据。
 */
class VideoSyntheticPlanTest {

    private companion object {
        const val AW = 144
        const val AH = 320
        const val VW = 720
        const val VH = 1600
        const val ROWS_PER_SECOND = 120.0
        const val DURATION_US = 2_000_000L
        const val DOC_H = 580
        const val FPS = 10.0
    }

    /** 虚拟长图：每一行 8 个横向分箱都是不同的确定性随机亮度，便于位移匹配。 */
    private class SyntheticVideo {
        private val doc = ByteArray(AW * DOC_H) { i ->
            val y = i / AW
            val b = (i % AW) * 8 / AW
            byteAt(y, b)
        }

        private fun byteAt(y: Int, bin: Int): Byte {
            var h = y * 0x9E3779B1.toInt() + bin * 0x85EBCA6B.toInt()
            h = h xor (h ushr 13)
            h *= 0xC2B2AE35.toInt()
            h = h xor (h ushr 16)
            return (h and 0xFF).toByte()
        }

        fun viewTopAt(timeUs: Long): Int =
            (ROWS_PER_SECOND * (timeUs / 1_000_000.0)).roundToInt().coerceIn(0, DOC_H - AH)

        fun sampleAt(timeUs: Long): GraySample {
            val top = viewTopAt(timeUs)
            val start = top * AW
            return GraySample(timeUs, doc.copyOfRange(start, start + AH * AW))
        }

        fun firstFrames(): List<GraySample> {
            val n = VideoMath.sampleCount(DURATION_US, FPS, 2400)
            return VideoMath.sampleTimes(0L, DURATION_US, n).map { sampleAt(it) }
        }

        fun sampler(): FrameSampler = FrameSampler { t0Us, t1Us, count ->
            VideoMath.sampleTimes(t0Us, t1Us, count).map { sampleAt(it) }
        }
    }

    @Test
    fun analyze_recoversScrollOffsets() {
        val video = SyntheticVideo()
        val firstFrames = video.firstFrames()
        assertEquals(20, firstFrames.size)

        val analysis = StitchPlanner.analyze(
            firstFrames = firstFrames,
            aw = AW,
            ah = AH,
            videoWidth = VW,
            videoHeight = VH,
            durationUs = DURATION_US,
            options = AnalyzeOptions(analysisWidth = AW, fps = FPS, refineRounds = 3, maxFrames = 2400),
            sampler = video.sampler(),
        )

        assertEquals(AW, analysis.aw)
        assertEquals(AH, analysis.ah)
        assertEquals(20, analysis.frames.size)
        assertTrue("内容区应基本占满整帧: ${analysis.band}", analysis.band.top <= 4)
        assertTrue("内容区高度异常: ${analysis.band.h}", analysis.band.h >= AH - 8)

        // 每帧的绝对位移应等于虚拟图视口 top 的变化量
        val expected = firstFrames.map { video.viewTopAt(it.timeUs) }
        val base = expected.first()
        val pos = analysis.track.pos
        assertEquals(expected.size, pos.size)
        for (i in pos.indices) {
            val err = abs(pos[i] - (expected[i] - base))
            assertTrue("第 $i 帧位移误差过大: pos=${pos[i]} 期望=${expected[i] - base}", err <= 3.0)
        }
        assertTrue("干净轨迹不该有对齐失败: ${analysis.track.bad.size} 处", analysis.track.bad.isEmpty())
    }

    @Test
    fun planMaster_coversWholeImageAndSplitsIntoContinuousPages() {
        val video = SyntheticVideo()
        val analysis = StitchPlanner.analyze(
            firstFrames = video.firstFrames(),
            aw = AW,
            ah = AH,
            videoWidth = VW,
            videoHeight = VH,
            durationUs = DURATION_US,
            options = AnalyzeOptions(analysisWidth = AW, fps = FPS, refineRounds = 3, maxFrames = 2400),
            sampler = video.sampler(),
        )

        // 不裁剪，每页高 = 整帧高 1600px（模拟 UI 的「标准」档）
        val plan = StitchPlanner.planMaster(analysis, 0, VH, VH)

        assertEquals(0, plan.topPx)
        assertEquals(VH, plan.botPx)
        assertEquals(VH, plan.cropHpx)
        // 主图总高 ≈ (视口高 + 滚动行数) * 放大倍数，放大倍数 = 1600 / 320 = 5
        val scrollRange = analysis.track.pos.last() - analysis.track.pos.first()
        val expectedMasterH = (AH + scrollRange) * (VH.toDouble() / AH)
        assertEquals("masterH 偏差过大（scrollRange=$scrollRange）", expectedMasterH, plan.masterH.toDouble(), 60.0)

        // 切页：首 0、末 masterH、严格递增，段高在 [0.75, 1.15] * tileH 附近
        assertTrue("切页数异常: ${plan.cuts.size}", plan.cuts.size >= 3)
        assertEquals(0, plan.cuts.first())
        assertEquals(plan.masterH, plan.cuts.last())
        for (i in 1 until plan.cuts.size) {
            assertTrue("切页边界没有严格递增: ${plan.cuts.toList()}", plan.cuts[i] > plan.cuts[i - 1])
        }
        for (i in 0 until plan.cuts.size - 1) {
            val h = plan.cuts[i + 1] - plan.cuts[i]
            assertTrue("第 $i 页高度异常: $h", h >= 1200 && h <= 1900)
        }
        val pages = plan.cuts.size - 1
        assertEquals("2740px 内容应切成 2 页", 2, pages)

        // ops 必须从 0 连续覆盖到 masterH，且 src 不越出裁剪区
        val ops = plan.ops
        assertTrue("ops 为空", ops.isNotEmpty())
        assertEquals("ops 没有从主图顶部开始", 0, ops.first().y0)
        assertEquals("ops 没有覆盖到主图底部", plan.masterH, ops.last().y1)
        for (i in ops.indices) {
            val op = ops[i]
            assertTrue("op 高度非法: $op", op.y1 > op.y0)
            assertTrue("op 源行越界: $op", op.src >= 0 && op.src + (op.y1 - op.y0) <= plan.cropHpx)
            if (i > 0) {
                val gap = op.y0 - ops[i - 1].y1
                assertTrue("ops 之间有空洞 $gap: ${ops[i - 1]} -> $op", gap in 0..8)
            }
        }

        // 渲染语义：相邻两段拼接处从虚拟长图里取到的行应该连续（StitchTiles 用 topPx + src + 行偏移）
        val scaleAll = VH.toDouble() / AH
        val cTop = (plan.topPx / scaleAll).roundToInt().coerceIn(0, AH - 8)
        val cBot = (plan.botPx / scaleAll).roundToInt().coerceIn(cTop + 8, AH)
        val pxPerAnalysisRow = plan.cropHpx.toDouble() / (cBot - cTop)
        val viewTops = analysis.frames.map { video.viewTopAt(it.timeUs) }
        val bandTop = analysis.band.top
        for (i in 1 until ops.size) {
            val prev = ops[i - 1]
            val cur = ops[i]
            if (cur.y0 != prev.y1) continue
            val prevEndRow = (prev.src + (prev.y1 - prev.y0)) / pxPerAnalysisRow
            val curRow = cur.src / pxPerAnalysisRow
            val docPrev = viewTops.getOrElse(prev.frame) { 0 } + bandTop + prevEndRow.roundToInt()
            val docCur = viewTops.getOrElse(cur.frame) { 0 } + bandTop + curRow.roundToInt()
            assertTrue(
                "第 $i 段拼接处内容不连续: docPrev=$docPrev docCur=$docCur",
                abs(docPrev - docCur) <= 2,
            )
        }
        // 顺带确保这个换算没有退化（整帧不裁剪时 1 分析行 = 5 像素）
        assertEquals(5.0, pxPerAnalysisRow, 0.01)
    }
}
