// js/adbcap.js —— 截图模式：手机截图帧源 + 固定 UI 栏检测 + 静态拼接/分页渲染
//
// 契约见 docs/CONTRACTS.md「js/adbcap.js」。截图由后端 /api/screencap 落盘，
// 前端从 /media/shot/<sid>/<shotId>.png 取图；shots 数组按截图顺序（最新→最旧），
// 这里统一反转成旧→新交给算法。
// 低配电脑优先：采样串行、图片解码后立即缩放成分析灰度；渲染时整图最多缓存 3 张。

import {
  ANALYSIS_WIDTH,
  StitchError,
  analyze,
  drawPageOps,
  grayFromRGBA,
  renderPages,
  trackOffsets,
} from './stitch.js';

// 方便 UI 只 import 本模块也能统一 catch 拼接错误
export { StitchError };

/** 后端截图路由（与 api.js 的 shotUrl 保持一致，这里独立实现避免反向依赖 UI 模块）。 */
export function shotUrl(sid, shotId) {
  return `/media/shot/${encodeURIComponent(String(sid))}/${encodeURIComponent(String(shotId))}.png`;
}

const CACHE_LIMIT = 3;

/** 取一张原分辨率截图：优先 fetch+createImageBitmap，失败回退 <img>。 */
async function loadShotImage(url) {
  if (typeof createImageBitmap === 'function' && typeof fetch === 'function') {
    try {
      const resp = await fetch(url);
      if (resp.ok) {
        const blob = await resp.blob();
        return await createImageBitmap(blob);
      }
    } catch {
      /* 回退 <img> 再试一次 */
    }
  }
  if (typeof Image === 'undefined') throw new StitchError('当前环境不支持图片解码');
  const img = new Image();
  img.decoding = 'async';
  await new Promise((resolve, reject) => {
    img.onload = () => resolve();
    img.onerror = () => reject(new StitchError('截图加载失败，文件可能已损坏'));
    img.src = url;
  });
  if (typeof img.decode === 'function') {
    try {
      await img.decode();
    } catch {
      /* 已 onload，解码失败也不影响继续 */
    }
  }
  return img;
}

function imageWidth(img) {
  return Math.round(img.naturalWidth || img.width || 0);
}

function imageHeight(img) {
  return Math.round(img.naturalHeight || img.height || 0);
}

function releaseImage(img) {
  if (img && typeof img.close === 'function') {
    try {
      img.close();
    } catch {
      /* ignore */
    }
  }
}

/** 把一张截图缩成分析灰度（aw 宽，高度按比例），返回 {timeUs, gray, width, height}。 */
async function imageToGray(img, timeUs, targetWidth) {
  const iw = imageWidth(img);
  const ih = imageHeight(img);
  if (!iw || !ih) throw new StitchError('截图尺寸异常，无法拼接');
  const aw = Math.max(16, Math.min(Math.round(targetWidth) || ANALYSIS_WIDTH, iw));
  const ah = Math.max(1, Math.round((ih * aw) / iw));
  if (typeof document === 'undefined') throw new StitchError('当前环境不支持图片解码');
  const canvas = document.createElement('canvas');
  canvas.width = aw;
  canvas.height = ah;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) throw new StitchError('无法创建绘图上下文');
  ctx.drawImage(img, 0, 0, aw, ah);
  const data = ctx.getImageData(0, 0, aw, ah);
  return { timeUs, gray: grayFromRGBA(data.data, aw, ah), width: aw, height: ah };
}

/**
 * 静态截图帧源。shots 按截图顺序「最新→最旧」，内部反转成旧→新。
 * 每项至少包含 {id}（也兼容 {shotId}），可选 {width,height}（后端 screencap 已给出原始像素）。
 */
export class ShotProvider {
  constructor(sid, shots) {
    if (!sid) throw new StitchError('缺少截图会话');
    const list = Array.isArray(shots) ? shots.slice() : [];
    // 契约：传入「最新→最旧」，算法要求「旧→新」
    this.shots = list
      .reverse()
      .map((s, i) => {
        if (!s) return null;
        const id = s.id != null ? s.id : s.shotId != null ? s.shotId : s.name;
        return {
          id: id != null ? id : `shot${i + 1}`,
          width: Math.round(Number(s.width) || 0),
          height: Math.round(Number(s.height) || 0),
        };
      })
      .filter(Boolean);
    this.sid = String(sid);
    this.kind = 'static';
    this.frameCount = this.shots.length;
    this._analysisWidth = ANALYSIS_WIDTH;
    const first = this.shots[0];
    this._width = first && first.width > 0 ? first.width : 0;
    this._height = first && first.height > 0 ? first.height : 0;
  }

  /** analyze() 会调用；设置分析缩略图的宽度。 */
  setAnalysisWidth(width) {
    const w = Math.round(Number(width));
    if (Number.isFinite(w) && w >= 16) this._analysisWidth = w;
  }

  frameWidth() {
    return this._width;
  }

  frameHeight() {
    return this._height;
  }

  /** indices 是「旧→新」顺序的截图下标；串行解码，返回 [{timeUs,gray,width,height}]。 */
  async sample(indices) {
    const list = Array.isArray(indices) ? indices : [];
    const out = [];
    for (const raw of list) {
      const i = Math.max(0, Math.min(this.shots.length - 1, Math.round(Number(raw) || 0)));
      const shot = this.shots[i];
      if (!shot) throw new StitchError('截图序号越界');
      let img = null;
      try {
        img = await loadShotImage(shotUrl(this.sid, shot.id));
        if (!this._width) {
          this._width = imageWidth(img);
          this._height = imageHeight(img);
        }
        const gray = await imageToGray(img, i, this._analysisWidth);
        out.push(gray);
      } finally {
        releaseImage(img);
      }
    }
    return out;
  }
}

/** 输入项归一化：支持 {gray,width,height}、ImageData({data,width,height})、裸 Uint8Array + opts.width/height。 */
function normalizeShotItem(item, opts) {
  if (!item) return null;
  const pw = Math.round(Number(item.pxWidth) || Number(opts.pxWidth) || 0);
  const ph = Math.round(Number(item.pxHeight) || Number(opts.pxHeight) || 0);
  if (ArrayBuffer.isView(item) && opts && opts.width && opts.height) {
    return { gray: item, width: opts.width | 0, height: opts.height | 0, pxWidth: pw, pxHeight: ph };
  }
  const w = Math.round(Number(item.width) || 0);
  const h = Math.round(Number(item.height) || 0);
  if (!w || !h) return null;
  if (item.gray && item.gray.length >= w * h) {
    return { gray: item.gray, width: w, height: h, pxWidth: pw || w, pxHeight: ph || h };
  }
  if (item.data && item.data.length >= w * h * 4) {
    return {
      gray: grayFromRGBA(item.data, w, h),
      width: w,
      height: h,
      pxWidth: pw || w,
      pxHeight: ph || h,
    };
  }
  return null;
}

/**
 * 用 2~3 张截图比较「逐行差异」找固定 UI 栏（状态栏/标题栏/输入框）边界。
 * 固定栏在不同截图之间像素几乎不变（行差异≈0），滚动内容行差异大。
 * grays: Array<{gray:Uint8Array,width,height,pxWidth?,pxHeight?}>（也接受 ImageData 或裸数组 + opts.width/height）
 * -> {topPx, botPx}（原图像素坐标，[topPx,botPx) 为可滚动内容区）；检测失败/无需裁剪返回 null。
 * 额外字段：topRows/botRows/contentRows/scaleY（调试与 UI 展示用）。
 */
export function detectCropFromShots(grays, opts = {}) {
  const raw = Array.isArray(grays) ? grays : [];
  const shots = [];
  for (const item of raw) {
    const s = normalizeShotItem(item, opts);
    if (s) shots.push(s);
    if (shots.length >= 3) break;
  }
  if (shots.length < 2) return null;
  const w = Math.min(...shots.map((s) => s.width));
  const h = Math.min(...shots.map((s) => s.height));
  if (w < 8 || h < 16) return null;
  const planes = shots.map((s) => s.gray);

  // 每行差异：所有截图两两比较（最多 3 对），逐行取平均绝对差。
  const pairs = [];
  for (let a = 0; a < planes.length; a++) {
    for (let b = a + 1; b < planes.length; b++) pairs.push([planes[a], planes[b]]);
  }
  const step = w > 200 ? 2 : 1; // 宽图隔列采样，省一点 CPU
  const diff = new Float64Array(h);
  let maxDiff = 0;
  for (let y = 0; y < h; y++) {
    let acc = 0;
    let cnt = 0;
    const row = y * w;
    for (const [a, b] of pairs) {
      for (let x = 0; x < w; x += step) {
        acc += Math.abs(a[row + x] - b[row + x]);
        cnt++;
      }
    }
    const d = cnt > 0 ? acc / cnt : 0;
    diff[y] = d;
    if (d > maxDiff) maxDiff = d;
  }
  // 两张截图内容完全一致（没滚动/重复截图）或页面纯色：无法判断固定栏
  if (!(maxDiff >= 2)) return null;
  const thr = Math.max(2, maxDiff * 0.12);

  // 二值化 + 3 行滑窗去毛刺，避免单行噪声把边界算歪
  const hot = new Uint8Array(h);
  for (let y = 0; y < h; y++) hot[y] = diff[y] >= thr ? 1 : 0;
  const active = new Uint8Array(h);
  for (let y = 0; y < h; y++) {
    const a = hot[Math.max(0, y - 1)];
    const b = hot[y];
    const c = hot[Math.min(h - 1, y + 1)];
    active[y] = a + b + c >= 2 ? 1 : 0;
  }
  let top = 0;
  while (top < h && !active[top]) top++;
  if (top >= h) return null;
  let bot = h;
  while (bot > top && !active[bot - 1]) bot--;
  const contentRows = bot - top;
  // 滚动内容太少（<15% 或 <24 行）：多半是截图重叠过多/重复，不裁剪交给上层报错更准确
  if (contentRows < 24 || contentRows < h * 0.15) return null;
  // 上下都没有固定栏：无需裁剪
  if (top === 0 && bot === h) return null;

  const scaleY = shots[0].height > 0 ? (shots[0].pxHeight || shots[0].height) / shots[0].height : 1;
  const topPx = Math.max(0, Math.round(top * scaleY));
  const botPx = Math.round(bot * scaleY);
  if (!(botPx > topPx)) return null;
  return {
    topPx,
    botPx,
    topRows: top,
    botRows: h - bot,
    contentRows,
    scaleY,
  };
}

function pickThree(count) {
  if (count <= 3) return Array.from({ length: count }, (_, i) => i);
  return [0, Math.floor((count - 1) / 2), count - 1];
}

const OVERLAP_MSG = '截图重叠不足或内容重复，无法拼接。请放慢滚动速度、让相邻截图保留少量重叠（约 10%～25%）后重试。';

/**
 * 静态截图分析：analyze(static) + 坏帧比例/重复截图校验 + 固定 UI 栏检测。
 * shots 按截图顺序（最新→最旧）。返回 AnalysisResult，并附加 analysis.crop = {topPx,botPx}|null。
 * 失败抛 StitchError（UI 显示「重试」引导）。
 */
/** 真机 input swipe 有惯性：偶尔会把一屏截在滚动/回弹过程中，相邻位移反向或匹配失败，
 *  这种截图会让后面的全局单调校验直接失败。这里先迭代剔除「位移非正向或可信度太低」的截图，
 *  再对剩余截图重新跟踪；最多剔除 maxSanitizeDrop 张（默认 6），剩余不足 3 张时保留原结果。 */
export function sanitizeStaticAnalysis(analysis, opts = {}) {
  const maxDrop = opts.maxSanitizeDrop == null ? 6 : Number(opts.maxSanitizeDrop);
  const minOverlap = opts.minOverlap == null ? 0.05 : opts.minOverlap;
  const bandH = analysis.band ? analysis.band.h : analysis.ah;
  let frames = analysis.frames;
  let track = analysis.track;
  let removed = 0;
  for (let round = 0; round < maxDrop; round += 1) {
    const steps = (track && track.step) || [];
    const ncc = (track && track.ncc) || [];
    let bad = -1;
    for (let i = 1; i < steps.length; i += 1) {
      const invalid = !(steps[i] > 0.5) || (ncc[i] != null && ncc[i] < 0.6);
      if (invalid) {
        bad = i;
        break;
      }
    }
    if (bad < 0) break;
    frames = frames.slice(0, bad).concat(frames.slice(bad + 1));
    removed += 1;
    if (frames.length < 3) break;
    track = trackOffsets(frames, bandH, { minOverlap, maxStepRatio: 1.0 });
  }
  return { frames, track, removed };
}

export async function analyzeShots(sid, shots, options = {}, onProgress) {
  // 容错：允许 analyzeShots(sid, shots, onProgress) 的调用形态
  if (typeof options === 'function' && onProgress === undefined) {
    onProgress = options;
    options = {};
  }
  const list = Array.isArray(shots) ? shots : [];
  const opts = options || {};
  if (list.length < 3) throw new StitchError(OVERLAP_MSG);
  const provider = new ShotProvider(sid, list);
  const analysis = await analyze(
    provider,
    {
      refineRounds: 0, // 截图不做补采
      analysisWidth: opts.analysisWidth || ANALYSIS_WIDTH,
      minOverlap: opts.minOverlap == null ? 0.05 : opts.minOverlap,
      maxFrames: opts.maxFrames,
    },
    onProgress,
  );
  // 先剔除惯性/回弹阶段截到的坏屏（否则后面的全局单调校验会整单失败）
  const sanitized = sanitizeStaticAnalysis(analysis, opts);
  if (sanitized.removed > 0) {
    analysis.frames = sanitized.frames;
    analysis.track = sanitized.track;
    analysis.stats = { ...(analysis.stats || {}), removedShots: sanitized.removed };
  }
  const pairs = Math.max(1, analysis.frames.length - 1);
  const badRatio = analysis.track.bad.length / pairs;
  const maxBadRatio = opts.maxBadRatio == null ? 0.3 : Number(opts.maxBadRatio);
  if (badRatio > maxBadRatio) throw new StitchError(OVERLAP_MSG);
  // 重复截图（内容没动/滚了又滚回来）：中位位移≈0，拼出来只会是一堆重复内容
  const medStep = analysis.stats ? analysis.stats.medianStep : null;
  const bandH = analysis.band ? analysis.band.h : analysis.ah;
  if (medStep != null && Math.abs(medStep) < Math.max(2, bandH * 0.02)) throw new StitchError(OVERLAP_MSG);

  // 固定 UI 栏检测（状态栏/标题栏/输入框）：再抽最多 3 张截图的分析灰度做逐行差异
  let crop = null;
  if (opts.cropStatusInput !== false) {
    try {
      const picks = pickThree(analysis.frames.length);
      const times = picks.map((i) => analysis.frames[i].timeUs);
      const got = await provider.sample(times);
      const items = got.map((g) => ({
        gray: g.gray,
        width: g.width,
        height: g.height,
        pxWidth: analysis.frameWidth,
        pxHeight: analysis.frameHeight,
      }));
      crop = detectCropFromShots(items);
    } catch {
      crop = null;
    }
  }
  analysis.crop = crop;
  analysis.stats = { ...(analysis.stats || {}), badRatio };
  return analysis;
}

/**
 * 按 Plan 逐页渲染截图拼接结果（惰性分页，整图最多缓存 3 张）。
 * plan 一般来自 planMaster(analysis, crop?.topPx ?? 0, crop?.botPx ?? frameHeight, tileH)。
 * -> 返回页数；onPage(page, i) 的 page = {index,y0,y1,width,height,canvas}
 */
export async function renderShotPages(plan, sid, shots, tileH, onPage, onProgress) {
  if (!plan || !Array.isArray(plan.ops)) throw new StitchError('拼接方案为空');
  const provider = new ShotProvider(sid, shots);
  const ordered = provider.shots;
  if (!ordered.length) throw new StitchError('没有可用的截图');
  const width = Math.max(
    1,
    Math.round(plan.frameWidth || plan.width || ordered[0].width || 0) || 1,
  );
  const topPx = Math.max(0, Math.round(Number(plan.topPx) || 0));
  const cropHpx = Math.max(
    1,
    Math.round(
      plan.cropHpx ||
        (Number.isFinite(plan.botPx) ? plan.botPx - topPx : 0) ||
        (ordered[0].height || 1),
    ),
  );
  const pageH = tileH > 0 ? Math.round(tileH) : cropHpx;

  // 整图缓存：LRU 3 张；淘汰时 ImageBitmap 主动 close() 释放内存
  const cache = new Map();
  async function getFrame(frameIndex) {
    const frame = plan.frames && plan.frames[frameIndex];
    const shotIndex = frame && Number.isFinite(frame.timeUs) ? Math.round(frame.timeUs) : frameIndex;
    const key = Math.max(0, Math.min(ordered.length - 1, shotIndex));
    if (cache.has(key)) {
      const hit = cache.get(key);
      cache.delete(key);
      cache.set(key, hit);
      return { image: hit, sxTop: topPx };
    }
    const shot = ordered[key];
    if (!shot) return null;
    const img = await loadShotImage(shotUrl(sid, shot.id));
    cache.set(key, img);
    while (cache.size > CACHE_LIMIT) {
      const oldKey = cache.keys().next().value;
      const old = cache.get(oldKey);
      cache.delete(oldKey);
      releaseImage(old);
    }
    return { image: img, sxTop: topPx };
  }

  const renderer = {
    width,
    height: pageH,
    async pageImage(pageIndex, planArg, y0, y1) {
      if (typeof document === 'undefined') throw new StitchError('当前环境不支持图片渲染');
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = Math.max(1, y1 - y0);
      await drawPageOps(canvas, planArg, y0, y1, getFrame);
      return canvas;
    },
  };
  return renderPages(plan, renderer, onPage, onProgress);
}
