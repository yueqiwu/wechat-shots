package com.dsh.wechatshot.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 真机截图回归测试（一加 Ace 6T / Android 17，无线调试页面）。
 *
 * 真机截图里同时存在两个「IP 地址和端口」：
 *  - 无线调试主页：192.168.0.107:41539 —— 配对成功后 TLS 连接的端口；
 *  - 配对弹窗：192.168.0.107:36865 —— adb pair 使用的配对端口。
 * v1.1.0 误把主页端口当配对端口，导致配对超时；这里锁定正确行为。
 */
class PairingCodeParserRealScreenshotTest {

    private val realScreenshotLines = listOf(
        "无线调试",
        "开启",
        "设备名称",
        "一加 Ace 6T 原神神里绫华定制机",
        "IP 地址和端口",
        "192.168.0.107:41539",
        "与设备配对",
        "WLAN 配对码",
        "733748",
        "IP 地址和端口",
        "192.168.0.107:36865",
        "取消",
    )

    @Test
    fun `real screenshot picks dialog pairing port and connect port`() {
        val r = PairingCodeParser.parseTexts(realScreenshotLines)
        assertEquals("733748", r.code)
        assertEquals(36865, r.port)
        assertEquals(41539, r.connectPort)
        assertEquals("192.168.0.107", r.host)
    }

    @Test
    fun `dialog only has pairing port and no connect port`() {
        val r = PairingCodeParser.parseTexts(
            listOf(
                "无线调试",
                "与设备配对",
                "WLAN 配对码",
                "733748",
                "IP 地址和端口",
                "192.168.0.107:36865",
                "取消",
            ),
        )
        assertEquals("733748", r.code)
        assertEquals(36865, r.port)
        assertNull(r.connectPort)
        assertEquals("192.168.0.107", r.host)
    }

    @Test
    fun `main screen only has no pairing info`() {
        val r = PairingCodeParser.parseTexts(
            listOf(
                "无线调试",
                "开启",
                "设备名称",
                "一加 Ace 6T",
                "IP 地址和端口",
                "192.168.0.107:41539",
            ),
        )
        assertNull(r.code)
        assertNull(r.connectPort)
    }

    @Test
    fun `pairing dialog where code line comes after port line still resolves`() {
        // 某些机型 OCR 顺序会把弹窗标题排在下面，此时仍应选中弹窗里的端口（离配对码最近的那个）
        val r = PairingCodeParser.parseTexts(
            listOf(
                "无线调试",
                "IP 地址和端口",
                "192.168.0.107:41539",
                "与设备配对",
                "WLAN 配对码",
                "733748",
                "IP 地址和端口",
                "192.168.0.107:36865",
            ),
        )
        assertEquals("733748", r.code)
        assertEquals(36865, r.port)
        assertEquals(41539, r.connectPort)
    }
}
