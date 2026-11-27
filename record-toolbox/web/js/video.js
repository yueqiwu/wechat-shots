// js/video.js —— 录屏模式：<video> 帧源 + 分析编排 + 分页渲染
//
// 契约见 docs/CONTRACTS.md「js/video.js」。纯浏览器模块（依赖 document/HTMLVideoElement），
// 算法全部复用 stitch.js，不在本文件重复实现。
// 低配电脑优先：采样串行、帧上下文只建一份、渲染时整帧图最多缓存 3 张。

import {
  ANALYSIS_WIDTH,
  BINS,
  StitchError,
  analyze,
  analyzeFramesSequence,
  contentBand,
  cropGray,
  drawPageOps,
  grayFromRGBA,
  median,
  alignAt,
  renderPages,
  rowActivity,
  rowSharpness,
  rowSig,
  scanShift,
} from './stitch.js';

// 方便 UI 只 import 本模块也能统一 catch 拼接错误
export { StitchError };

const SEEK_TIMEOUT_MS = 3000;
const META_TIMEOUT_MS = 20000;

/** 等两帧 rAF：seek 之后画面真正可画到 canvas 上（后台窗口 rAF 被节流时用超时兜底）。 */
function waitTwoRaf() {
  return new Promise((resolve) => {
    if (typeof requestAnimationFrame !== 'function') {
      setTimeout(resolve, 0);
      return;
    }
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      resolve();
    };
    requestAnimationFrame(() => requestAnimationFrame(finish));
    setTimeout(finish, 250);
  });
}

/**
 * 录屏帧源：videoUrl 一般是 "/media/video/<sid>"（支持 Range，直接交给 <video>）。
 * 用法：const p = new VideoProvider(url); await p.open(); analyzeVideo(p); renderVideoPages(...)
 */
export class VideoProvider {
  constructor(videoUrl) {
    if (!videoUrl) throw new StitchError('缺少录屏地址');
    this.url = String(videoUrl);
    this.kind = 'video';
    this.video = null;
    this._openPromise = null;
    this._width = 0;
    this._height = 0;
    this._duration = 0;
    this._analysisWidth = ANALYSIS_WIDTH;
    this._canvas = null;
    this._ctx = null;
    this._aw = 0;
    this._ah = 0;
  }

  /** analyze() 会调用；返回分析用缩略宽度（默认 144）。 */
  setAnalysisWidth(width) {
    const w = Math.round(Number(width));
    if (Number.isFinite(w) && w >= 16) this._analysisWidth = w;
  }

  /** 等待 metadata，返回 {width,height,duration,name}。可重复调用。 */
  async open() {
    if (!this._openPromise) {
      this._openPromise = this._open().catch((err) => {
        this._openPromise = null;
        throw err;
      });
    }
    return this._openPromise;
  }

  async _open() {
    if (typeof document === 'undefined') throw new StitchError('当前环境不支持视频解码');
    if (!this.video) {
      const v = document.createElement('video');
      v.preload = 'auto';
      v.muted = true;
      v.playsInline = true;
      v.setAttribute('playsinline', '');
      v.src = this.url;
      this.video = v;
    }
    const v = this.video;
    if (!(v.readyState >= 1 && v.videoWidth > 0)) {
      await new Promise((resolve, reject) => {
        let timer = null;
        const cleanup = () => {
          if (timer) clearTimeout(timer);
          v.removeEventListener('loadedmetadata', onMeta);
          v.removeEventListener('error', onError);
        };
        const onMeta = () => {
          if (!v.videoWidth) return;
          cleanup();
          resolve();
        };
        const onError = () => {
          cleanup();
          reject(new StitchError('视频加载失败，文件可能已损坏或格式不支持'));
        };
        timer = setTimeout(() => {
          cleanup();
          reject(new StitchError('视频加载超时，请重试'));
        }, META_TIMEOUT_MS);
        v.addEventListener('loadedmetadata', onMeta);
        v.addEventListener('error', onError);
        if (v.readyState >= 1 && v.videoWidth > 0) onMeta();
      });
    }
    this._width = Math.max(1, v.videoWidth);
    this._height = Math.max(1, v.videoHeight);
    this._duration = Number.isFinite(v.duration) ? v.duration : 0;
    const name = decodeURIComponent(String(this.url).split('/').filter(Boolean).pop() || '录屏');
    return { width: this._width, height: this._height, duration: this._duration, name };
  }

  frameWidth() {
    return this._width;
  }

  frameHeight() {
    return this._height;
  }

  /** times 单位秒；串行 seek + 缩放到 ANALYSIS_WIDTH，返回 [{timeUs,gray,width,height}]。 */
  async sample(times) {
    const list = Array.isArray(times) ? times : [];
    if (!list.length) return [];
    await this.open();
    this._ensureCanvas();
    const out = [];
    for (const raw of list) {
      const t = Number.isFinite(raw) ? Number(raw) : 0;
      await this._seekTo(t);
      await waitTwoRaf();
      this._ctx.drawImage(this.video, 0, 0, this._aw, this._ah);
      const img = this._ctx.getImageData(0, 0, this._aw, this._ah);
      out.push({
        timeUs: Math.round(t * 1e6),
        gray: grayFromRGBA(img.data, this._aw, this._ah),
        width: this._aw,
        height: this._ah,
      });
    }
    return out;
  }

  /**
   * 渲染用：定位到 timeUs 并把裁剪区画成一张原分辨率 canvas（宽=原视频宽，高=cropHpx）。
   * 返回的 canvas 左上角 = 裁剪区左上角（drawPageOps 用 sxTop=0 消费）。
   */
  async frameCanvas(timeUs, cropTopPx, cropHpx) {
    await this.open();
    const t = (Number(timeUs) || 0) / 1e6;
    await this._seekTo(t);
    await waitTwoRaf();
    const top = Math.max(0, Math.min(this._height - 1, Math.round(Number(cropTopPx) || 0)));
    const maxH = Math.max(1, this._height - top);
    const h = Math.max(1, Math.min(maxH, Math.round(Number(cropHpx) || maxH)));
    const w = this._width;
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new StitchError('无法创建绘图上下文');
    ctx.drawImage(this.video, 0, top, w, h, 0, 0, w, h);
    return canvas;
  }

  /**
   * 按「真实帧序列」扫描录屏（VFR 录屏必须走这里）。
   *
   * 用 requestVideoFrameCallback 逐帧回调，拿 meta.mediaTime 作为真实时间戳；
   * grays=true 时返回全帧灰度（用于第一遍定内容区），给出 band 时直接在回调里
   * 裁剪 + 生成行签名（省内存）。返回 {width,height,duration,aw,ah,frames}。
   */
  async scan(options = {}) {
    await this.open();
    const v = this.video;
    if (!v || typeof v.requestVideoFrameCallback !== 'function') {
      throw new StitchError('当前浏览器不支持逐帧分析录屏，请升级 WebView2 后重试');
    }
    this._ensureCanvas();
    const opts = options || {};
    const maxFrames = Math.max(1, Math.round(Number(opts.maxFrames) || 1600));
    const rate = Math.max(0.25, Math.min(16, Number(opts.playbackRate) || 6));
    const wantGray = !!opts.grays;
    const band = opts.band || null;
    if (!wantGray && !band) throw new StitchError('缺少内容裁切区域，无法逐帧分析');
    const aw = this._aw;
    const ah = this._ah;
    const bandTop = band ? Math.max(0, Math.min(ah - 2, Math.round(band.top))) : 0;
    const bandBot = band ? Math.max(bandTop + 2, Math.min(ah, Math.round(band.bot))) : ah;
    const cropH = bandBot - bandTop;
    const frames = [];
    let progressTick = 0;
    await new Promise((resolve, reject) => {
      let finished = false;
      const cleanup = () => {
        v.removeEventListener('ended', onEnded);
        v.removeEventListener('error', onError);
      };
      const finish = (err) => {
        if (finished) return;
        finished = true;
        cleanup();
        try {
          v.pause();
          v.playbackRate = 1;
        } catch {
          /* ignore */
        }
        if (err) reject(err);
        else resolve();
      };
      const onFrame = (now, metadata) => {
        if (finished) return;
        try {
          const mediaTime = metadata && Number.isFinite(metadata.mediaTime) ? metadata.mediaTime : v.currentTime;
          this._ctx.drawImage(v, 0, 0, aw, ah);
          const data = this._ctx.getImageData(0, 0, aw, ah).data;
          const gray = grayFromRGBA(data, aw, ah);
          if (wantGray) {
            frames.push({ timeUs: Math.round(mediaTime * 1e6), gray });
          } else {
            const cropped = cropGray(gray, aw, bandTop, bandBot);
            frames.push({
              timeUs: Math.round(mediaTime * 1e6),
              sig: rowSig(cropped, aw, cropH, BINS),
              sharp: rowSharpness(cropped, aw, 0, cropH),
            });
          }
        } catch (err) {
          finish(err instanceof Error ? err : new StitchError('录屏帧处理失败'));
          return;
        }
        progressTick++;
        if (typeof opts.onProgress === 'function' && progressTick % 5 === 0) {
          try {
            opts.onProgress(frames.length, maxFrames);
          } catch {
            /* ignore */
          }
        }
        if (frames.length >= maxFrames) {
          finish(null);
          return;
        }
        v.requestVideoFrameCallback(onFrame);
      };
      const onEnded = () => finish(null);
      const onError = () => finish(new StitchError('录屏播放失败，文件可能已损坏'));
      v.addEventListener('ended', onEnded);
      v.addEventListener('error', onError);
      const startSec = Math.max(0, Math.min(Number(opts.startSec) || 0, Math.max(0, (v.duration || 0) - 0.05)));
      this._seekTo(startSec)
        .then(() => {
          v.playbackRate = rate;
          v.requestVideoFrameCallback(onFrame);
          const playing = v.play();
          if (playing && typeof playing.catch === 'function') {
            playing.catch((err) => finish(new StitchError('无法播放录屏：' + (err && err.message ? err.message : err))));
          }
        })
        .catch((err) => finish(err instanceof Error ? err : new StitchError('视频定位失败')));
    });
    if (!frames.length) throw new StitchError('没有读到录屏画面，请重试');
    return { width: this._width, height: this._height, duration: this._duration, aw, ah, frames };
  }

  _ensureCanvas() {
    if (this._ctx && this._canvas) return;
    const vw = Math.max(1, this.frameWidth());
    const vh = Math.max(1, this.frameHeight());
    const aw = Math.max(16, Math.min(Math.round(this._analysisWidth) || ANALYSIS_WIDTH, vw));
    const ah = Math.max(1, Math.round((vh * aw) / vw));
    if (typeof document === 'undefined') throw new StitchError('当前环境不支持视频解码');
    this._canvas = document.createElement('canvas');
    this._canvas.width = aw;
    this._canvas.height = ah;
    this._ctx = this._canvas.getContext('2d', { willReadFrequently: true });
    if (!this._ctx) throw new StitchError('无法创建绘图上下文');
    this._aw = aw;
    this._ah = ah;
  }

  async _seekTo(t) {
    const v = this.video;
    if (!v) throw new StitchError('视频尚未打开');
    const dur = Number.isFinite(v.duration) && v.duration > 0 ? v.duration : this._duration;
    const target = Math.min(Math.max(0, t), Math.max(0, (dur || 0) - 0.002));
    if (Math.abs(v.currentTime - target) < 1e-4 && v.readyState >= 2) return;
    await new Promise((resolve, reject) => {
      let timer = null;
      const cleanup = () => {
        if (timer) clearTimeout(timer);
        v.removeEventListener('seeked', onSeeked);
        v.removeEventListener('error', onError);
      };
      const onSeeked = () => {
        cleanup();
        resolve();
      };
      const onError = () => {
        cleanup();
        reject(new StitchError('视频定位失败，文件可能已损坏'));
      };
      v.addEventListener('seeked', onSeeked);
      v.addEventListener('error', onError);
      // 个别机器 seeked 不触发（已在目标附近/解码抖动）时兜底，避免整条流水线卡死
      timer = setTimeout(() => {
        cleanup();
        resolve();
      }, SEEK_TIMEOUT_MS);
      try {
        v.currentTime = target;
      } catch {
        cleanup();
        reject(new StitchError('视频定位失败'));
      }
    });
  }
}

/** 录屏分析：确保 metadata 就绪后交给 stitch.analyze（视频分支内部会做失配补采）。 */
export async function analyzeVideo(provider, options, onProgress) {
  // 容错：允许 analyzeVideo(provider, onProgress) 的调用形态
  if (typeof options === 'function' && onProgress === undefined) {
    onProgress = options;
    options = {};
  }
  if (!provider || (typeof provider.sample !== 'function' && typeof provider.scan !== 'function')) {
    throw new StitchError('缺少录屏帧源');
  }
  const opts = options || {};
  // 有 scan() 的帧源（真实录屏）优先走「真实帧序列 + 时间差先验」路径：
  // 手机录屏常见可变帧率，按固定时间 seek 采样会重复/跳帧，导致位移匹配错乱。
  // 默认走「按内容自适应步进」的逐帧扫描：录屏速度不均匀时（快速甩动/中途停顿），
  // 固定时间采样会让相邻帧位移一会儿超过一屏、一会儿重复同一帧，匹配必然错乱。
  // 只有真实 VideoProvider（内部有 <video> 元素）才默认走 walk；单测里的简单
  // provider 仍然走旧 analyze 路径，保持既有契约。
  if (opts.sequenceScan === true && typeof provider.scan === 'function') {
    try {
    } catch {
      /* ignore */
    }
    return analyzeFramesSequence(provider, opts, onProgress);
  }
  const realVideo =
    typeof provider.scan === 'function' && Object.prototype.hasOwnProperty.call(provider, 'video');
  try {
  } catch {
    /* ignore */
  }
  if (opts.walkScan === true || (realVideo && opts.walkScan !== false)) {
    try {
    } catch {
      /* ignore */
    }
    return analyzeVideoWalk(provider, opts, onProgress);
  }
  try {
  } catch {
    /* ignore */
  }
  if (typeof provider.open === 'function') await provider.open();
  return analyze(provider, opts, onProgress);
}

/**
 * 按 Plan 逐页渲染录屏拼接结果（惰性分页，整帧图最多缓存 3 张）。
 * opts: {cropTopPx, cropBotPx, tileH}——plan 里已有 topPx/cropHpx 时优先用 plan。
 * -> 返回页数；onPage(page, i) 的 page = {index,y0,y1,width,height,canvas}
 */
export async function renderVideoPages(plan, provider, opts, onPage, onProgress) {
  if (!plan || !Array.isArray(plan.ops)) throw new StitchError('拼接方案为空');
  if (!provider || typeof provider.frameCanvas !== 'function') throw new StitchError('缺少录屏帧源');
  await provider.open();
  const options = opts || {};
  const topPx = Number.isFinite(plan.topPx) ? plan.topPx : Math.max(0, Math.round(Number(options.cropTopPx) || 0));
  const botPx = Number.isFinite(plan.botPx)
    ? plan.botPx
    : Math.max(topPx + 16, Math.round(Number(options.cropBotPx) || provider.frameHeight()));
  const cropHpx = Math.max(1, Math.round(plan.cropHpx || botPx - topPx));
  const width = Math.max(1, Math.round(plan.frameWidth || provider.frameWidth() || plan.width || 1));
  const tileH = options.tileH > 0 ? Math.round(options.tileH) : cropHpx;

  // 整帧 canvas 缓存：LRU 3 张（1080x2400 每张约 10MB，3 张 ≈ 30MB，低配电脑可接受）
  const cache = new Map();
  const MAX_CACHE = 3;
  async function getFrame(frameIndex) {
    if (cache.has(frameIndex)) {
      const hit = cache.get(frameIndex);
      cache.delete(frameIndex);
      cache.set(frameIndex, hit);
      return { image: hit, sxTop: 0 };
    }
    const frame = plan.frames && plan.frames[frameIndex];
    const timeUs = frame && Number.isFinite(frame.timeUs) ? frame.timeUs : 0;
    const canvas = await provider.frameCanvas(timeUs, topPx, cropHpx);
    cache.set(frameIndex, canvas);
    while (cache.size > MAX_CACHE) cache.delete(cache.keys().next().value);
    return { image: canvas, sxTop: 0 };
  }

  const renderer = {
    width,
    height: tileH,
    async pageImage(pageIndex, planArg, y0, y1) {
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = Math.max(1, y1 - y0);
      await drawPageOps(canvas, planArg, y0, y1, getFrame);
      return canvas;
    },
  };
  return renderPages(plan, renderer, onPage, onProgress);
}


// ---------------------------------------------------------------------------
// 录屏逐帧「自适应步进」扫描
// ---------------------------------------------------------------------------

/**
 * 录屏分析（推荐路径）：按真实画面内容自适应步进，逐帧测位移并拼接。
 *
 * 每抽一帧后与上一帧做全窗口位移匹配，按实测位移反推下一个抽样时间，使相邻帧位移
 * 稳定在 targetRows（默认 0.4 × 内容区高度）；静止画面自动拉大间隔，滚动快时密集抽样。
 * 这样既不会像固定 fps 那样跳帧/重复，也不依赖可变帧率的时间戳。
 *
 * @returns analysis（kind='video'，含 frames[{timeUs,sig,sharp}] 与 track.pos/step/ncc）
 */
export async function analyzeVideoWalk(provider, options = {}, onProgress = () => {}) {
  const opts = options || {};
  const report = (p, msg) => {
    try {
      onProgress(Math.max(0, Math.min(1, p)), msg || '');
    } catch {
      /* 进度回调异常不影响分析 */
    }
  };
  const probe = await provider.open();
  const duration = Math.max(0.2, Number(probe && probe.duration) || 0);

  // 1) 定内容区：全片均匀取 12 帧，按行活跃度找上下固定的状态栏/输入框
  report(0.02, '正在定位聊天内容区域…');
  const probeTimes = [];
  const probeCount = 12;
  for (let i = 0; i < probeCount; i++) {
    probeTimes.push(Math.min(duration - 0.05, ((i + 0.5) / probeCount) * duration));
  }
  let probeFrames = await provider.sample(probeTimes);
  if (!probeFrames.length) throw new StitchError('没有读到录屏画面，请重试');
  const aw = probeFrames[0].width;
  const ah = probeFrames[0].height;
  const rowMeanOf = (gray) => {
    const out = new Float32Array(ah);
    for (let y = 0; y < ah; y++) {
      let sum = 0;
      for (let x = 0; x < aw; x++) sum += gray[y * aw + x];
      out[y] = sum / aw;
    }
    return out;
  };
  const rowTemporalDiff = (frames) => {
    const means = frames.map((f) => rowMeanOf(f.gray));
    const diff = new Float32Array(ah);
    for (let i = 1; i < means.length; i++) {
      for (let y = 0; y < ah; y++) diff[y] += Math.abs(means[i][y] - means[i - 1][y]);
    }
    const k = Math.max(1, means.length - 1);
    for (let y = 0; y < ah; y++) diff[y] /= k;
    return diff;
  };
  let grays = probeFrames.map((f) => f.gray);
  let rowDiff = rowTemporalDiff(probeFrames);
  let maxRowDiff = 0;
  for (let y = 0; y < ah; y++) if (rowDiff[y] > maxRowDiff) maxRowDiff = rowDiff[y];
  if (maxRowDiff < 0.8) {
    // 探针帧几乎没变化：视频可能还没解码出画面，稍等后重采一次
    await new Promise((r) => setTimeout(r, 200));
    const retryFrames = await provider.sample(probeTimes);
    if (retryFrames.length) {
      probeFrames = retryFrames;
      grays = probeFrames.map((f) => f.gray);
      rowDiff = rowTemporalDiff(probeFrames);
      maxRowDiff = 0;
      for (let y = 0; y < ah; y++) if (rowDiff[y] > maxRowDiff) maxRowDiff = rowDiff[y];
    }
  }
  // 用「跨帧有变化」的连续行当聊天内容区：状态栏/标题栏/输入框这些固定元素几乎不变
  const contentThreshold = Math.max(0.8, maxRowDiff * 0.12);
  let runTop = -1;
  let runBot = -1;
  let bestTop = -1;
  let bestBot = -1;
  for (let y = 0; y < ah; y++) {
    if (rowDiff[y] >= contentThreshold) {
      if (runTop < 0) runTop = y;
      runBot = y;
    } else if (runTop >= 0) {
      if (bestTop < 0 || runBot - runTop > bestBot - bestTop) {
        bestTop = runTop;
        bestBot = runBot;
      }
      runTop = -1;
    }
  }
  if (runTop >= 0 && (bestTop < 0 || runBot - runTop > bestBot - bestTop)) {
    bestTop = runTop;
    bestBot = runBot;
  }
  let band;
  if (bestTop >= 0 && bestBot - bestTop >= ah * 0.3) {
    band = { top: bestTop, bot: bestBot + 1 };
  } else {
    band = contentBand(rowActivity(grays, aw, ah), ah);
  }
  const bandTop = Math.max(0, Math.min(ah - 8, band.top));
  const bandBot = Math.max(bandTop + 8, Math.min(ah, band.bot));
  const bandH = bandBot - bandTop;
  const maxStep = Math.max(4, Math.round(bandH * 0.95));
  const targetRows = Math.max(16, Math.min(Math.round(bandH * 0.4), Math.round(maxStep * 0.75)));
  const stationaryEps = Math.max(2, Math.round(bandH * 0.02));
  const costLimit = Number.isFinite(opts.costLimit) ? opts.costLimit : 14;
  const relaxedCostLimit = costLimit * 1.8;
  const dtMin = 1 / 25; // 视频常见 30fps：采样间隔必须大于一帧，否则会反复采到同一帧
  const maxFrames = Math.max(32, Math.round(Number(opts.maxWalkFrames) || 2600));

  /** 每行的「信息量」：该行 8 段灰度均值相对行均值的偏离。纯色背景行≈0，聊天气泡行较大。 */
  const rowWeights = (sig) => {
    const w = new Float32Array(bandH);
    for (let y = 0; y < bandH; y++) {
      let sum = 0;
      for (let b = 0; b < BINS; b++) sum += sig[y * BINS + b];
      const mean = sum / BINS;
      let varSum = 0;
      for (let b = 0; b < BINS; b++) {
        const d = sig[y * BINS + b] - mean;
        varSum += d * d;
      }
      w[y] = Math.sqrt(varSum / BINS);
    }
    return w;
  };
  const makeFrame = (gray, timeUs) => {
    const cropped = cropGray(gray, aw, bandTop, bandBot).gray;
    const sig = rowSig(cropped, aw, bandH, BINS);
    return {
      timeUs,
      sig,
      sharp: rowSharpness(cropped, aw, 0, bandH),
      rowW: rowWeights(sig),
    };
  };
  /** 按行活跃度加权的平均绝对差：只有「有内容的行」参与打分，纯背景行基本不贡献。 */
  let dbgFinite = 0;
  let dbgMin = Infinity;
  const weightedCost = (SIG_A, SIG_B, s) => {
    const from = Math.max(0, -s);
    const to = Math.min(bandH, bandH - s);
    let num = 0;
    let den = 0;
    for (let y = from; y < to; y++) {
      const w = A_W[y];
      if (w < 0.5) continue;
      const base = y * BINS;
      const baseB = (y + s) * BINS;
      let d = 0;
      for (let b = 0; b < BINS; b++) d += Math.abs(SIG_A[base + b] - SIG_B[baseB + b]);
      num += w * (d / BINS);
      den += w;
    }
    if (den < totalW * 0.18) return Infinity; // 重叠区里几乎没有内容行：不可信
    const c = num / den;
    dbgFinite++;
    if (c < dbgMin) dbgMin = c;
    return c;
  };
  let A_W = null;
  let totalW = 1;
  const dbgPairs = [];
  const wDiag = { sum: 0, max: 0, rows: 0 };
  const matchPair = (prevFrame, curFrame, lo, hi) => {
    const A = prevFrame.sig;
    const B = curFrame.sig;
    A_W = prevFrame.rowW;
    totalW = 0;
    for (let y = 0; y < bandH; y++) totalW += A_W[y];
    if (!(totalW > 1)) return null;
    const evaluate = (dir) => {
      let best = null;
      for (let s = Math.max(2, lo); s <= hi; s += 2) {
        const c = dir > 0 ? weightedCost(A, B, s) : weightedCost(B, A, s);
        if (c < (best ? best.cost : Infinity)) best = { shift: s, cost: c };
      }
      if (!best) return null;
      // ±2 精修
      for (let s = best.shift - 2; s <= best.shift + 2; s++) {
        if (s < 2 || s > hi) continue;
        const c = dir > 0 ? weightedCost(A, B, s) : weightedCost(B, A, s);
        if (c < best.cost) best = { shift: s, cost: c };
      }
      return best;
    };
    const fwd = evaluate(1);
    const bwd = evaluate(-1);
    if (dbgPairs.length < 6) {
      dbgPairs.push({
        lo,
        hi,
        totalW: Math.round(totalW),
        finite: dbgFinite,
        min: Number.isFinite(dbgMin) ? Math.round(dbgMin * 100) / 100 : null,
        fwd: fwd ? { shift: fwd.shift, cost: Number.isFinite(fwd.cost) ? Math.round(fwd.cost * 100) / 100 : 'inf' } : null,
        bwd: bwd ? { shift: bwd.shift, cost: Number.isFinite(bwd.cost) ? Math.round(bwd.cost * 100) / 100 : 'inf' } : null,
      });
    }
    dbgFinite = 0;
    dbgMin = Infinity;
    const fOk = fwd && fwd.cost <= costLimit;
    const bOk = bwd && bwd.cost <= costLimit;
    if (!fOk && !bOk) return null;
    if (fOk && (!bOk || fwd.cost <= bwd.cost)) {
      // 正位移 = 后一帧内容整体向下（滚向更早的消息）
      return { down: fwd.shift, ncc: Math.max(0, 1 - fwd.cost / 40), cost: fwd.cost };
    }
    return { down: -bwd.shift, ncc: Math.max(0, 1 - bwd.cost / 40), cost: bwd.cost };
  };

  // 2) 自适应步进扫描
  report(0.06, '正在逐帧分析录屏…');
  const frames = [];
  const stepsRaw = []; // stepsRaw[i] = 第 i-1 帧到第 i 帧的有符号位移（正=内容向上）
  const nccs = [];
  const costs = [];
  let dt = 0.12;
  let staticStreak = 0;
  let wideUsed = 0;
  let retries = 0;
  let hitCount = 0;
  let sameCount = 0;
  let retrySamples = 0;
  const [firstSample] = await provider.sample([0]);
  if (!firstSample) throw new StitchError('没有读到录屏画面，请重试');
  let first = makeFrame(firstSample.gray, 0);
  for (let y = 0; y < bandH; y++) {
    const w = first.rowW[y];
    wDiag.sum += w;
    if (w > wDiag.max) wDiag.max = w;
    if (w >= 0.5) wDiag.rows++;
  }
  frames.push(first);
  let prev = first;
  let prevGray = firstSample.gray;
  let prevTime = 0;
  let guard = 0;
  while (prevTime < duration - 0.03 && frames.length < maxFrames && guard < maxFrames * 8) {
    guard++;
    const t = Math.min(duration - 0.02, prevTime + dt);
    const [sample] = await provider.sample([t]);
    if (!sample) break;
    const curGray = sample.gray;
    const curCropped = cropGray(curGray, aw, bandTop, bandBot).gray;
    const prevCropped = cropGray(prevGray, aw, bandTop, bandBot).gray;
    const cur = makeFrame(curGray, Math.round(t * 1e6));
    const expected = Math.max(6, Math.min(maxStep, Math.round(prev._expected || targetRows)));
    let lo = Math.max(3, Math.round(expected * 0.3));
    let hi = Math.min(maxStep, Math.round(expected * 2.0) + 8);
    if (hi < lo + 3) hi = Math.min(maxStep, lo + 3);
    let hit = matchPair(prev, cur, lo, hi);
    if (!hit || (hit.cost > costLimit && hi < maxStep)) {
      const wide = matchPair(prev, cur, 3, maxStep);
      if (wide && (!hit || wide.cost < hit.cost)) {
        hit = wide;
        wideUsed++;
      }
    }
    // 静止帧判定：整幅裁剪灰度图的平均像素差。聊天界面里大量行是背景色，
    // 用行签名 cost 会把「画面完全不同但背景占多数」的两帧误判成同一帧。
    let diffSum = 0;
    for (let k = 0; k < prevCropped.length; k++) diffSum += Math.abs(prevCropped[k] - curCropped[k]);
    const meanDiff = diffSum / Math.max(1, prevCropped.length);
    const sameFrame = meanDiff <= 2.5;
    if (hit) hitCount++;
    if (sameFrame) sameCount++;
    let step = null; // null = 这一次抽样作废（重试）
    if (hit && hit.cost <= costLimit && !sameFrame) {
      step = -hit.down; // 内容向上为正
    } else if (sameFrame) {
      step = 0;
    }

    if (step === null) {
      // 画面变了但对不上位（很可能位移超过一屏）：步长减半，从上一帧重试
      retries++;
      retrySamples++;
      if (retries > 6) {
        // 实在对不上：有放宽阈值内的候选就接受，保持前进；否则跳过并拉大采样间隔
        if (hit && hit.cost <= relaxedCostLimit) {
          step = -hit.down;
          retries = 0;
        } else {
          // 对不上（多半是两帧之间滑太快）：记一次 0 位移，并把采样间隔拉回最小，
          // 尽快从新画面重新对齐；加大间隔只会让下一次位移更大、更容易继续失配。
          step = 0;
          staticStreak += 1;
          dt = dtMin;
          retries = 0;
        }
      } else {
        dt = Math.max(dtMin, dt / 2);
        continue;
      }
    } else {
      retries = 0;
    }
    frames.push(cur);
    stepsRaw.push(step);
    nccs.push(hit && hit.cost <= costLimit ? hit.ncc : 0);
    costs.push(hit && hit.cost <= costLimit ? hit.cost : 99);
    prevTime = t;
    prev = cur;
    prevGray = curGray;
    if (Math.abs(step) > stationaryEps) {
      staticStreak = 0;
      const measured = Math.abs(step);
      const nextDt = dt * (targetRows / measured);
      dt = Math.max(dtMin, Math.min(2.0, Math.min(dt * 3, nextDt)));
      cur._expected = measured;
    } else {
      staticStreak++;
      // 静止画面（还没开始滑/中途停顿/已到顶）：拉大间隔，避免重复帧堆满内存
      dt = Math.min(2.0, Math.max(dt * 2, 0.18));
      cur._expected = targetRows;
      if (staticStreak >= 10 && prevTime >= duration - 0.6) break;
    }
    report(0.06 + 0.84 * Math.min(1, prevTime / duration), `正在逐帧分析录屏… ${Math.round((prevTime / duration) * 100)}%`);
  }

  // 3) 方向归一：滚向更早消息时（内容整体向下），把帧序列倒过来，使 pos 递增
  const movingSteps = stepsRaw.filter((s) => Math.abs(s) > stationaryEps);
  const dirSign = Math.sign(median(movingSteps.map((v) => v))) || 1;
  // step 为正 = 内容向上（正常「往上翻看更早消息」是内容向下 = step 负），
  // 主轴为负时把时间轴反过来，让 pos 单调递增。
  const reversed = dirSign < 0;
  const framesOut = reversed ? frames.slice().reverse() : frames;
  const n = framesOut.length;
  const pos = new Float64Array(n);
  const stepArr = new Float64Array(n);
  const nccArr = new Float64Array(n);
  const costArr = new Float64Array(n);
  const agree = new Float64Array(n);
  for (let j = 1; j < n; j++) {
    const src = stepsRaw[reversed ? n - j - 1 : j - 1];
    const s = reversed ? -src : src;
    stepArr[j] = s > 0 ? s : 0;
    pos[j] = pos[j - 1] + stepArr[j];
    const oi = reversed ? n - j - 1 : j - 1;
    nccArr[j] = stepArr[j] === 0 ? 1 : nccs[oi] || 0;
    costArr[j] = costs[oi] || 99;
  }
  nccArr[0] = 1;
  const positive = [];
  for (let j = 1; j < n; j++) if (stepArr[j] > 0) positive.push(stepArr[j]);
  const medStep = positive.length ? median(positive) : 0;
  const track = {
    pos,
    step: stepArr,
    ncc: nccArr,
    agree,
    cost: costArr,
    bad: [],
    maxShift: maxStep,
    tol: 2,
    medCost: costLimit,
  };
  report(0.92, '正在拼接长图…');
  const scaleAll = probe.height / ah;
  return {
    kind: 'video',
    aw,
    ah,
    // 裁剪区严格等于内容区（原生像素）：不含状态栏/标题栏/输入框，避免拼图里重复出现固定 UI
    crop: {
      topPx: Math.round(bandTop * scaleAll),
      botPx: Math.round(bandBot * scaleAll),
    },
    band: { top: bandTop, bot: bandBot, h: bandH },
    bandH,
    videoWidth: probe.width,
    videoHeight: probe.height,
    durationUs: Math.round(duration * 1e6),
    frameWidth: provider.frameWidth(),
    frameHeight: provider.frameHeight(),
    frames: framesOut.map((f) => ({ timeUs: f.timeUs, sig: f.sig, sharp: f.sharp })),
    track,
    stats: {
      frameCount: n,
      badCount: 0,
      directionReversed: reversed ? 1 : 0,
      imputedSteps: 0,
      walkScan: 1,
      skippedFrames: 0,
      medianStep: medStep,
      medianOverlapRatio: bandH > 0 && medStep > 0 ? 1 - medStep / bandH : 0,
      targetRows,
      wideUsed,
      hitCount,
      sameCount,
      retrySamples,
      debugPairs: dbgPairs,
      wSum: Math.round(wDiag.sum * 10) / 10,
      wMax: Math.round(wDiag.max * 10) / 10,
      wRows: wDiag.rows,
      sampleMs: 0,
    },
  };
}
