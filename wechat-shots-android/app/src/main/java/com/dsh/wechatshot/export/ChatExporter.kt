package com.dsh.wechatshot.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import com.dsh.wechatshot.adb.AdbController
import com.dsh.wechatshot.automation.ChatLayout
import com.dsh.wechatshot.automation.DateTags
import com.dsh.wechatshot.automation.OcrChatReader
import com.dsh.wechatshot.automation.UiDocument
import com.dsh.wechatshot.automation.WeChatAutomator
import com.dsh.wechatshot.stitch.toAnalysisGray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportMode { ALL, RANGE }

data class ExportRequest(
    val contact: String,
    val mode: ExportMode,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val cropStatusAndInput: Boolean = true,
    val makeZip: Boolean = true,
    val saveToGallery: Boolean = false,
)

data class ExportOutcome(
    val directory: File,
    val zipFile: File?,
    val imageCount: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val warnings: List<String>,
)

private data class CapturedPage(
    val file: File,
    val width: Int,
    val height: Int,
    val gray: ByteArray,
    val newestDate: LocalDate?,
    val oldestDate: LocalDate?,
)

private data class OutputPage(
    val file: File,
    val newestDate: LocalDate?,
    val oldestDate: LocalDate?,
)

private data class PageImage(
    val png: ByteArray,
    val gray: ByteArray,
    val width: Int,
    val height: Int,
)

private data class PageDecision(val save: Boolean, val stopAfter: Boolean)

/**
 * 从聊天最新消息开始，以固定步长向上翻页，每页 dump UI 判断日期并截图。
 * 输出按“最旧 -> 最新”重命名为 001.png、002.png…，方便直接按顺序阅读。
 */
class ChatExporter(
    private val context: Context,
    private val adb: AdbController,
    private val log: (String) -> Unit,
    private val onProgress: (scanned: Int, saved: Int, currentDate: String) -> Unit,
    private val onOutput: (ExportOutcome) -> Unit,
) {
    companion object {
        private const val MAX_PAGES = 400
        private const val SWIPE_DURATION_MS = 750
        private const val SETTLE_DELAY_MS = 700L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val SYSTEM_UI_GRACE_MS = 60_000L

        /** 捕获阶段的裁剪页 PNG 先放这个隐藏子目录，拼接成功后整体删除。 */
        private const val CAPTURE_DIR_NAME = ".capture"
    }

    private val automator = WeChatAutomator(adb, log)

    suspend fun export(request: ExportRequest): ExportOutcome {
        validate(request)
        val directory = createOutputDirectory(request.contact)
        val captureDir = File(directory, CAPTURE_DIR_NAME)
        if (!captureDir.mkdirs()) {
            runCatching { directory.delete() }
            throw IOException("无法创建临时截图目录：" + captureDir.absolutePath)
        }
        val pages = mutableListOf<CapturedPage>()
        var finalized = false
        var lastNewest: LocalDate? = null
        var lastOldest: LocalDate? = null
        var previousSignature: String? = null
        var sameSignatureCount = 0
        var reachedTop = false
        var maxReached = false
        var scanned = 0
        var skippedUnknown = 0
        var systemUiSince = 0L
        var systemUiPaused = false

        try {
            log("正在打开与“" + request.contact.trim() + "”的聊天…")
            val opened = automator.openChat(request.contact)
            val layout = opened.layout
            val useOcr = opened.useOcr
            log(
                if (useOcr) "聊天界面已打开（OCR 模式），开始逐页截图"
                else "聊天界面已打开，开始逐页截图"
            )

            while (scanned < MAX_PAGES) {
                currentCoroutineContext().ensureActive()
                var doc: UiDocument
                var pageSignature: String
                var preCaptured: ByteArray? = null

                if (useOcr) {
                    val top = runCatching { adb.topPackage() }.getOrDefault("")
                    if (top == SYSTEM_UI_PACKAGE) {
                        // 用户可能下拉通知栏点“停止导出”。通知栏属于 SystemUI，
                        // 此时暂停截图并等待回到微信，避免把通知栏截进结果里。
                        val now = System.currentTimeMillis()
                        if (systemUiSince == 0L) systemUiSince = now
                        if (now - systemUiSince > SYSTEM_UI_GRACE_MS) {
                            throw IOException("通知栏停留过久，导出已停止。请回到微信后重新导出")
                        }
                        if (!systemUiPaused) {
                            systemUiPaused = true
                            log("检测到通知栏，已暂停截图，等待回到微信…")
                        }
                        delay(600)
                        continue
                    }
                    if (top != WeChatAutomator.WECHAT_PACKAGE && !adb.isWeChatForeground()) {
                        throw IOException(
                            "微信已不在前台（当前界面：" + top.ifBlank { "未知" } +
                                "），导出停止。请勿在导出过程中切换应用或锁屏"
                        )
                    }
                    if (systemUiPaused) {
                        systemUiPaused = false
                        systemUiSince = 0L
                        log("已回到微信，继续截图")
                    }
                    val png = try {
                        adb.screencapPng()
                    } catch (e: Exception) {
                        throw IOException("截图失败：" + (e.message ?: "未知错误"), e)
                    }
                    preCaptured = png
                    // 截图需要几百毫秒，期间用户可能下拉通知栏或切走。
                    // 再查一次前台，避免把通知栏/本 App 的截图写进结果。
                    val afterCapture = runCatching { adb.topPackage() }.getOrDefault("")
                    if (afterCapture == SYSTEM_UI_PACKAGE) {
                        val now = System.currentTimeMillis()
                        if (systemUiSince == 0L) systemUiSince = now
                        if (now - systemUiSince > SYSTEM_UI_GRACE_MS) {
                            throw IOException("通知栏停留过久，导出已停止。请回到微信后重新导出")
                        }
                        if (!systemUiPaused) {
                            systemUiPaused = true
                            log("检测到通知栏，已暂停截图，等待回到微信…")
                        }
                        delay(600)
                        continue
                    }
                    if (afterCapture.isNotBlank() && afterCapture != WeChatAutomator.WECHAT_PACKAGE) {
                        throw IOException(
                            "微信已不在前台（当前界面：" + afterCapture +
                                "），导出停止。请勿在导出过程中切换应用或锁屏"
                        )
                    }
                    val analysis = try {
                        OcrChatReader.analyze(png, layout)
                    } catch (e: Exception) {
                        throw IOException("识别聊天截图失败：" + (e.message ?: "未知错误"), e)
                    }
                    doc = analysis.document
                    pageSignature = analysis.signature
                } else {
                    doc = try {
                        UiDocument.parse(adb.dumpUi())
                    } catch (e: Exception) {
                        throw IOException("读取聊天界面失败：" + (e.message ?: "未知错误"), e)
                    }
                    if (doc.packageName == SYSTEM_UI_PACKAGE) {
                        val now = System.currentTimeMillis()
                        if (systemUiSince == 0L) systemUiSince = now
                        if (now - systemUiSince > SYSTEM_UI_GRACE_MS) {
                            throw IOException("通知栏停留过久，导出已停止。请回到微信后重新导出")
                        }
                        if (!systemUiPaused) {
                            systemUiPaused = true
                            log("检测到通知栏，已暂停截图，等待回到微信…")
                        }
                        delay(600)
                        continue
                    }
                    if (doc.packageName != WeChatAutomator.WECHAT_PACKAGE) {
                        throw IOException(
                            "微信已不在前台（当前界面：" + doc.packageName +
                                "），导出停止。请勿在导出过程中切换应用或锁屏"
                        )
                    }
                    if (systemUiPaused) {
                        systemUiPaused = false
                        systemUiSince = 0L
                        log("已回到微信，继续截图")
                    }
                    pageSignature = doc.visibleTextSignature()
                }
                val dates = DateTags.datesIn(doc)
                val pageNewest = dates.maxOrNull()
                val pageOldest = dates.minOrNull()
                if (pageNewest != null) lastNewest = pageNewest
                if (pageOldest != null) lastOldest = pageOldest
                val effectiveNewest = pageNewest ?: lastNewest
                val effectiveOldest = pageOldest ?: lastOldest
                val currentLabel = when {
                    pageNewest != null && pageOldest != null && pageNewest != pageOldest ->
                        pageOldest.toString() + " ~ " + pageNewest.toString()
                    pageNewest != null -> pageNewest.toString()
                    effectiveNewest != null -> effectiveNewest.toString()
                    else -> "日期未知"
                }
                scanned++

                val decision = decide(request, effectiveNewest, effectiveOldest)
                if (!decision.save) {
                    if (request.mode == ExportMode.RANGE && effectiveNewest == null) skippedUnknown++
                    log("第 " + scanned + " 页（" + currentLabel + "）不在导出范围，跳过")
                    onProgress(scanned, pages.size, currentLabel)
                } else {
                    val png = preCaptured ?: adb.screencapPng()
                    val pageImage = cropPageImage(png, layout, request.cropStatusAndInput)
                    val file = File(captureDir, String.format("page_%04d.png", pages.size))
                    file.writeBytes(pageImage.png)
                    pages.add(
                        CapturedPage(
                            file = file,
                            width = pageImage.width,
                            height = pageImage.height,
                            gray = pageImage.gray,
                            newestDate = pageNewest,
                            oldestDate = pageOldest,
                        )
                    )
                    log("已保存第 " + pages.size + " 张截图（第 " + scanned + " 页，" + currentLabel + "）")
                    onProgress(scanned, pages.size, currentLabel)
                }

                if (pageSignature.isNotBlank() && pageSignature == previousSignature) {
                    sameSignatureCount++
                } else {
                    sameSignatureCount = 0
                }
                previousSignature = pageSignature

                if (request.mode == ExportMode.ALL) {
                    if (sameSignatureCount >= 2) {
                        reachedTop = true
                        break
                    }
                } else {
                    if (decision.stopAfter) break
                    // 日期区间模式下，如果连续三页界面完全不变，通常已经到顶或无法继续滚动。
                    if (sameSignatureCount >= 2) {
                        reachedTop = true
                        break
                    }
                }

                val startY = layout.top + (layout.height * 0.25f).toInt()
                val endY = layout.top + (layout.height * 0.75f).toInt()
                adb.swipe(layout.centerX, startY, layout.centerX, endY, SWIPE_DURATION_MS)
                delay(SETTLE_DELAY_MS)
            }

            if (scanned >= MAX_PAGES) maxReached = true
            if (pages.isEmpty()) {
                captureDir.deleteRecursively()
                directory.delete()
                throw IOException("指定日期范围内没有截取到聊天页面。请检查起止日期，或确认聊天记录是否可以正常向上滚动")
            }

            val warnings = mutableListOf<String>()
            if (skippedUnknown > 0) {
                warnings.add("开头 " + skippedUnknown + " 页没有日期标签且导出范围不含今天，已跳过")
            }
            if (maxReached) warnings.add("已达到 " + MAX_PAGES + " 页上限，导出可能尚未完成")
            if (reachedTop && request.mode == ExportMode.RANGE) {
                warnings.add("已滚动到聊天顶部，开始日期之前没有更多记录")
            }
            val outcome = finish(request, directory, captureDir, pages, warnings, allowStitch = true)
            finalized = true
            onOutput(outcome)
            return outcome
        } catch (e: CancellationException) {
            if (!finalized && pages.isNotEmpty()) {
                runCatching {
                    val outcome = finish(
                        request,
                        directory,
                        captureDir,
                        pages,
                        mutableListOf("用户停止了导出，当前截图已尽量保存"),
                        allowStitch = false,
                    )
                    onOutput(outcome)
                }
            } else if (pages.isEmpty()) {
                captureDir.deleteRecursively()
                runCatching { directory.delete() }
            }
            throw e
        } catch (e: Exception) {
            if (!finalized && pages.isNotEmpty()) {
                runCatching {
                    val outcome = finish(
                        request,
                        directory,
                        captureDir,
                        pages,
                        mutableListOf("导出中途出错，已保存出错前的截图：" + (e.message ?: "")),
                        allowStitch = false,
                    )
                    onOutput(outcome)
                }
            } else if (pages.isEmpty()) {
                captureDir.deleteRecursively()
                runCatching { directory.delete() }
            }
            throw e
        }
    }

    private fun validate(request: ExportRequest) {
        if (request.contact.trim().isEmpty()) throw IOException("联系人名称不能为空")
        if (request.mode == ExportMode.RANGE) {
            val start = request.startDate ?: throw IOException("请选择开始日期")
            val end = request.endDate ?: throw IOException("请选择结束日期")
            if (start.isAfter(end)) throw IOException("开始日期不能晚于结束日期")
        }
    }

    private fun decide(
        request: ExportRequest,
        effectiveNewest: LocalDate?,
        effectiveOldest: LocalDate?,
    ): PageDecision {
        if (request.mode == ExportMode.ALL) return PageDecision(save = true, stopAfter = false)
        val start = request.startDate ?: return PageDecision(save = false, stopAfter = true)
        val end = request.endDate ?: return PageDecision(save = false, stopAfter = true)

        if (effectiveNewest == null || effectiveOldest == null) {
            // 开头几页可能还没有滚出日期标签。若结束日期不是今天，这些页很可能
            // 属于结束日期之后的最新消息，直接跳过以免混入范围外的截图；
            // 结束日期是今天时，它们只可能是今天（或更早）的消息，可以保存。
            val today = LocalDate.now()
            return PageDecision(save = !end.isBefore(today), stopAfter = false)
        }

        // 当前页最旧的标签仍比结束日期晚两天以上：整页都在目标区间之前，跳过。
        if (effectiveOldest.isAfter(end.plusDays(1))) {
            return PageDecision(save = false, stopAfter = false)
        }

        // 当前页最新的标签已经早于开始日期：整页都在目标区间之前，不保存并结束。
        if (effectiveNewest.isBefore(start)) {
            return PageDecision(save = false, stopAfter = true)
        }

        val stopAfter = !effectiveOldest.isAfter(start)
        return PageDecision(save = true, stopAfter = stopAfter)
    }

    private fun createOutputDirectory(contact: String): File {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "Pictures")
        val root = File(pictures, "WeChatShots").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val safe = safeName(contact)
        var dir = File(root, safe + "_" + stamp)
        var index = 2
        while (dir.exists()) {
            dir = File(root, safe + "_" + stamp + "_" + index)
            index++
        }
        if (!dir.mkdirs()) throw IOException("无法创建输出目录：" + dir.absolutePath)
        return dir
    }

    private fun safeName(contact: String): String {
        val value = contact.trim().map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
        }.joinToString("").trim('_').take(40)
        return value.ifEmpty { "contact" }
    }

    /**
     * 一次解码同时得到：裁剪后的 PNG（写临时文件）、分析用灰度图（宽度 144，存内存）。
     * 灰度图用于捕获完成后把重叠截图对齐、重建长图。
     */
    private fun cropPageImage(png: ByteArray, layout: ChatLayout, crop: Boolean): PageImage {
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IOException("截图解码失败")
        try {
            if (!crop) {
                return PageImage(
                    png = png,
                    gray = source.toAnalysisGray(StitchAssembler.ANALYSIS_WIDTH),
                    width = source.width,
                    height = source.height,
                )
            }
            val x = layout.left.coerceIn(0, (source.width - 1).coerceAtLeast(0))
            val y = layout.top.coerceIn(0, (source.height - 1).coerceAtLeast(0))
            val width = layout.width.coerceAtMost(source.width - x)
            val height = layout.height.coerceAtMost(source.height - y)
            if (width <= 0 || height <= 0) {
                return PageImage(
                    png = png,
                    gray = source.toAnalysisGray(StitchAssembler.ANALYSIS_WIDTH),
                    width = source.width,
                    height = source.height,
                )
            }
            val cropped = if (x == 0 && y == 0 && width == source.width && height == source.height) {
                source
            } else {
                Bitmap.createBitmap(source, x, y, width, height)
            }
            try {
                val output = ByteArrayOutputStream()
                if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("裁剪后的截图编码失败")
                }
                return PageImage(
                    png = output.toByteArray(),
                    gray = cropped.toAnalysisGray(StitchAssembler.ANALYSIS_WIDTH),
                    width = width,
                    height = height,
                )
            } finally {
                if (cropped !== source) cropped.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    /**
     * 捕获结束后的收尾：优先把重叠截图拼成互不重叠的长图再切页；
     * 拼接不可用时回退为原始截图页（旧逻辑，页间保留约 50% 重叠），保证不丢图。
     */
    private suspend fun finish(
        request: ExportRequest,
        directory: File,
        captureDir: File,
        pages: List<CapturedPage>,
        warnings: MutableList<String>,
        allowStitch: Boolean,
    ): ExportOutcome {
        var stitched = false
        var quality: StitchQuality? = null
        val finalPages: List<OutputPage>
        if (allowStitch && pages.size >= 2) {
            log("共 " + pages.size + " 张带重叠的原始截图，开始拼接去重…")
            val ordered = pages.asReversed().map { page ->
                StitchFrame(
                    file = page.file,
                    gray = page.gray,
                    width = page.width,
                    height = page.height,
                    newestDate = page.newestDate,
                    oldestDate = page.oldestDate,
                )
            }
            val result = try {
                StitchAssembler.stitch(ordered, directory, captureDir, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StitchResult.Failure("拼接过程出错：" + (e.message ?: "未知错误"))
            }
            when (result) {
                is StitchResult.Success -> {
                    finalPages = result.pages.map { OutputPage(it.file, it.newestDate, it.oldestDate) }
                    stitched = true
                    quality = result.quality
                    log("拼接完成：" + finalPages.size + " 页互不重叠的截图")
                }
                is StitchResult.Failure -> {
                    val message = "拼接对齐失败，已回退为带重叠的原始截图页：" + result.reason
                    warnings.add(message)
                    log(message)
                    finalPages = moveRawPages(directory, pages)
                }
            }
        } else {
            finalPages = moveRawPages(directory, pages)
        }
        captureDir.deleteRecursively()
        return finalizeOutput(request, directory, finalPages, warnings, stitched, quality)
    }

    /** 兜底：把临时目录里的原始截图页按「旧 -> 新」重命名为 001.png…（保留页间重叠）。 */
    private fun moveRawPages(directory: File, pages: List<CapturedPage>): List<OutputPage> {
        val ordered = pages.asReversed()
        return ordered.mapIndexed { index, page ->
            val target = File(directory, String.format("%03d.png", index + 1))
            if (target.exists()) target.delete()
            if (page.file.absolutePath != target.absolutePath) {
                if (!page.file.renameTo(target)) {
                    page.file.copyTo(target, overwrite = true)
                    page.file.delete()
                }
            }
            OutputPage(target, page.newestDate, page.oldestDate)
        }
    }

    private fun finalizeOutput(
        request: ExportRequest,
        directory: File,
        pages: List<OutputPage>,
        warnings: MutableList<String>,
        stitched: Boolean,
        quality: StitchQuality?,
    ): ExportOutcome {
        writeMetadata(request, directory, pages, warnings, stitched, quality)
        val zip = if (request.makeZip) runCatching { createZip(directory) }
            .onFailure { warnings.add("ZIP 打包失败：" + (it.message ?: "")) }
            .getOrNull() else null
        if (request.saveToGallery) {
            runCatching { copyToGallery(directory, pages) }
                .onFailure { warnings.add("保存到相册失败：" + (it.message ?: "")) }
        }

        val firstDate = pages.asSequence()
            .mapNotNull { it.oldestDate ?: it.newestDate }
            .firstOrNull()
        val lastDate = pages.asReversed().asSequence()
            .mapNotNull { it.newestDate ?: it.oldestDate }
            .firstOrNull()
        return ExportOutcome(
            directory = directory,
            zipFile = zip,
            imageCount = pages.size,
            firstDate = firstDate,
            lastDate = lastDate,
            warnings = warnings.toList(),
        )
    }

    private fun writeMetadata(
        request: ExportRequest,
        directory: File,
        pages: List<OutputPage>,
        warnings: List<String>,
        stitched: Boolean,
        quality: StitchQuality?,
    ) {
        val json = JSONObject()
            .put("contact", request.contact.trim())
            .put("mode", request.mode.name)
            .put("startDate", request.startDate?.toString() ?: JSONObject.NULL)
            .put("endDate", request.endDate?.toString() ?: JSONObject.NULL)
            .put("exportedAt", LocalDateTime.now().toString())
            .put("imageCount", pages.size)
            .put("cropStatusAndInput", request.cropStatusAndInput)
            .put("stitched", stitched)
            .put("warnings", JSONArray(warnings))
        if (quality != null) {
            json.put(
                "stitchQuality",
                JSONObject()
                    .put("frames", quality.frameCount)
                    .put("badCount", quality.badCount)
                    .put("badRatio", round3(quality.badRatio))
                    .put("masterHeight", quality.masterHeight)
                    .put("missingPx", quality.missingPx)
                    .put("pageCount", quality.pageCount),
            )
        }
        val pageArray = JSONArray()
        pages.forEach { page ->
            pageArray.put(
                JSONObject()
                    .put("file", page.file.name)
                    .put("newestDate", page.newestDate?.toString() ?: JSONObject.NULL)
                    .put("oldestDate", page.oldestDate?.toString() ?: JSONObject.NULL)
            )
        }
        json.put("pages", pageArray)
        File(directory, "export.json").writeText(json.toString(2), Charsets.UTF_8)

        val firstDate = pages.asSequence()
            .mapNotNull { it.oldestDate ?: it.newestDate }
            .firstOrNull()
        val lastDate = pages.asReversed().asSequence()
            .mapNotNull { it.newestDate ?: it.oldestDate }
            .firstOrNull()
        val text = buildString {
            appendLine("微信聊天截图导出")
            appendLine("联系人：" + request.contact.trim())
            appendLine("导出时间：" + LocalDateTime.now())
            appendLine("模式：" + if (request.mode == ExportMode.ALL) "全部聊天记录" else "日期区间")
            if (request.mode == ExportMode.RANGE) {
                appendLine("日期范围：" + request.startDate + " 至 " + request.endDate)
            }
            appendLine("截图数量：" + pages.size)
            appendLine()
            appendLine("截图按照聊天时间从旧到新编号（001.png 最早，最后一张最新）。")
            if (stitched && quality != null) {
                appendLine(
                    "已把 " + quality.frameCount +
                        " 张带约 50% 重叠的原始截图自动对齐、重建成长图，再按消息之间的空行切分。"
                )
                appendLine("页面关系：页与页之间无重叠、上下连续，没有重复内容，也没有缺失内容。")
                appendLine(
                    "拼接质量：" + quality.summary() + "，重建长图高度 " + quality.masterHeight + " px。"
                )
            } else if (pages.size >= 2) {
                appendLine("每一页之间约有 50% 重叠，便于拼接阅读。")
            }
            if (firstDate != null || lastDate != null) {
                appendLine(
                    "聊天日期范围：" + (firstDate?.toString() ?: "未知") +
                        " 至 " + (lastDate?.toString() ?: "未知")
                )
            }
            appendLine("图片可能包含聊天中的隐私内容，请妥善保管。")
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine("提示：")
                warnings.forEach { appendLine("- " + it) }
            }
        }
        File(directory, "说明.txt").writeText(text, Charsets.UTF_8)
    }

    private fun round3(value: Double): Double =
        if (value.isNaN() || value.isInfinite()) 0.0 else Math.round(value * 1000.0) / 1000.0

    private fun createZip(directory: File): File {
        val zip = File(directory.parentFile, directory.name + ".zip")
        if (zip.exists()) zip.delete()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { output ->
            directory.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (!file.isFile) return@forEach
                output.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        return zip
    }

    private fun copyToGallery(directory: File, pages: List<OutputPage>) {
        val resolver = context.contentResolver
        val relative = Environment.DIRECTORY_PICTURES + "/WeChatShots/" + directory.name
        pages.forEach { page ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, page.file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, relative)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("相册插入失败")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    page.file.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("无法写入相册")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }
    }
}
