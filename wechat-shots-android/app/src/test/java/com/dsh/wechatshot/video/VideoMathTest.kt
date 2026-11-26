package com.dsh.wechatshot.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 纯 JVM 测试：录屏转截图里与 Android 无关的数值逻辑（与网页版 pipeline.js 对齐）。 */
class VideoMathTest {

    @Test
    fun normalizeRotation_snapsToQuarterTurns() {
        assertEquals(0, VideoMath.normalizeRotation(0))
        assertEquals(0, VideoMath.normalizeRotation(44))
        assertEquals(90, VideoMath.normalizeRotation(45))
        assertEquals(90, VideoMath.normalizeRotation(90))
        assertEquals(90, VideoMath.normalizeRotation(134))
        assertEquals(180, VideoMath.normalizeRotation(135))
        assertEquals(180, VideoMath.normalizeRotation(-180))
        assertEquals(270, VideoMath.normalizeRotation(225))
        assertEquals(270, VideoMath.normalizeRotation(270))
        assertEquals(270, VideoMath.normalizeRotation(-90))
        assertEquals(0, VideoMath.normalizeRotation(315))
        assertEquals(0, VideoMath.normalizeRotation(359))
        assertEquals(0, VideoMath.normalizeRotation(360))
        assertEquals(90, VideoMath.normalizeRotation(450))
    }

    @Test
    fun displaySize_swapsWidthAndHeightOnLandscapeRotations() {
        assertEquals(1080 to 1920, VideoMath.displaySize(1080, 1920, 0))
        assertEquals(1080 to 1920, VideoMath.displaySize(1080, 1920, 180))
        assertEquals(1920 to 1080, VideoMath.displaySize(1080, 1920, 90))
        assertEquals(1920 to 1080, VideoMath.displaySize(1080, 1920, 270))
        // 非法尺寸兜底成至少 1
        assertEquals(1 to 1, VideoMath.displaySize(0, 0, 90))
    }

    @Test
    fun analysisHeight_matchesWebFormula() {
        // 720x1584 的录屏：AH = round(144 * 1584 / 720) = 317
        assertEquals(317, VideoMath.analysisHeight(720, 1584, 144))
        // 1080x1920：AH = 256
        assertEquals(256, VideoMath.analysisHeight(1080, 1920, 144))
        // 极端扁的帧不能低于下限 16
        assertEquals(VideoMath.MIN_ANALYSIS_HEIGHT, VideoMath.analysisHeight(1440, 100, 144))
        // 非法尺寸兜底
        assertEquals(VideoMath.MIN_ANALYSIS_HEIGHT, VideoMath.analysisHeight(0, 1920, 144))
        assertEquals(VideoMath.MIN_ANALYSIS_HEIGHT, VideoMath.analysisHeight(1080, 0, 144))
    }

    @Test
    fun effectiveFps_dropsForLongVideos() {
        // 23.8 秒、10fps → 238 帧，不需要降
        assertEquals(10.0, VideoMath.effectiveFps(23_800_000L, 10.0, 2400), 1e-9)
        // 5 分钟、10fps → 3000 帧超过上限 2400 → max(1.5, 2400/300) = 8
        assertEquals(8.0, VideoMath.effectiveFps(300_000_000L, 10.0, 2400), 1e-9)
        // 超长视频不会低于 1.5fps
        assertEquals(1.5, VideoMath.effectiveFps(3_600_000_000L, 10.0, 2400), 1e-9)
    }

    @Test
    fun sampleCount_isTwoAtMinimumAndCappedByMaxFrames() {
        assertEquals(20, VideoMath.sampleCount(2_000_000L, 10.0, 2400))
        assertEquals(238, VideoMath.sampleCount(23_800_000L, 10.0, 2400))
        // 时长非法时返回 0，由调用方报错
        assertEquals(0, VideoMath.sampleCount(0L, 10.0, 2400))
        assertEquals(0, VideoMath.sampleCount(-1L, 10.0, 2400))
        // 上限生效
        val n = VideoMath.sampleCount(3_600_000_000L, 10.0, 2400)
        assertTrue("采样数应被 maxFrames 限制: $n", n <= 2400)
    }

    @Test
    fun sampleTimes_areIntervalCenters() {
        val times = VideoMath.sampleTimes(0L, 1_000_000L, 4)
        assertEquals(listOf(125_000L, 375_000L, 625_000L, 875_000L), times.toList())
        // 最后一帧必须严格早于区间结束
        assertTrue(times.all { it < 1_000_000L })
        // count=1 取区间中点
        assertEquals(500_000L, VideoMath.sampleTimes(0L, 1_000_000L, 1).single())
        // 空区间不越界
        val same = VideoMath.sampleTimes(700L, 700L, 3)
        assertTrue(same.all { it == 700L })
    }

    @Test
    fun cropFromBand_keepsFullFrameWhenAutoCropOff() {
        val full = VideoMath.cropFromBand(40, 360, 400, 1600, autoCrop = false)
        assertEquals(VideoMath.CropPx(0, 1600), full)
        // 内容区几乎占满整帧时不裁
        assertEquals(VideoMath.CropPx(0, 1600), VideoMath.cropFromBand(0, 320, 320, 1600, true))
        // 内容区太窄（<25%）时视为检测失败，不裁
        assertEquals(VideoMath.CropPx(0, 1600), VideoMath.cropFromBand(100, 150, 400, 1600, true))
    }

    @Test
    fun cropFromBand_mapsBandRowsToPixelsWithSmallPadding() {
        // AH=400、vh=1600（scale=4）：band 40..360 → topPx=160、botPx=1440，上下各留 6px
        val crop = VideoMath.cropFromBand(40, 360, 400, 1600, autoCrop = true)
        assertEquals(154, crop.topPx)
        assertEquals(1446, crop.botPx)
        assertEquals(1292, crop.heightPx)
        assertTrue(crop.topPx >= 0 && crop.botPx <= 1600)
    }

    @Test
    fun cropFromBand_clampsWhenBandTouchesEdges() {
        val crop = VideoMath.cropFromBand(0, 200, 400, 1600, autoCrop = true)
        assertEquals(0, crop.topPx)
        assertEquals(800 + 6, crop.botPx)
        assertTrue(crop.heightPx > 16)
    }

    @Test
    fun tileHeight_respectsFactorAndFloor() {
        assertEquals(1584, VideoMath.tileHeight(1584, 1.0))
        assertEquals(1267, VideoMath.tileHeight(1584, 0.8))
        assertEquals(1901, VideoMath.tileHeight(1584, 1.2))
        // 非法 factor 退回 1.0
        assertEquals(1584, VideoMath.tileHeight(1584, 0.0))
        assertEquals(1584, VideoMath.tileHeight(1584, Double.NaN))
        // 最小 64
        assertEquals(64, VideoMath.tileHeight(20, 1.0))
    }

    @Test
    fun pageFileName_padsToThreeOrFourDigits() {
        assertEquals("001.png", VideoMath.pageFileName(0, 129))
        assertEquals("129.png", VideoMath.pageFileName(128, 129))
        assertEquals("0001.png", VideoMath.pageFileName(0, 1000))
        assertEquals("1000.png", VideoMath.pageFileName(999, 1200))
    }

    @Test
    fun outputDirName_hasExpectedFormat() {
        val name = VideoMath.outputDirName(1_700_000_000_000L)
        assertTrue("目录名格式不对: $name", Regex("^录屏_\\d{8}_\\d{4}$").matches(name))
    }

    @Test
    fun uniqueDir_appendsSuffixWhenExists() {
        val parent = Files.createTempDirectory("video-math").toFile()
        try {
            assertEquals("录屏_x", VideoMath.uniqueDir(parent, "录屏_x").name)
            assertTrue(File(parent, "录屏_x").mkdirs())
            assertEquals("录屏_x_2", VideoMath.uniqueDir(parent, "录屏_x").name)
            assertTrue(File(parent, "录屏_x_2").mkdirs())
            assertEquals("录屏_x_3", VideoMath.uniqueDir(parent, "录屏_x").name)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun zipFileName_includesPageCount() {
        assertEquals("聊天截图_129张.zip", VideoMath.zipFileName(129))
        assertEquals("聊天截图_2张.zip", VideoMath.zipFileName(2))
    }

    @Test
    fun formatElapsed_switchesToMinutesAfterOneMinute() {
        assertEquals("0.0 秒", VideoMath.formatElapsed(0))
        assertEquals("1.2 秒", VideoMath.formatElapsed(1_234))
        assertEquals("49.0 秒", VideoMath.formatElapsed(49_000))
        assertEquals("60.0 秒", VideoMath.formatElapsed(59_999))
        assertEquals("1 分 00 秒", VideoMath.formatElapsed(60_000))
        assertEquals("2 分 05 秒", VideoMath.formatElapsed(125_000))
        assertEquals("0.0 秒", VideoMath.formatElapsed(-5))
    }

    @Test
    fun formatDuration_andFormatBytes() {
        assertEquals("23.8 秒", VideoMath.formatDuration(23_800_000L))
        assertEquals("1 分 05 秒", VideoMath.formatDuration(65_000_000L))
        assertEquals("512 B", VideoMath.formatBytes(512))
        assertEquals("2.0 KB", VideoMath.formatBytes(2048))
        assertEquals("5.0 MB", VideoMath.formatBytes(5L * 1024 * 1024))
        assertEquals("0 B", VideoMath.formatBytes(-1))
    }

    @Test
    fun cropPxHeight_isBotMinusTop() {
        val crop = VideoMath.CropPx(10, 300)
        assertEquals(290, crop.heightPx)
        assertFalse(crop.heightPx <= 0)
    }
}
