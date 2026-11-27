// stitch.js —— 连续滚动重建（拼接）纯算法核心，无 DOM 依赖，可在 Node 中单测。
//
// 移植自 Android 版已验证实现：
//   wechat-shots-android/app/src/main/java/com/dsh/wechatshot/stitch/StitchCore.kt
//   wechat-shots-android/app/src/main/java/com/dsh/wechatshot/stitch/StitchPlanner.kt
//
// 坐标/数值约定（与 Kotlin 版一致）：
//  - 灰度 Uint8Array，值 0..255，行优先 gray[y*width+x]（Kotlin 的 ByteArray 是有符号的，
//    移植时统一按无符号处理，比较/阈值与旧 web 版 core.js 一致）。
//  - 分析帧先缩到 ANALYSIS_WIDTH=144 宽，高按比例取整；所有位移都在「分析行」里算。
//  - band = {top, bot}（[top, bot) 行区间），行签名 sig 只覆盖 band。
//  - trackOffsets 的 pos：后一帧内容向上滚动为正（看到更下面的内容）。
//  - 帧数组必须从旧到新（最早的消息在前）。

export const BINS = 8;
export const ANALYSIS_WIDTH = 144;

/** 拼接相关错误（UI 可直接显示 message）。 */
export class StitchError extends Error {
  constructor(message) {
    super(message);
    this.name = 'StitchError';
  }
}

const clampInt = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const roundInt = (v) => Math.round(v);

// ---------------------------------------------------------------------------
// 基础灰度操作
// ---------------------------------------------------------------------------

/** RGBA(Uint8ClampedArray) -> 灰度 Uint8Array（77/150/29 加权，与 Kotlin/旧 web 一致）。 */
export function grayFromRGBA(rgba, width, height) {
  const w = Math.max(0, width | 0);
  const h = Math.max(0, height | 0);
  const n = Math.min(w * h, rgba.length >> 2);
  const out = new Uint8Array(n);
  for (let i = 0, o = 0; i < n; i++, o += 4) {
    out[i] = ((rgba[o] * 77 + rgba[o + 1] * 150 + rgba[o + 2] * 29) >> 8) & 0xff;
  }
  return out;
}

/**
 * 面积平均缩小灰度图。targetWidth 为目标宽，高按原比例取整。
 * -> {gray, width, height}
 */
export function downscaleGray(gray, width, height, targetWidth) {
  const sw = Math.max(1, width | 0);
  const sh = Math.max(1, height | 0);
  const dw = Math.max(1, Math.round(targetWidth || sw));
  if (dw >= sw) {
    // 不需要缩小：直接返回原图副本，保持原尺寸
    return { gray: gray.slice(0, sw * sh), width: sw, height: sh };
  }
  const dh = Math.max(1, Math.round((sh * dw) / sw));
  const out = new Uint8Array(dw * dh);
  for (let y = 0; y < dh; y++) {
    const y0 = Math.floor((y * sh) / dh);
    const y1 = Math.max(y0 + 1, Math.floor(((y + 1) * sh) / dh));
    for (let x = 0; x < dw; x++) {
      const x0 = Math.floor((x * sw) / dw);
      const x1 = Math.max(x0 + 1, Math.floor(((x + 1) * sw) / dw));
      let s = 0;
      let cnt = 0;
      for (let yy = y0; yy < y1; yy++) {
        const off = yy * sw;
        for (let xx = x0; xx < x1; xx++) {
          s += gray[off + xx];
          cnt++;
        }
      }
      out[y * dw + x] = Math.floor(s / Math.max(1, cnt));
    }
  }
  return { gray: out, width: dw, height: dh };
}

/** 按行裁剪灰度图（[top, bottom) 行）。-> {gray, width, height} */
export function cropGray(gray, width, top, bottom) {
  const w = Math.max(1, width | 0);
  const h = Math.max(1, Math.floor(gray.length / w));
  const t = clampInt(Math.round(top || 0), 0, h - 1);
  const b = clampInt(Math.round(bottom == null ? h : bottom), t + 1, h);
  return { gray: gray.slice(t * w, b * w), width: w, height: b - t };
}

/**
 * 逐行在相邻帧之间的平均像素变化量：静态栏（状态栏/标题栏/输入框）≈0，
 * 滚动的聊天内容很大。grays: Array<Uint8Array>。-> Float64Array(height)
 */
export function rowActivity(grays, width, height) {
  const w = Math.max(1, width | 0);
  const h = Math.max(0, height | 0);
  const act = new Float64Array(h);
  if (!grays || grays.length < 2) return act;
  const pairs = grays.length - 1;
  for (let k = 1; k < grays.length; k++) {
    const a = grays[k - 1];
    const b = grays[k];
    for (let y = 0; y < h; y++) {
      const off = y * w;
      let s = 0;
      for (let x = 0; x < w; x++) s += Math.abs(a[off + x] - b[off + x]);
      act[y] += s / w;
    }
  }
  for (let y = 0; y < h; y++) act[y] /= pairs;
  return act;
}

/** 从逐行活跃度里找真正在滚动的内容区 band（去掉固定 UI 栏）。-> {top,bot,h} */
export function contentBand(activity, height) {
  const ah = Math.max(0, height | 0);
  if (!activity || activity.length < ah) return { top: 0, bot: ah, h: ah };
  let peak = 0;
  for (let y = 0; y < ah; y++) if (activity[y] > peak) peak = activity[y];
  if (!(peak > 0)) return { top: 0, bot: ah, h: ah };
  const thr = Math.max(0.6, peak * 0.18);
  let top = 0;
  let bot = ah;
  for (let y = 0; y < ah; y++) {
    if (activity[y] >= thr) {
      top = y;
      break;
    }
  }
  for (let y = ah - 1; y >= 0; y--) {
    if (activity[y] >= thr) {
      bot = y + 1;
      break;
    }
  }
  if (bot - top < 32) return { top: 0, bot: ah, h: ah };
  return { top, bot, h: bot - top };
}

/**
 * 垂直梯度能量（标量）：滑动越快帧越糊，值越小；用来在同一片段里挑最清晰的一帧。
 * 注意：契约注释写 Float64Array，但语义与 Kotlin FrameSig.sharp 一致，是单帧标量；
 * buildRowMap 的 sharp 参数是「每帧一个数」的数组。UI 不直接调用本函数。
 */
export function rowSharpness(gray, width, top, bottom) {
  const w = Math.max(1, width | 0);
  const h = Math.max(1, Math.floor(gray.length / w));
  const t = Math.max(1, Math.min(h - 1, top == null ? 0 : Math.round(top)));
  const b = Math.max(t + 1, Math.min(h, bottom == null ? h : Math.round(bottom)));
  let s = 0;
  let n = 0;
  for (let y = t; y < b; y++) {
    const o = y * w;
    const p = o - w;
    let d = 0;
    for (let x = 0; x < w; x++) d += Math.abs(gray[o + x] - gray[p + x]);
    s += d / w;
    n++;
  }
  return n === 0 ? 0 : s / n;
}

/** 行签名：每行按横向 bins 个分箱取平均亮度。-> Uint8Array(height*bins) */
export function rowSig(gray, width, height, bins = BINS) {
  const w = Math.max(1, width | 0);
  const h = Math.max(0, height | 0);
  const bn = Math.max(1, bins | 0);
  const sig = new Uint8Array(h * bn);
  for (let y = 0; y < h; y++) {
    const off = y * w;
    for (let b = 0; b < bn; b++) {
      const x0 = Math.floor((b * w) / bn);
      const x1 = Math.max(x0 + 1, Math.floor(((b + 1) * w) / bn));
      let s = 0;
      let cnt = 0;
      for (let x = x0; x < Math.min(x1, w); x++) {
        s += gray[off + x];
        cnt++;
      }
      sig[y * bn + b] = Math.floor(s / Math.max(1, cnt));
    }
  }
  return sig;
}

/** 每行在 bins 个分箱上的离散度：文字/气泡行高，消息之间空行接近 0。-> Float32Array(height) */
export function sigActivity(sig, height, bins = BINS) {
  const h = Math.max(0, height | 0);
  const bn = Math.max(1, bins | 0);
  const act = new Float32Array(h);
  for (let y = 0; y < h; y++) {
    const off = y * bn;
    let s = 0;
    for (let b = 0; b < bn; b++) s += sig[off + b];
    const m = s / bn;
    let v = 0;
    for (let b = 0; b < bn; b++) {
      const d = sig[off + b] - m;
      v += d * d;
    }
    act[y] = Math.sqrt(v / bn);
  }
  return act;
}

// ---------------------------------------------------------------------------
// 位移匹配
// ---------------------------------------------------------------------------

/** minOverlap 既接受「行数」也接受「比例(<=1)」，统一换成行数。 */
function overlapRows(minOverlap, height) {
  if (minOverlap == null) return Math.max(6, Math.round(height * 0.1));
  if (minOverlap <= 1) return Math.max(1, Math.round(height * minOverlap));
  return Math.max(1, Math.round(minOverlap));
}

/** 内部对齐：rowStride>1 时少采样几行（粗扫提速）。返回 null 表示重叠行数不足。 */
function alignAtStride(sigA, sigB, h, bins, shift, minRows, rowStride) {
  const bn = Math.max(1, bins | 0);
  const y0 = Math.max(0, -shift);
  const y1 = Math.min(h, h - shift);
  if (y1 - y0 < minRows) return null;
  const rs = rowStride > 1 ? rowStride : 1;
  let sa = 0;
  let sb = 0;
  let saa = 0;
  let sbb = 0;
  let sab = 0;
  let raw = 0;
  let rows = 0;
  for (let y = y0; y < y1; y += rs) {
    const oa = y * bn;
    const ob = (y + shift) * bn;
    for (let q = 0; q < bn; q++) {
      const a = sigA[oa + q];
      const b = sigB[ob + q];
      sa += a;
      sb += b;
      saa += a * a;
      sbb += b * b;
      sab += a * b;
      raw += Math.abs(a - b);
    }
    rows++;
  }
  if (rows === 0) return null;
  const n = rows * bn;
  const va = saa - (sa * sa) / n;
  const vb = sbb - (sb * sb) / n;
  const cov = sab - (sa * sb) / n;
  let ncc;
  if (va > 1e-6 && vb > 1e-6) ncc = cov / Math.sqrt(va * vb);
  else if (va <= 1e-6 && vb <= 1e-6) ncc = 1; // 两边都是纯色（大空白），视为同一位置
  else ncc = 0.5; // 一边纯色一边有内容：无从判断，不算失配
  if (ncc > 1) ncc = 1;
  else if (ncc < -1) ncc = -1;
  return { cost: raw / n, ncc, rows };
}

/**
 * 一个候选位移 s 的相似度：sigA[y] vs sigB[y+s]。
 * cost = 行签名平均绝对差；ncc = 归一化互相关（0=无关，1=完全相同）。
 * minOverlap 为重叠行数（也接受 <=1 的比例）。-> {cost,ncc,rows} | null
 */
export function alignAt(sigA, sigB, height, bins, shift, minOverlap) {
  const h = Math.max(1, height | 0);
  return alignAtStride(sigA, sigB, h, bins, shift | 0, overlapRows(minOverlap, h), 1);
}

const COARSE_CANDIDATES = 6;

/**
 * 在 [lo, hi] 内找最佳位移。逐像素扫（聊天行内容频率高，隔几像素会错过真解），
 * 省时间靠「先粗扫候选峰，再对前几名用全部行复核」，最后抛物线亚像素精修。
 * minOverlap 为重叠行数（也接受 <=1 的比例）。-> {shift,ncc,cost}
 */
export function scanShift(sigA, sigB, height, bins, lo, hi, minOverlap, hint = null) {
  const h = Math.max(1, height | 0);
  const bn = Math.max(1, bins | 0);
  const minRows = overlapRows(minOverlap, h);
  const loI = lo | 0;
  const hiI = hi | 0;
  if (loI > hiI) return { shift: 0, ncc: -1, cost: Infinity };
  const coarseRows = Math.max(1, Math.round(h / 24));
  const span = hiI - loI + 1;
  const shifts = new Int32Array(span);
  const nccs = new Float64Array(span).fill(-2);
  let count = 0;
  for (let s = loI; s <= hiI; s++) {
    const r = alignAtStride(sigA, sigB, h, bn, s, minRows, coarseRows);
    if (!r) continue;
    shifts[count] = s;
    nccs[count] = r.ncc;
    count++;
  }
  if (count === 0) return { shift: 0, ncc: -1, cost: Infinity };
  // 按 ncc 降序取前 6 个彼此相距 >=3 的候选
  const order = Array.from({ length: count }, (_, i) => i).sort((a, b) => nccs[b] - nccs[a]);
  const picks = [];
  for (const idx of order) {
    if (picks.length >= COARSE_CANDIDATES) break;
    let far = true;
    for (const p of picks) {
      if (Math.abs(p - shifts[idx]) < 3) {
        far = false;
        break;
      }
    }
    if (far) picks.push(shifts[idx]);
  }
  if (hint != null && hint >= loI && hint <= hiI) picks.push(hint | 0);
  let bestShift = 0;
  let bestNcc = -2;
  let bestCost = Infinity;
  let found = false;
  for (const p of picks) {
    const a = Math.max(loI, p - 1);
    const b = Math.min(hiI, p + 1);
    for (let s = a; s <= b; s++) {
      const r = alignAtStride(sigA, sigB, h, bn, s, minRows, 1);
      if (!r) continue;
      const better =
        !found ||
        r.ncc > bestNcc + 1e-4 ||
        (Math.abs(r.ncc - bestNcc) <= 1e-4 && Math.abs(s) < Math.abs(bestShift));
      if (better) {
        found = true;
        bestShift = s;
        bestNcc = r.ncc;
        bestCost = r.cost;
      }
    }
  }
  if (!found) return { shift: 0, ncc: -1, cost: Infinity };
  let shiftD = bestShift;
  // 亚像素精修：整数位移误差会随帧数累积，几百帧下来能差出十几像素。
  const sL = bestShift - 1;
  const sR = bestShift + 1;
  if (sL >= loI && sR <= hiI) {
    const nL = alignAtStride(sigA, sigB, h, bn, sL, minRows, 1);
    const nR = alignAtStride(sigA, sigB, h, bn, sR, minRows, 1);
    if (nL && nR) {
      const den = nL.ncc - 2 * bestNcc + nR.ncc;
      if (den < -1e-6) {
        let d = (0.5 * (nL.ncc - nR.ncc)) / den;
        if (d > 0.7) d = 0.7;
        else if (d < -0.7) d = -0.7;
        shiftD = bestShift + d;
      }
    }
  }
  return { shift: shiftD, ncc: bestNcc, cost: bestCost };
}

/**
 * 在上一帧位移附近先窄窗搜索（快），贴边或不可信再全范围重搜（稳）。
 * center/win 为上一帧位移与搜索半窗；minOverlap 默认 0.10（比例）。-> {shift,ncc,cost,sat}
 */
export function matchPair(sigA, sigB, height, bins, center, win, maxShift, minOverlap = 0.10) {
  const h = Math.max(1, height | 0);
  const minRows = overlapRows(minOverlap, h);
  const ms = Math.max(1, maxShift | 0);
  const w = Math.max(1, win | 0);
  const c0 = Math.round(center || 0);
  const lo = Math.max(-ms, c0 - w);
  const hi = Math.min(ms, c0 + w);
  let r = scanShift(sigA, sigB, h, bins, lo, hi, minRows);
  const edge = r.shift <= lo || r.shift >= hi;
  if ((edge || r.ncc < 0.75) && (lo > -ms || hi < ms)) {
    const full = scanShift(sigA, sigB, h, bins, -ms, ms, minRows);
    if (full.ncc > r.ncc) r = full;
  }
  const sat = Math.abs(r.shift) >= ms - 1;
  return { shift: r.shift, ncc: r.ncc, cost: r.cost, sat };
}

// ---------------------------------------------------------------------------
// 逐帧位移与主图规划
// ---------------------------------------------------------------------------

/** 数值中位数（会复制数组，不改入参）。 */
export function median(arr) {
  if (!arr || arr.length === 0) return 0;
  const v = Array.from(arr, Number).sort((a, b) => a - b);
  return v[v.length >> 1];
}

const DEFAULT_TRACK_OPTIONS = { bins: BINS, minOverlap: 0.10, minNcc: 0.60, tolRatio: 0.02 };

/**
 * 逐帧求位移并累加成绝对位置。pos[i] 变大表示内容向上滚（看到更下面的内容）。
 * frames: [{timeUs, sig, sharp}]（旧的在前）；height = band 高度。
 * -> Track {pos, step, ncc, cost, agree, bad:[{i,reason,ncc,cost,agree,score}], maxShift, tol, medCost}
 */
export function trackOffsets(frames, height, options = {}) {
  const opts = { ...DEFAULT_TRACK_OPTIONS, ...(options || {}) };
  const h = Math.max(1, height | 0);
  const bins = Math.max(1, opts.bins | 0);
  const minOv = Math.max(6, Math.round(h * opts.minOverlap));
  // +2 行余量：截图「正好 10% 重叠」时真实位移恰好落在搜索上限，不能误判为 jump。
  const maxShift = Math.max(8, h - minOv + 2);
  const tol = Math.max(2, h * opts.tolRatio);
  const n = frames ? frames.length : 0;
  const pos = new Float64Array(n);
  const step = new Float64Array(n);
  const cost = new Float64Array(n).fill(Infinity);
  const ncc = new Float64Array(n).fill(-1);
  const agree = new Float64Array(n).fill(Infinity);
  const win = Math.max(20, Math.round(h * 0.3));
  let center = 0;
  for (let i = 1; i < n; i++) {
    const a = frames[i - 1].sig;
    const b = frames[i].sig;
    const fwd = matchPair(a, b, h, bins, center, win, maxShift, minOv);
    const bwd = matchPair(b, a, h, bins, -fwd.shift, win, maxShift, minOv);
    cost[i] = fwd.cost;
    ncc[i] = Math.min(fwd.ncc, bwd.ncc);
    agree[i] = Math.abs(fwd.shift + bwd.shift);
    const st = -fwd.shift;
    step[i] = st === 0 ? 0 : st; // 归一化 -0（截图完全静止时）
    pos[i] = pos[i - 1] + step[i];
    center = -step[i];
  }
  const cs = [];
  for (let i = 1; i < n; i++) if (Number.isFinite(step[i]) && Number.isFinite(cost[i])) cs.push(cost[i]);
  const med = median(cs);
  const bad = [];
  for (let i = 1; i < n; i++) {
    const score = 1 - ncc[i];
    const c = cost[i];
    if (!Number.isFinite(c) || Math.abs(step[i]) >= maxShift - 1) {
      bad.push({ i, reason: 'jump', ncc: ncc[i], cost: c, agree: agree[i], score: 2 + score });
    } else if (ncc[i] < opts.minNcc) {
      bad.push({ i, reason: 'nocc', ncc: ncc[i], cost: c, agree: agree[i], score });
    } else if (agree[i] > tol) {
      bad.push({ i, reason: 'match', ncc: ncc[i], cost: c, agree: agree[i], score });
    }
  }
  for (let i = 1; i < n; i++) if (!Number.isFinite(pos[i])) pos[i] = pos[i - 1];
  return { pos, step, ncc, cost, agree, bad, maxShift, tol, medCost: med };
}

/**
 * 每一行主图像素由哪一帧提供：清晰度优先，先到先得（painter's algorithm）。
 * sharp: 每帧清晰度数组；pos: 每帧绝对位置。
 * -> {map:Int32Array, height, posMin, missing}
 */
export function buildRowMap(sharp, pos, cTop, cBot) {
  const n = pos.length;
  let pmin = Infinity;
  let pmax = -Infinity;
  for (let i = 0; i < n; i++) {
    if (pos[i] < pmin) pmin = pos[i];
    if (pos[i] > pmax) pmax = pos[i];
  }
  if (!Number.isFinite(pmin)) {
    pmin = 0;
    pmax = 0;
  }
  const win = Math.max(1, (cBot | 0) - (cTop | 0));
  const height = Math.max(1, Math.round(win + (pmax - pmin)));
  const map = new Int32Array(height).fill(-1);
  const order = Array.from({ length: n }, (_, i) => i).sort(
    (a, b) => (sharp[b] || 0) - (sharp[a] || 0) || a - b,
  );
  for (const i of order) {
    // 主图第 0 行 = 最早那一帧裁剪区的第 0 行。cTop 只用于渲染定位，不能加进主图坐标。
    const base = Math.round(pos[i] - pmin);
    const end = base + win;
    if (base < 0 || end > height) continue;
    for (let y = base; y < end; y++) if (map[y] < 0) map[y] = i;
  }
  // 帧位置取整会留下 1~4 行的缝，用上一行那一帧向下顺延补掉，否则渲染时出现白线。
  let missing = 0;
  for (let y = 0; y < height; y++) {
    if (map[y] < 0 && y > 0 && map[y - 1] >= 0) map[y] = map[y - 1];
    if (map[y] < 0) missing++;
  }
  return { map, height, posMin: pmin, missing };
}

/**
 * rowMap -> 主图分段 ops：[{y0,y1,frame,src}]
 * src 是「裁剪区里的行号」：渲染时源行 = plan.topPx + op.src。
 * rowMap 参数可传 buildRowMap 的结果，也可直接传 Int32Array。
 */
export function mapToOps(rowMap, pos, cTop, posMin) {
  // 注意：Int32Array 自带 .map 方法，必须先排除 TypedArray 再取 .map 字段。
  const isObj = rowMap != null && !ArrayBuffer.isView(rowMap) && rowMap.map != null;
  const map = isObj ? rowMap.map : rowMap;
  const pmin = posMin == null ? (isObj && rowMap.posMin != null ? rowMap.posMin : 0) : posMin;
  const ops = [];
  const height = map.length;
  let y = 0;
  while (y < height) {
    const f = map[y];
    let e = y + 1;
    while (e < height && map[e] === f) e++;
    ops.push({
      y0: y,
      y1: e,
      frame: f,
      src: f >= 0 ? Math.round(y - (pos[f] - pmin)) : 0,
    });
    y = e;
  }
  return ops;
}

/**
 * 在标称切点附近挑最安静的一段（消息之间的空行）居中切，避免把一条消息劈成两半。
 * act: 主图逐行活跃度；total: 主图高；tileH: 目标页高；win: 搜索半窗。
 * -> Int32Array 边界数组（长度 n+1，严格递增，首=0，末=total）
 */
export function cutPoints(act, total, tileH, win, minRatio = 0.75, maxRatio = 1.15) {
  const T = Math.max(1, total | 0);
  const th = Math.max(1, tileH | 0);
  const w = Math.max(4, win | 0);
  const minH = Math.max(8, Math.round(th * minRatio));
  const maxH = Math.max(minH + 8, Math.round(th * maxRatio));
  const n = Math.max(1, Math.round(T / th));
  const cuts = [0];
  if (n <= 1) {
    cuts.push(T);
    return Int32Array.from(cuts);
  }
  for (let k = 1; k < n; k++) {
    const target = Math.round((k * T) / n);
    const prev = cuts[cuts.length - 1];
    const lo = Math.max(prev + minH, target - w);
    const hi = Math.min(T - (n - k) * minH, prev + maxH, target + w);
    let best = Math.max(lo, Math.min(hi, target));
    if (hi > lo) {
      let minV = Infinity;
      let maxV = -Infinity;
      for (let y = lo; y <= hi; y++) {
        const v = act[y] || 0;
        if (v < minV) minV = v;
        if (v > maxV) maxV = v;
      }
      if (maxV - minV > 1e-3) {
        const thr = minV + (maxV - minV) * 0.08;
        let ymin = lo;
        for (let y = lo + 1; y <= hi; y++) if ((act[y] || 0) < (act[ymin] || 0)) ymin = y;
        // 只在「最安静那一行所在的连续安静段」里取中点
        let a = ymin;
        let b = ymin;
        while (a > lo && (act[a - 1] || 0) <= thr) a--;
        while (b < hi && (act[b + 1] || 0) <= thr) b++;
        best = Math.round((a + b) / 2);
      }
    }
    best = Math.max(prev + minH, Math.min(best, prev + maxH, T - (n - k) * minH));
    cuts.push(best);
  }
  cuts.push(T);
  return Int32Array.from(cuts);
}

// ---------------------------------------------------------------------------
// 分析编排（视频 + 截图共用）
// ---------------------------------------------------------------------------

/** 由轨道位移算「实测重叠率」：1 - 中位位移/内容区高。 */
function overlapStats(track, bandH) {
  const steps = [];
  if (track && track.step) {
    for (let i = 1; i < track.step.length; i++) {
      const s = track.step[i];
      if (Number.isFinite(s) && s > 0.5 && s < bandH + 2) steps.push(s);
    }
  }
  if (!steps.length || !(bandH > 0)) {
    return { medianStep: 0, medianOverlapRatio: null, minOverlapRatio: null, maxOverlapRatio: null };
  }
  const med = median(steps);
  const ratios = steps.map((s) => 1 - s / bandH).sort((a, b) => a - b);
  return {
    medianStep: med,
    medianOverlapRatio: 1 - med / bandH,
    minOverlapRatio: ratios[0],
    maxOverlapRatio: ratios[ratios.length - 1],
  };
}

function prepOne(grayFrame, aw, band) {
  const h = Math.floor(grayFrame.gray.length / aw);
  const top = clampInt(band.top, 0, Math.max(0, h - 1));
  const bot = clampInt(band.bot, top + 1, h);
  const crop = cropGray(grayFrame.gray, aw, top, bot);
  const sharp = rowSharpness(crop.gray, aw, 0, bot - top);
  const sig = rowSig(crop.gray, aw, bot - top, BINS);
  return { timeUs: grayFrame.timeUs, sig, sharp };
}

function detectBand(grayFrames, aw, ah) {
  if (!grayFrames || grayFrames.length < 2) return { top: 0, bot: ah, h: ah };
  const step = Math.max(1, Math.floor(grayFrames.length / 60));
  const sub = [];
  for (let i = 0; i < grayFrames.length; i += step) sub.push(grayFrames[i].gray);
  if (sub.length < 2) return { top: 0, bot: ah, h: ah };
  const act = rowActivity(sub, aw, ah);
  return contentBand(act, ah);
}

function resolveDuration(provider, options) {
  let dur = options && options.duration;
  if (dur == null && provider) {
    if (typeof provider.duration === 'number' && Number.isFinite(provider.duration)) dur = provider.duration;
    else if (typeof provider.durationUs === 'number') dur = provider.durationUs / 1e6;
  }
  if (!(dur > 0) && provider && typeof provider.frameCount === 'number' && provider.frameCount > 0 && provider.fps) {
    dur = provider.frameCount / provider.fps;
  }
  if (!(dur > 0)) throw new StitchError('无法确定视频时长，无法抽帧');
  return dur;
}

/** 分批采样并汇报进度，避免一次性申请过多内存。 */
async function sampleBatches(provider, items, batchSize, onProgress, p0, p1, label) {
  const out = [];
  const total = items.length;
  for (let i = 0; i < total; i += batchSize) {
    const batch = items.slice(i, i + batchSize);
    const got = await provider.sample(batch);
    if (got && got.length) out.push(...got);
    const f = Math.min(1, (i + batch.length) / Math.max(1, total));
    onProgress(p0 + (p1 - p0) * f, label);
  }
  return out;
}

/** 补采样计划：把失配的相邻帧对之间的区间加密。 */
function buildRefillPlan(frames, track, fpsBase, round, budget) {
  const targets = new Set();
  const sorted = track.bad.slice().sort((a, b) => (b.score || 0) - (a.score || 0));
  for (const b of sorted) {
    if (targets.size >= 8) break;
    targets.add(b.i);
  }
  if (!targets.size) return [];
  const density = fpsBase * Math.pow(2, round + 1);
  const plan = [];
  let used = 0;
  for (const i of Array.from(targets).sort((a, b) => a - b)) {
    if (i <= 0 || i >= frames.length) continue;
    const t0 = frames[i - 1].timeUs;
    const t1 = frames[i].timeUs;
    if (t1 <= t0) continue;
    const dt = (t1 - t0) / 1e6; // 秒
    const existing = Math.max(1, Math.round(dt * fpsBase));
    let want = Math.round(dt * density);
    if (want < 2) want = 2;
    const add = want - existing;
    if (add <= 0) continue;
    if (used + add > budget) break;
    used += add;
    plan.push({ i, t0Us: t0, t1Us: t1, count: add });
  }
  return plan;
}

/**
 * 分析编排（视频 + 截图共用）。
 * provider 接口：
 *   provider.frameCount / provider.kind ("video"|"static")
 *   async provider.sample(times) -> [{timeUs, gray, width, height}]（gray 为整帧分析灰度）
 *   provider.frameWidth() / provider.frameHeight()（原始帧像素尺寸）
 * options: {fps=10, refineRounds=2, maxFrames=2600, analysisWidth=144, minOverlap=0.05}
 * -> AnalysisResult {aw, ah, band, frames, track, frameWidth, frameHeight, missingCount, stats, kind}
 */
export async function analyze(provider, options = {}, onProgress) {
  if (!provider || typeof provider.sample !== 'function') throw new StitchError('缺少帧来源（provider）');
  const opts = options || {};
  const progress = typeof onProgress === 'function' ? onProgress : () => {};
  const awWanted = Math.max(32, Math.round(opts.analysisWidth || ANALYSIS_WIDTH));
  const fps = Math.max(0.5, Number(opts.fps) || 10);
  const refineRounds = Math.max(0, opts.refineRounds == null ? 2 : opts.refineRounds);
  const maxFrames = Math.max(2, opts.maxFrames == null ? 2600 : opts.maxFrames);
  const stop = typeof opts.shouldStop === 'function' ? opts.shouldStop : null;
  const isStatic = provider.kind === 'static';

  if (provider.setAnalysisWidth) {
    try {
      provider.setAnalysisWidth(awWanted);
    } catch {
      /* provider 可忽略 */
    }
  }

  // ---- 1. 首轮采样 ----
  progress(0.01, isStatic ? '读取截图' : '抽取视频帧');
  let grayFrames;
  if (isStatic) {
    const count = Math.max(0, provider.frameCount | 0);
    if (count < 1) throw new StitchError('没有可用的截图');
    const indices = [];
    if (count <= maxFrames) {
      for (let i = 0; i < count; i++) indices.push(i);
    } else {
      // 帧数超上限时均匀取样，保留首尾
      for (let i = 0; i < maxFrames; i++) indices.push(Math.round((i * (count - 1)) / (maxFrames - 1)));
    }
    grayFrames = await sampleBatches(provider, indices, 32, progress, 0.02, 0.30, '读取截图');
  } else {
    const duration = resolveDuration(provider, opts);
    const total = Math.max(2, Math.min(maxFrames, Math.round(duration * fps)));
    const times = [];
    for (let i = 0; i < total; i++) {
      let t = ((i + 0.5) * duration) / total;
      if (t < 0) t = 0;
      if (t > duration - 0.005) t = Math.max(0, duration - 0.005);
      times.push(t);
    }
    grayFrames = await sampleBatches(provider, times, 32, progress, 0.02, 0.30, '抽取视频帧');
  }
  if (stop && stop()) throw new StitchError('已取消');
  if (!grayFrames || grayFrames.length < 2) throw new StitchError('有效帧不足，无法拼接');
  if (grayFrames.length > maxFrames) grayFrames = grayFrames.slice(0, maxFrames);
  grayFrames.sort((a, b) => a.timeUs - b.timeUs);

  // ---- 2. 内容区检测 + 每帧签名 ----
  const aw = Math.max(16, Math.round(grayFrames[0].width || awWanted));
  const ah = Math.max(1, Math.round(grayFrames[0].height || Math.round((aw * (provider.frameHeight() || aw)) / Math.max(1, provider.frameWidth() || aw))));
  const band = detectBand(grayFrames, aw, ah);
  let sigFrames = grayFrames.map((f) => prepOne(f, aw, band));
  grayFrames = null; // 释放整帧灰度图，只保留签名

  // ---- 3. 逐帧位移 ----
  progress(0.32, '分析滚动位移');
  const trackOpts = {
    bins: BINS,
    minOverlap: opts.minOverlap == null ? 0.05 : Number(opts.minOverlap),
    minNcc: opts.minNcc == null ? 0.60 : Number(opts.minNcc),
    tolRatio: opts.tolRatio == null ? 0.02 : Number(opts.tolRatio),
  };
  let track = trackOffsets(sigFrames, band.h, trackOpts);

  // ---- 4. 视频：对失配区间加密补采样（静态截图不做） ----
  let round = 0;
  while (!isStatic && track.bad.length && round < refineRounds && sigFrames.length < maxFrames) {
    if (stop && stop()) throw new StitchError('已取消');
    const budget = Math.min(maxFrames - sigFrames.length, Math.max(24, track.bad.length * 8));
    const plan = buildRefillPlan(sigFrames, track, fps, round, budget);
    if (!plan.length) break;
    const times = [];
    for (const p of plan) {
      const t0 = p.t0Us / 1e6;
      const t1 = p.t1Us / 1e6;
      for (let j = 1; j <= p.count; j++) times.push(t0 + ((t1 - t0) * j) / (p.count + 1));
    }
    const more = await sampleBatches(provider, times, 24, progress, 0, 0, '加密补帧');
    if (!more.length) break;
    const existing = new Set(sigFrames.map((f) => Math.round(f.timeUs / 1000)));
    const add = [];
    for (const f of more) {
      const key = Math.round(f.timeUs / 1000);
      if (existing.has(key)) continue;
      existing.add(key);
      add.push(prepOne(f, aw, band));
    }
    if (!add.length) break;
    sigFrames = sigFrames.concat(add).sort((a, b) => a.timeUs - b.timeUs);
    if (sigFrames.length > maxFrames) sigFrames = sigFrames.slice(0, maxFrames);
    track = trackOffsets(sigFrames, band.h, trackOpts);
    round++;
    progress(0.35 + (0.55 * round) / Math.max(1, refineRounds), `细化对齐（第 ${round} 轮）`);
  }

  progress(0.95, '对齐完成');
  const stats = {
    ...overlapStats(track, band.h),
    frameCount: sigFrames.length,
    badCount: track.bad.length,
    medCost: track.medCost,
    refineRounds: round,
  };
  return {
    aw,
    ah,
    band,
    frames: sigFrames,
    track,
    frameWidth: Math.round(provider.frameWidth ? provider.frameWidth() : aw),
    frameHeight: Math.round(provider.frameHeight ? provider.frameHeight() : ah),
    missingCount: 0,
    kind: isStatic ? 'static' : 'video',
    stats,
  };
}

// ---------------------------------------------------------------------------
// 全局一致性校验（防重复内容把整体位移带偏）
// ---------------------------------------------------------------------------

/** 全局单调性校验失败时的统一错误文案（UI 直接展示，引导用户重试）。 */
const GLOBAL_MONO_MSG =
  '截图/录屏内容存在重复片段，无法稳定对齐。请放慢滚动速度或重新录制后重试。';

/**
 * 全局单调性兜底校验：逐对 NCC 在重复气泡/重复内容上可能给出很高相似度却匹配到错误位移，
 * 局部质量门（badRatio/badCount）抓不到“整体位移折返/跳错”。这里在生成主图后统一检查：
 *
 *  1. track.pos 换算到主图坐标系后必须逐帧不回退；相邻位移范围：
 *     - 上限 maxStep = 0.98*bandH：位移超过一屏说明中间内容没拍到（跳帧）；
 *     - 下限 minStep = max(4, 0.05*bandH) 只对静态截图生效：截图一屏一张，
 *       位移过小说明重叠远超产品范围（5%~50%）；视频是 10fps 稠密采样，
 *       慢速匀速滚动时相邻帧位移天然可能很小，不能套用该下限。
 *     - 静止/重复帧（|位移|<=1% bandH 且 ncc>=0.92，如录屏停顿、滚到底重复截图）允许。
 *  2. ops 按 y0 升序后 frame 序号单调不减（容忍 ≤1 帧交错）。
 *  3. ops 覆盖区间不重叠（后一个 op 的 y0 >= 前一个 op 的 y1 - 2px 容差）。
 *
 * 任何违例 -> 抛 StitchError，让上层回退原始截图/提示重试，而不是输出错位长图。
 */
export function validateGlobalMonotonic(analysis, plan) {
  if (!analysis) return;
  const fail = () => {
    throw new StitchError(GLOBAL_MONO_MSG);
  };
  const planObj = plan || {};
  const band = analysis.band || {};
  const bandH = Math.max(
    1,
    Math.round(planObj.bandH || band.h || (band.bot || 0) - (band.top || 0) || analysis.ah || 1),
  );
  const track = analysis.track || {};
  const pos = track.pos;
  // 视频是稠密采样，minStep 不适用（见上）；静态截图（含未标注 kind 的兼容情况）严格按产品范围检查。
  const isStatic = analysis.kind !== 'video';
  const posMin = Number.isFinite(planObj.posMin) ? planObj.posMin : 0;
  const scale = Number.isFinite(planObj.scale) && planObj.scale > 0 ? planObj.scale : 1;

  // ---- 1. 位移单调 + 步长范围（分析行 -> 主图像素坐标） ----
  if (pos && pos.length > 1) {
    const minStep = Math.max(4, 0.05 * bandH);
    const maxStep = 0.98 * bandH;
    const stationaryEps = Math.max(1.5, 0.01 * bandH);
    let prevPos = Number(pos[0]);
    if (!Number.isFinite(prevPos)) fail();
    let prevMaster = (prevPos - posMin) * scale;
    for (let i = 1; i < pos.length; i++) {
      const cur = Number(pos[i]);
      if (!Number.isFinite(cur)) fail();
      const step = cur - prevPos;
      const master = (cur - posMin) * scale;
      if (!(master >= prevMaster - stationaryEps * scale)) fail(); // 主图坐标回退
      const ncc = track.ncc && Number.isFinite(track.ncc[i]) ? track.ncc[i] : 1;
      if (Math.abs(step) <= stationaryEps && ncc >= 0.92) {
        // 静止帧/重复截图：位置不变但内容几乎一致，没有“错位”风险，直接放过。
        prevPos = cur;
        prevMaster = master;
        continue;
      }
      if (step <= 0) fail(); // 折返，或者零位移却不是重复帧（匹配到了别处）
      if (step > maxStep) fail(); // 位移超过一屏：中间内容没拍到/匹配错位
      if (isStatic && step < minStep) fail(); // 静态截图位移过小：重叠远超产品范围
      prevPos = cur;
      prevMaster = master;
    }
  }

  // ---- 2/3. ops 帧序 & 覆盖区间（先按 y0 排序，容忍输入乱序） ----
  const ops = Array.isArray(planObj.ops)
    ? planObj.ops.slice().sort((a, b) => a.y0 - b.y0 || a.y1 - b.y1)
    : null;
  if (ops && ops.length) {
    let prevFrame = -1;
    let prevEnd = -Infinity;
    for (const op of ops) {
      if (!op || !Number.isFinite(op.y0) || !Number.isFinite(op.y1) || op.y1 < op.y0) fail();
      if (op.frame >= 0) {
        // 允许相邻 1 帧交错：重复内容附近两帧的清晰度互有胜负时会小范围互相穿插。
        if (prevFrame >= 0 && op.frame < prevFrame - 1) fail();
        prevFrame = op.frame;
      }
      if (prevEnd > -Infinity && op.y0 < prevEnd - 2) fail(); // 覆盖区间重叠
      if (op.y1 > prevEnd) prevEnd = op.y1;
    }
  }
}

// ---------------------------------------------------------------------------
// 主图规划
// ---------------------------------------------------------------------------

/**
 * 把分析结果变成主图 + 切页方案（记录每行像素来自哪一帧）。
 * cropTopPx/cropBotPx 基于原始帧像素；tileH 为目标每页像素高（通常 = cropHpx）。
 * -> Plan {topPx,botPx,cropHpx,masterH,ops,cuts,pageCount,missingPx,bandTop,bandH,
 *          frameCount,badCount,scale,stats,frames,width,frameWidth,frameHeight}
 */
export function planMaster(analysis, cropTopPxIn, cropBotPxIn, tileH) {
  if (!analysis || !analysis.frames || !analysis.track) throw new StitchError('分析结果不完整，无法规划主图');
  const vw = Math.max(1, Math.round(analysis.frameWidth || analysis.aw));
  const vh = Math.max(1, Math.round(analysis.frameHeight || analysis.ah));
  const ah = Math.max(1, analysis.ah | 0);
  const scaleAll = vh / ah;
  let topPx = cropTopPxIn == null ? 0 : Math.round(cropTopPxIn);
  let botPx = cropBotPxIn == null ? vh : Math.round(cropBotPxIn);
  topPx = clampInt(topPx, 0, Math.max(0, vh - 16));
  botPx = clampInt(botPx, topPx + 16, vh);
  let cTop = clampInt(Math.round(topPx / scaleAll), 0, Math.max(0, ah - 8));
  let cBot = clampInt(Math.round(botPx / scaleAll), cTop + 8, ah);
  if (cBot - cTop < 16) {
    cTop = 0;
    cBot = ah;
  }
  const cropHpx = Math.max(1, botPx - topPx);
  const scale = cropHpx / (cBot - cTop);
  const track = analysis.track;
  const sharp = new Float64Array(analysis.frames.length);
  for (let i = 0; i < analysis.frames.length; i++) sharp[i] = analysis.frames[i].sharp || 0;
  const pos = track.pos;
  const rm = buildRowMap(sharp, pos, cTop, cBot);
  const masterH = Math.max(1, Math.round(rm.height * scale));
  const band = analysis.band || { top: cTop, bot: cBot, h: cBot - cTop };
  const bandH = Math.max(1, band.h || (band.bot || 0) - (band.top || 0) || 1);

  // ---- 主图每行像素来源 -> 像素坐标 ops ----
  const rawOps = mapToOps(rm.map, pos, cTop, rm.posMin);
  const ops = [];
  let prevEnd = 0;
  for (const o of rawOps) {
    let y0 = clampInt(Math.round(o.y0 * scale), prevEnd, masterH);
    let y1 = Math.min(masterH, Math.round(o.y1 * scale));
    if (y1 <= y0) continue;
    let h = y1 - y0;
    if (h > cropHpx) {
      y1 = y0 + cropHpx;
      if (y1 > masterH) {
        y1 = masterH;
        y0 = Math.max(prevEnd, y1 - cropHpx);
      }
    }
    let src = Math.round(o.src * scale);
    if (src < 0) src = 0;
    if (src > cropHpx - (y1 - y0)) src = cropHpx - (y1 - y0);
    if (src < 0) src = 0;
    ops.push({ y0, y1, frame: o.frame, src });
    prevEnd = y1;
    if (prevEnd >= masterH) break;
  }

  // 全局单调性兜底：重复内容可能让局部 NCC 很高却折返/跳错位移，必须在出图前拦下。
  validateGlobalMonotonic(analysis, { ops, bandH, scale, cropHpx, posMin: rm.posMin, masterH });

  // ---- 切页：把每帧「行活跃度」映射到主图坐标系，挑消息之间的空行切 ----
  const frameAct = new Array(analysis.frames.length);
  for (let i = 0; i < analysis.frames.length; i++) {
    frameAct[i] = sigActivity(analysis.frames[i].sig, bandH, BINS);
  }
  const actMaster = new Float32Array(masterH);
  for (const o of ops) {
    const fa = frameAct[o.frame];
    if (!fa) continue;
    for (let y = o.y0; y < o.y1; y++) {
      const srcPx = o.src + (y - o.y0);
      const bandRow = clampInt(Math.round(cTop + (srcPx + 0.5) / scale - band.top), 0, bandH - 1);
      actMaster[y] = fa[bandRow];
    }
  }
  const effTileH = tileH > 0 ? Math.round(tileH) : cropHpx;
  const win = Math.max(6, Math.round(effTileH * 0.1));
  const cuts = cutPoints(actMaster, masterH, effTileH, win);
  const stats = {
    ...overlapStats(track, bandH),
    frameCount: analysis.frames.length,
    badCount: track.bad.length,
    scale,
    cropScale: scale,
  };
  return {
    topPx,
    botPx,
    cropHpx,
    masterH,
    ops,
    cuts,
    pageCount: cuts.length - 1,
    missingPx: Math.round(rm.missing * scale),
    bandTop: band.top,
    bandH,
    frameCount: analysis.frames.length,
    badCount: track.bad.length,
    scale,
    cTop,
    cBot,
    stats,
    // 以下为额外字段（供渲染器使用，不改变契约必需字段）：
    width: vw,
    frameWidth: vw,
    frameHeight: vh,
    frames: analysis.frames,
    medianOverlapRatio: stats.medianOverlapRatio,
  };
}

// ---------------------------------------------------------------------------
// 渲染分页
// ---------------------------------------------------------------------------

/**
 * 把主图的一段 [y0,y1) 画到已创建好的 canvas 上（canvas.width = 原图宽，高 = y1-y0）。
 * getFrame(frameIndex) -> HTMLImageElement/ImageBitmap/HTMLCanvasElement，
 *   或 {image, sxTop}；sxTop 是源图的裁剪区起始行（整帧截图 = plan.topPx，已裁好的帧 = 0）。
 * 渲染器只需关心自己如何提供「按帧取图」，分页/ops 遍历统一在这里做（供 video.js / adbcap.js 复用）。
 */
export async function drawPageOps(canvas, plan, y0, y1, getFrame) {
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new StitchError('无法创建绘图上下文');
  const width = canvas.width;
  const height = canvas.height;
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, width, height);
  const ops = plan.ops || [];
  // ops 按 y 递增；二分找到第一个覆盖 y0 的段
  let lo = 0;
  let hi = ops.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (ops[mid].y1 <= y0) lo = mid + 1;
    else hi = mid;
  }
  let lastFrame = -1;
  let lastImg = null;
  let lastSxTop = 0;
  for (let k = lo; k < ops.length; k++) {
    const op = ops[k];
    if (op.y0 >= y1) break;
    if (op.frame < 0) continue;
    const from = Math.max(op.y0, y0);
    const to = Math.min(op.y1, y1);
    if (to <= from) continue;
    let img = null;
    let sxTop;
    if (op.frame === lastFrame && lastImg) {
      img = lastImg;
      sxTop = lastSxTop;
    } else {
      const info = await getFrame(op.frame);
      if (!info) continue;
      img = info.image || info;
      sxTop = info.sxTop != null ? info.sxTop : plan.topPx || 0;
      lastFrame = op.frame;
      lastImg = img;
      lastSxTop = sxTop;
    }
    const len = to - from;
    const imgH = Math.round(img.naturalHeight || img.height || 0);
    let sy = sxTop + op.src + (from - op.y0);
    const maxSy = Math.max(0, imgH - len);
    sy = clampInt(sy, 0, maxSy);
    ctx.drawImage(img, 0, sy, width, len, 0, from - y0, width, len);
  }
}

/**
 * 按 Plan 逐页渲染：renderer.pageImage(pageIndex, plan, y0, y1) 返回该页 canvas。
 * onPage(page, pageIndex)：page = {index,y0,y1,width,height,canvas}。
 * 逐页 await，保证同一时刻只有一页整图在内存里（低配电脑友好）。-> 返回页数
 */
export async function renderPages(plan, renderer, onPage, onProgress) {
  if (!plan || !renderer || typeof renderer.pageImage !== 'function') {
    throw new StitchError('渲染参数不完整');
  }
  const progress = typeof onProgress === 'function' ? onProgress : () => {};
  const cuts = plan.cuts;
  const hasCuts = cuts && cuts.length > 1;
  const masterH = Math.max(1, Math.round(plan.masterH || 1));
  const pageH = Math.max(1, Math.round(renderer.height || plan.cropHpx || masterH));
  const n = hasCuts ? cuts.length - 1 : Math.max(1, Math.ceil(masterH / pageH));
  for (let i = 0; i < n; i++) {
    const y0 = hasCuts ? cuts[i] : Math.round((i * masterH) / n);
    const y1 = hasCuts ? cuts[i + 1] : Math.round(((i + 1) * masterH) / n);
    progress(i / Math.max(1, n), `渲染第 ${i + 1}/${n} 页`);
    const canvas = await renderer.pageImage(i, plan, y0, y1);
    if (!canvas) continue;
    const page = {
      index: i,
      y0,
      y1,
      width: canvas.width || renderer.width,
      height: canvas.height || Math.max(1, y1 - y0),
      canvas,
    };
    if (onPage) await onPage(page, i);
  }
  progress(1, '渲染完成');
  return n;
}
