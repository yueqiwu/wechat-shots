package com.dsh.wechatshot.stitch

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 纯 Kotlin 的「连续滚动重建」算法核心，逐函数移植自
 * wechat-shots/app/js/core.js（网页版），没有任何 Android 依赖，可在 JVM 单元测试里跑。
 *
 * 坐标约定（与网页版一致）：
 *  - 每帧先缩到 aw x ah 的灰度图（analysis 坐标），内容区（band）为 [bandTop, bandBot) 绝对行号。
 *  - 行签名 sig 只覆盖内容区，长度 = band.h * bins。
 *  - trackOffsets 求出每帧相对第一帧的累计位移 pos（内容向上滚 = pos 变大）。
 *  - 主图（master）行号 = 裁剪区在同一内容坐标下的拼接结果，由 buildRowMap / mapToOps 给出
 *    每一行像素由哪一帧的哪一行提供。
 */
object StitchCore {
    const val BINS: Int = 8

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    // ---------- 基础图像操作 ----------

    /** 从 ARGB 像素数组取亮度（77/150/29 加权，和网页版一致）。 */
    fun grayFromArgb(argb: IntArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        val n = minOf(out.size, argb.size)
        for (i in 0 until n) {
            val c = argb[i]
            out[i] = ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
        }
        return out
    }

    /** 面积平均缩小灰度图。 */
    fun downscaleGray(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh)
        for (y in 0 until dh) {
            val y0 = (y.toLong() * sh / dh).toInt()
            val y1 = maxOf(y0 + 1, ((y + 1).toLong() * sh / dh).toInt())
            for (x in 0 until dw) {
                val x0 = (x.toLong() * sw / dw).toInt()
                val x1 = maxOf(x0 + 1, ((x + 1).toLong() * sw / dw).toInt())
                var s = 0
                var cnt = 0
                for (yy in y0 until y1) {
                    val o = yy * sw
                    for (xx in x0 until x1) {
                        s += u(src[o + xx]); cnt++
                    }
                }
                out[y * dw + x] = (s / maxOf(1, cnt)).toByte()
            }
        }
        return out
    }

    fun cropGray(gray: ByteArray, w: Int, h: Int, top: Int, bottom: Int): ByteArray {
        val t = top.coerceIn(0, h - 1)
        val b = bottom.coerceIn(t + 1, h)
        return gray.copyOfRange(t * w, b * w)
    }

    fun frameDiff(a: ByteArray, b: ByteArray): Double {
        val n = minOf(a.size, b.size)
        var s = 0L
        for (i in 0 until n) s += abs(u(a[i]) - u(b[i]))
        return s.toDouble() / maxOf(1, n)
    }

    /** 逐行在相邻帧之间的像素变化量：静态栏（状态栏/输入栏）≈ 0，滚动的聊天内容很大。 */
    fun rowActivity(frames: List<ByteArray>, w: Int, h: Int): DoubleArray {
        val act = DoubleArray(h)
        if (frames.size < 2) return act
        for (k in 1 until frames.size) {
            val a = frames[k - 1]
            val b = frames[k]
            for (y in 0 until h) {
                val off = y * w
                var s = 0L
                for (x in 0 until w) s += abs(u(a[off + x]) - u(b[off + x]))
                act[y] += s.toDouble() / w
            }
        }
        val n = (frames.size - 1).toDouble()
        for (y in 0 until h) act[y] /= n
        return act
    }

    data class Band(val top: Int, val bot: Int) {
        val h: Int get() = bot - top
    }

    fun contentBand(act: DoubleArray, ah: Int): Band {
        if (act.size < ah) return Band(0, ah)
        var peak = 0.0
        for (y in 0 until ah) if (act[y] > peak) peak = act[y]
        if (!(peak > 0.0)) return Band(0, ah)
        val thr = maxOf(0.6, peak * 0.18)
        var top = 0
        var bot = ah
        for (y in 0 until ah) if (act[y] >= thr) { top = y; break }
        for (y in ah - 1 downTo 0) if (act[y] >= thr) { bot = y + 1; break }
        if (bot - top < 32) return Band(0, ah)
        return Band(top, bot)
    }

    /** 垂直梯度能量：滑动越快帧越糊，这个值越小。用来在同一片段里挑最清晰的一帧。 */
    fun rowSharpness(gray: ByteArray, w: Int, top: Int, bottom: Int): Double {
        val h = gray.size / w
        val t = maxOf(1, minOf(h - 1, top))
        val b = maxOf(t + 1, minOf(h, bottom))
        var s = 0.0
        var n = 0
        for (y in t until b) {
            val o = y * w
            val p = o - w
            var d = 0L
            for (x in 0 until w) d += abs(u(gray[o + x]) - u(gray[p + x]))
            s += d.toDouble() / w; n++
        }
        return if (n == 0) 0.0 else s / n
    }

    /** 行签名：每行按横向 bins 个分箱取平均亮度。 */
    fun rowSig(gray: ByteArray, w: Int, h: Int, bins: Int = BINS): ByteArray {
        val sig = ByteArray(h * bins)
        for (y in 0 until h) {
            val off = y * w
            for (b in 0 until bins) {
                val x0 = (b.toLong() * w / bins).toInt()
                val x1 = maxOf(x0 + 1, ((b + 1).toLong() * w / bins).toInt())
                var s = 0L
                var cnt = 0
                for (x in x0 until minOf(x1, w)) { s += u(gray[off + x]); cnt++ }
                sig[y * bins + b] = (s / maxOf(1, cnt)).toByte()
            }
        }
        return sig
    }

    /** 每行在 bins 个分箱上的离散度：文字/气泡行高，消息之间的空行接近 0。 */
    fun sigActivity(sig: ByteArray, h: Int, bins: Int = BINS): DoubleArray {
        val act = DoubleArray(h)
        for (y in 0 until h) {
            val off = y * bins
            var s = 0.0
            for (b in 0 until bins) s += u(sig[off + b])
            val m = s / bins
            var v = 0.0
            for (b in 0 until bins) {
                val d = u(sig[off + b]) - m
                v += d * d
            }
            act[y] = sqrt(v / bins)
        }
        return act
    }

    // ---------- 位移匹配 ----------

    /**
     * 一个候选位移 s 的相似度：sigA[y] vs sigB[y+s]。
     * cost = 行签名平均绝对差（随画面内容变化，不能直接当阈值）；
     * ncc  = 归一化互相关，0=无关，1=完全相同，和亮度/对比度无关。
     */
    data class Align(val cost: Double, val ncc: Double, val rows: Int)

    fun alignAt(
        sigA: ByteArray,
        sigB: ByteArray,
        h: Int,
        bins: Int,
        shift: Int,
        minOv: Int,
        rowStride: Int,
    ): Align? {
        val y0 = maxOf(0, -shift)
        val y1 = minOf(h, h - shift)
        if (y1 - y0 < minOv) return null
        val rs = if (rowStride > 1) rowStride else 1
        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        var raw = 0.0
        var rows = 0
        var y = y0
        while (y < y1) {
            val oa = y * bins
            val ob = (y + shift) * bins
            for (q in 0 until bins) {
                val a = u(sigA[oa + q]).toDouble()
                val b = u(sigB[ob + q]).toDouble()
                sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b
                raw += abs(a - b)
            }
            rows++
            y += rs
        }
        if (rows == 0) return null
        val n = (rows * bins).toDouble()
        val va = saa - sa * sa / n
        val vb = sbb - sb * sb / n
        val cov = sab - sa * sb / n
        var ncc = when {
            va > 1e-6 && vb > 1e-6 -> cov / sqrt(va * vb)
            va <= 1e-6 && vb <= 1e-6 -> 1.0 // 两边都是纯色（大空白），视为同一位置
            else -> 0.5                    // 一边纯色一边有内容：无从判断，不算失配
        }
        if (ncc > 1) ncc = 1.0 else if (ncc < -1) ncc = -1.0
        return Align(cost = raw / n, ncc = ncc, rows = rows)
    }

    data class Shift(val shift: Double, val ncc: Double, val cost: Double)

    private const val COARSE_CANDIDATES = 6

    /**
     * 在 [lo, hi] 内找最佳位移。位移必须逐像素扫（聊天行内容频率高，隔几像素会错过真解），
     * 省时间靠「少采样几行」：先粗扫出候选峰，再对前几名用全部行复核，最后抛物线亚像素精修。
     */
    fun scanShift(
        sigA: ByteArray,
        sigB: ByteArray,
        h: Int,
        bins: Int,
        loIn: Int,
        hiIn: Int,
        minOv: Int,
        hint: Int? = null,
    ): Shift {
        val lo = loIn
        val hi = hiIn
        if (lo > hi) return Shift(0.0, -1.0, Double.POSITIVE_INFINITY)
        val coarseRows = maxOf(1, (h / 24.0).roundToInt())
        val shifts = IntArray(hi - lo + 1)
        val nccs = DoubleArray(hi - lo + 1)
        var count = 0
        for (s in lo..hi) {
            val r = alignAt(sigA, sigB, h, bins, s, minOv, coarseRows) ?: continue
            shifts[count] = s; nccs[count] = r.ncc; count++
        }
        if (count == 0) return Shift(0.0, -1.0, Double.POSITIVE_INFINITY)
        // 按 ncc 降序取前 6 个彼此相距 >=3 的候选
        val order = (0 until count).sortedByDescending { nccs[it] }
        val picks = ArrayList<Int>(COARSE_CANDIDATES + 1)
        for (idx in order) {
            if (picks.size >= COARSE_CANDIDATES) break
            var far = true
            for (p in picks) if (abs(p - shifts[idx]) < 3) { far = false; break }
            if (far) picks.add(shifts[idx])
        }
        if (hint != null && hint in lo..hi) picks.add(hint)
        var bestShift = 0
        var bestNcc = -2.0
        var bestCost = Double.POSITIVE_INFINITY
        var found = false
        for (p in picks) {
            val a = maxOf(lo, p - 1)
            val b = minOf(hi, p + 1)
            for (s in a..b) {
                val r = alignAt(sigA, sigB, h, bins, s, minOv, 1) ?: continue
                val better = !found ||
                    r.ncc > bestNcc + 1e-4 ||
                    (abs(r.ncc - bestNcc) <= 1e-4 && abs(s) < abs(bestShift))
                if (better) {
                    found = true; bestShift = s; bestNcc = r.ncc; bestCost = r.cost
                }
            }
        }
        if (!found) return Shift(0.0, -1.0, Double.POSITIVE_INFINITY)
        var shiftD = bestShift.toDouble()
        // 亚像素精修：整数位移误差会随帧数累积，几百帧下来能差出十几像素（接缝处重复/漏行）。
        val sL = bestShift - 1
        val sR = bestShift + 1
        if (sL >= lo && sR <= hi) {
            val nL = alignAt(sigA, sigB, h, bins, sL, minOv, 1)
            val nR = alignAt(sigA, sigB, h, bins, sR, minOv, 1)
            if (nL != null && nR != null) {
                val den = nL.ncc - 2 * bestNcc + nR.ncc
                if (den < -1e-6) {
                    var d = 0.5 * (nL.ncc - nR.ncc) / den
                    if (d > 0.7) d = 0.7 else if (d < -0.7) d = -0.7
                    shiftD = bestShift + d
                }
            }
        }
        return Shift(shiftD, bestNcc, bestCost)
    }

    data class Match(val shift: Double, val ncc: Double, val cost: Double, val sat: Boolean)

    /**
     * 在上一帧位移附近先窄窗搜索（快），贴边或不可信再全范围重搜（稳）。
     */
    fun matchPair(
        sigA: ByteArray,
        sigB: ByteArray,
        h: Int,
        bins: Int,
        maxShift: Int,
        minOv: Int,
        center: Double,
        win: Int,
    ): Match {
        val c0 = center.roundToInt()
        val lo = maxOf(-maxShift, c0 - win)
        val hi = minOf(maxShift, c0 + win)
        var r = scanShift(sigA, sigB, h, bins, lo, hi, minOv)
        val edge = r.shift <= lo.toDouble() || r.shift >= hi.toDouble()
        if ((edge || r.ncc < 0.75) && (lo > -maxShift || hi < maxShift)) {
            val full = scanShift(sigA, sigB, h, bins, -maxShift, maxShift, minOv)
            if (full.ncc > r.ncc) r = full
        }
        val sat = abs(r.shift) >= maxShift - 1
        return Match(r.shift, r.ncc, r.cost, sat)
    }

    // ---------- 逐帧位移与主图规划 ----------

    data class FrameSig(val timeUs: Long, val sig: ByteArray, val sharp: Double)

    data class BadPair(
        val i: Int,
        val reason: String,
        val ncc: Double,
        val cost: Double,
        val agree: Double,
        val score: Double,
    )

    data class TrackOptions(
        val bins: Int = BINS,
        val minOverlap: Double = 0.10,
        val minNcc: Double = 0.60,
        val tolRatio: Double = 0.02,
    )

    data class Track(
        val pos: DoubleArray,
        val step: DoubleArray,
        val cost: DoubleArray,
        val ncc: DoubleArray,
        val agree: DoubleArray,
        val bad: List<BadPair>,
        val maxShift: Int,
        val tol: Double,
        val medCost: Double,
    )

    fun median(arr: DoubleArray): Double {
        if (arr.isEmpty()) return 0.0
        val v = arr.copyOf()
        v.sort()
        return v[v.size / 2]
    }

    /**
     * 逐帧求位移并累加成绝对位置。pos[i] 变大表示内容向上滚（看到更下面的内容）。
     * 判据用「前向 + 后向一致性」而不是绝对代价：同一对帧正着匹配的位移和倒着匹配的
     * 位移必须互为相反数，这一条和画面内容无关。
     */
    fun trackOffsets(sigs: List<FrameSig>, h: Int, opts: TrackOptions = TrackOptions()): Track {
        val bins = opts.bins
        val minOv = maxOf(6, (h * opts.minOverlap).roundToInt())
        val maxShift = maxOf(8, h - minOv)
        val tol = maxOf(2.0, h * opts.tolRatio)
        val n = sigs.size
        val pos = DoubleArray(n)
        val step = DoubleArray(n)
        val cost = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val ncc = DoubleArray(n) { -1.0 }
        val agree = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val win = maxOf(20, (h * 0.3).roundToInt())
        var center = 0.0
        for (i in 1 until n) {
            val a = sigs[i - 1].sig
            val b = sigs[i].sig
            val fwd = matchPair(a, b, h, bins, maxShift, minOv, center, win)
            val bwd = matchPair(b, a, h, bins, maxShift, minOv, -fwd.shift, win)
            cost[i] = fwd.cost
            ncc[i] = minOf(fwd.ncc, bwd.ncc)
            agree[i] = abs(fwd.shift + bwd.shift)
            step[i] = -fwd.shift
            pos[i] = pos[i - 1] + step[i]
            center = -step[i]
        }
        val cs = ArrayList<Double>()
        for (i in 1 until n) if (step[i].isFinite() && cost[i].isFinite()) cs.add(cost[i])
        val med = median(cs.toDoubleArray())
        val bad = ArrayList<BadPair>()
        for (i in 1 until n) {
            val score = 1 - ncc[i]
            val c = cost[i]
            when {
                !c.isFinite() || abs(step[i]) >= (maxShift - 1).toDouble() -> bad.add(
                    BadPair(i, "jump", ncc[i], c, agree[i], 2 + score)
                )
                ncc[i] < opts.minNcc -> bad.add(BadPair(i, "nocc", ncc[i], c, agree[i], score))
                agree[i] > tol -> bad.add(BadPair(i, "match", ncc[i], c, agree[i], score))
            }
        }
        for (i in 1 until n) if (!pos[i].isFinite()) pos[i] = pos[i - 1]
        return Track(pos, step, cost, ncc, agree, bad, maxShift, tol, med)
    }

    data class RowMap(val map: IntArray, val height: Int, val posMin: Double, val missing: Int)

    /** 每一行像素由哪一帧提供：清晰度优先，先到先得（painter's algorithm）。 */
    fun buildRowMap(sharp: DoubleArray, pos: DoubleArray, cTop: Int, cBot: Int): RowMap {
        val n = pos.size
        var pmin = Double.POSITIVE_INFINITY
        var pmax = Double.NEGATIVE_INFINITY
        for (p in pos) { if (p < pmin) pmin = p; if (p > pmax) pmax = p }
        if (!pmin.isFinite()) { pmin = 0.0; pmax = 0.0 }
        val win = cBot - cTop
        val height = (win + (pmax - pmin)).roundToInt()
        val map = IntArray(height) { -1 }
        val order = (0 until n).sortedWith(compareByDescending<Int> { sharp[it] }.thenBy { it })
        for (i in order) {
            // 主图第 0 行 = 最早那一帧裁剪区的第 0 行。cTop 只用于渲染定位，不能加进主图坐标。
            val base = (pos[i] - pmin).roundToInt()
            val end = base + win
            if (base < 0 || end > height) continue
            for (y in base until end) if (map[y] < 0) map[y] = i
        }
        // 帧位置取整会留下 1~4 行的缝，用上一行那一帧向下顺延补掉，否则渲染时出现白线。
        var missing = 0
        for (y in 0 until height) {
            if (map[y] < 0 && y > 0 && map[y - 1] >= 0) map[y] = map[y - 1]
            if (map[y] < 0) missing++
        }
        return RowMap(map, height, pmin, missing)
    }

    data class Op(val y0: Int, val y1: Int, val frame: Int, val src: Int)

    fun mapToOps(map: IntArray, pos: DoubleArray, cTop: Int, posMin: Double): List<Op> {
        val ops = ArrayList<Op>()
        val height = map.size
        var y = 0
        while (y < height) {
            val f = map[y]
            var e = y + 1
            while (e < height && map[e] == f) e++
            ops.add(
                Op(
                    y0 = y,
                    y1 = e,
                    frame = f,
                    // src 是「裁剪区里的行号」：主图 y 行 = 该帧 pos 处裁剪区的第 (y - pos + posMin) 行
                    src = if (f >= 0) (y - (pos[f] - posMin)).roundToInt() else 0,
                )
            )
            y = e
        }
        return ops
    }

    /**
     * 在标称切点附近挑最安静的一段（消息之间的空行）居中切，避免把一条消息劈成两半。
     * 返回长度 n+1 的边界数组，严格递增、每段不少于 tileH*minRatio 像素。
     */
    fun cutPoints(
        act: FloatArray,
        total: Int,
        tileH: Int,
        win: Int,
        minRatio: Double = 0.75,
        maxRatio: Double = 1.15,
    ): IntArray {
        val w = maxOf(4, win)
        val minH = maxOf(8, (tileH * minRatio).roundToInt())
        val maxH = maxOf(minH + 8, (tileH * maxRatio).roundToInt())
        val n = maxOf(1, (total.toDouble() / tileH).roundToInt())
        val cuts = ArrayList<Int>(n + 1)
        cuts.add(0)
        if (n <= 1) { cuts.add(total); return cuts.toIntArray() }
        for (k in 1 until n) {
            val target = (k.toDouble() * total / n).roundToInt()
            val prev = cuts[cuts.size - 1]
            val lo = maxOf(prev + minH, target - w)
            val hi = minOf(total - (n - k) * minH, prev + maxH, target + w)
            var best = maxOf(lo, minOf(hi, target))
            if (hi > lo) {
                var minV = Double.POSITIVE_INFINITY
                var maxV = Double.NEGATIVE_INFINITY
                for (y in lo..hi) {
                    val v = act[y].toDouble()
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
                if (maxV - minV > 1e-3) {
                    val thr = minV + (maxV - minV) * 0.08
                    var ymin = lo
                    for (y in lo + 1..hi) if (act[y] < act[ymin]) ymin = y
                    // 只在「最安静那一行所在的连续安静段」里取中点，否则窗口两端各有一个空行时
                    // 中点会落回内容上（剪出来的截图照样劈开消息）。
                    var a = ymin
                    var b = ymin
                    while (a > lo && act[a - 1] <= thr) a--
                    while (b < hi && act[b + 1] <= thr) b++
                    best = ((a + b) / 2.0).roundToInt()
                }
            }
            best = maxOf(prev + minH, minOf(best, prev + maxH, total - (n - k) * minH))
            cuts.add(best)
        }
        cuts.add(total)
        return cuts.toIntArray()
    }
}
