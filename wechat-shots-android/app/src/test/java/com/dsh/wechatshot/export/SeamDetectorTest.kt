package com.dsh.wechatshot.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页级 seam 检测的单测（纯 JVM，不依赖 Android）。
 * 用合成的行哈希验证：正常相邻页不误报、成段重复内容命中、单条重复消息不误报。
 */
class SeamDetectorTest {

    /** 构造合成行签名：contentPattern 里 true 表示该行有内容。 */
    private fun signature(hashes: LongArray, contentPattern: (Int) -> Boolean): PageRowSignature =
        PageRowSignature(hashes, BooleanArray(hashes.size) { contentPattern(it) })

    private fun uniqueHashes(from: Long, count: Int): LongArray =
        LongArray(count) { from + it * 7919L }

    /** 模拟聊天页：每 30 行一个消息块（20 行内容 + 10 行空白）。 */
    private fun chatContent(row: Int): Boolean = row % 30 < 20

    @Test
    fun `normal adjacent pages are not flagged`() {
        val prev = signature(uniqueHashes(0L, 1000), ::chatContent)
        // 后一页内容完全不同（水平或垂直都不相同），最多只有零星相同行
        val next = signature(uniqueHashes(1_000_000L, 900), ::chatContent)
        val stats = SeamDetector.analyze(listOf(prev, next))
        assertFalse(stats.hit != null)
        assertEquals(0, stats.maxRun)
    }

    @Test
    fun `duplicated region across page boundary is flagged`() {
        val prevHashes = uniqueHashes(0L, 1200)
        val nextHashes = uniqueHashes(5_000_000L, 1000)
        // 前一页 300..699 行被重复拼到后一页 100..499 行（跨多个消息块）
        for (row in 0 until 400) {
            nextHashes[100 + row] = prevHashes[300 + row]
        }
        val prev = signature(prevHashes, ::chatContent)
        val next = signature(nextHashes, ::chatContent)
        val scan = SeamDetector.analyze(listOf(prev, next))
        val hit = scan.hit
        assertNotNull("成段重复内容应被检测出来", hit)
        assertTrue(hit!!.longestRun >= SeamDetector.MIN_RUN_ROWS)
        assertTrue(hit.longestRunBlocks >= SeamDetector.MIN_CONTENT_BLOCKS)
    }

    @Test
    fun `single duplicated block of content rows is not flagged`() {
        // 一条超长消息（连续 300 行内容，行与行之间没有空白分隔）重复出现：
        // 只有 1 个消息块，按设计不算 seam（真实聊天里重复短消息很常见）。
        val prevHashes = uniqueHashes(0L, 800)
        val nextHashes = uniqueHashes(9_000_000L, 800)
        for (row in 0 until 300) {
            nextHashes[200 + row] = prevHashes[100 + row]
        }
        val prev = signature(prevHashes) { it in 100 until 400 }
        val next = signature(nextHashes) { it in 200 until 500 }
        val scan = SeamDetector.analyze(listOf(prev, next))
        assertEquals(300, scan.maxRun)
        assertNull(scan.hit)
    }

    @Test
    fun `blank rows alone do not trigger a seam`() {
        // 两页大部分是空白行（哈希相同），但没有内容行作为锚点 → 不判定
        val blank = 42L
        val prevHashes = LongArray(800) { if (it % 50 < 30) 7L else blank }
        val nextHashes = LongArray(800) { if (it % 50 < 30) 7L else blank }
        val prev = signature(prevHashes) { false }
        val next = signature(nextHashes) { false }
        val scan = SeamDetector.analyze(listOf(prev, next))
        assertNull(scan.hit)
    }

    @Test
    fun `hashRows marks content rows and equal rows match`() {
        val width = 4
        // 4 行：纯白、带一个深色像素、纯白、深色行
        val pixels = IntArray(width * 4) { 0xFFFFFFFF.toInt() }
        pixels[width * 1 + 2] = 0xFF101010.toInt()
        for (x in 0 until width) pixels[width * 3 + x] = 0xFF202020.toInt()
        val sig = SeamDetector.hashRows(pixels, width, 4)
        assertEquals(4, sig.height)
        assertFalse(sig.contentRows[0])
        assertTrue(sig.contentRows[1])
        assertFalse(sig.contentRows[2])
        assertTrue(sig.contentRows[3])
        assertEquals(sig.rowHashes[0], sig.rowHashes[2])
        // 相同行再算一次，哈希必须稳定
        val again = SeamDetector.hashRows(pixels, width, 4)
        assertEquals(sig.rowHashes[1], again.rowHashes[1])
    }
}
