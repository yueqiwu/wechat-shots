package com.dsh.wechatshot.adb

/**
 * 无线调试「使用配对码配对设备」弹窗截图文本的解析器。
 *
 * 纯 Kotlin、零 Android 依赖，方便 JVM 单元测试（见 `app/src/test/.../PairingCodeParserTest.kt`）。
 *
 * 输入是 ML Kit 中文 OCR 的文本行（可带行高，行高用于给「大字展示的配对码」加权），
 * 输出为 6 位配对码 + 端口（可选，还可能附带 IP 地址）。
 *
 * 设计要点：
 * - 全角数字/中文冒号/全角空格先归一化；
 * - 配对码识别支持 6 位连续、3+3、2+2+2、4+2、2+4 等系统弹窗常见排版（空格或连字符分隔）；
 * - OCR 常见混淆（O/o→0、I/l/|/!→1、Z/z→2、S/s→5、B→8、G→6、g→9、b→6）只在「整段 token
 *   全部由数字/混淆字符组成」时才做纠正，避免把英文单词当成数字；
 * - 用关键词上下文（配对码 / pairing code / 无线调试 / pair device）给候选打分，
 *   多个 6 位数字时优先关键词同一行/相邻行的候选，没有足够上下文时不返回（防误报）；
 * - 端口支持 `192.168.x.x:41234`、`端口：41234`、`Port 41234` 等；只有端口没有 IP 时 host 为 null，
 *   交由 AdbManager 用 127.0.0.1 兜底。
 */
object PairingCodeParser {

    /** OCR 行；height 为像素行高（可传 0，表示未知）。 */
    data class TextLine(val text: String, val height: Int = 0)

    data class Result(
        val code: String? = null,
        /** 配对弹窗「IP 地址和端口」里的端口（adb pair 用这个） */
        val port: Int? = null,
        /** 无线调试主页「IP 地址和端口」里的端口（配对成功后 TLS 连接用） */
        val connectPort: Int? = null,
        val host: String? = null,
        val codeScore: Int = 0,
        val portScore: Int = 0,
        val codeEvidence: String = "",
        val portEvidence: String = "",
        val connectEvidence: String = "",
        val lines: List<String> = emptyList(),
    ) {
        val hasCode: Boolean get() = code != null
        val hasPort: Boolean get() = port != null
        val hasConnectPort: Boolean get() = connectPort != null
        val isEmpty: Boolean get() = code == null && port == null
    }

    // ---------------------------------------------------------------------------------------------
    // 对外入口
    // ---------------------------------------------------------------------------------------------

    fun parse(rawText: String): Result =
        parse(rawText.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { TextLine(it.trim()) })

    fun parseTexts(texts: List<String>): Result = parse(texts.map { TextLine(it.trim()) })

    fun parse(lines: List<TextLine>): Result {
        val norm = lines.map { normalize(it.text) }
        val heights = lines.map { it.height }
        val mediaHeight = median(heights.filter { it > 0 }).toDouble()

        val codeCands = mutableListOf<CodeCandidate>()
        norm.forEachIndexed { idx, line ->
            if (line.isBlank()) return@forEachIndexed
            codeCands += digitCandidates(line, idx)
            codeCands += confusableCandidates(line, idx)
        }

        val scored = codeCands.map { scoreCode(it, norm, heights, mediaHeight, codeCands.size) }
            .sortedByDescending { it.score }

        var bestCode: ScoredCode? = null
        if (scored.isNotEmpty()) {
            val first = scored.first()
            val second = scored.getOrNull(1)
            val clearlyBest = second == null || first.score - second.score >= 15
            if (first.score >= CODE_ACCEPT_SCORE && clearlyBest) bestCode = first
        }

        val portCands = findPorts(norm)
        val pairPort = choosePairPort(portCands, bestCode?.lineIndex)
        val connectCand = chooseConnectPort(portCands, pairPort)

        return Result(
            code = bestCode?.value,
            port = pairPort?.port,
            connectPort = connectCand?.port,
            host = pairPort?.host ?: connectCand?.host,
            codeScore = bestCode?.score ?: 0,
            portScore = pairPort?.score ?: 0,
            codeEvidence = bestCode?.evidence ?: "",
            portEvidence = pairPort?.evidence ?: "",
            connectEvidence = connectCand?.evidence ?: "",
            lines = norm,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 关键词
    // ---------------------------------------------------------------------------------------------

    /** 强上下文：几乎只出现在配对弹窗上的标签。 */
    private val strongPairingKeyword = Regex(
        "配\\s*[对對対]\\s*[码碼玛吗馬]" + // 配对码 / 配對碼 / 配对玛 / 配对吗
            "|配对\\s*设备|配對\\s*裝置|pair\\s*(?:with\\s*)?(?:a\\s*)?device|pairing\\s*code|pair\\s*code|paircode",
        RegexOption.IGNORE_CASE,
    )

    /** 弱上下文：只说明当前是无线调试界面，不足以单独确认数字含义。 */
    private val weakPairingKeyword = Regex(
        "配对|配對|pair|无线|無線|调试|偵錯|调试|wireless|debugging|wlan|ip\\s*地址|ip\\s*address",
        RegexOption.IGNORE_CASE,
    )

    private val portKeyword = Regex(
        "端\\s*[口囗]|\\bport\\b|ip\\s*地址|ip\\s*address|ip\\s*addr|地址\\s*和\\s*端\\s*口",
        RegexOption.IGNORE_CASE,
    )

    // ---------------------------------------------------------------------------------------------
    // 候选 1：纯数字（含系统弹窗常见的分组排版）
    // ---------------------------------------------------------------------------------------------

    private data class RawMatch(val start: Int, val end: Int, val value: String, val corrected: Int)

    private val digitPatterns = listOf(
        Regex("(?<![0-9])(\\d{6})(?![0-9])"),
        Regex("(?<![0-9])(\\d{3})[ \\u00a0\\-\\u2010-\\u2015](\\d{3})(?![0-9])"),
        Regex("(?<![0-9])(\\d{2})[ \\u00a0\\-\\u2010-\\u2015](\\d{2})[ \\u00a0\\-\\u2010-\\u2015](\\d{2})(?![0-9])"),
        Regex("(?<![0-9])(\\d{4})[ \\u00a0\\-\\u2010-\\u2015](\\d{2})(?![0-9])"),
        Regex("(?<![0-9])(\\d{2})[ \\u00a0\\-\\u2010-\\u2015](\\d{4})(?![0-9])"),
    )

    private fun digitCandidates(line: String, lineIdx: Int): List<CodeCandidate> {
        val found = mutableListOf<CodeCandidate>()
        for (pattern in digitPatterns) {
            for (m in pattern.findAll(line)) {
                val value = buildString {
                    for (g in 1..m.groupValues.lastIndex) append(m.groupValues[g])
                }
                if (value.length != 6) continue
                if (found.any { it.start == m.range.first && it.value == value }) continue
                found += CodeCandidate(
                    value = value,
                    start = m.range.first,
                    end = m.range.last + 1,
                    corrected = 0,
                    lineIndex = lineIdx,
                    evidence = line,
                )
            }
        }
        return found
    }

    // ---------------------------------------------------------------------------------------------
    // 候选 2：OCR 混淆字符纠正（如把 123456 识别成 I23 45G / l23AS6 / O12345）
    // ---------------------------------------------------------------------------------------------

    private val confusable: Map<Char, Char> = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
        'I' to '1', 'l' to '1', 'L' to '1', '|' to '1', '!' to '1',
        'Z' to '2', 'z' to '2',
        'S' to '5', 's' to '5',
        'B' to '8',
        'A' to '4',
        'G' to '6', 'b' to '6',
        'g' to '9', 'q' to '9',
        'T' to '7', 't' to '7',
    )

    private data class Token(val raw: String, val start: Int, val end: Int) {
        val mapped: String = raw.map { ch -> if (ch.isDigit()) ch else confusable[ch] ?: ch }.joinToString("")
        val digitish: Boolean = raw.all { it.isDigit() || confusable.containsKey(it) }
        val corrected: Int = raw.count { !it.isDigit() && confusable.containsKey(it) }
    }

    private fun confusableCandidates(line: String, lineIdx: Int): List<CodeCandidate> {
        // 按「非字母数字」切 token，空格与连字符属于分隔符，但不允许跨 '.' ':' 等符号拼接（避免 IP/时间）。
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c.isLetterOrDigit() && c.code < 128) {
                var j = i
                while (j < line.length && line[j].isLetterOrDigit() && line[j].code < 128) j++
                tokens += Token(line.substring(i, j), i, j)
                i = j
            } else {
                i++
            }
        }
        val found = mutableListOf<CodeCandidate>()

        fun joinable(a: Token, b: Token): Boolean {
            if (a.end >= b.start) return false
            val gap = line.substring(a.end, b.start)
            return gap.all { it == ' ' || it == '\u00a0' || it == '-' || it == '\u2010' || it == '\u2011' }
        }

        fun glyphCount(t: Token) = t.mapped.length

        // 单个 token 纠正后正好 6 位（如 S12345 / O12345）
        for (t in tokens) {
            if (!t.digitish || t.corrected == 0 || t.mapped.length != 6) continue
            found += CodeCandidate(
                value = t.mapped,
                start = t.start,
                end = t.end,
                corrected = t.corrected,
                lineIndex = lineIdx,
                evidence = line,
            )
        }
        // 相邻两个 token 合起来 6 位（如 I23 45G / l2 34 56 这种按对拼）
        for (k in 0 until tokens.size - 1) {
            val a = tokens[k]
            val b = tokens[k + 1]
            if (!joinable(a, b)) continue
            if (!a.digitish || !b.digitish) continue
            if (a.mapped.length + b.mapped.length != 6) continue
            if (a.corrected + b.corrected == 0) continue // 纯数字的交给 digitCandidates
            found += CodeCandidate(
                value = a.mapped + b.mapped,
                start = a.start,
                end = b.end,
                corrected = a.corrected + b.corrected,
                lineIndex = lineIdx,
                evidence = line,
            )
        }
        // 三个 token 合起来 6 位（如 12 34 5G 很少见，但保留）
        for (k in 0 until tokens.size - 2) {
            val a = tokens[k]
            val b = tokens[k + 1]
            val c = tokens[k + 2]
            if (!joinable(a, b) || !joinable(b, c)) continue
            if (!a.digitish || !b.digitish || !c.digitish) continue
            if (a.mapped.length + b.mapped.length + c.mapped.length != 6) continue
            if (a.corrected + b.corrected + c.corrected == 0) continue
            found += CodeCandidate(
                value = a.mapped + b.mapped + c.mapped,
                start = a.start,
                end = c.end,
                corrected = a.corrected + b.corrected + c.corrected,
                lineIndex = lineIdx,
                evidence = line,
            )
        }
        // 去重（同一区间/同一值）
        return found.distinctBy { it.start to it.value }
    }

    // ---------------------------------------------------------------------------------------------
    // 打分
    // ---------------------------------------------------------------------------------------------

    private const val CODE_ACCEPT_SCORE = 45

    private data class CodeCandidate(
        val value: String,
        val start: Int,
        val end: Int,
        val corrected: Int,
        val lineIndex: Int,
        val evidence: String,
    )

    private data class ScoredCode(
        val value: String,
        val score: Int,
        val evidence: String,
        val lineIndex: Int,
    )

    private fun scoreCode(
        cand: CodeCandidate,
        lines: List<String>,
        heights: List<Int>,
        medianHeight: Double,
        totalCandidates: Int,
    ): ScoredCode {
        var score = 0
        val line = lines[cand.lineIndex]
        val strongHere = strongPairingKeyword.containsMatchIn(line)
        val weakHere = weakPairingKeyword.containsMatchIn(line)
        val strongBefore = cand.lineIndex > 0 && strongPairingKeyword.containsMatchIn(lines[cand.lineIndex - 1])
        val weakBefore = cand.lineIndex > 0 && weakPairingKeyword.containsMatchIn(lines[cand.lineIndex - 1])
        val strongAfter = cand.lineIndex + 1 < lines.size &&
            strongPairingKeyword.containsMatchIn(lines[cand.lineIndex + 1])
        val weakAfter = cand.lineIndex + 1 < lines.size && weakPairingKeyword.containsMatchIn(lines[cand.lineIndex + 1])
        val anyStrong = lines.any { strongPairingKeyword.containsMatchIn(it) }

        when {
            strongHere -> score += 50
            weakHere -> score += 22
        }
        if (strongHere) {
            val label = strongPairingKeyword.find(line)
            if (label != null && label.range.first < cand.start) score += 20 // 「配对码：123456」
        }
        if (!strongHere && (strongBefore || strongAfter)) score += 30
        else if (!strongHere && (weakBefore || weakAfter)) score += 12
        if (!strongHere && anyStrong) score += 15

        // 文档里只有一个 6 位候选时略加权；多个候选时该权重用于拉开差距
        if (totalCandidates == 1) score += 20
        if (line.trim().length <= 20) score += 10
        if (medianHeight > 0 && heights.getOrNull(cand.lineIndex)?.let { it > 0 && it > medianHeight * 1.25 } == true) {
            score += 10
        }

        // 与 IP、端口同行的候选要扣分：IP 里的数字不该当配对码
        if (ipPortRegex.containsMatchIn(line)) score -= 60
        // 6 位数字出现在「名称/SSID/MAC/时间」等行里要扣分
        if (nonCodeLineRegex.containsMatchIn(line) && !strongHere) score -= 35
        // 混淆纠正越少越可信
        score -= cand.corrected * 8
        // 完全无上下文兜底：既无强弱关键词且文档里也没有任何配对相关词
        if (!anyStrong && !weakHere && !weakBefore && !weakAfter) score -= 20

        return ScoredCode(cand.value, score, line.trim(), cand.lineIndex)
    }

    // ---------------------------------------------------------------------------------------------
    // 端口 / IP
    // ---------------------------------------------------------------------------------------------

    private val ipPortRegex = Regex(
        "(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*[:：]\\s*(\\d{2,5})",
    )

    private val portKeywordNumberRegex = Regex(
        "(?:端\\s*[口囗]|\\bport\\b)\\s*(?:是|为|為|:|：)?\\s*(\\d{2,5})",
        RegexOption.IGNORE_CASE,
    )

    /** 纯数字端口：不带小数点的 2-5 位独立数字 */
    private val portNumberRegex = Regex("(?<![0-9.])(\\d{2,5})(?![0-9.])")
    private val dateLikeRegex = Regex("^(?:19|20)\\d{2}$")

    private data class ScoredPort(
        val port: Int,
        val host: String?,
        val score: Int,
        val evidence: String,
        val lineIndex: Int,
    )

    /** 收集所有端口候选（IPv4:port、端口关键字、独立 5 位数字兜底）。 */
    private fun findPorts(lines: List<String>): List<ScoredPort> {
        val cands = mutableListOf<ScoredPort>()

        // 1) IPv4:port —— 最可信：无线调试主页是连接端口，配对弹窗里是配对端口
        lines.forEachIndexed { idx, line ->
            for (m in ipPortRegex.findAll(line)) {
                val host = m.groupValues[1]
                val port = m.groupValues[2].toIntOrNull() ?: continue
                if (!validPort(port)) continue
                val sameLineKeyword = portKeyword.containsMatchIn(line)
                val anyKeyword = lines.any { portKeyword.containsMatchIn(it) }
                var score = 90
                if (sameLineKeyword) score += 10
                if (anyKeyword) score += 10
                cands += ScoredPort(port, host, score, line.trim(), idx)
            }
        }

        // 2) 「端口：41234」「Port 41234」或「端口」相邻行的独立数字
        lines.forEachIndexed { idx, line ->
            for (m in portKeywordNumberRegex.findAll(line)) {
                val port = m.groupValues[1].toIntOrNull() ?: continue
                if (!validPort(port)) continue
                cands += ScoredPort(port, null, 70, line.trim(), idx)
            }
        }
        lines.forEachIndexed { idx, line ->
            val keywordHere = portKeyword.containsMatchIn(line)
            if (keywordHere) return@forEachIndexed
            val nearKeyword = (idx > 0 && portKeyword.containsMatchIn(lines[idx - 1])) ||
                (idx + 1 < lines.size && portKeyword.containsMatchIn(lines[idx + 1]))
            if (!nearKeyword) return@forEachIndexed
            for (m in portNumberRegex.findAll(line)) {
                val raw = m.groupValues[1]
                val port = raw.toIntOrNull() ?: continue
                if (!validPort(port)) continue
                if (raw.length == 6) continue
                cands += ScoredPort(port, null, 55, line.trim(), idx)
            }
        }

        // 3) 无关键词兜底：全文唯一的 5 位数字（仅低置信度接受）
        val unqualified = mutableListOf<ScoredPort>()
        lines.forEachIndexed { idx, line ->
            for (m in portNumberRegex.findAll(line)) {
                val raw = m.groupValues[1]
                if (raw.length != 5) continue
                val port = raw.toIntOrNull() ?: continue
                if (!validPort(port) || dateLikeRegex.matches(raw)) continue
                unqualified += ScoredPort(port, null, 25, line.trim(), idx)
            }
        }
        if (unqualified.map { it.port }.distinct().size == 1) cands += unqualified

        return cands
    }

    /**
     * 配对端口 = 配对弹窗里的端口。
     *
     * 关键：系统「无线调试」主页和「使用配对码配对设备」弹窗都显示「IP 地址和端口」，
     * 两者都是 IPv4:port、关键词分值完全相同（真机截图：主页 41539、弹窗 36865）。
     * 若按分值取第一个会误把主页的连接端口当配对端口，导致 adb pair 超时。
     * 弹窗端口一定出现在配对码所在行之后 1~4 行，因此按「配对码行距」优先选取。
     */
    private fun choosePairPort(cands: List<ScoredPort>, codeLine: Int?): ScoredPort? {
        if (cands.isEmpty()) return null
        // 低于 40 分的候选（无关键词的 5 位数字兜底）不作为端口，避免误判
        val distinct = cands.filter { it.score >= 40 }.distinctBy { it.port to it.lineIndex }
        if (distinct.isEmpty()) return null
        if (codeLine != null) {
            val after = distinct.filter { it.lineIndex > codeLine && it.lineIndex <= codeLine + 4 }
            if (after.isNotEmpty()) {
                return after.maxWithOrNull(
                    compareBy<ScoredPort> { it.score }.thenBy { -kotlin.math.abs(it.lineIndex - codeLine) },
                )
            }
            val near = distinct.filter { kotlin.math.abs(it.lineIndex - codeLine) <= 3 }
            if (near.isNotEmpty()) {
                return near.maxWithOrNull(
                    compareBy<ScoredPort> { it.score }.thenBy { -kotlin.math.abs(it.lineIndex - codeLine) },
                )
            }
        }
        val sorted = distinct.sortedByDescending { it.score }
        val best = sorted.first()
        val second = sorted.firstOrNull { it.port != best.port }
        if (best.score < 40) return null
        if (codeLine == null && second != null && best.score - second.score < 10 && best.score < 80) return null
        return best
    }

    /** 连接端口 = 无线调试主页「IP 地址和端口」的端口（配对成功后直接 TLS 连接用）。 */
    private fun chooseConnectPort(cands: List<ScoredPort>, pair: ScoredPort?): ScoredPort? {
        if (cands.isEmpty()) return null
        val distinct = cands.filter { it.score >= 40 }.distinctBy { it.port to it.lineIndex }
        val others = distinct.filter { it.port != pair?.port && it.host != null }
        if (others.isEmpty()) return null
        val pairLine = pair?.lineIndex
        return others.maxWithOrNull(
            compareBy<ScoredPort> { it.score }
                .thenBy { if (pairLine != null && it.lineIndex > pairLine) 1 else 0 },
        )
    }

    private fun validPort(port: Int): Boolean = port in 1..65535

    private val nonCodeLineRegex = Regex(
        "wifi|wi-fi|wlan|ssid|名称|名稱|mac|蓝牙|藍牙|版本|version|日期|date|时间|時間|流量|电量|電量",
        RegexOption.IGNORE_CASE,
    )

    // ---------------------------------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------------------------------

    private fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (raw in input) {
            val c = when {
                raw.code in 0xFF10..0xFF19 -> ('0' + (raw.code - 0xFF10)) // 全角数字
                raw == '\uFF1A' -> ':'
                raw == '\uFF0D' || raw == '\u2010' || raw == '\u2011' || raw == '\u2013' || raw == '\u2014' -> '-'
                raw == '\u00a0' || raw == '\u3000' -> ' '
                raw.code in 0x200B..0x200F || raw.code == 0xFEFF -> continue
                else -> raw
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
