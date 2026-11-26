package com.dsh.wechatshot.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.dsh.wechatshot.stitch.AnalyzeOptions
import com.dsh.wechatshot.stitch.FrameImageSource
import com.dsh.wechatshot.stitch.FrameSampler
import com.dsh.wechatshot.stitch.GraySample
import com.dsh.wechatshot.stitch.StitchCore
import com.dsh.wechatshot.stitch.StitchPlanner
import com.dsh.wechatshot.stitch.StitchTiles
import com.dsh.wechatshot.stitch.toAnalysisGray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * 方案二「录屏转截图」的完整处理管线（不含 UI）：
 *
 * 视频 → MediaMetadataRetriever 首轮采样（10fps 灰度 144 宽）
 *      → StitchPlanner.analyze（失败区间补采样）
 *      → planMaster（内容区自动裁剪 + 切页规划）
 *      → StitchTiles 逐页渲染 PNG（001.png…）+ ZIP
 *
 * 全部工作跑在 Dispatchers.IO，协程取消可在采样/渲染/打包阶段中断。
 */
object VideoPipeline {

    const val TAG = "VideoExport"

    /** FileProvider authority，与 AndroidManifest 中一致。 */
    const val FILE_PROVIDER_AUTHORITY = "com.dsh.wechatshot.fileprovider"

    private const val CACHE_FRAMES = 3
    private const val PROGRESS_LOG_STEP = 20

    /** 视频来源：SAF 选出来的 content uri，或本地绝对路径（调试入口用）。 */
    sealed interface VideoSource {
        val label: String

        data class Content(val uri: Uri, override val label: String = uri.toString()) : VideoSource

        data class LocalPath(val path: String) : VideoSource {
            override val label: String get() = path
        }
    }

    /** 面向用户的中文错误（message 可直接展示）。 */
    class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Options(
        val autoCrop: Boolean = true,
        val tileFactor: Double = 1.0,
        val fps: Double = VideoMath.DEFAULT_FPS,
        val analysisWidth: Int = VideoMath.ANALYSIS_WIDTH,
        val refineRounds: Int = 3,
        val maxFrames: Int = VideoMath.MAX_SAMPLE_COUNT,
        val outputRoot: File,
    )

    /** 进度：fraction ∈ [0,1]，stage 是当前阶段中文名，detail 是细节（第 N/M 帧…）。 */
    data class StageProgress(val fraction: Float, val stage: String, val detail: String = "")

    data class Result(
        val pages: Int,
        val masterH: Int,
        val cropTopPx: Int,
        val cropBotPx: Int,
        val cropHpx: Int,
        val pageWidth: Int,
        val pageHeights: IntArray,
        val durationUs: Long,
        val videoWidth: Int,
        val videoHeight: Int,
        val rotationCw: Int,
        val analysisFrames: Int,
        val refinedFrames: Int,
        val badPairs: Int,
        val missingPx: Int,
        val elapsedMs: Long,
        val outDir: File,
        val zipFile: File,
        val pageFiles: List<File>,
    ) {
        val firstPage: File get() = pageFiles.first()
        val totalPagePx: Int get() = pageHeights.sum()
    }

    /**
     * 跑完整流程。可取消（取消时删除半成品目录并抛 [CancellationException]）；
     * 失败时抛 [ExportException]（中文 message），并清理半成品。
     */
    suspend fun export(
        context: Context,
        source: VideoSource,
        options: Options,
        onProgress: (StageProgress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val ctx = currentCoroutineContext()
        val appContext = context.applicationContext

        fun report(fraction: Float, stage: String, detail: String = "") {
            onProgress(StageProgress(fraction.coerceIn(0f, 1f), stage, detail))
        }

        report(0.01f, "读取视频信息")
        val reader = MediaReader(appContext)
        var outDir: File? = null
        try {
            reader.open(source)
            val info = reader.info
            Log.i(
                TAG,
                "START src=${source.label} display=${info.displayWidth}x${info.displayHeight} " +
                    "raw=${info.rawWidth}x${info.rawHeight} rot=${info.rotation} rotationCw=${info.rotationCw} " +
                    "durMs=${info.durationUs / 1000}",
            )
            report(
                0.04f,
                "读取视频信息",
                "${info.displayWidth}×${info.displayHeight} · ${VideoMath.formatDuration(info.durationUs)}",
            )

            // ---------- 首轮采样 ----------
            val aw = max(16, options.analysisWidth)
            val ah = VideoMath.analysisHeight(info.displayWidth, info.displayHeight, aw)
            val totalSamples = VideoMath.sampleCount(info.durationUs, options.fps, options.maxFrames)
            if (totalSamples < 2) throw ExportException("视频太短，至少需要 0.2 秒的录屏")
            val fpsEff = VideoMath.effectiveFps(info.durationUs, options.fps, options.maxFrames)
            val times = VideoMath.sampleTimes(0L, info.durationUs, totalSamples)
            val firstFrames = ArrayList<GraySample>(totalSamples)
            for (i in times.indices) {
                ctx.ensureActive()
                val gray = reader.analysisGray(times[i], aw, ah) ?: continue
                firstFrames.add(GraySample(times[i], gray))
                if (i % 3 == 0 || i == times.size - 1) {
                    report(
                        0.04f + 0.28f * (i + 1) / times.size,
                        "采样画面",
                        "第 ${i + 1}/${times.size} 帧",
                    )
                }
            }
            if (firstFrames.size < 2) throw ExportException("没能从视频里读出足够画面，请换一个录屏视频试试")
            Log.i(
                TAG,
                "SAMPLE frames=${firstFrames.size}/$totalSamples aw=$aw ah=$ah fps=" + fmt2(fpsEff),
            )

            // ---------- 分析（含补采样） ----------
            val sampler = FrameSampler { t0Us, t1Us, count ->
                if (!ctx.isActive) return@FrameSampler emptyList()
                val ts = VideoMath.sampleTimes(t0Us, t1Us, count)
                val more = ArrayList<GraySample>(ts.size)
                for (t in ts) {
                    if (!ctx.isActive) break
                    val gray = reader.analysisGray(t, aw, ah) ?: continue
                    more.add(GraySample(t, gray))
                }
                more
            }
            val analyzeProgress: (Double, String) -> Unit = { p, msg ->
                val f = 0.32f + 0.18f * (((p - 0.30) / 0.65).toFloat().coerceIn(0f, 1f))
                report(f, "分析滚动位移", msg)
            }
            val analysis = StitchPlanner.analyze(
                firstFrames = firstFrames,
                aw = aw,
                ah = ah,
                videoWidth = info.displayWidth,
                videoHeight = info.displayHeight,
                durationUs = info.durationUs,
                options = AnalyzeOptions(
                    analysisWidth = aw,
                    fps = fpsEff,
                    refineRounds = options.refineRounds,
                    maxFrames = options.maxFrames,
                ),
                sampler = sampler,
                progress = analyzeProgress,
            )
            ctx.ensureActive()

            val pos = analysis.track.pos
            val scrollRows = if (pos.size >= 2) abs(pos.last() - pos.first()) else 0.0
            val refined = analysis.frames.size - firstFrames.size
            Log.i(
                TAG,
                "ANALYZE band=${analysis.band.top}..${analysis.band.bot} h=${analysis.band.h} " +
                    "frames=${analysis.frames.size} refined=$refined bad=${analysis.track.bad.size} " +
                    "scrollRows=" + fmt1(scrollRows) + " maxShift=" + analysis.track.maxShift +
                    " medCost=" + fmt2(analysis.track.medCost),
            )
            if (scrollRows < 4.0) {
                throw ExportException("没有检测到页面滚动。请从聊天最上面开始，匀速向上滑，让录屏里有连续滚动的内容。")
            }

            // ---------- 规划裁剪 + 切页 ----------
            report(0.51f, "规划截图页面")
            val crop = VideoMath.cropFromBand(
                bandTop = analysis.band.top,
                bandBot = analysis.band.bot,
                analysisHeight = ah,
                videoHeight = info.displayHeight,
                autoCrop = options.autoCrop,
            )
            val tileH = VideoMath.tileHeight(crop.heightPx, options.tileFactor)
            val plan = StitchPlanner.planMaster(analysis, crop.topPx, crop.botPx, tileH)
            val pageCount = (plan.cuts.size - 1).coerceAtLeast(1)
            Log.i(
                TAG,
                "PLAN crop=${crop.topPx}..${crop.botPx} cropH=${plan.cropHpx} tileH=$tileH " +
                    "masterH=${plan.masterH} pages=$pageCount missingPx=${plan.missingPx} " +
                    "bandTop=${plan.bandTop} bandH=${plan.bandH}",
            )

            // ---------- 准备输出目录 ----------
            val root = File(options.outputRoot, "WeChatShots")
            val dir = VideoMath.uniqueDir(root)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw ExportException("无法创建保存目录，请检查手机存储空间")
            }
            outDir = dir
            report(0.55f, "渲染截图页面", "共 $pageCount 页")

            // ---------- 逐页渲染 + PNG 编码（流水线重叠） ----------
            val tiles = StitchTiles(
                plan,
                MMRFrameSource(reader, analysis.frames, info.displayWidth),
            )
            val actualPages = tiles.count
            if (actualPages != pageCount) Log.w(TAG, "WARN pageCount $pageCount != tiles.count $actualPages")
            val pageFiles = ArrayList<File>(actualPages)
            val pageHeights = IntArray(actualPages)

            coroutineScope {
                val channel = Channel<PageBitmap>(capacity = 2)
                val writeError = AtomicReference<Throwable?>(null)
                val writer = launch(Dispatchers.Default) {
                    try {
                        for (page in channel) {
                            try {
                                val file = File(dir, VideoMath.pageFileName(page.index, actualPages))
                                writePng(page.bitmap, file)
                                pageFiles.add(file)
                                pageHeights[page.index] =
                                    plan.cuts[page.index + 1] - plan.cuts[page.index]
                                if ((page.index + 1) % PROGRESS_LOG_STEP == 0) {
                                    Log.i(TAG, "RENDER ${page.index + 1}/$actualPages")
                                }
                            } finally {
                                page.bitmap.recycle()
                            }
                        }
                    } catch (ce: CancellationException) {
                        writeError.compareAndSet(null, ce)
                        channel.cancel()
                    } catch (t: Throwable) {
                        writeError.compareAndSet(null, t)
                        channel.cancel()
                    }
                }
                try {
                    for (i in 0 until actualPages) {
                        ctx.ensureActive()
                        val bmp = tiles.render(i)
                        try {
                            channel.send(PageBitmap(i, bmp))
                        } catch (ce: CancellationException) {
                            bmp.recycle()
                            if (writeError.get() != null) break else throw ce
                        }
                        report(
                            0.55f + 0.40f * (i + 1) / actualPages,
                            "渲染截图页面",
                            "第 ${i + 1}/$actualPages 页",
                        )
                    }
                } finally {
                    channel.close()
                }
                writer.join()
                ctx.ensureActive()
                writeError.get()?.let { err ->
                    throw if (err is ExportException) err
                    else ExportException("保存截图失败：${err.message ?: err.javaClass.simpleName}", err)
                }
            }
            if (pageFiles.size != actualPages) {
                throw ExportException("只保存了 ${pageFiles.size}/$actualPages 页截图，请检查手机存储空间")
            }
            pageFiles.forEach { f ->
                if (!f.isFile || f.length() <= 0L) throw ExportException("截图文件写入失败：${f.name}")
            }
            Log.i(TAG, "PAGES done=${pageFiles.size}")

            // ---------- ZIP ----------
            report(0.96f, "打包 ZIP")
            val zipFile = File(dir, VideoMath.zipFileName(actualPages))
            writeZip(zipFile, pageFiles) { done, total ->
                if (done == total || done % PROGRESS_LOG_STEP == 0) {
                    report(0.96f + 0.03f * done / total, "打包 ZIP", "第 $done/$total 个")
                }
            }

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.i(
                TAG,
                "DONE pages=${pageFiles.size} pageWidth=${info.displayWidth} crops=${pageHeights.joinToString(",")} " +
                    "masterH=${plan.masterH} crop=${crop.topPx}..${crop.botPx} elapsedMs=$elapsed " +
                    "dir=${dir.absolutePath} zip=${zipFile.length()}B frames=${analysis.frames.size} " +
                    "bad=${analysis.track.bad.size}",
            )
            report(0.99f, "完成")
            Result(
                pages = pageFiles.size,
                masterH = plan.masterH,
                cropTopPx = crop.topPx,
                cropBotPx = crop.botPx,
                cropHpx = plan.cropHpx,
                pageWidth = info.displayWidth,
                pageHeights = pageHeights,
                durationUs = info.durationUs,
                videoWidth = info.displayWidth,
                videoHeight = info.displayHeight,
                rotationCw = info.rotationCw,
                analysisFrames = analysis.frames.size,
                refinedFrames = refined,
                badPairs = analysis.track.bad.size,
                missingPx = plan.missingPx,
                elapsedMs = elapsed,
                outDir = dir,
                zipFile = zipFile,
                pageFiles = pageFiles.toList(),
            )
        } catch (ce: CancellationException) {
            outDir?.let { if (it.exists()) it.deleteRecursively() }
            Log.w(TAG, "CANCEL")
            throw ce
        } catch (e: ExportException) {
            outDir?.let { if (it.exists()) it.deleteRecursively() }
            Log.e(TAG, "FAIL ${e.message}", e)
            throw e
        } catch (e: Throwable) {
            outDir?.let { if (it.exists()) it.deleteRecursively() }
            Log.e(TAG, "FAIL unexpected ${e.message}", e)
            throw ExportException("导出失败：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            reader.close()
        }
    }

    private class PageBitmap(val index: Int, val bitmap: Bitmap)

    private fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { fos ->
            BufferedOutputStream(fos, 256 * 1024).use { bos ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
                    throw ExportException("PNG 编码失败：${file.name}")
                }
                bos.flush()
            }
        }
    }

    private fun writeZip(zipFile: File, pageFiles: List<File>, onItem: (Int, Int) -> Unit) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile), 1 shl 20)).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            for ((i, file) in pageFiles.withIndex()) {
                val entry = ZipEntry(file.name)
                entry.time = file.lastModified()
                zos.putNextEntry(entry)
                file.inputStream().buffered(64 * 1024).use { it.copyTo(zos) }
                zos.closeEntry()
                onItem(i + 1, pageFiles.size)
            }
        }
    }

    // ------------------------------------------------------------------
    // 解码层
    // ------------------------------------------------------------------

    private data class VideoInfo(
        val displayWidth: Int,
        val displayHeight: Int,
        val rawWidth: Int,
        val rawHeight: Int,
        val rotation: Int,
        val rotationCw: Int,
        val durationUs: Long,
    )

    /**
     * 单线程顺序使用一个 [MediaMetadataRetriever]：
     *  - 分析：getScaledFrameAtTime 直接解 144 宽的小图并转灰度（快、内存小）；
     *  - 渲染：getFrameAtTime 解整帧，交给 StitchTiles 绘制。
     * 所有帧都归一化成「显示方向」（按旋转元数据决定是否自己旋转）。
     */
    private class MediaReader(private val context: Context) : Closeable {

        private val mmr = MediaMetadataRetriever()
        private var rotationCw = 0

        lateinit var info: VideoInfo
            private set

        fun open(source: VideoSource) {
            try {
                when (source) {
                    is VideoSource.Content -> mmr.setDataSource(context, source.uri)
                    is VideoSource.LocalPath -> mmr.setDataSource(source.path)
                }
            } catch (e: Exception) {
                throw ExportException("打不开这个视频：文件可能已损坏或格式不支持", e)
            }
            val hasVideo = try {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            } catch (e: Exception) {
                throw ExportException("打不开这个视频：读不到视频信息", e)
            }
            if (hasVideo != "yes") throw ExportException("这个文件里没有视频画面，请选择录屏视频")

            val rawW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = VideoMath.normalizeRotation(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
            )
            val durationMs =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) throw ExportException("读不出视频时长，文件可能不完整")

            // 解一帧探测：部分系统/解码器会自己按旋转元数据把帧转正，需要识别出来避免二次旋转。
            var probeW = rawW
            var probeH = rawH
            val probe = try {
                mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                null
            }
            if (probe != null) {
                probeW = probe.width
                probeH = probe.height
                probe.recycle()
            }
            if (probeW <= 0 || probeH <= 0) throw ExportException("没能解码出画面，请换一个录屏视频试试")

            val swap = rot == 90 || rot == 270
            val metaW = if (swap) rawH else rawW
            val metaH = if (swap) rawW else rawH
            val dispW: Int
            val dispH: Int
            when {
                rot == 0 -> {
                    rotationCw = 0
                    dispW = probeW
                    dispH = probeH
                }
                rot == 180 -> {
                    // 180° 不改变宽高，无法从尺寸判断解码器有没有转；按「未转」处理（绝大多数设备如此）。
                    rotationCw = 180
                    dispW = probeW
                    dispH = probeH
                }
                rawW <= 0 || rawH <= 0 -> {
                    rotationCw = 0
                    dispW = probeW
                    dispH = probeH
                }
                aspectClose(probeW, probeH, rawW, rawH) -> {
                    // 解码帧还是存储方向 → 需要自己旋转
                    rotationCw = rot
                    dispW = probeH
                    dispH = probeW
                }
                aspectClose(probeW, probeH, metaW, metaH) -> {
                    // 解码器已按元数据转正
                    rotationCw = 0
                    dispW = probeW
                    dispH = probeH
                }
                else -> {
                    val probeLandscape = probeW >= probeH
                    val metaLandscape = metaW >= metaH
                    rotationCw = if (probeLandscape != metaLandscape) rot else 0
                    if (rotationCw == 90 || rotationCw == 270) {
                        dispW = probeH
                        dispH = probeW
                    } else {
                        dispW = probeW
                        dispH = probeH
                    }
                }
            }
            info = VideoInfo(
                displayWidth = max(2, dispW),
                displayHeight = max(2, dispH),
                rawWidth = rawW,
                rawHeight = rawH,
                rotation = rot,
                rotationCw = rotationCw,
                durationUs = durationMs * 1000L,
            )
        }

        /** 分析帧：144 宽灰度，高恰好 = ah（保证 analyze 的 detectBand 行列一致）。 */
        fun analysisGray(timeUs: Long, aw: Int, ah: Int): ByteArray? {
            val swap = rotationCw == 90 || rotationCw == 270
            val targetW = if (swap) ah else aw
            val targetH = if (swap) aw else ah
            val scaled: Bitmap? = try {
                mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH)
            } catch (e: Exception) {
                null
            }
            var bmp = scaled ?: try {
                // 个别设备/编码不支持 getScaledFrameAtTime，退回整帧解码 + 自己缩放
                mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                null
            } ?: return null
            bmp = normalizeOrientation(bmp)
            if (bmp.width != aw || bmp.height != ah) {
                bmp = scaleTo(bmp, aw, ah) ?: return null
            }
            return try {
                bmp.toAnalysisGray(aw)
            } finally {
                bmp.recycle()
            }
        }

        /** 渲染帧：显示方向的整帧（宽 = displayWidth，高 = displayHeight）。 */
        fun fullFrame(timeUs: Long): Bitmap? {
            var bmp = try {
                mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                null
            } ?: return null
            bmp = normalizeOrientation(bmp)
            if (bmp.width != info.displayWidth || bmp.height != info.displayHeight) {
                bmp = scaleTo(bmp, info.displayWidth, info.displayHeight) ?: return null
            }
            return bmp
        }

        /**
         * 把解码帧转成「显示方向」。部分系统会自己按旋转元数据把帧转正，用宽高方向判断还要不要再转，
         * 否则竖屏录屏会被拼成横向。180° 不改变宽高、无法判断，按「未转」处理。
         */
        private fun normalizeOrientation(frame: Bitmap): Bitmap {
            if (rotationCw == 0 || info.displayWidth <= 0) return frame
            val expectLandscape = info.displayWidth >= info.displayHeight
            val isLandscape = frame.width >= frame.height
            if (rotationCw == 180 || isLandscape != expectLandscape) return rotate(frame, rotationCw)
            return frame
        }

        override fun close() {
            try {
                mmr.release()
            } catch (e: Throwable) {
                Log.w(TAG, "MMR release failed", e)
            }
        }
    }

    /** 渲染用的整帧来源（2~3 帧 LRU，避免同一帧被多页重复解码）。 */
    private class MMRFrameSource(
        private val reader: MediaReader,
        private val frames: List<StitchCore.FrameSig>,
        override val width: Int,
    ) : FrameImageSource {
        private val cache = object : LinkedHashMap<Int, Bitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean =
                size > CACHE_FRAMES
        }

        override fun frameImage(frameIndex: Int): Bitmap? {
            cache[frameIndex]?.let { return it }
            val frame = frames.getOrNull(frameIndex) ?: return null
            val bmp = reader.fullFrame(frame.timeUs) ?: return null
            cache[frameIndex] = bmp
            return bmp
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun aspectClose(w1: Int, h1: Int, w2: Int, h2: Int): Boolean {
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return false
        val a = w1.toDouble() / h1
        val b = w2.toDouble() / h2
        if (!a.isFinite() || !b.isFinite()) return false
        return abs(a - b) / max(a, b) < 0.02
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bitmap
        return try {
            val m = Matrix().apply { postRotate(d.toFloat()) }
            val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (out !== bitmap) bitmap.recycle()
            out
        } catch (e: OutOfMemoryError) {
            bitmap.recycle()
            throw ExportException("内存不足，视频分辨率太高了", e)
        }
    }

    private fun scaleTo(bitmap: Bitmap, width: Int, height: Int): Bitmap? {
        if (bitmap.width == width && bitmap.height == height) return bitmap
        return try {
            val out = Bitmap.createScaledBitmap(bitmap, max(1, width), max(1, height), true)
            if (out !== bitmap) bitmap.recycle()
            out
        } catch (e: OutOfMemoryError) {
            bitmap.recycle()
            throw ExportException("内存不足，视频分辨率太高了", e)
        }
    }
}
