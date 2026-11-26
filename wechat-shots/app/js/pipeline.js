// pipeline.js - 视频抽帧 + 连续滚动重建（只在浏览器里跑）。
import * as core from './core.js';

export const ANALYSIS_WIDTH = 144;
const BINS = 8;

export function seekTo(video, t) {
  return new Promise((resolve) => {
    let finished = false;
    function finish() {
      if (finished) return;
      finished = true;
      video.removeEventListener('seeked', onSeeked);
      let done = false;
      const go = () => { if (!done) { done = true; resolve(); } };
      requestAnimationFrame(() => requestAnimationFrame(go));
      setTimeout(go, 240);
    }
    function onSeeked() { finish(); }
    video.addEventListener('seeked', onSeeked);
    setTimeout(finish, 6000);
    const target = Math.min(Math.max(0, t), Math.max(0, (video.duration || 0) - 0.02));
    if (Math.abs(video.currentTime - target) < 0.001) { finish(); return; }
    video.currentTime = target;
  });
}

function makeCanvas(w, h) {
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w));
  c.height = Math.max(1, Math.round(h));
  return c;
}

function canvasBlob(canvas, format, quality) {
  const mime = format === 'jpeg' ? 'image/jpeg' : 'image/png';
  return new Promise((res) => canvas.toBlob(res, mime, quality || 0.94));
}

// 抽一段时间的帧，返回浅色小图（还没算签名，签名要等内容区确定后统一算）。
export async function sampleFrames(video, opts, hooks) {
  const AW = opts.analysisWidth || ANALYSIS_WIDTH;
  const vw = video.videoWidth, vh = video.videoHeight;
  const AH = Math.max(16, Math.round(AW * vh / vw));
  const dur = video.duration || 0;
  const t0 = Math.max(0, opts.t0 || 0);
  const t1 = Math.min(dur - 0.03, opts.t1 == null ? dur : opts.t1);
  const fps = Math.max(0.5, opts.fps || 8);
  const n = Math.max(2, Math.round(Math.max(0.05, t1 - t0) * fps));
  const canvas = makeCanvas(AW, AH);
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const frames = [];
  for (let i = 0; i < n; i++) {
    if (hooks && hooks.stop && hooks.stop()) break;
    const t = t0 + (i + 0.5) * (t1 - t0) / n;
    await seekTo(video, t);
    ctx.drawImage(video, 0, 0, AW, AH);
    frames.push({ t, gray: core.grayFromRGBA(ctx.getImageData(0, 0, AW, AH).data, AW, AH) });
    if (hooks && hooks.progress) hooks.progress((i + 1) / n, opts.label || '抽帧');
  }
  return { frames, AW, AH, vw, vh, dur };
}

export function detectBand(frames, AW, AH) {
  const step = Math.max(1, Math.floor(frames.length / 60));
  const sub = [];
  for (let i = 0; i < frames.length; i += step) sub.push(frames[i].gray);
  if (sub.length < 2) return { top: 0, bot: AH, h: AH };
  const act = core.rowActivity(sub, AW, AH);
  return contentBand(act, AH);
}

// 静态状态栏/输入栏在任何偏移下都自匹配，会把它拖向 s=0；只取真正在滚动的条带。
export function contentBand(act, AH) {
  if (!act || act.length < AH) return { top: 0, bot: AH, h: AH };
  let peak = 0;
  for (let y = 0; y < AH; y++) if (act[y] > peak) peak = act[y];
  if (!(peak > 0)) return { top: 0, bot: AH, h: AH };
  const thr = Math.max(0.6, peak * 0.18);
  let top = 0, bot = AH;
  for (let y = 0; y < AH; y++) if (act[y] >= thr) { top = y; break; }
  for (let y = AH - 1; y >= 0; y--) if (act[y] >= thr) { bot = y + 1; break; }
  if (bot - top < 32) return { top: 0, bot: AH, h: AH };
  return { top, bot, h: bot - top };
}

// 算每帧的行签名（只在内容条带里）和清晰度，然后丢掉灰度图省内存。
export function prepFrames(frames, AW, AH, band) {
  for (const f of frames) {
    if (!f.gray) continue;
    f.sharp = core.rowSharpness(f.gray, AW, band.top, band.bot);
    f.sig = core.rowSig(core.cropGray(f.gray, AW, AH, band.top, band.bot), AW, band.h, BINS);
    f.gray = null;
  }
}

function badIntervals(frames, track, maxK) {
  const out = [];
  for (const b of track.bad) {
    const i = b.i;
    if (i < 1 || i >= frames.length) continue;
    out.push({ i, score: b.score == null ? (isFinite(b.cost) ? b.cost : 1e9) : b.score, t0: frames[i - 1].t, t1: frames[i].t });
  }
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, maxK);
}

// 需要补帧的区间：每个跟丢的相邻帧对之间插 n 帧。轮次越深插得越密。
// 单独抽出来是为了能在纯 node 里测试（不依赖 <video>）。
export function refillPlan(frames, track, fpsBase, round, budget) {
  const iv = badIntervals(frames, track, 60);
  const sub = round === 0 ? 4 : 8;
  const out = [];
  let used = 0;
  for (const it of iv) {
    const dt = it.t1 - it.t0;
    if (dt < 1e-4) continue;
    const n = Math.max(2, Math.min(16, Math.round(dt * fpsBase * sub)));
    if (used + n > budget) break;
    used += n;
    out.push({ t0: it.t0, t1: it.t1, n, score: it.score });
  }
  return out;
}

// 分析整段录屏：抽帧 → 找内容区 → 逐帧位移 → 对跳变/失配的区间加密重抽（最多两轮）。
export async function analyze(video, opts, hooks) {
  const AW = opts.analysisWidth || ANALYSIS_WIDTH;
  const fpsBase = Math.max(1, opts.fps || 10);
  const label = opts.label || '';
  const first = await sampleFrames(video, { fps: fpsBase, analysisWidth: AW, label: label + '抽帧' }, {
    progress: (f, t) => hooks && hooks.progress && hooks.progress(f * 0.7, t),
    stop: hooks && hooks.stop,
  });
  let frames = first.frames;
  const { AH, vw, vh } = first;
  if (!frames.length) throw new Error('没能从视频里抽到画面');
  const band = detectBand(frames, AW, AH);
  prepFrames(frames, AW, AH, band);

  let track = core.trackOffsets(frames.map((f) => f.sig), band.h, { bins: BINS });

  const rounds = opts.refineRounds == null ? 3 : opts.refineRounds;
  const maxFrames = opts.maxFrames || 2600;
  for (let r = 0; r < rounds && track.bad.length && frames.length < maxFrames; r++) {
    const plan = refillPlan(frames, track, fpsBase, r, Math.max(24, maxFrames - frames.length));
    if (!plan.length) break;
    const add = [];
    for (const it of plan) {
      if (hooks && hooks.stop && hooks.stop()) break;
      const sub = await sampleFrames(video, {
        fps: it.n / (it.t1 - it.t0), t0: it.t0, t1: it.t1, analysisWidth: AW,
        label: '加密补帧 ' + (r + 1),
      }, {
        progress: (f) => { if (hooks && hooks.progress) hooks.progress(0.7 + f * 0.1, '加密补帧 ' + (r + 1)); },
        stop: hooks && hooks.stop,
      });
      add.push(...sub.frames);
    }
    if (!add.length) break;
    prepFrames(add, AW, AH, band);
    frames = frames.concat(add).sort((a, b) => a.t - b.t);
    if (frames.length > maxFrames) frames = frames.slice(0, maxFrames);
    track = core.trackOffsets(frames.map((f) => f.sig), band.h, { bins: BINS });
  }
  if (hooks && hooks.progress) hooks.progress(1, '对齐完成');
  return { frames, AW, AH, vw, vh, dur: first.dur, band, track };
}

// 把像素坐标里的裁剪区域换成主图（整段聊天内容拼成的一张大图）。
export function planMaster(sample, track, cropPx) {
  const { AW, AH, vw, vh, frames } = sample;
  const scaleAll = vh / AH;
  let topPx = Math.max(0, Math.min(vh - 16, Math.round(cropPx.topPx)));
  let botPx = Math.max(topPx + 16, Math.min(vh, Math.round(cropPx.botPx)));
  let cTop = Math.max(0, Math.min(AH - 8, Math.round(topPx / scaleAll)));
  let cBot = Math.max(cTop + 8, Math.min(AH, Math.round(botPx / scaleAll)));
  if (cBot - cTop < 16) { cTop = 0; cBot = AH; topPx = 0; botPx = vh; }
  const cropHpx = botPx - topPx;
  const scale = cropHpx / (cBot - cTop);
  const sharp = frames.map((f) => (f.sharp == null ? 0 : f.sharp));
  const rm = core.buildRowMap(sharp, track.pos, cTop, cBot);
  const raw = core.mapToOps(rm.map, track.pos, cTop, rm.posMin);
  const MP = Math.round(rm.H * scale);
  const ops = [];
  let prevEnd = 0;
  for (const o of raw) {
    let y0 = Math.max(prevEnd, Math.min(MP, Math.round(o.y0 * scale)));
    let y1 = Math.max(y0, Math.min(MP, Math.round(o.y1 * scale)));
    if (y1 <= y0) continue;
    let src = Math.round(o.src * scale);
    src = Math.max(0, Math.min(cropHpx - (y1 - y0), src));
    ops.push({ y0, y1, frame: o.frame, src });
    prevEnd = y1;
  }
  // 逐行的「内容离散度」，用来把截图切在消息之间的空行上（不劈开一条消息）。
  const band = sample.band || { top: cTop, bot: cBot, h: cBot - cTop };
  const acts = frames.map((f) => (f.sig ? core.sigActivity(f.sig, band.h, BINS) : null));
  const actPx = new Float32Array(MP).fill(1e9);
  for (const o of ops) {
    if (o.frame < 0 || !acts[o.frame]) continue;
    const a = acts[o.frame];
    for (let d = 0, len = o.y1 - o.y0; d < len; d++) {
      let row = Math.round(cTop + (o.src + d) / scale) - band.top;
      if (row < 0) row = 0; else if (row >= band.h) row = band.h - 1;
      actPx[o.y0 + d] = a[row];
    }
  }
  const cuts = core.cutPoints(actPx, MP, cropHpx, { win: Math.round(cropHpx * 0.10) });
  if (typeof window !== 'undefined' && window.__DBG_PLAN) { window.__actPx = actPx; window.__lastCuts = cuts; }
  return {
    topPx, botPx, cropHpx, MP, ops, scale, cTop, cBot, cuts,
    missingPx: Math.round(rm.missing * scale),
    frames: frames.length,
    bad: track.bad.length,
  };
}

function makeRenderer(video, frames, topPx, cropHpx, vw) {
  const cache = new Map();
  return {
    async get(i) {
      const hit = cache.get(i);
      if (hit) return hit;
      const f = frames[i];
      if (!f) return null;
      await seekTo(video, f.t);
      const c = makeCanvas(vw, cropHpx);
      const ctx = c.getContext('2d');
      ctx.drawImage(video, 0, topPx, vw, cropHpx, 0, 0, vw, cropHpx);
      cache.set(i, c);
      while (cache.size > 5) {
        const k = cache.keys().next().value;
        if (k === i) break;
        cache.delete(k);
      }
      return c;
    },
  };
}

function lowerBound(ops, y) {
  let lo = 0, hi = ops.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (ops[mid].y1 <= y) lo = mid + 1; else hi = mid;
  }
  return lo;
}

async function renderRange(renderer, ops, Y0, Y1, vw, format, quality, bg) {
  const h = Math.max(1, Y1 - Y0);
  const cnv = makeCanvas(vw, h);
  const ctx = cnv.getContext('2d');
  ctx.fillStyle = bg || '#ffffff';
  ctx.fillRect(0, 0, vw, h);
  for (let k = lowerBound(ops, Y0); k < ops.length; k++) {
    const op = ops[k];
    if (op.y0 >= Y1) break;
    if (op.frame < 0) continue; // 没帧覆盖的缺口，留白
    const a = Math.max(op.y0, Y0);
    const b = Math.min(op.y1, Y1);
    if (b <= a) continue;
    const src = await renderer.get(op.frame);
    if (!src) continue;
    const sy = op.src + (a - op.y0);
    ctx.drawImage(src, 0, sy, vw, b - a, 0, a - Y0, vw, b - a);
  }
  const blob = await canvasBlob(cnv, format, quality);
  return { canvas: cnv, blob, w: vw, h };
}

// 把主图按固定高度切成截图：每张都是主图上连续、互不重叠的一段 → 天然不漏不重。
export async function exportTiles(video, sample, plan, opts, hooks) {
  const { vw } = sample;
  const T = Math.max(64, Math.round(opts.tileH || plan.cropHpx));
  const cuts = plan.cuts && plan.cuts.length > 1 ? plan.cuts : null;
  const n = cuts ? cuts.length - 1 : Math.max(1, Math.ceil(plan.MP / T));
  const renderer = makeRenderer(video, sample.frames, plan.topPx, plan.cropHpx, vw);
  const pad = n >= 1000 ? 4 : 3;
  const ext = opts.format === 'jpeg' ? 'jpg' : 'png';
  const out = [];
  for (let k = 0; k < n; k++) {
    if (hooks && hooks.stop && hooks.stop()) break;
    const Y0 = cuts ? cuts[k] : k * T;
    const Y1 = cuts ? cuts[k + 1] : Math.min(plan.MP, Y0 + T);
    const r = await renderRange(renderer, plan.ops, Y0, Y1, vw, opts.format, opts.quality);
    out.push({
      blob: r.blob, w: r.w, h: r.h, index: k, y0: Y0,
      name: 'shot_' + String(k + 1).padStart(pad, '0') + '.' + ext,
    });
    if (hooks && hooks.progress) hooks.progress((k + 1) / n, '导出截图 ' + (k + 1) + '/' + n);
  }
  return out;
}

// 整条聊天记录拼成超长图，按 maxSegH 分段（浏览器 canvas 单张有高度上限）。
export async function exportLong(video, sample, plan, opts, hooks) {
  const { vw } = sample;
  const SEG = Math.max(2000, opts.maxSegH || 12000);
  const n = Math.max(1, Math.ceil(plan.MP / SEG));
  const renderer = makeRenderer(video, sample.frames, plan.topPx, plan.cropHpx, vw);
  const ext = opts.format === 'jpeg' ? 'jpg' : 'png';
  const segs = [];
  for (let k = 0; k < n; k++) {
    if (hooks && hooks.stop && hooks.stop()) break;
    const Y0 = k * SEG;
    const Y1 = Math.min(plan.MP, Y0 + SEG);
    const r = await renderRange(renderer, plan.ops, Y0, Y1, vw, opts.format, opts.quality);
    segs.push({
      blob: r.blob, w: r.w, h: r.h,
      name: 'chat_long_' + String(k + 1).padStart(2, '0') + '.' + ext,
    });
    if (hooks && hooks.progress) hooks.progress((k + 1) / n, '拼接长图 ' + (k + 1) + '/' + n);
  }
  return { segs, total: plan.MP };
}
