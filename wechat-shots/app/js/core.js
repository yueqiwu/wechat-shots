// core.js - pure image/sequence algorithms (no DOM), shared by the app and tests.

export function grayFromRGBA(rgba, w, h) {
  const g = new Uint8Array(w * h);
  for (let i = 0, j = 0; i < g.length; i++, j += 4) {
    g[i] = (rgba[j] * 77 + rgba[j + 1] * 150 + rgba[j + 2] * 29) >> 8;
  }
  return g;
}

export function downscaleGray(src, sw, sh, dw, dh) {
  const out = new Uint8Array(dw * dh);
  for (let y = 0; y < dh; y++) {
    const y0 = Math.floor(y * sh / dh);
    const y1 = Math.max(y0 + 1, Math.floor((y + 1) * sh / dh));
    for (let x = 0; x < dw; x++) {
      const x0 = Math.floor(x * sw / dw);
      const x1 = Math.max(x0 + 1, Math.floor((x + 1) * sw / dw));
      let s = 0, n = 0;
      for (let yy = y0; yy < y1; yy++) {
        const o = yy * sw;
        for (let xx = x0; xx < x1; xx++) { s += src[o + xx]; n++; }
      }
      out[y * dw + x] = Math.round(s / Math.max(1, n));
    }
  }
  return out;
}

export function cropGray(gray, w, h, top, bottom) {
  const t = Math.max(0, Math.min(h - 1, top));
  const b = Math.max(t + 1, Math.min(h, bottom));
  const out = new Uint8Array(w * (b - t));
  out.set(gray.subarray(t * w, b * w));
  return out;
}

export function frameDiff(a, b) {
  const n = Math.min(a.length, b.length);
  let s = 0;
  for (let i = 0; i < n; i++) s += Math.abs(a[i] - b[i]);
  return s / Math.max(1, n);
}

export function rowActivity(frames, w, h) {
  const act = new Float64Array(h);
  if (frames.length < 2) return act;
  for (let k = 1; k < frames.length; k++) {
    const a = frames[k - 1], b = frames[k];
    for (let y = 0; y < h; y++) {
      const off = y * w;
      let s = 0;
      for (let x = 0; x < w; x++) s += Math.abs(a[off + x] - b[off + x]);
      act[y] += s / w;
    }
  }
  for (let y = 0; y < h; y++) act[y] /= (frames.length - 1);
  return act;
}

export function smooth1d(a, r) {
  const out = new Float64Array(a.length);
  for (let i = 0; i < a.length; i++) {
    let s = 0, n = 0;
    for (let k = Math.max(0, i - r); k <= Math.min(a.length - 1, i + r); k++) { s += a[k]; n++; }
    out[i] = s / n;
  }
  return out;
}

export function detectBounds(act) {
  const h = act.length;
  const sm = smooth1d(act, 4);
  let peak = 0;
  for (let y = 0; y < h; y++) if (sm[y] > peak) peak = sm[y];
  if (peak < 0.35) return { top: 0, bottom: h, peak, auto: false };
  const thr = Math.max(0.22, peak * 0.06);
  let top = 0, bottom = h;
  for (let y = 0; y < h; y++) if (sm[y] > thr) { top = y; break; }
  for (let y = h - 1; y >= 0; y--) if (sm[y] > thr) { bottom = y + 1; break; }
  if (bottom - top < h * 0.2) return { top: 0, bottom: h, peak, auto: false };
  return { top, bottom, peak, auto: true };
}

export function autoThreshold(diffs) {
  const v = Array.from(diffs).sort((a, b) => a - b);
  if (!v.length) return 1;
  const q = (p) => v[Math.min(v.length - 1, Math.max(0, Math.round(p * (v.length - 1))))];
  const p20 = q(0.2), p50 = q(0.5), p75 = q(0.75), p90 = q(0.9);
  let thr = Math.max(p20 * 1.5, p20 + (p75 - p20) * 0.45);
  thr = Math.max(thr, 0.30);
  if (p90 > 2) thr = Math.min(thr, p90 * 0.55);
  if (p50 > thr) thr = Math.max(thr, p50 * 1.35);
  return Math.max(0.25, thr);
}

export function detectRuns(diffs, thr, minRun) {
  const runs = [];
  let a = 0;
  for (let i = 0; i < diffs.length; i++) {
    if (diffs[i] >= thr) {
      if (i > a) runs.push([a, i]);
      a = i + 1;
    }
  }
  if (diffs.length >= a) runs.push([a, diffs.length]);
  const mr = minRun || 1;
  const out = [];
  for (const r of runs) {
    if (r[1] - r[0] + 1 >= mr) out.push({ start: r[0], end: r[1], mid: (r[0] + r[1]) >> 1 });
  }
  return out;
}

export function rowSig(gray, w, h, bins) {
  const sig = new Uint8Array(h * bins);
  for (let y = 0; y < h; y++) {
    const off = y * w;
    for (let b = 0; b < bins; b++) {
      const x0 = Math.floor(b * w / bins);
      const x1 = Math.max(x0 + 1, Math.floor((b + 1) * w / bins));
      let s = 0;
      for (let x = x0; x < x1; x++) s += gray[off + x];
      sig[y * bins + b] = Math.round(s / (x1 - x0));
    }
  }
  return sig;
}

export function findShift(sigA, sigB, h, bins, maxShift, minOverlapRatio) {
  const maxS = Math.max(0, Math.min(maxShift, h - 8));
  return findShiftRange(sigA, sigB, h, bins, -maxS, maxS, minOverlapRatio);
}

export function findShiftRange(sigA, sigB, h, bins, lo, hi, minOverlapRatio) {
  const minOv = Math.max(8, Math.floor(h * (minOverlapRatio === undefined ? 0.15 : minOverlapRatio)));
  let best = 0, bestCost = Infinity, bestOv = 0;
  for (let s = lo; s <= hi; s++) {
    const y0 = Math.max(0, -s), y1 = Math.min(h, h - s);
    const ov = y1 - y0;
    if (ov < minOv) continue;
    let cost = 0, cnt = 0;
    for (let y = y0; y < y1; y++) {
      const oa = y * bins, ob = (y + s) * bins;
      let d = 0;
      for (let b = 0; b < bins; b++) d += Math.abs(sigA[oa + b] - sigB[ob + b]);
      cost += d; cnt++;
    }
    cost = cost / cnt;
    if (cost < bestCost - 0.85 || (Math.abs(cost - bestCost) <= 0.85 && ov > bestOv)) {
      bestCost = cost; best = s; bestOv = ov;
    }
  }
  return { shift: best, cost: bestCost, overlap: bestOv, ok: bestOv > 0 };
}

export function normalizeDirection(screens, shifts) {
  const pos = shifts.filter((s) => s > 0).length;
  const neg = shifts.filter((s) => s < 0).length;
  const reverse = pos >= neg;
  const n = shifts.length;
  const order = [];
  for (let i = 0; i < screens.length; i++) order.push(screens[i]);
  const t = [];
  if (reverse) {
    order.reverse();
    for (let j = 1; j <= n; j++) t.push(Math.abs(shifts[n - j]));
  } else {
    for (let j = 1; j <= n; j++) t.push(Math.abs(shifts[j - 1]));
  }
  return { screens: order, steps: t, reverse };
}

export function planStrips(cropH, steps, names) {
  const strips = [{ screen: 0, srcY: 0, h: cropH, name: names ? names[0] : null }];
  let total = cropH;
  for (let j = 1; j <= steps.length; j++) {
    const h = Math.max(0, Math.round(steps[j - 1]));
    if (h <= 0) continue;
    if (h > cropH) continue;
    strips.push({ screen: j, srcY: cropH - h, h, name: names ? names[j] : null });
    total += h;
  }
  return { strips, total };
}

export function median(arr) {
  if (!arr.length) return 0;
  const v = Array.from(arr).sort((a, b) => a - b);
  return v[v.length >> 1];
}

// ---------- 连续滚动重建（v2） ----------

// 垂直梯度能量：滑动越快帧越糊，这个值越小。用来在同一片段里挑最清晰的一帧。
export function rowSharpness(gray, w, top, bottom) {
  const h = Math.floor(gray.length / w);
  const t = Math.max(1, Math.min(h - 1, top | 0));
  const b = Math.max(t + 1, Math.min(h, bottom | 0));
  let s = 0, n = 0;
  for (let y = t; y < b; y++) {
    const o = y * w, p = o - w;
    let d = 0;
    for (let x = 0; x < w; x++) d += Math.abs(gray[o + x] - gray[p + x]);
    s += d / w; n++;
  }
  return n ? s / n : 0;
}

// 一个候选位移的相似度。同时给两个指标：
//   raw = 行签名平均绝对差（原始像素尺度，画面越花越大，不能直接当阈值）
//   ncc = 归一化互相关（0=毫无关系，1=完全相同）。它跟亮度/对比度/内容多少无关，
//         所以「这一对帧到底对不对得上」只在 ncc 上判。
export function alignAt(sigA, sigB, h, bins, s, minOv, rowStride) {
  const y0 = Math.max(0, -s);
  const y1 = Math.min(h, h - s);
  if (y1 - y0 < minOv) return null;
  const rs = rowStride > 1 ? rowStride : 1;
  let sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0, raw = 0, rows = 0;
  for (let y = y0; y < y1; y += rs) {
    const oa = y * bins, ob = (y + s) * bins;
    for (let q = 0; q < bins; q++) {
      const a = sigA[oa + q], b = sigB[ob + q];
      sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b;
      raw += a > b ? a - b : b - a;
    }
    rows++;
  }
  if (!rows) return null;
  const n = rows * bins;
  const va = saa - (sa * sa) / n;
  const vb = sbb - (sb * sb) / n;
  const cov = sab - (sa * sb) / n;
  let ncc = 0;
  if (va > 1e-6 && vb > 1e-6) ncc = cov / Math.sqrt(va * vb);
  else if (va <= 1e-6 && vb <= 1e-6) ncc = 1;  // 两边都是纯色（大空白），视为同一位置
  else ncc = 0.5;                              // 一边纯色一边有内容：无从判断，不算失配
  if (ncc > 1) ncc = 1; else if (ncc < -1) ncc = -1;
  return { cost: raw / n, ncc, rows };
}

export function alignCost(sigA, sigB, h, bins, s, minOv, stride) {
  const r = alignAt(sigA, sigB, h, bins, s, minOv, stride);
  return r ? r.cost : Infinity;
}

// 位移必须逐像素扫：聊天记录的行内容是高频率的，隔几个像素采一次会整个错过真解。
// 省时间靠「少采样几行」：先用约 24 行粗扫出候选峰，再对前几名用全部行复核。
// （只靠粗扫定胜负会被噪声骗：样本少时随机位移也能撞出 0.5 以上的相关系数。）
const COARSE_CANDIDATES = 6;
export function scanShift(sigA, sigB, h, bins, lo, hi, minOv, hint) {
  const coarseRows = Math.max(1, Math.round(h / 24));
  const cands = [];
  for (let s = lo; s <= hi; s++) {
    const r = alignAt(sigA, sigB, h, bins, s, minOv, coarseRows);
    if (r) cands.push({ shift: s, ncc: r.ncc });
  }
  if (!cands.length) return { shift: 0, ncc: -1, cost: Infinity };
  cands.sort((a, b) => b.ncc - a.ncc);
  const picks = [];
  for (const c of cands) {
    if (picks.length >= COARSE_CANDIDATES) break;
    let far = true;
    for (const p of picks) if (Math.abs(p - c.shift) < 3) { far = false; break; }
    if (far) picks.push(c.shift);
  }
  if (hint != null && hint >= lo && hint <= hi) picks.push(hint);
  let best = null;
  for (const p of picks) {
    for (let s = Math.max(lo, p - 1); s <= Math.min(hi, p + 1); s++) {
      const r = alignAt(sigA, sigB, h, bins, s, minOv, 1);
      if (!r) continue;
      if (!best || r.ncc > best.ncc + 1e-4 || (Math.abs(r.ncc - best.ncc) <= 1e-4 && Math.abs(s) < Math.abs(best.shift))) {
        best = { shift: s, ncc: r.ncc, cost: r.cost };
      }
    }
  }
  if (best) {
    // 亚像素精修：整数位移的误差会随帧数累积（几百帧下来能差出十几像素，
    // 主图上就是接缝处重复/漏行）。用峰位左右各 1 的相关系数做抛物线拟合。
    const sL = best.shift - 1, sR = best.shift + 1;
    if (sL >= lo && sR <= hi) {
      const nL = alignAt(sigA, sigB, h, bins, sL, minOv, 1);
      const nR = alignAt(sigA, sigB, h, bins, sR, minOv, 1);
      if (nL && nR) {
        const den = nL.ncc - 2 * best.ncc + nR.ncc;
        if (den < -1e-6) {
          let d = 0.5 * (nL.ncc - nR.ncc) / den;
          if (d > 0.7) d = 0.7; else if (d < -0.7) d = -0.7;
          best = { shift: best.shift + d, ncc: best.ncc, cost: best.cost };
        }
      }
    }
  }
  return best || { shift: 0, ncc: -1, cost: Infinity };
}

// 在上一帧位移附近先做窄窗搜索（快），结果贴边再全范围重搜（稳）。
// 先在上一帧位移附近窄窗搜（大多数帧的位移是连续的），不可信再全范围重搜。
export function matchPair(sigA, sigB, h, bins, maxShift, minOv, center, win) {
  const c0 = center || 0;
  const lo = Math.max(-maxShift, c0 - win);
  const hi = Math.min(maxShift, c0 + win);
  let r = scanShift(sigA, sigB, h, bins, lo, hi, minOv);
  const edge = r.shift <= lo || r.shift >= hi;
  if ((edge || r.ncc < 0.75) && (lo > -maxShift || hi < maxShift)) {
    const full = scanShift(sigA, sigB, h, bins, -maxShift, maxShift, minOv);
    if (full.ncc > r.ncc) r = full;
  }
  r.maxShift = maxShift;
  r.sat = Math.abs(r.shift) >= maxShift - 1;
  return r;
}

// 逐帧求位移并累加成绝对位置。pos[i] 变大表示内容向上滚（看到更下面的内容）。
// 判据用「前向 + 后向一致性」而不是绝对代价：代价随画面内容剧烈变化（对比度高的
// 聊天记录代价本来就大），但对同一对帧，正着匹配出的位移和倒着匹配出的位移必须
// 互为相反数，这一条与画面内容无关，实测误差恒为 0。
export function trackOffsets(sigs, h, opts) {
  const o = opts || {};
  const bins = o.bins || 8;
  const minOv = Math.max(6, Math.round(h * (o.minOverlap == null ? 0.1 : o.minOverlap)));
  const maxShift = Math.max(8, h - minOv);
  const tol = Math.max(2, Math.round(h * (o.tol == null ? 0.02 : o.tol)));
  const n = sigs.length;
  const pos = new Float64Array(n);
  const step = new Float64Array(n);
  const cost = new Float64Array(n);
  const ncc = new Float64Array(n);
  const agree = new Float64Array(n);
  const win = Math.max(20, Math.round(h * 0.3));
  let center = 0;
  for (let i = 1; i < n; i++) {
    const fwd = matchPair(sigs[i - 1], sigs[i], h, bins, maxShift, minOv, center, win);
    const bwd = matchPair(sigs[i], sigs[i - 1], h, bins, maxShift, minOv, -fwd.shift, win);
    const a = Math.abs(fwd.shift + bwd.shift);
    cost[i] = fwd.cost;
    ncc[i] = Math.min(fwd.ncc, bwd.ncc);
    agree[i] = a;
    // 不可信的帧对不做折中平均（两个错估计平均还是错的），一律取前向结果并标记，
    // 交给上层加密补帧重算。
    step[i] = isFinite(fwd.shift) ? -fwd.shift : 0;
    pos[i] = pos[i - 1] + step[i];
    center = -step[i];
  }
  const cs = [];
  for (let i = 1; i < n; i++) if (isFinite(cost[i])) cs.push(cost[i]);
  const med = median(cs);
  const minNcc = o.minNcc == null ? 0.6 : o.minNcc;
  const bad = [];
  for (let i = 1; i < n; i++) {
    const score = 1 - ncc[i];
    if (cost[i] === Infinity || Math.abs(step[i]) >= maxShift - 1) {
      bad.push({ i, reason: 'jump', cost: cost[i], agree: agree[i], ncc: ncc[i], score: 2 + score });
    } else if (ncc[i] < minNcc) {
      bad.push({ i, reason: 'nocc', cost: cost[i], agree: agree[i], ncc: ncc[i], score: score });
    } else if (agree[i] > tol) {
      bad.push({ i, reason: 'match', cost: cost[i], agree: agree[i], ncc: ncc[i], score: score });
    }
  }
  for (let i = 1; i < n; i++) if (!isFinite(pos[i])) pos[i] = pos[i - 1];
  return { pos, step, cost, ncc, agree, bad, maxShift, medCost: med, tol };
}

// 每一行像素由哪一帧提供：清晰度优先，先到先得（painter's algorithm）。
export function buildRowMap(sharp, pos, cTop, cBot) {
  const n = pos.length;
  let pmin = Infinity, pmax = -Infinity;
  for (let i = 0; i < n; i++) { if (pos[i] < pmin) pmin = pos[i]; if (pos[i] > pmax) pmax = pos[i]; }
  if (!isFinite(pmin)) { pmin = 0; pmax = 0; }
  const win = cBot - cTop;
  const H = Math.round(win + (pmax - pmin));
  const map = new Int32Array(H).fill(-1);
  const order = [];
  for (let i = 0; i < n; i++) order.push(i);
  order.sort((a, b) => (sharp[b] - sharp[a]) || (a - b));
  for (const i of order) {
    // 主图第 0 行 = 最早那一帧裁剪区的第 0 行（cTop 是裁剪区在画面里的起始行，
    // 只用于渲染时定位，不能加进主图坐标——加了会让每帧错开 cTop 行，整张图串位）。
    const base = Math.round(pos[i] - pmin);
    const end = base + win;
    if (base < 0 || end > H) continue;
    for (let y = base; y < end; y++) if (map[y] < 0) map[y] = i;
  }
  // 帧位置取整会留下 1~4 行的缝，用上一行那一帧向上顺延补掉（等于重复一行像素，
  // 肉眼看不出来），否则渲染时会出现一条白线。
  let missing = 0;
  for (let y = 0; y < H; y++) {
    if (map[y] < 0 && y > 0 && map[y - 1] >= 0) map[y] = map[y - 1];
    if (map[y] < 0) missing++;
  }
  return { map, H, posMin: pmin, cTop, cBot, win, missing };
}

export function mapToOps(map, pos, cTop, posMin) {
  const ops = [];
  const H = map.length;
  let y = 0;
  while (y < H) {
    const f = map[y];
    let e = y + 1;
    while (e < H && map[e] === f) e++;
    ops.push({
      y0: y, y1: e, frame: f,
      // src 是「裁剪区里的行号」，与 buildRowMap 的 base 用同一套坐标：
      // 主图 y 行 = 该帧 pos 处裁剪区的第 (y - pos + posMin) 行。
      src: f >= 0 ? Math.round(y - (pos[f] - posMin)) : 0,
    });
    y = e;
  }
  return ops;
}

// 每行在 bins 个横向分箱上的离散度：文字/气泡横跨多列时高，纯背景行接近 0。
// 用来找「消息之间的空行」，把截图切在空行上，避免把一条消息劈成两半。
export function sigActivity(sig, h, bins) {
  const act = new Float64Array(h);
  for (let y = 0; y < h; y++) {
    const off = y * bins;
    let s = 0;
    for (let b = 0; b < bins; b++) s += sig[off + b];
    const m = s / bins;
    let v = 0;
    for (let b = 0; b < bins; b++) { const d = sig[off + b] - m; v += d * d; }
    act[y] = Math.sqrt(v / bins);
  }
  return act;
}

// 在标称切点附近挑最安静的一段（消息之间的空行）居中切。
// 返回长度 n+1 的边界数组，保证严格递增、每段不少于 minH 像素。
export function cutPoints(act, total, tileH, opts) {
  const o = opts || {};
  const win = Math.max(4, Math.round(o.win == null ? tileH * 0.10 : o.win));
  const minH = Math.max(8, Math.round(tileH * (o.minRatio == null ? 0.75 : o.minRatio)));
  const maxH = Math.max(minH + 8, Math.round(tileH * (o.maxRatio == null ? 1.15 : o.maxRatio)));
  const n = Math.max(1, Math.round(total / tileH));
  const cuts = [0];
  if (n <= 1) { cuts.push(total); return cuts; }
  for (let k = 1; k < n; k++) {
    const target = Math.round(k * total / n);
    const prev = cuts[cuts.length - 1];
    const lo = Math.max(prev + minH, target - win);
    const hi = Math.min(total - (n - k) * minH, prev + maxH, target + win);
    let best = Math.max(lo, Math.min(hi, target));
    if (hi > lo) {
      let minV = Infinity, maxV = -Infinity;
      for (let y = lo; y <= hi; y++) { const v = act[y]; if (v < minV) minV = v; if (v > maxV) maxV = v; }
      if (maxV - minV > 1e-3) {
        const thr = minV + (maxV - minV) * 0.08;
        let ymin = lo;
        for (let y = lo + 1; y <= hi; y++) if (act[y] < act[ymin]) ymin = y;
        // 只在「最安静那一行所在的连续安静段」里取中点，否则窗口两端各有一个空行时
        // 中点会落回内容上（剪出来的截图照样劈开消息）。
        let a = ymin, b = ymin;
        while (a > lo && act[a - 1] <= thr) a--;
        while (b < hi && act[b + 1] <= thr) b++;
        best = Math.round((a + b) / 2);
      }
    }
    best = Math.max(prev + minH, Math.min(best, prev + maxH, total - (n - k) * minH));
    cuts.push(best);
  }
  cuts.push(total);
  return cuts;
}

