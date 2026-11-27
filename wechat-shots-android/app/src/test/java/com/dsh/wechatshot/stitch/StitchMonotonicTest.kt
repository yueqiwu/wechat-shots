package com.dsh.wechatshot.stitch

import com.dsh.wechatshot.export.StitchQualityCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 全局单调性校验（[StitchPlanner.validateMaster]）的回归单测。
 *
 * 背景：拼接算法在重复气泡上可能把某对相邻帧锁到错误位移——NCC 很高、正反向也一致，
 * 坏帧率质量门抓不到，但重建出来的长图会在某一处内容折返/重复（真实发生过：模拟器上
 * 同一参数两次导出，一次 16 页正确、一次 15 页顶部内容错位）。这里验证几条硬约束：
 *
 *  - 正常 24% 左右重叠的截图序列（模拟器实测步长 ≈ 1504px / 可视高 1979px，即分析坐标
 *    264 行高里每步约 200 行）必须通过；
 *  - 位移超出 [0.05, 0.98]×内容区高度 的序列必须被拒绝；
 *  - 滚动步长忽大忽小（重复气泡错锁的典型症状）必须被拒绝；
 *  - 主图取帧顺序折返（内容重复/错位）必须被拒绝；
 *  - 滚动到顶后重复截屏产生的相同帧要容忍（去重后再校验，不能误杀）。
 *
 * 另外在 [.tmp_e2e_new] 里存了模拟器真实导出的样例时（真实 90% 滑动坏样本
 * out/001..018.png、run3 干净长图的 16 页），额外跑真实数据回归；文件不存在时
 * 自动跳过（不阻塞 CI）。
 */
class StitchMonotonicTest {

    private val aw = 144
    private val fullWidth = 1080
    private val frameH = 1979

    /** 分析坐标下的帧高（= 分析灰度图行数，1080→144 缩放后约 264 行）。 */
    private val ah = (frameH.toDouble() * aw / fullWidth).roundToInt() // 264

    /** 模拟器实测步长 1504px 换算到分析坐标 ≈ 200 行。 */
    private val goodStep = (1504.0 * aw / fullWidth).roundToInt().coerceAtMost(ah * 98 / 100) // 201

    // ---------- 合成聊天长图（与 StitchPlannerTest 同款思路，坐标 = 分析灰度行） ----------

    private fun buildChatImage(totalH: Int, seed: Int = 7): ByteArray {
        val pixels = ByteArray(totalH * aw) { 255.toByte() }
        val random = Random(seed)
        var y = random.nextInt(20)
        var block = 0
        while (y < totalH) {
            val gap = 24 + random.nextInt(56)
            val msgH = 60 + random.nextInt(160)
            val top = y + gap
            val bottom = minOf(totalH, top + msgH)
            if (top >= totalH) break
            if (bottom > top) {
                val incoming = block % 2 == 0
                if (incoming) {
                    val avatarW = 22 + random.nextInt(8)
                    fillRect(pixels, totalH, 4, 4 + avatarW, top + 4, bottom - 4, 30 + random.nextInt(40))
                }
                val bubbleW = 46 + random.nextInt(72)
                val bubbleX = if (incoming) 34 + random.nextInt(10) else aw - bubbleW - 12 - random.nextInt(10)
                val bubbleTop = top + 2
                val bubbleBot = bottom - 2
                fillRect(pixels, totalH, bubbleX, bubbleX + bubbleW, bubbleTop, bubbleBot, 70 + random.nextInt(100))
                var ty = bubbleTop + 6 + random.nextInt(6)
                while (ty + 4 < bubbleBot - 4) {
                    val tx = bubbleX + 5 + random.nextInt(6)
                    val tw = 16 + random.nextInt(maxOf(1, bubbleW - 14))
                    val tx1 = minOf(bubbleX + bubbleW - 3, tx + tw)
                    fillRect(pixels, totalH, tx, tx1, ty, ty + 3 + random.nextInt(4), random.nextInt(80))
                    ty += 10 + random.nextInt(8)
                }
            }
            y = bottom + random.nextInt(10)
            block++
        }
        return pixels
    }

    private fun fillRect(pixels: ByteArray, totalH: Int, x0: Int, x1: Int, y0: Int, y1: Int, value: Int) {
        val v = value.coerceIn(0, 255).toByte()
        for (yy in y0.coerceAtLeast(0) until y1.coerceAtMost(totalH)) {
            val base = yy * aw
            for (xx in x0.coerceAtLeast(0) until x1.coerceAtMost(aw)) pixels[base + xx] = v
        }
    }

    private fun cropFrame(longImage: ByteArray, top: Int): ByteArray {
        val frame = ByteArray(ah * aw)
        for (row in 0 until ah) System.arraycopy(longImage, (top + row) * aw, frame, row * aw, aw)
        return frame
    }

    private fun framesOf(longImage: ByteArray, totalH: Int, step: Int): List<GraySample> {
        val frames = ArrayList<GraySample>()
        var top = 0
        var index = 0
        while (top + ah <= totalH) {
            frames.add(GraySample(index.toLong(), cropFrame(longImage, top)))
            top += step
            index++
        }
        return frames
    }

    private fun analyzeSamples(samples: List<GraySample>): Pair<AnalysisResult, MasterPlan> {
        val analysis = StitchPlanner.analyzeStatic(
            samples, aw, ah, fullWidth, frameH, StitchCore.Band(0, ah),
        )
        val plan = StitchPlanner.planMaster(analysis, 0, frameH, frameH)
        return analysis to plan
    }

    // ---------- 假轨迹/假主图，直接测校验规则 ----------

    private fun sig(seed: Int): StitchCore.FrameSig =
        StitchCore.FrameSig(seed.toLong(), ByteArray(ah) { (seed * 31 + it).toByte() }, 1.0)

    private fun trackOf(vararg pos: Double): StitchCore.Track {
        val step = DoubleArray(pos.size)
        for (i in 1 until pos.size) step[i] = pos[i] - pos[i - 1]
        return StitchCore.Track(
            pos = pos.copyOf(),
            step = step,
            cost = DoubleArray(pos.size),
            ncc = DoubleArray(pos.size) { 1.0 },
            agree = DoubleArray(pos.size) { 0.0 },
            bad = emptyList(),
            maxShift = (ah - ah / 10),
            tol = 2.0,
            medCost = 0.0,
        )
    }

    /**
     * 按给定轨迹造一份 AnalysisResult + MasterPlan。
     * frameOrder 为各段主图使用的帧号（省略时按 0,1,2… 顺序铺满）。
     */
    private fun masterOf(
        track: StitchCore.Track,
        frameOrder: List<Int>? = null,
    ): Pair<AnalysisResult, MasterPlan> {
        val frames = track.pos.indices.map { sig(it + 1) }
        val analysis = AnalysisResult(
            aw = aw,
            ah = ah,
            videoWidth = fullWidth,
            videoHeight = frameH,
            durationUs = frames.size.toLong(),
            band = StitchCore.Band(0, ah),
            frames = frames,
            track = track,
        )
        val spanRows = track.pos.last() - track.pos.first()
        val masterH = ((ah + spanRows) * frameH.toDouble() / ah).roundToInt()
        val order = frameOrder ?: frames.indices.toList()
        val ops = ArrayList<StitchCore.Op>(order.size)
        for ((k, frame) in order.withIndex()) {
            val y0 = (masterH.toLong() * k / order.size).toInt()
            val y1 = (masterH.toLong() * (k + 1) / order.size).toInt()
            if (y1 > y0) ops.add(StitchCore.Op(y0, y1, frame, 0))
        }
        val fixed = if (ops.isNotEmpty() && ops.last().y1 != masterH) {
            ops[ops.size - 1] = ops.last().copy(y1 = masterH)
            ops
        } else {
            ops
        }
        val plan = MasterPlan(
            topPx = 0,
            botPx = frameH,
            cropHpx = frameH,
            masterH = masterH,
            ops = fixed,
            cuts = intArrayOf(0, masterH),
            missingPx = 0,
            bandTop = 0,
            bandH = ah,
            frameCount = frames.size,
            badCount = track.bad.size,
        )
        return analysis to plan
    }

    // ---------- 测试 ----------

    @Test
    fun `normal step passes monotonic check`() {
        // 40% 帧高重叠 → 20 步覆盖 4000 行分析坐标（对应真实 1979px 屏、每步约 1504px）。
        val totalH = goodStep * 20 + ah
        val longImage = buildChatImage(totalH)
        val (analysis, plan) = analyzeSamples(framesOf(longImage, totalH, goodStep))
        val reasons = StitchPlanner.validateMaster(analysis, plan)
        assertTrue("正常重叠的截图序列不应被拒绝，实际原因：" + reasons, reasons.isEmpty())
        val quality = StitchQualityCheck.evaluate(analysis, plan, frameH)
        assertTrue("正常序列质量门也应通过，原因：" + quality.reasons, quality.ok)
    }

    @Test
    fun `scroll step above engine limit is rejected`() {
        // 每步 260 分析行 > 0.98×264 ≈ 258.7：重叠不足，必须拒绝。
        val track = trackOf(0.0, 260.0, 520.0)
        val (analysis, plan) = masterOf(track)
        val reasons = StitchPlanner.validateMaster(analysis, plan)
        assertFalse("位移超过引擎可匹配上限时必须拒绝", reasons.isEmpty())
        assertTrue("原因应说明滚动过大：" + reasons, reasons.any { it.contains("滚动过大") })
    }

    @Test
    fun `unstable step is rejected`() {
        // 步长 200、50、200、200：中位数 200，第二步偏差 150 > max(8, 70)，是重复气泡错锁的典型症状。
        val track = trackOf(0.0, 200.0, 250.0, 450.0, 650.0)
        val (analysis, plan) = masterOf(track)
        val reasons = StitchPlanner.validateMaster(analysis, plan)
        assertFalse("步长明显不一致时必须拒绝", reasons.isEmpty())
        assertTrue("原因应说明步长异常：" + reasons, reasons.any { it.contains("步长异常") })
    }

    @Test
    fun `content fold back in master plan is rejected`() {
        // 位移单调但主图取帧顺序折返（第 8 帧覆盖顶部、随后又回到第 1 帧）：内容会重复。
        val track = trackOf(0.0, 200.0, 400.0, 600.0, 800.0, 1000.0, 1200.0, 1400.0)
        val (analysis, plan) = masterOf(track, frameOrder = listOf(7, 0, 1, 2, 3, 4, 5, 6))
        val reasons = StitchPlanner.validateMaster(analysis, plan)
        assertFalse("主图取帧顺序折返时必须拒绝", reasons.isEmpty())
        assertTrue("原因应说明取帧顺序折返：" + reasons, reasons.any { it.contains("折返") })
    }

    @Test
    fun `duplicate frames at chat top are tolerated`() {
        // 滚动到顶后重复截屏会产生内容完全相同的帧：去重后仍应通过（不能误杀）。
        val totalH = goodStep * 6 + ah
        val longImage = buildChatImage(totalH)
        val samples = framesOf(longImage, totalH, goodStep)
        val first = samples.first().gray
        val withDup = listOf(
            GraySample(0, first), GraySample(1, first), GraySample(2, first),
        ) + samples.drop(1).mapIndexed { i, s -> GraySample(i.toLong() + 3, s.gray) }
        val (analysis, plan) = analyzeSamples(withDup)
        val reasons = StitchPlanner.validateMaster(analysis, plan)
        assertTrue("顶部重复帧不应被拒绝，实际原因：" + reasons, reasons.isEmpty())
    }

    // ---------- 真实数据回归（样例不存在时跳过） ----------

    private fun e2eDir(): File? {
        val candidates = listOfNotNull(
            System.getenv("WECHAT_SHOT_E2E_DIR")?.let(::File),
            File(System.getProperty("user.dir"), "../../.tmp_e2e_new"),
            File(System.getProperty("user.dir"), ".tmp_e2e_new"),
            File("D:/code/wechat-shot/.tmp_e2e_new"),
        )
        return candidates.firstOrNull { it.isDirectory }
    }

    /** 读取 PNG，按宽度缩成 144 宽的灰度（最近邻，模拟分析阶段的缩放，不依赖 android.graphics）。 */
    private fun grayOfFile(file: File): ByteArray {
        val img = ImageIO.read(file) ?: error("读取图片失败：" + file)
        try {
            val th = maxOf(1, (img.height.toDouble() * aw / img.width).roundToInt())
            val out = ByteArray(aw * th)
            for (y in 0 until th) {
                val sy = ((y + 0.5) * img.height / th).toInt().coerceIn(0, img.height - 1)
                for (x in 0 until aw) {
                    val sx = ((x + 0.5) * img.width / aw).toInt().coerceIn(0, img.width - 1)
                    out[y * aw + x] = luminance(img.getRGB(sx, sy))
                }
            }
            return out
        } finally {
            img.flush()
        }
    }

    private fun luminance(rgb: Int): Byte {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }

    /**
     * 把 run3 的 16 页无重叠成图竖直拼回真实长图，并按与运行时相同的比例（1080 宽 → 144 宽，
     * 高度等比缩放）转成分析坐标灰度长图。
     */
    private fun buildLongGrayFromPages(pages: List<File>): ByteArray? {
        if (pages.isEmpty()) return null
        val scaledHeights = ArrayList<Int>(pages.size)
        var totalRows = 0
        for (p in pages) {
            val img = ImageIO.read(p) ?: return null
            val th = maxOf(1, (img.height.toDouble() * ah / frameH).roundToInt())
            img.flush()
            scaledHeights.add(th)
            totalRows += th
        }
        if (totalRows <= ah) return null
        val longGray = ByteArray(totalRows * aw)
        var dstRow = 0
        for ((index, p) in pages.withIndex()) {
            val img = ImageIO.read(p) ?: return null
            try {
                val th = scaledHeights[index]
                for (row in 0 until th) {
                    val sy = ((row + 0.5) * img.height / th).toInt().coerceIn(0, img.height - 1)
                    val y = dstRow + row
                    for (x in 0 until aw) {
                        val sx = ((x + 0.5) * img.width / aw).toInt().coerceIn(0, img.width - 1)
                        longGray[y * aw + x] = luminance(img.getRGB(sx, sy))
                    }
                }
            } finally {
                img.flush()
            }
            dstRow += scaledHeights[index]
        }
        return longGray
    }

    /** longGray 是分析坐标（144 宽、每行对应 1080 宽的 7.5 行）拼成的长图，切帧也在分析坐标进行。 */
    private fun windowsFromLongGray(longGray: ByteArray, totalRows: Int, count: Int): List<GraySample> {
        val stepRows = (totalRows - ah).toDouble() / (count - 1)
        val samples = ArrayList<GraySample>(count)
        for (k in 0 until count) {
            val top = (k * stepRows).roundToInt().coerceIn(0, totalRows - ah)
            samples.add(GraySample(k.toLong(), longGray.copyOfRange(top * aw, (top + ah) * aw)))
        }
        return samples
    }

    @Test
    fun `real 90 percent scroll samples are rejected`() {
        val dir = e2eDir() ?: run { assumeTrue("没有 .tmp_e2e_new 样例，跳过真实数据回归", false); return }
        val pngs = File(dir, "out").listFiles { f -> f.isFile && f.name.endsWith(".png") }
            .orEmpty().sortedBy { it.name }
        assumeTrue("没有真实 90% 滑动坏样本（out/），跳过", pngs.size >= 5)
        val samples = pngs.mapIndexed { index, f -> GraySample(index.toLong(), grayOfFile(f)) }
        val (analysis, plan) = analyzeSamples(samples)
        val reasons = StitchPlanner.validateMaster(analysis, plan)
        val quality = StitchQualityCheck.evaluate(analysis, plan, frameH)
        // 这批样例的位移本身超过引擎可匹配上限（≈2143px > 1781px），既有的坏帧率质量门
        // 会拒绝；新增的单调性校验也必须给出拒绝原因，避免静默输出错图。
        assertFalse(
            "90% 滑动（重叠≈0）的样例必须被拒绝：quality=" + quality.ok + " reasons=" + reasons,
            quality.ok && reasons.isEmpty(),
        )
        assertTrue("单调性校验应指出位移/步长问题，实际：" + reasons, reasons.isNotEmpty())
    }

    @Test
    fun `real long chat content passes monotonic check`() {
        val dir = e2eDir() ?: run { assumeTrue("没有 .tmp_e2e_new 样例，跳过真实数据回归", false); return }
        val pages = File(dir, "run3/pages").listFiles { f -> f.isFile && f.name.endsWith(".png") }
            .orEmpty().sortedBy { it.name }
        assumeTrue("没有 run3 干净长图的 16 页样例，跳过", pages.size >= 8)
        val longGray = buildLongGrayFromPages(pages) ?: run {
            assumeTrue("无法从 run3 成图重建长图，跳过", false)
            return
        }
        val totalRows = longGray.size / aw
        val samples = windowsFromLongGray(longGray, totalRows, count = 21)
        val (analysis, plan) = analyzeSamples(samples)
        val reasons = StitchPlanner.validateMaster(analysis, plan)
        assertTrue("真实聊天内容按约 24% 重叠切帧应通过单调性校验，实际原因：" + reasons, reasons.isEmpty())
        // 分析坐标长图行数 → 原始像素高度（scale = 1080/144 = 7.5，即 frameH/ah）。
        val expectedMaster = (totalRows.toDouble() * frameH / ah).roundToInt()
        assertTrue(
            "重建长图高度应接近原始长图：master=" + plan.masterH + " expected≈" + expectedMaster,
            abs(plan.masterH - expectedMaster) <= 40,
        )
    }

    @Test
    fun `real long chat with a duplicated frame is rejected`() {
        val dir = e2eDir() ?: run { assumeTrue("没有 .tmp_e2e_new 样例，跳过真实数据回归", false); return }
        val pages = File(dir, "run3/pages").listFiles { f -> f.isFile && f.name.endsWith(".png") }
            .orEmpty().sortedBy { it.name }
        assumeTrue("没有 run3 干净长图的 16 页样例，跳过", pages.size >= 8)
        val longGray = buildLongGrayFromPages(pages) ?: run {
            assumeTrue("无法从 run3 成图重建长图，跳过", false)
            return
        }
        val totalH = longGray.size / aw
        val samples = windowsFromLongGray(longGray, totalH, count = 21).toMutableList()
        // 把第 12 帧换成第 9 帧的内容：模拟「某对帧错锁到重复内容上」，步长会变成负数（内容折返）。
        samples[11] = GraySample(11, samples[8].gray)
        val (analysis, plan) = analyzeSamples(samples)
        val reasons = StitchPlanner.validateMaster(analysis, plan)
        assertFalse("有帧错锁到重复内容（内容折返）时必须拒绝", reasons.isEmpty())
    }
}
