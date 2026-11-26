package com.dsh.wechatshot.export

/**
 * 输出页的行签名：每行像素的哈希 + 该行是否包含内容（非空白）。
 * 纯 JVM 数据，便于单元测试。
 */
class PageRowSignature(
    val rowHashes: LongArray,
    val contentRows: BooleanArray,
) {
    val height: Int get() = rowHashes.size
}

/** 一对相邻页之间的 seam 统计。 */
data class PageSeamStats(
    /** 前一页页码（0 起）。 */
    val prevPage: Int,
    val nextPage: Int,
    /** 两页之间「行像素完全相同」的最长连续行数（任意位置对齐）。 */
    val longestRun: Int,
    /** 最长连续行段里跨过的消息块数量（被空白行分隔的内容段）。 */
    val longestRunBlocks: Int,
    /** 该页对是否命中 seam 判定。 */
    val hit: Boolean,
) {
    fun summary(): String = "第 " + (prevPage + 1) + "→" + (nextPage + 1) +
        " 页最长相同行段 " + longestRun + " 行（跨 " + longestRunBlocks + " 个消息块）"
}

data class SeamScan(
    val stats: List<PageSeamStats>,
    val hit: PageSeamStats?,
) {
    val maxRun: Int get() = stats.maxOfOrNull { it.longestRun } ?: 0
    val maxRunSummary: String get() = stats.maxByOrNull { it.longestRun }?.summary() ?: "无相邻页"
}

/**
 * 页级 seam（重复拼接）检测。
 *
 * 重建长图如果有一段内容被拼了两次（tracker 对某一对截图估错位移），输出页里会出现
 * 成段像素完全相同的内容。真实聊天里只有「极少数短消息」可能重复，因此判定条件是：
 * 1) 相邻页之间存在足够长的连续完全相同行段（[MIN_RUN_ROWS]）；
 * 2) 该行段跨越至少 [MIN_CONTENT_BLOCKS] 个消息块（被空白行隔开的内容段），
 *    排除单条重复消息造成的误报。
 *
 * 纯 JVM：输入是行哈希，不依赖 Android，可直接单测。
 */
object SeamDetector {
    /** 判定 seam 的最短连续相同行数（约 0.12 张 1080p 页面高度，可基于实测调整）。 */
    const val MIN_RUN_ROWS = 240

    /** 判定 seam 的最少消息块数量（单个重复气泡不算）。 */
    const val MIN_CONTENT_BLOCKS = 2

    /** 从 ARGB 像素行计算行签名。宽度必须与截图宽度一致。 */
    fun hashRows(pixels: IntArray, width: Int, height: Int): PageRowSignature {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return PageRowSignature(LongArray(0), BooleanArray(0))
        }
        val hashes = LongArray(height)
        val content = BooleanArray(height)
        for (y in 0 until height) {
            val base = y * width
            var hash = 1125899906842597L
            var hasContent = false
            for (x in 0 until width) {
                val pixel = pixels[base + x]
                hash = hash * 31 + (pixel.toLong() and 0xFFFFFFFFL)
                if (!hasContent) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if ((r * 299 + g * 587 + b * 114) / 1000 < 200) hasContent = true
                }
            }
            hashes[y] = hash
            content[y] = hasContent
        }
        return PageRowSignature(hashes, content)
    }

    /** 扫描所有相邻页，返回统计与第一个命中的 seam。 */
    fun analyze(pages: List<PageRowSignature>): SeamScan {
        val stats = ArrayList<PageSeamStats>(maxOf(0, pages.size - 1))
        var hit: PageSeamStats? = null
        for (index in 0 until pages.size - 1) {
            val stat = analyzePair(index, pages[index], pages[index + 1])
            stats.add(stat)
            if (stat.hit && hit == null) hit = stat
        }
        return SeamScan(stats, hit)
    }

    fun analyzePair(
        prevPage: Int,
        prev: PageRowSignature,
        next: PageRowSignature,
    ): PageSeamStats {
        if (prev.height == 0 || next.height == 0) {
            return PageSeamStats(prevPage, prevPage + 1, 0, 0, false)
        }
        // 以「后一页的内容行」为锚点，在前一页中找相同的行，再向后扩展连续相同行段。
        val index = HashMap<Long, MutableList<Int>>(prev.height)
        for (row in 0 until prev.height) {
            if (!prev.contentRows[row]) continue
            index.getOrPut(prev.rowHashes[row]) { ArrayList(2) }.add(row)
        }
        var bestRun = 0
        var bestBlocks = 0
        val nextRows = next.height
        for (j in 0 until nextRows) {
            if (!next.contentRows[j]) continue
            val candidates = index[next.rowHashes[j]] ?: continue
            for (i0 in candidates) {
                var run = 0
                while (i0 + run < prev.height && j + run < nextRows &&
                    prev.rowHashes[i0 + run] == next.rowHashes[j + run]
                ) {
                    run++
                }
                if (run > bestRun) {
                    bestRun = run
                    bestBlocks = countContentBlocks(prev, i0, run)
                }
            }
        }
        val hit = bestRun >= MIN_RUN_ROWS && bestBlocks >= MIN_CONTENT_BLOCKS
        return PageSeamStats(prevPage, prevPage + 1, bestRun, bestBlocks, hit)
    }

    /** 统计 [from, from+count) 行内的内容块数量（被空白行隔开的连续内容行段）。 */
    private fun countContentBlocks(page: PageRowSignature, from: Int, count: Int): Int {
        var blocks = 0
        var inside = false
        val end = minOf(from + count, page.height)
        for (row in from until end) {
            val content = page.contentRows[row]
            if (content && !inside) blocks++
            inside = content
        }
        return blocks
    }
}
