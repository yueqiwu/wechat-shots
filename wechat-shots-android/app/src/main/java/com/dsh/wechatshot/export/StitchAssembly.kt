package com.dsh.wechatshot.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dsh.wechatshot.stitch.AnalysisResult
import com.dsh.wechatshot.stitch.FrameImageSource
import com.dsh.wechatshot.stitch.GraySample
import com.dsh.wechatshot.stitch.MasterPlan
import com.dsh.wechatshot.stitch.StitchCore
import com.dsh.wechatshot.stitch.StitchPlanner
import com.dsh.wechatshot.stitch.StitchTiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 拼接质量评估结果。纯 JVM 数据（不依赖 Android 类），可直接单元测试。
 */
data class StitchQuality(
    val frameCount: Int,
    val badCount: Int,
    val badRatio: Double,
    val masterHeight: Int,
    val missingPx: Int,
    val missingRatio: Double,
    val scrollPx: Double,
    val pageCount: Int,
    val ok: Boolean,
    val reasons: List<String>,
) {
    /** 写进 说明.txt / export.json 的一句话质量描述。 */
    fun summary(): String =
        "对齐失败 " + badCount + "/" + max(1, frameCount - 1) + " 帧，缺失 " + missingPx + " 像素"
}

/**
 * 判断一次拼接结果是否可信。不可信时调用方回退为「带重叠的原始截图页」，保证不丢图。
 * 纯 JVM（只用 stitch 包的纯 Kotlin 类型），便于单元测试。
 */
object StitchQualityCheck {
    /** 允许的相邻截图对齐失败比例上限。 */
    const val MAX_BAD_RATIO = 0.20

    /** 重建主图中允许的缺失像素比例上限（截图完全没有覆盖到的行）。 */
    const val MAX_MISSING_RATIO = 0.05

    /** 每张截图预期带来的最小滚动位移（相对单页高度），低于此值说明几乎没翻动。 */
    const val MIN_SCROLL_RATIO = 0.15

    /** 重建长图至少应有一张截图那么高。 */
    const val MIN_MASTER_RATIO = 0.9

    /** 重建长图最多允许比「截图数 + N」张截图还高（防止位移被放大成巨大空白）。 */
    const val MAX_MASTER_EXTRA_FRAMES = 2

    fun evaluate(
        analysis: AnalysisResult,
        plan: MasterPlan,
        imageHeight: Int,
    ): StitchQuality = evaluate(
        analysis.frames.size,
        analysis.track,
        plan,
        imageHeight,
        analysis.ah,
    )

    private fun evaluate(
        frameCount: Int,
        track: StitchCore.Track,
        plan: MasterPlan,
        imageHeightIn: Int,
        analysisHeight: Int,
    ): StitchQuality {
        val imageHeight = max(1, imageHeightIn)
        val pairs = max(1, frameCount - 1)
        val badRatio = plan.badCount.toDouble() / pairs
        val masterHeight = max(0, plan.masterH)
        val missingRatio = if (masterHeight > 0) plan.missingPx.toDouble() / masterHeight else 1.0
        val scale = if (analysisHeight > 0) imageHeight.toDouble() / analysisHeight else 1.0
        val pos = track.pos
        val scrollPx = if (pos.size >= 2) (pos[pos.size - 1] - pos[0]) * scale else 0.0
        val pageCount = max(0, plan.cuts.size - 1)

        val reasons = ArrayList<String>(4)
        if (frameCount >= 3 && badRatio > MAX_BAD_RATIO) {
            reasons.add("相邻截图对齐失败过多（" + plan.badCount + "/" + pairs + "）")
        }
        if (frameCount >= 2 && missingRatio > MAX_MISSING_RATIO) {
            reasons.add("重建长图缺失像素过多（" + plan.missingPx + " px）")
        }
        if (frameCount >= 3) {
            val minScroll = MIN_SCROLL_RATIO * imageHeight * (frameCount - 1)
            if (scrollPx < minScroll) {
                reasons.add("整体滚动位移过小（约 " + scrollPx.roundToInt() + " px），疑似没有翻动页面")
            }
        }
        if (masterHeight < imageHeight * MIN_MASTER_RATIO) {
            reasons.add("重建长图高度异常（" + masterHeight + " px，低于单页高度）")
        }
        if (masterHeight.toDouble() > imageHeight.toDouble() * (frameCount + MAX_MASTER_EXTRA_FRAMES)) {
            reasons.add(
                "重建长图高度异常（" + masterHeight + " px，截图 " + frameCount + " 张）"
            )
        }
        if (pageCount < 1) {
            reasons.add("没有切分出任何页面")
        }
        return StitchQuality(
            frameCount = frameCount,
            badCount = plan.badCount,
            badRatio = badRatio,
            masterHeight = masterHeight,
            missingPx = plan.missingPx,
            missingRatio = missingRatio,
            scrollPx = scrollPx,
            pageCount = pageCount,
            ok = reasons.isEmpty(),
            reasons = reasons,
        )
    }
}

/** 捕获阶段保存的一页：临时 PNG + 内存灰度图 + 日期标签。 */
data class StitchFrame(
    val file: File,
    val gray: ByteArray,
    val width: Int,
    val height: Int,
    val newestDate: LocalDate?,
    val oldestDate: LocalDate?,
)

/** 最终输出的一页。 */
data class StitchedPage(
    val file: File,
    val newestDate: LocalDate?,
    val oldestDate: LocalDate?,
)

sealed class StitchResult {
    data class Success(val pages: List<StitchedPage>, val quality: StitchQuality) : StitchResult()

    /** 拼接不可用（数据不足/质量不达标/渲染出错），调用方应回退为原始截图页。 */
    data class Failure(val reason: String) : StitchResult()
}

/**
 * 无线 ADB 截图去重拼接：把「相邻截图保留少量重叠（约 10%～25%，视机型而自动调整）」
 * 的裁剪页自动对齐、去重，重建成长图，再按消息之间的空行切成互不重叠的连续页。
 *
 * 失败时返回 [StitchResult.Failure]，原始截图 PNG 不会被删除，调用方兜底输出。
 */
object StitchAssembler {
    /** 分析灰度宽度，必须与 Bitmap.toAnalysisGray(144) 一致。 */
    const val ANALYSIS_WIDTH = 144

    /**
     * @param framesOldestFirst 按聊天时间从旧到新的截图（第一张是最早的）。
     * @param outputDir 输出目录，成功时会写入 001.png、002.png…
     * @param workDir 临时目录（调用方负责在结束后整体删除）。
     */
    suspend fun stitch(
        framesOldestFirst: List<StitchFrame>,
        outputDir: File,
        workDir: File,
        log: (String) -> Unit,
    ): StitchResult {
        if (framesOldestFirst.size < 2) return StitchResult.Failure("截图数量不足，无法拼接")
        val first = framesOldestFirst.first()
        if (framesOldestFirst.any { it.width != first.width || it.height != first.height }) {
            return StitchResult.Failure("截图尺寸不一致")
        }
        if (first.width <= 0 || first.height <= 0) return StitchResult.Failure("截图尺寸异常")
        val aw = ANALYSIS_WIDTH
        val ah = first.gray.size / aw
        if (ah <= 0) return StitchResult.Failure("分析灰度图尺寸异常")

        val samples = framesOldestFirst.mapIndexed { index, frame ->
            GraySample(index.toLong(), frame.gray)
        }
        val analysis: AnalysisResult
        val plan: MasterPlan
        try {
            // 截图已经裁剪到聊天内容区（去掉了状态栏/输入栏），分析时直接用整帧，
            // 否则内容区检测会把首尾的空白聊天区域裁掉。
            analysis = StitchPlanner.analyzeStatic(
                samples, aw, ah, first.width, first.height, StitchCore.Band(0, ah),
            ) { _, message -> log("拼接分析：" + message) }
            plan = StitchPlanner.planMaster(analysis, 0, first.height, first.height)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StitchResult.Failure("拼接分析失败：" + (e.message ?: "未知错误"))
        }

        val quality = StitchQualityCheck.evaluate(analysis, plan, first.height)
        if (!quality.ok) return StitchResult.Failure(quality.reasons.joinToString("；"))

        // 全局单调性校验：拼接算法在重复气泡上可能把某对帧锁到错误位移（NCC 仍很高，
        // 质量门的坏帧率抓不到），这里用「位移/步长/取帧顺序」硬约束兜底，
        // 任何折返或异常步长都直接回退原始截图，绝不输出错位的长图。
        val monotonicIssues = StitchPlanner.validateMaster(analysis, plan)
        if (monotonicIssues.isNotEmpty()) {
            log("拼接全局单调性校验未通过：" + monotonicIssues.first())
            return StitchResult.Failure(monotonicIssues.joinToString("；"))
        }
        log("拼接全局单调性校验通过：相邻帧位移单调、步长稳定、主图取帧顺序无折返")

        val staged = File(workDir, "stitched")
        if (staged.isDirectory) staged.deleteRecursively()
        if (!staged.mkdirs()) return StitchResult.Failure("无法创建拼接临时目录")

        val source = FileFrameSource(framesOldestFirst.map { it.file }, first.width)
        var rendered = 0
        val stagedPages = ArrayList<StitchedPage>(quality.pageCount)
        // 每页的行签名，用于输出后的页级 seam 检测（防止长图里内容被重复拼接）。
        val signatures = ArrayList<PageRowSignature>(quality.pageCount)
        try {
            val tiles = StitchTiles(plan, source)
            val total = tiles.count
            if (total < 1) return StitchResult.Failure("切页结果为空")
            log(
                "正在重建长图并切页：原始截图 " + framesOldestFirst.size +
                    " 张 → 长图 " + plan.masterH + "px → " + total + " 页"
            )
            val logStep = max(1, total / 8)
            for (index in 0 until total) {
                currentCoroutineContext().ensureActive()
                val bitmap = tiles.render(index)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                signatures.add(SeamDetector.hashRows(pixels, bitmap.width, bitmap.height))
                val file = File(staged, String.format("%03d.png", index + 1))
                try {
                    FileOutputStream(file).use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            throw IOException("PNG 编码失败")
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                val dates = tileDates(plan, framesOldestFirst, index)
                stagedPages.add(StitchedPage(file, dates.second, dates.first))
                rendered = index + 1
                if (index % logStep == 0 || index == total - 1) {
                    log("正在生成拼接页 " + (index + 1) + "/" + total)
                }
            }
            // 页级 seam 检测：长图里若有一段内容被拼了两次，相邻页会出现成段完全相同的行。
            // 命中说明重建结果不可信，直接回退原始重叠截图页（不会丢图）。
            val seamScan = SeamDetector.analyze(signatures)
            val seamHit = seamScan.hit
            if (seamHit != null) {
                log(
                    "拼接质量检查：检测到内容重复，" + seamHit.summary() +
                        "（" + seamScan.maxRunSummary + "），回退原始截图页"
                )
                staged.deleteRecursively()
                return StitchResult.Failure(
                    "检测到相邻页内容重复（" + seamHit.summary() + "），已回退原始截图页"
                )
            }
            log("拼接质量检查通过：相邻页最长相同行段 " + seamScan.maxRun + " 行")
        } catch (e: CancellationException) {
            staged.deleteRecursively()
            throw e
        } catch (e: Exception) {
            staged.deleteRecursively()
            return StitchResult.Failure(
                "拼接渲染失败" + (if (rendered > 0) "（第 " + (rendered + 1) + " 页）" else "") +
                    "：" + (e.message ?: "未知错误")
            )
        } finally {
            source.close()
        }

        // 全部页渲染成功后再移动到输出目录；中途失败不会污染输出目录。
        val pages = ArrayList<StitchedPage>(stagedPages.size)
        stagedPages.forEachIndexed { index, page ->
            val target = File(outputDir, String.format("%03d.png", index + 1))
            if (target.exists()) target.delete()
            if (page.file.absolutePath != target.absolutePath) {
                if (!page.file.renameTo(target)) {
                    page.file.copyTo(target, overwrite = true)
                    page.file.delete()
                }
            }
            pages.add(page.copy(file = target))
        }
        staged.deleteRecursively()
        return StitchResult.Success(pages, quality)
    }

    /**
     * 每一页对应的聊天日期：取与该页相交的帧里最早/最晚的日期标签。
     * 日期标签属于某一屏，页边界可能落在两屏之间，因此取区间内第一条非空标签。
     */
    private fun tileDates(
        plan: MasterPlan,
        framesOldestFirst: List<StitchFrame>,
        pageIndex: Int,
    ): Pair<LocalDate?, LocalDate?> {
        if (pageIndex < 0 || pageIndex + 1 >= plan.cuts.size) return null to null
        val y0 = plan.cuts[pageIndex]
        val y1 = plan.cuts[pageIndex + 1]
        var firstFrame = Int.MAX_VALUE
        var lastFrame = -1
        for (op in plan.ops) {
            if (op.y1 <= y0) continue
            if (op.y0 >= y1) break
            if (op.frame < 0) continue
            if (op.frame < firstFrame) firstFrame = op.frame
            if (op.frame > lastFrame) lastFrame = op.frame
        }
        if (lastFrame < firstFrame) return null to null
        var oldest: LocalDate? = null
        for (index in firstFrame..lastFrame) {
            val frame = framesOldestFirst.getOrNull(index) ?: continue
            oldest = frame.oldestDate ?: frame.newestDate
            if (oldest != null) break
        }
        var newest: LocalDate? = null
        for (index in lastFrame downTo firstFrame) {
            val frame = framesOldestFirst.getOrNull(index) ?: continue
            newest = frame.newestDate ?: frame.oldestDate
            if (newest != null) break
        }
        return oldest to newest
    }
}

/** 渲染时按帧号从临时 PNG 解码；小容量 LRU，避免重复解码占内存。 */
private class FileFrameSource(
    private val files: List<File>,
    override val width: Int,
) : FrameImageSource {
    private val cache = object : LinkedHashMap<Int, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean {
            if (size > IMAGE_CACHE_SIZE) {
                eldest?.value?.let { if (!it.isRecycled) it.recycle() }
                return true
            }
            return false
        }
    }

    override fun frameImage(frameIndex: Int): Bitmap? {
        val cached = cache[frameIndex]
        if (cached != null && !cached.isRecycled) return cached
        cache.remove(frameIndex)
        val file = files.getOrNull(frameIndex) ?: return null
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        if (bitmap.width != width) {
            bitmap.recycle()
            return null
        }
        cache[frameIndex] = bitmap
        return bitmap
    }

    fun close() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
    }

    private companion object {
        const val IMAGE_CACHE_SIZE = 3
    }
}
