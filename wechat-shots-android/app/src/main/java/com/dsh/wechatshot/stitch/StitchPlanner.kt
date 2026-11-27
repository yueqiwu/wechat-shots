package com.dsh.wechatshot.stitch

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一帧分析用灰度图（aw x ah，字节值 0..255）。
 * timeUs：视频帧时间戳；静态截图序列可用帧序号（任意单调值），只用于排序。
 */
data class GraySample(val timeUs: Long, val gray: ByteArray)

/** 需要补采样更多帧时的回调：同步返回 (t0Us, t1Us) 之间的 count 帧，按时间升序。 */
fun interface FrameSampler {
    fun sample(t0Us: Long, t1Us: Long, count: Int): List<GraySample>
}

data class AnalyzeOptions(
    val analysisWidth: Int = 144,
    /** 第一轮采样密度（帧/秒）。 */
    val fps: Double = 10.0,
    /** 对齐失败时最多补采样几轮（每轮把失败区间的采样密度翻倍）。 */
    val refineRounds: Int = 3,
    /** 采样帧数上限，控制内存与耗时。 */
    val maxFrames: Int = 2600,
)

/** 一帧的签名与清晰度（签名只看内容区 band）。 */
data class AnalysisFrame(val timeUs: Long, val sig: ByteArray, val sharp: Double)

data class AnalysisResult(
    val aw: Int,
    val ah: Int,
    /** 原始帧宽高（像素），用于 cropTopPx/cropBotPx 与最终渲染坐标系。 */
    val videoWidth: Int,
    val videoHeight: Int,
    val durationUs: Long,
    val band: StitchCore.Band,
    val frames: List<StitchCore.FrameSig>,
    val track: StitchCore.Track,
)

/**
 * 主图与切页方案。
 *  - masterH：主图总高（像素，宽度 = 原帧宽度）。
 *  - ops：主图每一段像素由第 frame 帧的「裁剪区第 src 行」起提供；渲染时源行 = topPx + src。
 *  - cuts：切页边界（像素，升序，最后一个是 masterH）。
 *  - missingPx：无法由任何帧提供、只能顺延的行数（越少越好）。
 */
data class MasterPlan(
    val topPx: Int,
    val botPx: Int,
    val cropHpx: Int,
    val masterH: Int,
    val ops: List<StitchCore.Op>,
    val cuts: IntArray,
    val missingPx: Int,
    val bandTop: Int,
    val bandH: Int,
    val frameCount: Int,
    val badCount: Int,
)

/**
 * 把「一堆按时间排序的帧」分析成位移轨迹，再把裁剪区拼成一张主图并规划切页。
 * 视频方案：首轮采样由 UI/解码器完成，后续补采样走 [FrameSampler]。
 * 无线 ADB 方案：每张已裁剪截图直接作为一帧（[analyzeStatic]），没有补采样。
 */
object StitchPlanner {

    /** 视频：首轮帧已由调用方采好（timeUs 升序），内部按需补采样细化对齐。 */
    fun analyze(
        firstFrames: List<GraySample>,
        aw: Int,
        ah: Int,
        videoWidth: Int,
        videoHeight: Int,
        durationUs: Long,
        options: AnalyzeOptions = AnalyzeOptions(),
        sampler: FrameSampler? = null,
        progress: (Double, String) -> Unit = { _, _ -> },
    ): AnalysisResult {
        require(firstFrames.size >= 2) { "至少需要 2 帧才能拼接" }
        require(aw > 0 && ah > 0) { "分析宽高非法" }
        val frames = ArrayList<GraySample>(firstFrames)
        frames.sortBy { it.timeUs }
        var band = detectBand(frames, aw, ah)
        var sigs = prepFrames(frames, aw, band)
        progress(0.30, "分析滚动位移")
        var track = StitchCore.trackOffsets(sigs, band.h)
        var fpsBase = options.fps
        var round = 0
        while (sampler != null &&
            track.bad.isNotEmpty() &&
            round < options.refineRounds &&
            frames.size < options.maxFrames
        ) {
            val budget = minOf(options.maxFrames - frames.size, maxOf(24, track.bad.size * 8))
            val plan = refillPlan(sigs, track, fpsBase, round, budget)
            if (plan.isEmpty()) break
            var added = 0
            for (p in plan) {
                val more = sampler.sample(p.t0Us, p.t1Us, p.count)
                if (more.isNotEmpty()) {
                    frames.addAll(more); added += more.size
                }
            }
            if (added == 0) break
            frames.sortBy { it.timeUs }
            while (frames.size > options.maxFrames) frames.removeAt(frames.size - 1)
            band = detectBand(frames, aw, ah)
            sigs = prepFrames(frames, aw, band)
            track = StitchCore.trackOffsets(sigs, band.h)
            fpsBase *= 2
            round++
            progress(0.30 + 0.6 * round / options.refineRounds, "细化对齐（第 ${round} 轮）")
        }
        progress(0.95, "对齐完成")
        return AnalysisResult(aw, ah, videoWidth, videoHeight, durationUs, band, sigs, track)
    }

    /**
     * 静态截图序列（无线 ADB 方案）。frames 必须按时间升序（最早的一页在第一张）。
     * band 默认整帧（截图已裁掉状态栏/输入栏），传入自定义 Band 可跳过内容区检测。
     */
    fun analyzeStatic(
        frames: List<GraySample>,
        aw: Int,
        ah: Int,
        imageWidth: Int,
        imageHeight: Int,
        band: StitchCore.Band? = null,
        progress: (Double, String) -> Unit = { _, _ -> },
    ): AnalysisResult {
        require(frames.size >= 1) { "至少需要一张截图" }
        require(aw > 0 && ah > 0) { "分析宽高非法" }
        val sorted = frames.sortedBy { it.timeUs }
        val b = band ?: detectBand(sorted, aw, ah)
        val sigs = prepFrames(sorted, aw, b)
        progress(0.5, "分析滚动位移")
        val track = StitchCore.trackOffsets(sigs, b.h)
        progress(0.9, "对齐完成")
        return AnalysisResult(
            aw, ah, imageWidth, imageHeight,
            durationUs = sorted.last().timeUs, band = b, frames = sigs, track = track,
        )
    }

    /** 把分析结果变成主图 + 切页方案。tileH 为目标每页像素高（通常 = cropHpx）。 */
    fun planMaster(
        analysis: AnalysisResult,
        cropTopPxIn: Int,
        cropBotPxIn: Int,
        tileH: Int,
    ): MasterPlan {
        val vh = analysis.videoHeight
        val scaleAll = if (analysis.ah > 0) vh.toDouble() / analysis.ah else 1.0
        val topPx = cropTopPxIn.coerceIn(0, maxOf(0, vh - 16))
        val botPx = cropBotPxIn.coerceIn(topPx + 16, vh)
        var cTop = (topPx / scaleAll).roundToInt().coerceIn(0, maxOf(0, analysis.ah - 8))
        var cBot = (botPx / scaleAll).roundToInt().coerceIn(cTop + 8, analysis.ah)
        if (cBot - cTop < 16) { cTop = 0; cBot = analysis.ah }
        val cropHpx = maxOf(1, botPx - topPx)
        val scale = cropHpx.toDouble() / (cBot - cTop)
        val track = analysis.track
        val sharp = DoubleArray(analysis.frames.size) { analysis.frames[it].sharp }
        val pos = track.pos
        val rm = StitchCore.buildRowMap(sharp, pos, cTop, cBot)
        val masterH = maxOf(1, (rm.height * scale).roundToInt())

        // ---- 主图每行像素来源 → 像素坐标的 ops ----
        val rawOps = StitchCore.mapToOps(rm.map, pos, cTop, rm.posMin)
        val ops = ArrayList<StitchCore.Op>(rawOps.size)
        var prevEnd = 0
        for (o in rawOps) {
            var y0 = (o.y0 * scale).roundToInt().coerceAtLeast(prevEnd).coerceAtMost(masterH)
            var y1 = (o.y1 * scale).roundToInt().coerceAtMost(masterH)
            if (y1 <= y0) continue
            val h = y1 - y0
            if (h > cropHpx) { y1 = y0 + cropHpx; if (y1 > masterH) { y1 = masterH; y0 = maxOf(prevEnd, y1 - cropHpx) } }
            var src = (o.src * scale).roundToInt()
            if (src < 0) src = 0
            if (src > cropHpx - (y1 - y0)) src = cropHpx - (y1 - y0)
            if (src < 0) src = 0
            ops.add(StitchCore.Op(y0, y1, o.frame, src))
            prevEnd = y1
            if (prevEnd >= masterH) break
        }

        // ---- 切页：把每帧的「行活跃度」映射到主图坐标系，挑消息之间的空行切 ----
        val band = analysis.band
        val frameAct = ArrayList<FloatArray>(analysis.frames.size)
        for (f in analysis.frames) {
            val a = StitchCore.sigActivity(f.sig, band.h)
            val fa = FloatArray(band.h)
            for (i in fa.indices) fa[i] = a[i].toFloat()
            frameAct.add(fa)
        }
        val actMaster = FloatArray(masterH)
        for (o in ops) {
            val fa = frameAct.getOrNull(o.frame) ?: continue
            for (y in o.y0 until o.y1) {
                val srcPx = o.src + (y - o.y0)
                val bandRow = (cTop + (srcPx + 0.5) / scale - band.top).roundToInt()
                    .coerceIn(0, band.h - 1)
                actMaster[y] = fa[bandRow]
            }
        }
        val effTileH = if (tileH > 0) tileH else cropHpx
        val win = maxOf(6, (effTileH * 0.10).roundToInt())
        val cuts = StitchCore.cutPoints(actMaster, masterH, effTileH, win)
        return MasterPlan(
            topPx = topPx,
            botPx = botPx,
            cropHpx = cropHpx,
            masterH = masterH,
            ops = ops,
            cuts = cuts,
            missingPx = (rm.missing * scale).roundToInt(),
            bandTop = band.top,
            bandH = band.h,
            frameCount = analysis.frames.size,
            badCount = track.bad.size,
        )
    }

    // ---------- 全局单调性校验（质量门兜底） ----------

    /**
     * 全局单调性校验：确认「截图是依次向前滚动得到的」这一前提成立。
     *
     * 拼接算法在重复气泡（相同头像+相同宽度气泡反复出现）上可能把某一对帧锁到错误的
     * 位移上——NCC 依然很高、正反向也一致，质量门的「坏帧率」抓不到，但重建出的长图
     * 会在某一处内容折返/重复。这里用几条和画面内容无关的硬约束兜底：
     *
     *  1. 相邻（去重后）帧在分析坐标系里的位移必须严格向前，且在
     *     [0.05*bandH, 0.98*bandH] 之间：太小说明停在原地或锁到了重复内容，
     *     太大说明滚动超过引擎可匹配上限（重叠不足）。
     *  2. 固定滑动距离的截图序列步长应基本稳定：与中位数偏差超过 35% 视为错锁。
     *  3. 主图各行的取帧顺序不能折返：ops 按 y 升序时帧号应单调不减
     *     （允许同一行附近相邻帧的轻微交错，等于同时覆盖该行的帧数）。
     *
     * @return 空列表表示通过；否则返回每条中文原因（最多几条），调用方应回退原始截图。
     */
    fun validateMaster(analysis: AnalysisResult, plan: MasterPlan): List<String> {
        val sigs = analysis.frames
        if (sigs.size < 2) return listOf("截图数量不足，无法校验拼接顺序")
        val bandH = if (plan.bandH > 0) plan.bandH else (analysis.band.bot - analysis.band.top)
        if (bandH <= 0) return listOf("聊天内容区高度非法，无法校验拼接顺序")
        val minStep = maxOf(4.0, bandH * 0.05)
        val maxStep = maxOf(minStep + 1.0, bandH * 0.98)
        val reasons = ArrayList<String>(4)

        // 逐帧位移：连续完全相同的截图（滚动到顶后重复截屏）不算步长，其余必须向前。
        val track = analysis.track
        val keep = ArrayList<Int>(sigs.size)
        for (i in sigs.indices) {
            if (i > 0 && sigs[i].sig.contentEquals(sigs[i - 1].sig)) continue
            keep.add(i)
        }
        if (keep.size < 2) return listOf("有效滚动帧不足（重复截图过多），无法拼接")
        val steps = DoubleArray(keep.size - 1)
        for (k in 1 until keep.size) {
            val cur = keep[k]
            val prev = keep[k - 1]
            val step = track.pos[cur] - track.pos[prev]
            steps[k - 1] = step
            if (reasons.size >= 3) continue
            when {
                !step.isFinite() -> reasons.add("第 ${cur + 1} 帧位移非法")
                step <= 0.0 -> reasons.add(
                    "第 ${cur + 1} 帧没有继续向前滚动（位移 ${step.roundToInt()}px），内容可能折返"
                )
                step < minStep -> reasons.add(
                    "第 ${cur + 1} 帧滚动过小（${step.roundToInt()}px，下限 ${minStep.roundToInt()}px），" +
                        "疑似锁在重复内容上"
                )
                step > maxStep -> reasons.add(
                    "第 ${cur + 1} 帧滚动过大（${step.roundToInt()}px，上限 ${maxStep.roundToInt()}px），" +
                        "截图之间重叠不足"
                )
            }
        }

        // 步长一致性：固定滑动距离下，各相邻帧的滚动量应接近。
        val validSteps = steps.filter { it.isFinite() && it > 0.0 }
        if (validSteps.size >= 2 && reasons.size < 3) {
            val med = StitchCore.median(validSteps.toDoubleArray())
            val tolerance = maxOf(8.0, med * 0.35)
            var worstAt = -1
            var worstDev = 0.0
            for (k in steps.indices) {
                if (!steps[k].isFinite() || steps[k] <= 0.0) continue
                val dev = abs(steps[k] - med)
                if (dev > tolerance && dev > worstDev) {
                    worstDev = dev
                    worstAt = k
                }
            }
            if (worstAt >= 0) {
                reasons.add(
                    "第 ${keep[worstAt + 1] + 1} 帧滚动步长异常" +
                        "（${steps[worstAt].roundToInt()}px，正常约 ${med.roundToInt()}px），疑似在重复气泡上错锁"
                )
            }
        }

        // 主图帧序：ops 按 y 升序，取帧号不能明显折返。
        val ops = plan.ops
        if (ops.isEmpty()) {
            reasons.add("主图没有任何像素来源")
        } else {
            if (ops.first().y0 != 0) reasons.add("主图顶部缺少像素来源")
            if (ops.last().y1 != plan.masterH) reasons.add("主图底部缺少像素来源")
            var prevFrame = -1
            var dropAt = -1
            var dropFrom = -1
            var dropTo = -1
            for (op in ops) {
                if (op.frame < 0) continue
                if (prevFrame >= 0 && op.frame < prevFrame - 2 && dropAt < 0) {
                    dropAt = op.y0
                    dropFrom = prevFrame
                    dropTo = op.frame
                }
                if (op.frame > prevFrame) prevFrame = op.frame
            }
            if (dropAt >= 0) {
                reasons.add(
                    "主图第 ${dropAt}px 处取帧顺序折返（帧号 $dropFrom → $dropTo），内容顺序错乱"
                )
            }
        }
        return reasons
    }

    // ---------- 内部 ----------

    private fun detectBand(frames: List<GraySample>, aw: Int, ah: Int): StitchCore.Band {
        if (frames.size < 2) return StitchCore.Band(0, ah)
        val step = maxOf(1, frames.size / 60)
        val sub = ArrayList<ByteArray>(minOf(60, frames.size))
        var i = 0
        while (i < frames.size) { sub.add(frames[i].gray); i += step }
        if (sub.size < 2) return StitchCore.Band(0, ah)
        val act = StitchCore.rowActivity(sub, aw, ah)
        return StitchCore.contentBand(act, ah)
    }

    private fun prepFrames(frames: List<GraySample>, aw: Int, band: StitchCore.Band): List<StitchCore.FrameSig> {
        val out = ArrayList<StitchCore.FrameSig>(frames.size)
        for (f in frames) {
            val h = f.gray.size / aw
            val top = band.top.coerceIn(0, maxOf(0, h - 1))
            val bot = band.bot.coerceIn(top + 1, h)
            val crop = StitchCore.cropGray(f.gray, aw, h, top, bot)
            val sharp = StitchCore.rowSharpness(crop, aw, 0, bot - top)
            val sig = StitchCore.rowSig(crop, aw, bot - top, StitchCore.BINS)
            out.add(StitchCore.FrameSig(f.timeUs, sig, sharp))
        }
        return out
    }

    private data class Refill(val t0Us: Long, val t1Us: Long, val count: Int)

    /**
     * 对齐失败区间补采样计划：把最可疑的若干对相邻帧之间的区间加密，重新给对齐一次机会。
     * 失败对按 score 从高到低选，相邻区间合并，避免对同一段重复采样。
     */
    private fun refillPlan(
        sigs: List<StitchCore.FrameSig>,
        track: StitchCore.Track,
        fpsBase: Double,
        round: Int,
        budget: Int,
    ): List<Refill> {
        val targets = HashSet<Int>()
        val sorted = track.bad.sortedByDescending { it.score }
        for (b in sorted) {
            if (targets.size >= 8) break
            targets.add(b.i)
        }
        if (targets.isEmpty()) return emptyList()
        val density = fpsBase * (1 shl (round + 1))
        val plans = ArrayList<Refill>()
        var used = 0
        for (i in targets.sorted()) {
            if (i <= 0 || i >= sigs.size) continue
            val t0 = sigs[i - 1].timeUs
            val t1 = sigs[i].timeUs
            if (t1 <= t0) continue
            // 该区间已有多密？按第一轮 fpsBase 估算现有帧数，只补差额。
            val existing = ((t1 - t0) / 1_000_000.0 * fpsBase).roundToInt().coerceAtLeast(1)
            var want = ((t1 - t0) / 1_000_000.0 * density).roundToInt()
            if (want < 2) want = 2
            val add = want - existing
            if (add <= 0) continue
            if (used + add > budget) break
            used += add
            plans.add(Refill(t0, t1, add))
        }
        return plans
    }
}
