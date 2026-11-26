package com.dsh.wechatshot.adb

import com.dsh.wechatshot.adb.PairingCodeParser.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PairingCodeParser 单元测试。
 *
 * 覆盖：中文/英文/繁体弹窗、配对码与端口的各种排版、OCR 混淆字符（I23 45G 之类）、
 * 多个 6 位数字时的优先级、无上下文时不误报、全角字符、空输入。
 */
class PairingCodeParserTest {

    // ------------------------------------------------------------------ 基础：中英文弹窗

    @Test
    fun `chinese dialog with label and port`() {
        val r = PairingCodeParser.parse(
            """
            无线调试
            使用配对码配对设备
            配对码
            123456
            IP 地址和端口
            192.168.1.23:41234
            取消
            """.trimIndent(),
        )
        assertEquals("123456", r.code)
        assertEquals(41234, r.port)
        assertEquals("192.168.1.23", r.host)
        assertTrue(r.hasCode)
        assertTrue(r.hasPort)
        assertEquals("123456", r.lines[3]) // 归一化后的行
    }

    @Test
    fun `english dialog with label and port`() {
        val r = PairingCodeParser.parse(
            """
            Wireless debugging
            Pair with device
            Pairing code
            654321
            IP address & port
            10.0.0.5:37123
            Cancel
            """.trimIndent(),
        )
        assertEquals("654321", r.code)
        assertEquals(37123, r.port)
        assertEquals("10.0.0.5", r.host)
    }

    @Test
    fun `traditional chinese label`() {
        val r = PairingCodeParser.parseTexts(listOf("無線偵錯", "配對碼", "246813"))
        assertEquals("246813", r.code)
    }

    @Test
    fun `code on same line as label`() {
        val r = PairingCodeParser.parseTexts(listOf("使用配对码配对设备", "配对码：123456"))
        assertEquals("123456", r.code)
    }

    // ------------------------------------------------------------------ 排版变体

    @Test
    fun `code split in groups`() {
        val r = PairingCodeParser.parseTexts(listOf("配对码", "123 456"))
        assertEquals("123456", r.code)
    }

    @Test
    fun `code split in 2 2 2 groups`() {
        val r = PairingCodeParser.parseTexts(listOf("配对码", "12 34 56"))
        assertEquals("123456", r.code)
    }

    @Test
    fun `code split 3 3 with hyphen`() {
        val r = PairingCodeParser.parseTexts(listOf("Pairing code", "654-321"))
        assertEquals("654321", r.code)
    }

    @Test
    fun `full width digits and colon`() {
        val r = PairingCodeParser.parseTexts(listOf("配对码：１２３４５６"))
        assertEquals("123456", r.code)
    }

    // ------------------------------------------------------------------ OCR 混淆字符

    @Test
    fun `confusable letters in code`() {
        // ML Kit 常把 123456 认成 I23 45G / l23 456 / O12345
        val r = PairingCodeParser.parseTexts(listOf("配对码：I23 45G"))
        assertEquals("123456", r.code)
    }

    @Test
    fun `confusable single token`() {
        val r = PairingCodeParser.parseTexts(listOf("配对码", "l23AS6"))
        assertEquals("123456", r.code)
    }

    @Test
    fun `confusable label itself`() {
        // 配对码 → 配对玛/配対码 也应识别
        val r = PairingCodeParser.parseTexts(listOf("配対玛", "361829"))
        assertEquals("361829", r.code)
    }

    @Test
    fun `confusable letters do not create bogus code`() {
        // 纯英文单词不能被当成配对码
        val r = PairingCodeParser.parseTexts(
            listOf("Wireless debugging", "Pair with device", "Cancel", "settings"),
        )
        assertNull(r.code)
    }

    // ------------------------------------------------------------------ 多候选优先级

    @Test
    fun `code next to label wins over wifi name digits`() {
        val r = PairingCodeParser.parseTexts(
            listOf(
                "配对码",
                "112233",
                "Wi-Fi 名称",
                "MyWiFi445566",
            ),
        )
        assertEquals("112233", r.code)
    }

    @Test
    fun `code next to label wins over device name digits`() {
        val r = PairingCodeParser.parse(
            """
            无线调试
            配对码：930517
            设备名称：OnePlus 123456
            取消
            """.trimIndent(),
        )
        assertEquals("930517", r.code)
    }

    @Test
    fun `ambiguous six digit numbers without context are rejected`() {
        val r = PairingCodeParser.parseTexts(listOf("123456", "654321"))
        assertNull(r.code)
    }

    @Test
    fun `single six digit number without any context is rejected`() {
        // 无关键词时宁可返回空，交给页面的手动兜底，避免误配对
        val r = PairingCodeParser.parseTexts(listOf("123456"))
        assertNull(r.code)
    }

    @Test
    fun `longer digit run is not treated as code`() {
        val r = PairingCodeParser.parseTexts(listOf("配对码", "12345678"))
        assertNull(r.code)
    }

    @Test
    fun `phone number is not treated as code`() {
        val r = PairingCodeParser.parseTexts(listOf("配对码", "13812345678"))
        assertNull(r.code)
    }

    // ------------------------------------------------------------------ 端口

    @Test
    fun `port from ip colon port without keyword`() {
        val r = PairingCodeParser.parseTexts(listOf("192.168.31.100:41234"))
        assertEquals(41234, r.port)
        assertEquals("192.168.31.100", r.host)
    }

    @Test
    fun `port after chinese keyword`() {
        val r = PairingCodeParser.parseTexts(listOf("配对码", "123456", "端口", "37123"))
        assertEquals("123456", r.code)
        assertEquals(37123, r.port)
    }

    @Test
    fun `port after port keyword same line`() {
        val r = PairingCodeParser.parseTexts(listOf("配对码", "246810", "Port: 43876"))
        assertEquals("246810", r.code)
        assertEquals(43876, r.port)
    }

    @Test
    fun `ip not treated as six digit code`() {
        val r = PairingCodeParser.parse(
            """
            配对码：123456
            IP 地址和端口：192.168.100.200:40001
            """.trimIndent(),
        )
        assertEquals("123456", r.code)
        assertEquals(40001, r.port)
    }

    @Test
    fun `five digit number without port context is not a port`() {
        val r = PairingCodeParser.parseTexts(listOf("配对码", "123456", "41234"))
        // 41234 与「配对码」无关、又不在「端口」附近，不应被当成端口
        assertNull(r.port)
    }

    // ------------------------------------------------------------------ 空输入 / 无意义输入

    @Test
    fun `empty input`() {
        assertTrue(PairingCodeParser.parse("").isEmpty)
        assertTrue(PairingCodeParser.parseTexts(emptyList()).isEmpty)
        assertTrue(PairingCodeParser.parseTexts(listOf("", "  ", "\n")).isEmpty)
    }

    @Test
    fun `unrelated screenshot text`() {
        val r = PairingCodeParser.parse(
            """
            微信
            今天 12:34
            好的，明天见
            """.trimIndent(),
        )
        assertNull(r.code)
        assertNull(r.port)
        assertFalse(r.hasCode)
    }

    @Test
    fun `large height code line is preferred`() {
        val lines = listOf(
            TextLine("配对码", 40),
            TextLine("123456", 96),
            TextLine("设备名称 OnePlus 884422", 40),
        )
        val r = PairingCodeParser.parse(lines)
        assertEquals("123456", r.code)
        // 大字号行应该有加分，分数高于普通行
        assertTrue(r.codeScore >= 60)
    }
}
