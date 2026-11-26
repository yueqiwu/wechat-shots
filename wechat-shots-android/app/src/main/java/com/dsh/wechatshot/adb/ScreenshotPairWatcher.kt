package com.dsh.wechatshot.adb

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统截图监听器：监听用户按「电源键+音量下」或控制中心截图后产生的新图片，把最新一张交给回调去 OCR。
 *
 * 为什么这么做（Android 14+ 的实际情况）：
 * Android 14（API 34）起提供 `Activity.registerScreenCaptureCallback`，但它**只能通知「用户在
 * 本 Activity 可见时截屏了」，不提供 Bitmap**（2023 年提交的 `registerScreenshotCallback`
 * 并没有进入正式 SDK）。本项目 compileSdk 37 的 android.jar 里也没有 `registerScreenshotCallback`。
 * 因此真正的主路径必须是：**读 MediaStore Screenshots 目录里新增的图片**。
 * 本类的设计：
 * - 主路径 1：ContentObserver 监听 MediaStore Images，截图后媒体库会新增「Screenshot_…」图片；
 * - 主路径 2：页面回到前台时主动轮询最新截图（用户在设置里截完图切回来 -> ON_RESUME 触发）；
 * - 增强路径：API 34+ 且权限/系统允许时注册 `registerScreenCaptureCallback`（只当触发信号，
 *   仍去 MediaStore 取图），失败静默降级；
 * - 需要 READ_MEDIA_IMAGES（33+）/ READ_EXTERNAL_STORAGE（30-32）权限；无权限时回调
 *   [onPermissionMissing]，页面显示授权横幅并提供手动输入兜底。
 *
 * 去重策略：
 * - [start] 时记录本页面开始时间，只处理最近 3 分钟内的截图；
 * - 记录已处理过的 id，同一张图只回调一次；
 * - 自动轮询使用「新于 start 时间 / 新于 baseline」的过滤，[pollLatest] force=true 时忽略 baseline，
 *   供页面「我已截图，重新识别」按钮使用（用户在 App 启动前就截了图也能识别）。
 *
 * 所有查询/解码在独立 HandlerThread 上做，回调 post 到主线程。
 */
class ScreenshotPairWatcher(
    context: Context,
    private val onScreenshot: (Bitmap, Source) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val onPermissionMissing: () -> Unit = {},
    /**
     * 页面模式：允许把开始监听前 3 分钟内的截图也当作目标（用户常常先截图再打开 App）。
     * 前台服务模式：只需要几十秒内的新截图，传小值可避免把上一次的旧配对码当成新目标。
     */
    private val initialLookbackSec: Long = 180L,
) {

    enum class Source { MEDIA_STORE, POLL, SCREEN_CAPTURE_CALLBACK }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var observer: ContentObserver? = null
    private var activity: Activity? = null
    private var screenCaptureCallback: Any? = null

    private val seenIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val started = AtomicBoolean(false)
    private var startWallClockSec: Long = 0L
    private var lastDeliveredId: Long = -1L
    private var lastDeliveredAtMs: Long = 0L
    private var pendingPoll: Runnable? = null

    // ---------------------------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------------------------

    fun start(activity: Activity?) {
        if (!started.compareAndSet(false, true)) {
            this.activity = activity
            return
        }
        this.activity = activity
        // 进入页面时，允许把最近 3 分钟内截的图也当成待识别目标（用户往往先截图再打开 App）
        startWallClockSec = System.currentTimeMillis() / 1000L - initialLookbackSec.coerceAtLeast(0L)
        val ht = HandlerThread("ScreenshotPairWatcher").also { it.start() }
        thread = ht
        val handler = Handler(ht.looper)
        worker = handler

        if (!hasPermission(appContext)) {
            mainHandler.post { onPermissionMissing() }
        }

        try {
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    scheduleDebouncedPoll(delayMs = 350L)
                }
            }
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                obs,
            )
            observer = obs
        } catch (t: Throwable) {
            Log.w(TAG, "registerContentObserver failed", t)
        }

        registerScreenCaptureCallback(activity)
        onStatus("截图监听已开启")
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        unregisterScreenCaptureCallback()
        observer?.let {
            try {
                appContext.contentResolver.unregisterContentObserver(it)
            } catch (_: Throwable) {
            }
        }
        observer = null
        pendingPoll?.let { worker?.removeCallbacks(it) }
        pendingPoll = null
        thread?.quitSafely()
        thread = null
        worker = null
        activity = null
    }

    /** 主动轮询一次最新截图；force=true 时连 start 之前的旧截图也会处理（用户点「我已截图」按钮）。 */
    fun pollLatest(force: Boolean = false) {
        val handler = worker ?: return
        handler.post { pollInternal(force, Source.POLL, attempt = 0) }
    }

    // ---------------------------------------------------------------------------------------
    // API 34+ 的屏幕捕获回调（只能当触发信号用，拿不到图）
    // ---------------------------------------------------------------------------------------

    private fun registerScreenCaptureCallback(activity: Activity?) {
        if (activity == null || Build.VERSION.SDK_INT < 34) return
        try {
            val executor = appContext.mainExecutor
            val cb = object : Activity.ScreenCaptureCallback {
                override fun onScreenCaptured() {
                    // 只能知道「刚刚截屏了」，图仍要从 MediaStore 拿；媒体库可能延迟几百毫秒写入
                    scheduleDebouncedPoll(delayMs = 600L, source = Source.SCREEN_CAPTURE_CALLBACK)
                }
            }
            activity.registerScreenCaptureCallback(executor, cb)
            screenCaptureCallback = cb
            this.activity = activity
        } catch (t: Throwable) {
            // 未声明 DETECT_SCREEN_CAPTURE（normal 权限）时可能抛 SecurityException；静默降级
            Log.i(TAG, "registerScreenCaptureCallback unavailable: ${t.javaClass.simpleName}")
            screenCaptureCallback = null
        }
    }

    private fun unregisterScreenCaptureCallback() {
        val cb = screenCaptureCallback as? Activity.ScreenCaptureCallback ?: return
        if (Build.VERSION.SDK_INT < 34) return
        try {
            activity?.unregisterScreenCaptureCallback(cb)
        } catch (_: Throwable) {
        }
        screenCaptureCallback = null
    }

    // ---------------------------------------------------------------------------------------
    // 轮询 / 去抖
    // ---------------------------------------------------------------------------------------

    private fun scheduleDebouncedPoll(delayMs: Long, source: Source = Source.MEDIA_STORE) {
        val handler = worker ?: return
        pendingPoll?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { pollInternal(false, source, attempt = 0) }
        pendingPoll = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun retryLater(attempt: Int, source: Source) {
        val handler = worker ?: return
        pendingPoll?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { pollInternal(false, source, attempt) }
        pendingPoll = runnable
        handler.postDelayed(runnable, 1_200L)
    }

    private fun pollInternal(force: Boolean, source: Source, attempt: Int) {
        if (!hasPermission(appContext)) {
            mainHandler.post { onPermissionMissing() }
            // 没有媒体权限时不直接返回：Android 10+ 允许 App 读取自己插入媒体库的图片（自检用），
            // 普通截图查询会因权限被拒而在 queryScreenshots 内部被 catch。
        }
        // 查最近 3 分钟内的新截图；force 时放宽到 5 分钟（用户点「我已截图」按钮）
        val minDateAddedSec = if (force) System.currentTimeMillis() / 1000L - 300L else startWallClockSec
        val shots = queryScreenshots(minDateAddedSec, limit = 12)
        var candidates = shots.filter { !seenIds.contains(it.id) }
            .sortedByDescending { it.dateAdded }
        Log.d(TAG, "poll force=$force source=$source attempt=$attempt shots=${shots.size} candidates=${candidates.size}")
        if (candidates.isEmpty()) {
            Log.d(TAG, "poll: no candidates (minDateAdded=$minDateAddedSec)")
            if (force) mainHandler.post { onStatus("没有找到新截图，请先回到设置截图，或手动输入配对码") }
            return
        }
        val best = candidates.first()
        val bitmap = decode(best.uri)
        Log.d(TAG, "poll: best id=${best.id} name=${best.name} bitmap=${bitmap?.let { "${it.width}x${it.height}" }}")
        if (bitmap == null) {
            // 媒体库刚插入时文件可能还没写完，最多重试 2 次
            if (attempt < 2) {
                retryLater(attempt + 1, source)
            }
            return
        }
        seenIds += best.id
        // 内容观察者会连续触发多次（同一张图），2.5 秒内相同 id 不重复回调
        if (best.id == lastDeliveredId && System.currentTimeMillis() - lastDeliveredAtMs < 2_500L) {
            return
        }
        lastDeliveredId = best.id
        lastDeliveredAtMs = System.currentTimeMillis()
        mainHandler.post {
            onStatus("已找到最新截图（${best.name}）")
            onScreenshot(bitmap, source)
        }
    }

    // ---------------------------------------------------------------------------------------
    // MediaStore 查询 / 解码
    // ---------------------------------------------------------------------------------------

    private data class Shot(val id: Long, val uri: Uri, val name: String, val dateAdded: Long, val relativePath: String)

    private val screenshotNameRegex = Regex(
        "screenshot|screen[_ -]?shot|screencap|截图|截屏|螢幕擷取|屏幕截图",
        RegexOption.IGNORE_CASE,
    )

    private fun queryScreenshots(minDateAddedSec: Long, limit: Int): List<Shot> {
        val out = mutableListOf<Shot>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf(minDateAddedSec.coerceAtLeast(0L).toString())
        try {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val date = cursor.getLong(dateCol)
                    val rel = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else ""
                    val looksLikeScreenshot =
                        screenshotNameRegex.containsMatchIn(name) ||
                            screenshotNameRegex.containsMatchIn(rel) ||
                            (rel.contains("Screenshot", true) || rel.contains("截图") || rel.contains("截屏"))
                    if (!looksLikeScreenshot) continue
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    out += Shot(id, uri, name, date, rel)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "query screenshots failed", t)
        }
        return out
    }

    private fun decode(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // 注意：第一趟只取尺寸时 decodeStream 一定返回 null，不能把它的返回值当作「流为空」判断，
            // 必须分开判断 openInputStream 是否为 null。
            val boundsStream = appContext.contentResolver.openInputStream(uri)
                ?: run { Log.w(TAG, "decode: null stream for $uri"); return null }
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "decode: bad bounds ${bounds.outWidth}x${bounds.outHeight} for $uri")
                return null
            }
            var sample = 1
            val maxEdge = maxOf(bounds.outWidth, bounds.outHeight)
            // OCR 不需要原图分辨率，限制到约 1600-2500px 长边，降低内存和 ML Kit 耗时
            while (maxEdge / (sample * 2) >= 1600) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decode screenshot failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "ScreenshotPairWatcher"

        /** 监听截图需要的运行时权限：33+ 为 READ_MEDIA_IMAGES，30-32 为 READ_EXTERNAL_STORAGE。 */
        fun permissionName(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED

        /** 当前系统是否需要（且能够）进行运行时授权。 */
        fun needsPermissionRequest(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 30 && !hasPermission(context)
    }
}
