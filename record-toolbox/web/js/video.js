// js/video.js —— 录屏模式：<video> 帧源 + 分析编排 + 分页渲染
//
// 契约见 docs/CONTRACTS.md「js/video.js」。纯浏览器模块（依赖 document/HTMLVideoElement），
// 算法全部复用 stitch.js，不在本文件重复实现。
// 低配电脑优先：采样串行、帧上下文只建一份、渲染时整帧图最多缓存 3 张。

import {
  ANALYSIS_WIDTH,
  StitchError,
  analyze,
  drawPageOps,
  grayFromRGBA,
  renderPages,
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
  if (!provider || typeof provider.sample !== 'function') throw new StitchError('缺少录屏帧源');
  if (typeof provider.open === 'function') await provider.open();
  return analyze(provider, options || {}, onProgress);
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
