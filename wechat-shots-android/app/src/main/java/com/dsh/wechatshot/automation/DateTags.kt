package com.dsh.wechatshot.automation

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 解析微信聊天界面里的日期/时间标签。
 *
 * 微信常见格式：
 *   2026年11月17日 09:20
 *   11月17日 09:20
 *   昨天 09:20 / 星期一 09:20 / 09:20
 * 没有日期只有时间时，认为是今天。
 */
object DateTags {
    private const val TIME_SUFFIX = """(?:\s*(?:上午|下午|早上|晚上|凌晨|中午))?(?:\s*\d{1,2}[:：]\d{2})?"""

    private val fullDate = Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日""" + TIME_SUFFIX)
    private val slashDate = Regex("""(\d{4})[-/.]\s*(\d{1,2})[-/.]\s*(\d{1,2})""" + TIME_SUFFIX)
    private val monthDay = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*日""" + TIME_SUFFIX)
    private val relative = Regex("""^(今天|昨天|前天)""" + TIME_SUFFIX + """$""")
    private val weekday = Regex("""^(?:星期|周)([一二三四五六日天])""" + TIME_SUFFIX + """$""")
    private val timeOnly = Regex("""^(?:上午|下午|早上|晚上|凌晨|中午)?\s*\d{1,2}[:：]\d{2}$""")

    fun parse(raw: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val text = normalize(raw)
        if (text.isEmpty() || text.length > 48) return null

        fullDate.matchEntire(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return null
            if (year !in (today.year - 15)..(today.year + 1)) return null
            return safeDate(year, match.groupValues[2], match.groupValues[3], today)
        }
        slashDate.matchEntire(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return null
            if (year !in (today.year - 15)..(today.year + 1)) return null
            return safeDate(year, match.groupValues[2], match.groupValues[3], today)
        }
        monthDay.matchEntire(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull() ?: return null
            val day = match.groupValues[2].toIntOrNull() ?: return null
            var candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
            if (candidate.isAfter(today.plusDays(1))) {
                candidate = runCatching { LocalDate.of(today.year - 1, month, day) }.getOrNull() ?: return null
            }
            return candidate
        }
        relative.matchEntire(text)?.let { match ->
            return when (match.groupValues[1]) {
                "今天" -> today
                "昨天" -> today.minusDays(1)
                "前天" -> today.minusDays(2)
                else -> null
            }
        }
        weekday.matchEntire(text)?.let { match ->
            val target = when (match.groupValues[1]) {
                "一" -> 1
                "二" -> 2
                "三" -> 3
                "四" -> 4
                "五" -> 5
                "六" -> 6
                "日", "天" -> 7
                else -> 0
            }
            if (target == 0) return null
            val back = (today.dayOfWeek.value - target + 7) % 7
            return today.minusDays(back.toLong())
        }
        if (text == "刚刚") return today
        if (timeOnly.matches(text)) return today
        return null
    }

    /**
     * 从一页界面中找出位于屏幕中间区域的日期标签。
     * 微信消息气泡在左右两侧，日期标签居中，因此用水平位置过滤可以有效避免把聊天气泡里的
     * “11月17日”文字误当成日期分隔。
     */
    fun datesIn(doc: UiDocument, today: LocalDate = LocalDate.now()): List<LocalDate> {
        val width = doc.width.toFloat()
        val height = doc.height.toFloat()
        if (width <= 0f || height <= 0f) return emptyList()
        val result = LinkedHashSet<LocalDate>()
        for (node in doc.nodes) {
            if (node.editable) continue
            if (node.bounds.width < 2 || node.bounds.height < 2) continue
            if (node.bounds.top < height * 0.04f || node.bounds.bottom > height * 0.97f) continue
            if (node.bounds.centerX < width * 0.22f || node.bounds.centerX > width * 0.78f) continue
            val raw = node.label().trim()
            if (raw.length !in 1..48) continue
            val date = parse(raw, today) ?: continue
            result.add(date)
        }
        return result.toList()
    }

    private fun safeDate(year: Int, monthText: String, dayText: String, today: LocalDate): LocalDate? {
        val month = monthText.toIntOrNull() ?: return null
        val day = dayText.toIntOrNull() ?: return null
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        // 允许“今天/明天”的边界误差，但不接受明显未来的日期（可能是消息正文）。
        if (date.isAfter(today.plusDays(1))) return null
        return date
    }

    /** 把全角数字、全角冒号、窄空格等归一化。 */
    private fun normalize(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (ch in raw) {
            val mapped = when (ch) {
                in '０'..'９' -> ('0' + (ch - '０'))
                '：' -> ':'
                '／' -> '/'
                '－' -> '-'
                '　', '\u00A0', '\u2007', '\u202F' -> ' '
                '\u200B', '\u200C', '\u200D', '\uFEFF' -> '\u0000'
                else -> ch
            }
            if (mapped != '\u0000') builder.append(mapped)
        }
        return builder.toString().replace(Regex("""\s+"""), " ").trim()
    }
}
