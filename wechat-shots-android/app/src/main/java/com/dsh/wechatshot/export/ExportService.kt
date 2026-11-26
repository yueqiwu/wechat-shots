package com.dsh.wechatshot.export

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dsh.wechatshot.adb.AdbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate

class ExportService : Service() {
    companion object {
        const val ACTION_START = "com.dsh.wechatshot.action.START_EXPORT"
        const val ACTION_STOP = "com.dsh.wechatshot.action.STOP_EXPORT"
        private const val EXTRA_CONTACT = "contact"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_START_DAY = "start_day"
        private const val EXTRA_END_DAY = "end_day"
        private const val EXTRA_CROP = "crop"
        private const val EXTRA_ZIP = "zip"
        private const val EXTRA_GALLERY = "gallery"
        private const val TAG = "ExportService"

        fun start(context: Context, request: ExportRequest) {
            val intent = Intent(context, ExportService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONTACT, request.contact)
                .putExtra(EXTRA_MODE, request.mode.name)
                .putExtra(EXTRA_START_DAY, request.startDate?.toEpochDay() ?: Long.MIN_VALUE)
                .putExtra(EXTRA_END_DAY, request.endDate?.toEpochDay() ?: Long.MIN_VALUE)
                .putExtra(EXTRA_CROP, request.cropStatusAndInput)
                .putExtra(EXTRA_ZIP, request.makeZip)
                .putExtra(EXTRA_GALLERY, request.saveToGallery)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ExportService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (job?.isActive == true) {
                    ExportBus.update { it.copy(stopping = true, message = "正在停止导出…") }
                    NotificationHelper.update(this, "正在停止导出", "已保存的截图会尽量保留", true)
                    job?.cancel()
                } else {
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (job?.isActive == true) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val request = readRequest(intent)
                if (request == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startExport(request, startId)
                return START_NOT_STICKY
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startExport(request: ExportRequest, startId: Int) {
        val notification = NotificationHelper.build(
            this,
            "正在导出聊天截图",
            "准备打开微信…",
            ongoing = true,
        )
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        ExportBus.reset()
        ExportBus.update {
            it.copy(
                running = true,
                contact = request.contact,
                modeLabel = if (request.mode == ExportMode.ALL) "全部聊天记录" else "日期区间",
                message = "正在准备…",
            )
        }
        setClipboard(request.contact)
        acquireWakeLock()

        job = scope.launch {
            runExport(request, startId)
        }
    }

    private suspend fun runExport(request: ExportRequest, startId: Int) {
        Log.i(TAG, "runExport start contact=" + request.contact + " mode=" + request.mode)
        val adb = AdbManager.controller()
        var originalStayOn: String? = null
        try {
            if (!adb.isConnected()) {
                throw IOException("ADB 尚未连接，请先到“连接”页完成连接")
            }
            originalStayOn = runCatching {
                adb.shell("settings get global stay_on_while_plugged_in", 8_000).trim()
            }.getOrNull()
            runCatching { adb.shell("svc power stayon true", 5_000) }

            val exporter = ChatExporter(
                context = this,
                adb = adb,
                log = { line ->
                    Log.i(TAG, line)
                    ExportBus.log(line)
                    NotificationHelper.update(this, "正在导出聊天截图", line, true)
                },
                onProgress = { scanned, saved, date ->
                    ExportBus.update {
                        it.copy(
                            pagesScanned = scanned,
                            imagesSaved = saved,
                            currentDate = date,
                            message = "已扫描 " + scanned + " 页，保存 " + saved + " 张",
                        )
                    }
                    NotificationHelper.update(
                        this,
                        "正在导出聊天截图",
                        "已扫描 " + scanned + " 页，保存 " + saved + " 张 · " + date,
                        true,
                    )
                },
                onOutput = { outcome ->
                    ExportBus.update {
                        it.copy(
                            resultDir = outcome.directory.absolutePath,
                            zipPath = outcome.zipFile?.absolutePath,
                            warnings = outcome.warnings,
                        )
                    }
                },
            )
            val outcome = exporter.export(request)
            Log.i(TAG, "export success count=" + outcome.imageCount + " dir=" + outcome.directory)
            val detail = "共 " + outcome.imageCount + " 张" +
                if (outcome.zipFile != null) "，已打包 ZIP" else ""
            ExportBus.update {
                it.copy(
                    running = false,
                    stopping = false,
                    finished = true,
                    imagesSaved = outcome.imageCount,
                    resultDir = outcome.directory.absolutePath,
                    zipPath = outcome.zipFile?.absolutePath,
                    warnings = outcome.warnings,
                    message = "导出完成：" + detail,
                )
            }
            NotificationHelper.update(this, "导出完成", detail, false)
        } catch (e: CancellationException) {
            ExportBus.update {
                it.copy(
                    running = false,
                    stopping = false,
                    finished = true,
                    message = if (it.resultDir != null) "已停止，已保存部分截图" else "已停止",
                )
            }
            NotificationHelper.update(
                this,
                "已停止导出",
                if (ExportBus.state.value.resultDir != null) "部分截图已保留" else "没有生成截图",
                false,
            )
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            ExportBus.update {
                it.copy(
                    running = false,
                    stopping = false,
                    finished = true,
                    error = e.message ?: "导出失败",
                    message = "导出失败",
                )
            }
            NotificationHelper.update(this, "导出失败", e.message ?: "请查看 App 内的日志", false)
        } finally {
            if (originalStayOn != null) {
                val value = originalStayOn.toIntOrNull() ?: 0
                runCatching { adb.shell("settings put global stay_on_while_plugged_in " + value, 8_000) }
            }
            releaseWakeLock()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf(startId)
        }
    }

    private fun readRequest(intent: Intent): ExportRequest? {
        val contact = intent.getStringExtra(EXTRA_CONTACT)?.trim().orEmpty()
        if (contact.isEmpty()) return null
        val mode = runCatching {
            ExportMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: ExportMode.ALL.name)
        }.getOrDefault(ExportMode.ALL)
        val startDay = intent.getLongExtra(EXTRA_START_DAY, Long.MIN_VALUE)
        val endDay = intent.getLongExtra(EXTRA_END_DAY, Long.MIN_VALUE)
        val start = if (startDay == Long.MIN_VALUE) null else runCatching { LocalDate.ofEpochDay(startDay) }.getOrNull()
        val end = if (endDay == Long.MIN_VALUE) null else runCatching { LocalDate.ofEpochDay(endDay) }.getOrNull()
        if (mode == ExportMode.RANGE && (start == null || end == null)) return null
        if (mode == ExportMode.RANGE && start!!.isAfter(end!!)) return null
        return ExportRequest(
            contact = contact,
            mode = mode,
            startDate = start,
            endDate = end,
            cropStatusAndInput = intent.getBooleanExtra(EXTRA_CROP, true),
            makeZip = intent.getBooleanExtra(EXTRA_ZIP, true),
            saveToGallery = intent.getBooleanExtra(EXTRA_GALLERY, false),
        )
    }

    private fun setClipboard(contact: String) {
        runCatching {
            val manager = getSystemService(ClipboardManager::class.java)
            manager.setPrimaryClip(ClipData.newPlainText("wechat_shots_contact", contact))
        }.onFailure {
            ExportBus.log("写入剪贴板失败，中文联系人可能无法自动搜索：" + (it.message ?: ""))
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wechatshot:export").apply {
                setReferenceCounted(false)
                acquire(40L * 60L * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
