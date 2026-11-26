package com.dsh.wechatshot.video

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 录屏转截图里所有可以纯 JVM 测试的数值逻辑（不依赖 Android 框架）。
 * 采样时序、旋转归一化、内容区→像素裁剪、切页文件名等都与网页版 pipeline.js 对齐。
 */
object VideoMath {

    /** 分析分辨率宽度，与网页版 ANALYSIS_WIDTH=144 一致。 */
    const val ANALYSIS_WIDTH = 144

    /** 分析区最小高度，与网页版 max(16, ...) 一致。 */
    const val MIN_ANALYSIS_HEIGHT = 16

    /** 单次分析最多采样帧数（网页版上限 2400）。 */
    const val MAX_SAMPLE_COUNT = 2400

    /** 默认分析帧率（网页版 fps=10）。 */
    const val DEFAULT_FPS = 10.0

    /** 每页像素高的下限（网页版 T = max(64, ...)）。 */
    const val MIN_TILE_HEIGHT = 64

    /**
     * 把任意角度归一化成 0/90/180/270（45° 分界四舍五入）。
     * 手机录屏一般只有 0/90/180/270。
     */
    fun normalizeRotation(degrees: Int): Int {
        val d = ((degrees % 360) + 360) % 360
        return when {
            d < 45 -> 0
            d < 135 -> 90
            d < 225 -> 180
            d < 315 -> 270
            else -> 0
        }
    }

    /** 应用旋转元数据之后的显示宽高。 */
    fun displaySize(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
        val rot = normalizeRotation(rotationDegrees)
        return if (rot == 90 || rot == 270) {
            max(1, rawHeight) to max(1, rawWidth)
        } else {
            max(1, rawWidth) to max(1, rawHeight)
        }
    }

    /** 分析图高度：AH = max(16, round(AW * videoHeight / videoWidth))。 */
    fun analysisHeight(videoWidth: Int, videoHeight: Int, analysisWidth: Int = ANALYSIS_WIDTH): Int {
        if (videoWidth <= 0 || videoHeight <= 0 || analysisWidth <= 0) return MIN_ANALYSIS_HEIGHT
        return max(MIN_ANALYSIS_HEIGHT, (analysisWidth.toDouble() * videoHeight / videoWidth).roundToInt())
    }

    /**
     * 实际使用的分析帧率：时长 * fps 超过 maxFrames 时降帧率（但不少于 1.5fps），
     * 与网页版 `fpsEff = max(1.5, 2400 / dur)` 一致。
     */
    fun effectiveFps(durationUs: Long, fps: Double = DEFAULT_FPS, maxFrames: Int = MAX_SAMPLE_COUNT): Double {
        if (durationUs <= 0L) return max(0.1, fps)
        val durSec = durationUs / 1_000_000.0
        if (fps <= 0.0) return 1.5
        val wanted = durSec * fps
        if (wanted <= maxFrames) return fps
        return max(1.5, maxFrames / durSec)
    }

    /**
     * 首轮采样帧数：max(2, round(durationSec * fpsEff))，且不超过 maxFrames。
     * 时长非法时返回 0，由调用方报错。
     */
    fun sampleCount(
        durationUs: Long,
        fps: Double = DEFAULT_FPS,
        maxFrames: Int = MAX_SAMPLE_COUNT,
    ): Int {
        if (durationUs <= 0L || fps <= 0.0) return 0
        val durSec = durationUs / 1_000_000.0
        val n = (durSec * effectiveFps(durationUs, fps, maxFrames)).roundToInt()
        return n.coerceIn(2, max(2, maxFrames))
    }

    /**
     * 区间中心采样：t[i] = t0 + (i + 0.5) * (t1 - t0) / n。
     * 与网页版 firstFrames/refill 的取帧方式一致，避免首尾关键帧对齐问题。
     */
    fun sampleTimes(t0Us: Long, t1Us: Long, count: Int): LongArray {
        val n = count.coerceAtLeast(1)
        val out = LongArray(n)
        if (t1Us <= t0Us) {
            java.util.Arrays.fill(out, t0Us)
            return out
        }
        val span = t1Us - t0Us
        for (i in 0 until n) {
            val t = t0Us + ((i + 0.5) * span / n).roundToLong()
            out[i] = if (t < t1Us) t else t1Us - 1
        }
        return out
    }

    /** 裁剪像素范围 [topPx, botPx)，高 = botPx - topPx。 */
    data class CropPx(val topPx: Int, val botPx: Int) {
        init {
            require(botPx >= topPx) { "裁剪范围非法: $topPx..$botPx" }
        }

        val heightPx: Int get() = botPx - topPx
    }

    /**
     * 由内容活跃区 [bandTop, bandBot) 映射出要保留的像素范围。
     *
     * 只有内容区明显小于整帧（说明录屏里包含状态栏/输入栏等静态条）时才裁；
     * 内容区高度不在 [25%, 100%) 之间时退回整帧（与网页版 applyAutoCrop 的判断一致）。
     * 这里用精确行映射而不是网页版的整数百分比，裁剪位置更准。
     */
    fun cropFromBand(
        bandTop: Int,
        bandBot: Int,
        analysisHeight: Int,
        videoHeight: Int,
        autoCrop: Boolean,
    ): CropPx {
        val full = CropPx(0, max(16, videoHeight))
        if (!autoCrop || analysisHeight <= 0 || videoHeight <= 16) return full
        val bt = bandTop.coerceIn(0, analysisHeight)
        val bb = bandBot.coerceIn(bt, analysisHeight)
        val bandH = bb - bt
        if (bandH <= 0) return full
        if (bandH >= analysisHeight) return full
        if (bandH < analysisHeight * 0.25) return full
        val scale = videoHeight.toDouble() / analysisHeight
        var topPx = (bt * scale).roundToInt().coerceIn(0, max(0, videoHeight - 16))
        var botPx = (bb * scale).roundToInt().coerceIn(topPx + 16, videoHeight)
        // 上下至少给内容区留一点边，避免把气泡边缘裁掉
        val pad = (videoHeight * 0.004).roundToInt()
        if (pad > 0) {
            topPx = (topPx - pad).coerceAtLeast(0)
            botPx = (botPx + pad).coerceAtMost(videoHeight)
        }
        if (botPx - topPx < 16) return full
        return CropPx(topPx, botPx)
    }

    /** 每页目标像素高；tileFactor 供「每页更短/更长」选项微调。 */
    fun tileHeight(cropHpx: Int, tileFactor: Double = 1.0): Int {
        val factor = if (tileFactor.isFinite() && tileFactor > 0.0) tileFactor else 1.0
        val h = (cropHpx * factor).roundToInt()
        return max(MIN_TILE_HEIGHT, h)
    }

    /** 页文件名：001.png、002.png…；总页数 >= 1000 时用 4 位补零（与网页版一致）。 */
    fun pageFileName(index: Int, total: Int): String {
        val pad = if (total >= 1000) 4 else 3
        return String.format(Locale.US, "%0${pad}d.png", index + 1)
    }

    /** 输出目录名：录屏_yyyyMMdd_HHmm。 */
    fun outputDirName(nowMillis: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        return "录屏_" + fmt.format(Date(nowMillis))
    }

    /** 在 parent 下挑一个不重名的输出目录（重名时追加 _2、_3…）。 */
    fun uniqueDir(parent: File, baseName: String = outputDirName()): File {
        var dir = File(parent, baseName)
        var i = 2
        while (dir.exists() && i < 1000) {
            dir = File(parent, "${baseName}_$i")
            i++
        }
        return dir
    }

    /** ZIP 文件名：聊天截图_129张.zip。 */
    fun zipFileName(pages: Int): String = "聊天截图_${pages}张.zip"

    /** 结果页展示的耗时：1.2 秒 / 23.4 秒 / 1 分 05 秒。 */
    fun formatElapsed(elapsedMs: Long): String {
        val ms = elapsedMs.coerceAtLeast(0L)
        return if (ms < 60_000L) {
            String.format(Locale.US, "%.1f 秒", ms / 1000.0)
        } else {
            val totalSec = (ms / 1000.0).roundToLong()
            val min = totalSec / 60
            val sec = totalSec % 60
            String.format(Locale.US, "%d 分 %02d 秒", min, sec)
        }
    }

    /** 结果页展示的视频时长。 */
    fun formatDuration(durationUs: Long): String {
        val ms = durationUs / 1000L
        return if (ms < 60_000L) {
            String.format(Locale.US, "%.1f 秒", ms / 1000.0)
        } else {
            val totalSec = ms / 1000L
            String.format(Locale.US, "%d 分 %02d 秒", totalSec / 60, totalSec % 60)
        }
    }

    /** 文件大小展示。 */
    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
        }
    }
}
