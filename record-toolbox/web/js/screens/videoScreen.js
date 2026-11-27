/**
 * screens/videoScreen.js —— 方案 B：录屏转截图。
 *
 * 流程：选择录屏 → 预览与选项 → 分析进度 → 结果页（缩略图 / 保存 / 打开）。
 * 拼接算法在 /js/video.js 与 /js/stitch.js 中，动态 import，缺失时显示「引擎加载中」。
 */
import { api, videoUrl } from '../api.js';
import * as ui from '../ui.js';
import { appendRecord } from './records.js';

const { h, icon } = ui;

const TILE_FACTORS = { short: 0.85, standard: 1, tall: 1.15 };
const PAGE_HEIGHT_ITEMS = [
  { value: 'short', label: '更短' },
  { value: 'standard', label: '标准' },
  { value: 'tall', label: '更长' },
];
const RECORD_TIPS = [
  '录制前先把聊天记录滚动到要开始的位置，再开始录屏。',
  '手指匀速向下滑动，每一屏稍作停留（约半秒），画面重叠更充分。',
  '滑到最早的消息后停留约两秒再结束录屏，导出的分页会更完整。',
];
const E2E_MODE = /(?:^|[?&])e2e=1(?:&|$)/.test(window.location.search);

/* ------------------------------------------------------------------ 小工具 */
function clampNumber(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function parseProgress(...args) {
  let phase = '';
  let progress = null;
  for (const arg of args) {
    if (typeof arg === 'string') {
      phase = arg;
      continue;
    }
    if (typeof arg === 'number' && Number.isFinite(arg)) {
      progress = arg;
      continue;
    }
    if (arg && typeof arg === 'object') {
      if (typeof arg.phase === 'string') phase = arg.phase;
      else if (typeof arg.stage === 'string') phase = arg.stage;
      else if (typeof arg.label === 'string') phase = arg.label;
      if (typeof arg.progress === 'number' && Number.isFinite(arg.progress)) progress = arg.progress;
      else if (typeof arg.percent === 'number' && Number.isFinite(arg.percent)) progress = arg.percent / 100;
      else if (typeof arg.ratio === 'number' && Number.isFinite(arg.ratio)) progress = arg.ratio;
    }
  }
  if (progress !== null) {
    if (progress > 1.5) progress /= 100;
    progress = clampNumber(progress, 0, 1);
  }
  return { phase, progress };
}

function pageToDataUrl(...args) {
  const visit = (item, depth) => {
    if (item === null || item === undefined || depth > 4) return '';
    if (typeof item === 'string') {
      return item.startsWith('data:') ? item : '';
    }
    if (typeof Blob !== 'undefined' && item instanceof Blob) {
      return URL.createObjectURL(item);
    }
    if (typeof HTMLCanvasElement !== 'undefined' && item instanceof HTMLCanvasElement) {
      try {
        return item.toDataURL('image/png');
      } catch (error) {
        return '';
      }
    }
    if (typeof HTMLImageElement !== 'undefined' && item instanceof HTMLImageElement) {
      return item.src || '';
    }
    if (typeof item === 'object') {
      for (const key of ['dataUrl', 'dataURL', 'data', 'url', 'src', 'image', 'canvas', 'blob']) {
        if (key in item) {
          const found = visit(item[key], depth + 1);
          if (found) return found;
        }
      }
    }
    return '';
  };
  for (const arg of args) {
    const found = visit(arg, 0);
    if (found) return found;
  }
  return '';
}

function stampNow() {
  const date = new Date();
  const p = (value) => String(value).padStart(2, '0');
  return `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}_${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`;
}

function formatDuration(seconds) {
  const value = Math.max(0, Math.round(Number(seconds) || 0));
  const minutes = Math.floor(value / 60);
  const rest = value % 60;
  return minutes ? `${minutes} 分 ${rest} 秒` : `${rest} 秒`;
}

/* ------------------------------------------------------------------ 页面 */
export function mount(root, ctx) {
  root.dataset.page = 'video';

  const settings = () => ctx.state.settings || {};
  const settingsNumber = (value, fallback) => {
    const number = Number(value);
    return Number.isFinite(number) && number > 0 ? number : fallback;
  };

  let sid = '';
  let meta = null;
  let info = null;
  let videoError = '';
  let picking = false;
  let busy = null; // 'processing'
  let busyRun = 0;
  let cancelled = false;
  let phase = '';
  let progress = 0;
  let pages = [];
  let files = [];
  let result = null;
  let saved = null;
  let saving = false;
  let setupError = '';
  let engineMissing = false;
  let disposed = false;
  let engine = { video: null, stitch: null, status: 'loading' };
  let opts = {
    pageHeight: settings().page_height || 'standard',
    zip: false,
    crop: settings().crop_status_input !== false,
  };
  let refs = {};

  /* ---------------------- 调试状态 ---------------------- */
  function publishDebug() {
    if (typeof ctx.setDebug !== 'function') return;
    ctx.setDebug('video', {
      hasVideo: Boolean(sid),
      running: busy === 'processing',
      processing: busy === 'processing',
      phase,
      progress,
      pages: (result && result.pageCount) || pages.length,
      saved: Boolean(saved),
      error: setupError || videoError || '',
    });
  }

  /* ---------------------- 引擎加载 ---------------------- */
  async function loadEngine() {
    const [videoModule, stitchModule] = await Promise.all([
      ui.tryImport('/js/video.js'),
      ui.tryImport('/js/stitch.js'),
    ]);
    engine.video = videoModule || null;
    engine.stitch = stitchModule || null;
    engine.status = engine.video && engine.stitch ? 'ready' : 'missing';
    return engine.status === 'ready';
  }

  function engineReady() {
    return Boolean(engine.video && engine.stitch && engine.video.VideoProvider && engine.video.analyzeVideo);
  }

  /* ---------------------- 选择录屏 ---------------------- */
  async function pickVideo() {
    if (picking || busy) return;
    picking = true;
    render();
    try {
      let response;
      if (E2E_MODE) {
        const injected = window.__RT_E2E_VIDEO;
        if (!injected) {
          ui.toast('测试模式缺少视频路径', 'error');
          return;
        }
        response = await api('session.create', { kind: 'video', path: injected });
        if (!response || !response.sid) throw new Error('无法打开这段录屏');
        meta = response.video || {
          name: String(injected).split(/[\\/]/).pop() || '录屏文件',
          size: 0,
          path: injected,
        };
      } else {
        response = await api('pick_video');
        if (!response || response.canceled) return;
        meta = response.video || { name: '录屏文件', size: 0, path: '' };
      }
      sid = response.sid;
      info = null;
      videoError = '';
      pages = [];
      files = [];
      result = null;
      saved = null;
      setupError = '';
      engineMissing = false;
      phase = '';
      progress = 0;
      render();
    } catch (error) {
      if (error && error.canceled) return;
      ctx.reportError('打开录屏失败：' + ((error && error.message) || error));
    } finally {
      picking = false;
      render();
    }
  }

  /* ---------------------- 视频信息 ---------------------- */
  function videoSubtitle() {
    const parts = [];
    if (meta && meta.size) parts.push(ui.formatBytes(meta.size));
    if (info && info.duration) parts.push(formatDuration(info.duration));
    if (info && info.width && info.height) parts.push(info.width + '×' + info.height);
    return parts.length ? parts.join(' · ') : '正在读取视频信息…';
  }

  function attachVideo(el) {
    el.addEventListener('loadedmetadata', () => {
      if (disposed) return;
      info = {
        width: el.videoWidth || 0,
        height: el.videoHeight || 0,
        duration: Number.isFinite(el.duration) ? el.duration : 0,
      };
      if (refs.videoSub) refs.videoSub.textContent = videoSubtitle();
      if (refs.videoMetaRow) refs.videoMetaRow.textContent = videoSubtitle();
      publishDebug();
    });
    el.addEventListener('error', () => {
      if (disposed || videoError) return;
      videoError = '无法预览或读取这段录屏，可能是编码不受支持（例如 H.265 / HEVC）。请把视频转成 MP4（H.264）后重试。';
      render();
    });
  }

  /* ---------------------- 采集/分析进度 ---------------------- */
  function updateProcessingUI() {
    const progressEl = refs.progress;
    const percentEl = refs.percent;
    const phaseEl = refs.phase;
    if (phaseEl) phaseEl.textContent = phase || '正在分析画面…';
    if (progressEl) progressEl.set(progress);
    if (percentEl) percentEl.textContent = Math.round(progress * 100) + '%';
    publishDebug();
  }

  function makeProgressHandler(run, base, span, textBuilder) {
    return (...args) => {
      if (disposed || run !== busyRun) return;
      const parsed = parseProgress(...args);
      if (parsed.phase && textBuilder) phase = textBuilder(parsed.phase);
      else if (parsed.phase) phase = parsed.phase;
      if (parsed.progress !== null) progress = clampNumber(base + parsed.progress * span, 0.01, 0.99);
      updateProcessingUI();
    };
  }

  /* ---------------------- 裁剪区域 ---------------------- */
  // 返回裁剪后的内容区域边界：topPx 为上边界，botPx 为下边界（原始帧像素坐标）。
  function computeCrop(analysis) {
    const frameHeight = (analysis && (analysis.frameHeight || analysis.height)) || (info && info.height) || 0;
    if (!opts.crop || !frameHeight) return { topPx: 0, botPx: frameHeight };
    const detected = analysis && analysis.crop;
    if (detected && Number.isFinite(detected.topPx) && Number.isFinite(detected.botPx)) {
      const cropH = detected.botPx - detected.topPx;
      if (cropH > frameHeight * 0.3 && detected.topPx >= 0 && detected.botPx <= frameHeight) {
        return { topPx: Math.round(detected.topPx), botPx: Math.round(detected.botPx) };
      }
    }
    return {
      topPx: Math.round(frameHeight * 0.055),
      botPx: frameHeight - Math.round(frameHeight * 0.1),
    };
  }

  /* ---------------------- 开始分析 ---------------------- */
  async function startAnalysis() {
    if (busy || !sid) return;
    if (!engineReady()) await loadEngine();
    if (!engineReady()) {
      engineMissing = true;
      render();
      return;
    }
    busy = 'processing';
    cancelled = false;
    setupError = '';
    engineMissing = false;
    pages = [];
    files = [];
    result = null;
    saved = null;
    phase = '正在读取录屏…';
    progress = 0.02;
    const run = ++busyRun;
    const startedAt = Date.now();

    ctx.setLeaveGuard(async () => {
      if (busy !== 'processing') return true;
      const allowed = await ui.confirm({
        title: '分析还没完成',
        message: '现在离开会取消本次分析，已生成的页面不会保留。',
        okText: '取消并离开',
        cancelText: '继续等待',
        danger: true,
      });
      if (allowed) cancelled = true;
      return allowed;
    });
    render();
    updateProcessingUI();

    try {
      const provider = new engine.video.VideoProvider(videoUrl(sid));
      const opened = await provider.open();
      if (disposed || run !== busyRun) return;
      if (cancelled) return;
      if (opened && (opened.width || opened.height)) {
        info = {
          width: opened.width || (info && info.width) || 0,
          height: opened.height || (info && info.height) || 0,
          duration: opened.duration || (info && info.duration) || 0,
        };
        if (!meta || !meta.name) meta = { ...(meta || {}), name: opened.name || '录屏文件' };
      }
      if (refs.videoSub) refs.videoSub.textContent = videoSubtitle();

      const analysis = await engine.video.analyzeVideo(provider, {
        // VideoProvider 只在 open() 的返回值里给出时长，这里显式传给 stitch.analyze
        duration: (opened && opened.duration) || (info && info.duration) || 0,
        fps: settingsNumber(settings().video_analysis_fps, 10),
        refineRounds: settingsNumber(settings().video_refine_rounds, 2),
      }, makeProgressHandler(run, 0.05, 0.5, () => '正在分析画面…'));
      if (disposed || run !== busyRun || cancelled) return;

      phase = '正在拼接页面…';
      progress = Math.max(progress, 0.56);
      updateProcessingUI();

      const crop = computeCrop(analysis);
      const cropHpx = Math.max(360, crop.botPx - crop.topPx);
      const factor = TILE_FACTORS[opts.pageHeight] || TILE_FACTORS.standard;
      const tileH = Math.max(240, Math.round(cropHpx * factor));
      const plan = engine.stitch.planMaster(analysis, crop.topPx, crop.botPx, tileH);
      const planStats = (plan && plan.stats) || {};
      const planOverlap = Number.isFinite(planStats.medianOverlapRatio) ? planStats.medianOverlapRatio : null;
      const analysisStats = (analysis && analysis.stats) || {};
      const skippedFrames = Number(analysisStats.skippedFrames) || 0;
      const imputedSteps = Number(analysisStats.imputedSteps) || 0;
      const collected = [];
      const renderDebug = { calls: 0, ok: 0, nullUrl: 0, skippedDisposed: 0, errors: [] };
      await engine.video.renderVideoPages(
        plan,
        provider,
        { cropTopPx: crop.topPx, cropBotPx: crop.botPx, tileH },
        (...args) => {
          renderDebug.calls++;
          if (disposed || run !== busyRun) {
            renderDebug.skippedDisposed++;
            return;
          }
          let dataUrl = null;
          try {
            dataUrl = pageToDataUrl(...args);
          } catch (err) {
            if (renderDebug.errors.length < 5) renderDebug.errors.push(String((err && err.message) || err));
          }
          if (!dataUrl) {
            renderDebug.nullUrl++;
            return;
          }
          renderDebug.ok++;
          collected.push(dataUrl);
          pages = collected.map((src, index) => ({ src, label: '第 ' + (index + 1) + ' 页' }));
          if (refs.pageCount) refs.pageCount.textContent = '已生成 ' + collected.length + ' 页';
        },
        makeProgressHandler(run, 0.56, 0.42, (text) => text || '正在拼接页面…'),
      );
      if (disposed || run !== busyRun) return;
      if (cancelled) return;
      if (!collected.length) throw new Error('没有生成任何截图页，请换一段录屏重试。');

      files = collected.map((src, index) => ({ name: ui.pad3(index + 1) + '.png', data: src }));
      result = {
        pages: pages.slice(),
        files,
        pageCount: files.length,
        duration: (info && info.duration) || 0,
        resolution: info && info.width ? info.width + '×' + info.height : '',
        overlap: planOverlap,
        skippedFrames,
        imputedSteps,
        elapsed: Date.now() - startedAt,
        saved: null,
      };
      busy = null;
      progress = 1;
      phase = '分析完成';
      ctx.clearLeaveGuard && ctx.clearLeaveGuard();
      render();
    } catch (error) {
      if (disposed || run !== busyRun || cancelled) return;
      busy = null;
      ctx.clearLeaveGuard && ctx.clearLeaveGuard();
      setupError = (error && error.message) || String(error);
      render();
    }
  }

  /* ---------------------- 取消分析 ---------------------- */
  function cancelProcessing() {
    if (busy !== 'processing') return;
    cancelled = true;
    busy = null;
    phase = '';
    progress = 0;
    ctx.clearLeaveGuard && ctx.clearLeaveGuard();
    ui.toast('已取消分析', 'info');
    render();
  }

  /* ---------------------- 保存 ---------------------- */
  function folderName() {
    return '录屏转截图_' + stampNow();
  }

  async function saveResult() {
    if (!result || saving) return;
    saving = true;
    render();
    try {
      const response = await api('export.save', {
        sid: sid || undefined,
        dir: settings().output_dir || undefined,
        noOpen: E2E_MODE,
        zip: opts.zip,
        files: result.files,
        meta: {
          contact: '',
          method: '录屏转截图',
          range: '整段录屏',
          pageCount: result.pageCount,
          folderName: folderName(),
          stats: {
            durationSec: Math.round(result.duration || 0),
            resolution: result.resolution || '',
          },
        },
      });
      if (!response || response.ok === false) {
        if (response && response.canceled) ui.toast('已取消保存', 'info');
        return;
      }
      saved = { dir: response.dir || '', zip: response.zip || '', pageCount: response.pageCount || result.pageCount };
      result.saved = saved;
      try {
        appendRecord({
          name: folderName(),
          contact: '',
          method: '录屏转截图',
          pageCount: saved.pageCount,
          dir: saved.dir,
          zip: saved.zip,
        });
      } catch (error) { /* 记录写入失败不影响保存结果 */ }
      ui.toast('已保存到 ' + (saved.dir || '所选文件夹'), 'success');
    } catch (error) {
      ctx.reportError('保存失败：' + ((error && error.message) || error));
    } finally {
      saving = false;
      render();
    }
  }

  async function openSavedFolder() {
    if (!saved || !saved.dir) {
      ui.toast('还没有保存结果', 'info');
      return;
    }
    try {
      await api('open_folder', { path: saved.dir });
    } catch (error) { /* api 已 toast */ }
  }

  /* ---------------------- 重置 ---------------------- */
  function resetVideo() {
    if (busy) return;
    const oldSid = sid;
    sid = '';
    meta = null;
    info = null;
    videoError = '';
    pages = [];
    files = [];
    result = null;
    saved = null;
    saving = false;
    setupError = '';
    engineMissing = false;
    phase = '';
    progress = 0;
    render();
    if (oldSid) api('session.drop', { sid: oldSid }, { silent: true }).catch(() => {});
  }

  /* ---------------------- 页面片段 ---------------------- */
  function pageHead() {
    return h('div', { class: 'page-head' },
      h('div', { class: 'page-heading' },
        h('h1', { class: 'page-title' }, '录屏转截图'),
        h('p', { class: 'page-sub' }, '选择一段手机录屏，自动拼成聊天记录长截图并分页保存。')),
      h('div', { class: 'page-head-actions' }));
  }

  function pickCard() {
    const pickButton = ui.button({
      label: picking ? '正在打开…' : '选择录屏',
      icon: 'film',
      variant: 'primary',
      size: 'lg',
      block: true,
      disabled: picking,
      onClick: pickVideo,
    });
    pickButton.setAttribute('data-testid', 'video-pick');
    return h('div', { class: 'card pick-card' },
      h('div', { class: 'pick-visual' },
        h('div', { class: 'pick-icon' }, icon('film', 30)),
        h('div', { class: 'pick-copy' },
          h('div', { class: 'pick-title' }, '选择手机录屏文件'),
          h('div', { class: 'pick-desc' }, '支持 MP4、MOV 等常见格式。若无法预览，请先把录屏转成 MP4（H.264）后重试。'))),
      h('div', { class: 'pick-actions' }, pickButton));
  }

  function tipsCard() {
    return h('section', { class: 'card section video-tips-card' },
      h('div', { class: 'section-head' },
        h('div', { class: 'section-icon' }, icon('info', 17)),
        h('div', { class: 'section-heading' },
          h('h2', { class: 'section-title' }, '录制建议'),
          h('p', { class: 'section-desc' }, '按下面的方式录制，拼接出来的分页会更完整。'))),
      h('div', { class: 'section-body' },
        h('ol', { class: 'video-tip-list' },
          ...RECORD_TIPS.map((text, index) => h('li', { class: 'video-tip' },
            h('span', { class: 'video-tip-index' }, String(index + 1)),
            h('span', { class: 'video-tip-text' }, text))))));
  }

  function videoCard() {
    const video = h('video', {
      class: 'video-preview',
      src: videoUrl(sid),
      controls: 'controls',
      preload: 'metadata',
      playsinline: '',
    });
    attachVideo(video);
    const reselect = ui.button({
      label: '重新选择',
      icon: 'refresh',
      variant: 'ghost',
      size: 'sm',
      disabled: busy === 'processing',
      onClick: resetVideo,
    });
    const subtitle = h('div', { class: 'video-sub', ref: (el) => { refs.videoSub = el; } }, videoSubtitle());
    const card = h('div', { class: 'card video-card' },
      h('div', { class: 'video-preview-wrap' },
        video,
        h('div', { class: 'video-preview-badge' }, icon('film', 13), '录屏文件')),
      h('div', { class: 'video-meta' },
        h('div', { class: 'video-meta-text' },
          h('div', { class: 'video-name' }, (meta && meta.name) || '录屏文件'),
          subtitle),
        h('div', { class: 'video-meta-actions' }, reselect)));
    return card;
  }

  function optionsCard() {
    const heightSegment = ui.segmented({
      items: PAGE_HEIGHT_ITEMS,
      value: opts.pageHeight,
      onChange: (value) => { opts.pageHeight = value; },
    });
    const zipSwitch = ui.switchRow({
      label: '同时打包 ZIP',
      desc: '保存时额外生成一个压缩包，方便发送。',
      checked: opts.zip,
      onChange: (checked) => { opts.zip = checked; },
    });
    const cropSwitch = ui.switchRow({
      label: '自动裁掉顶部状态栏与底部输入框',
      desc: '只保留聊天内容，避免每页重复出现状态栏。',
      checked: opts.crop,
      onChange: (checked) => { opts.crop = checked; },
    });
    return ui.sectionCard({
      title: '导出选项',
      desc: '相邻画面之间会自动去重，输出内容和顺序连续的截图。',
      icon: 'sparkles',
      children: [
        h('div', { class: 'setting-row' },
          h('div', { class: 'setting-text' },
            h('div', { class: 'setting-label' }, '每页高度'),
            h('div', { class: 'setting-desc' }, '每张截图的长度，越长页数越少。')),
          h('div', { class: 'setting-control' }, heightSegment)),
        zipSwitch,
        cropSwitch,
      ],
    });
  }

  function startCard() {
    const startButton = ui.button({
      label: '开始分析',
      icon: 'sparkles',
      variant: 'primary',
      size: 'lg',
      block: true,
      disabled: busy === 'processing' || !sid,
      onClick: startAnalysis,
    });
    startButton.setAttribute('data-testid', 'video-start');
    const hintParts = [];
    if (info && info.duration) hintParts.push('录屏时长 ' + formatDuration(info.duration));
    hintParts.push('分析时请保持窗口打开，可以随时取消。');
    return h('div', { class: 'card start-card' },
      h('div', { class: 'start-head' },
        h('div', { class: 'start-icon' }, icon('sparkles', 20)),
        h('div', { class: 'start-text' },
          h('div', { class: 'start-title' }, '自动拼接'),
          h('div', { class: 'start-desc' }, '逐帧分析录屏内容，识别滚动位移并去除重复部分，最后按页高切成图片。'))),
      h('div', { class: 'start-actions' }, startButton),
      h('div', { class: 'start-hint' }, hintParts.join(' · ')));
  }

  function processingCard() {
    const progressEl = ui.progressBar({ value: progress, indeterminate: false });
    progressEl.el.setAttribute('data-testid', 'video-progress');
    refs.progress = progressEl;
    refs.phase = h('div', { class: 'processing-phase' }, phase || '正在分析画面…');
    refs.percent = h('div', { class: 'processing-percent' }, Math.round(progress * 100) + '%');
    refs.pageCount = h('div', { class: 'processing-count' }, '已生成 0 页');
    const cancelButton = ui.button({
      label: '取消',
      variant: 'ghost',
      size: 'sm',
      onClick: cancelProcessing,
    });
    return h('div', { class: 'card processing-card' },
      h('div', { class: 'processing-head' },
        h('div', { class: 'processing-icon' }, ui.spinner('', 22)),
        h('div', { class: 'processing-text' },
          h('div', { class: 'processing-title' }, '正在分析录屏'),
          refs.phase)),
      h('div', { class: 'processing-progress-row' }, progressEl.el, refs.percent),
      h('div', { class: 'processing-meta' }, refs.pageCount),
      h('div', { class: 'processing-tip' }, '录屏越长，分析时间越久。分析期间请勿关闭程序。'),
      h('div', { class: 'processing-actions' }, cancelButton));
  }

  function resultCard() {
    const pageCount = result.pageCount;
    const saveButton = ui.button({
      label: saving ? '正在保存…' : (saved ? '再次保存' : '保存到文件夹'),
      icon: 'save',
      variant: 'primary',
      size: 'lg',
      block: true,
      disabled: saving,
      onClick: saveResult,
    });
    saveButton.setAttribute('data-testid', 'video-save');
    const openButton = ui.button({
      label: '打开文件夹',
      icon: 'folder',
      variant: 'secondary',
      disabled: !saved,
      onClick: openSavedFolder,
    });
    openButton.setAttribute('data-testid', 'video-open');
    const againButton = ui.button({
      label: '重新选择录屏',
      icon: 'refresh',
      variant: 'ghost',
      onClick: resetVideo,
    });
    const stats = h('div', { class: 'stat-grid' },
      ui.statTile({ label: '截图页数', value: String(pageCount), unit: '页' }),
      ui.statTile({ label: '录屏时长', value: result.duration ? formatDuration(result.duration) : '—' }),
      ui.statTile({ label: '分辨率', value: result.resolution || '—' }),
      ui.statTile({ label: '耗时', value: result.elapsed ? ui.formatMs(result.elapsed) : '—' }));
    const qualityNote = (Number(result.skippedFrames) > 0 || Number(result.imputedSteps) > 0)
      ? h('div', { class: 'result-quality-note' },
        '录屏里有 ' + (Number(result.skippedFrames) + Number(result.imputedSteps))
        + ' 帧因为滑动太快没能完整对齐，已自动跳过或就近补齐；个别位置可能有轻微重复/缺失。下次录制时慢一点、匀速滑动效果更好。')
      : null;
    const savedRow = saved
      ? h('div', { class: 'result-saved' },
        icon('checkCircle', 16),
        h('span', { class: 'result-saved-path', title: saved.dir }, '已保存到 ' + saved.dir))
      : null;
    return h('div', { class: 'card result-card', 'data-testid': 'video-result' },
      h('div', { class: 'result-head' },
        h('div', { class: 'result-icon' }, icon('checkCircle', 22)),
        h('div', { class: 'result-text' },
          h('div', { class: 'result-title' }, '分析完成'),
          h('div', { class: 'result-sub' }, '共生成 ' + pageCount + ' 页截图，相邻页面内容连续、已自动去重，可以保存到电脑或直接打开查看。'))),
      qualityNote,
      stats,
      savedRow,
      h('div', { class: 'result-actions' }, saveButton, h('div', { class: 'result-secondary' }, openButton, againButton)));
  }

  function thumbsCard() {
    if (!result || !result.pages.length) return null;
    return ui.sectionCard({
      title: '截图预览',
      desc: '点击保存后可以在文件夹中查看完整图片。',
      icon: 'image',
      children: [ui.thumbsGrid(result.pages, { limit: 60, emptyText: '暂无截图' })],
    });
  }

  function errorBanner() {
    const actions = [];
    if (engineMissing) {
      actions.push(ui.button({
        label: '重新加载引擎',
        variant: 'secondary',
        size: 'sm',
        onClick: async () => { await loadEngine(); engineMissing = !engineReady(); render(); },
      }));
    }
    actions.push(ui.button({
      label: '重新选择',
      variant: 'ghost',
      size: 'sm',
      onClick: resetVideo,
    }));
    if (setupError) {
      return ui.banner({
        kind: 'danger',
        title: '分析没有完成',
        message: setupError,
        actions,
      });
    }
    if (engineMissing) {
      return ui.banner({
        kind: 'warn',
        title: '引擎加载中',
        message: '拼接引擎正在加载，请稍后重试。如果一直显示这个提示，请重新打开程序。',
        actions,
      });
    }
    if (videoError) {
      return ui.banner({
        kind: 'warn',
        title: '视频读取失败',
        message: videoError,
        actions: [ui.button({ label: '重新选择', variant: 'secondary', size: 'sm', onClick: resetVideo })],
      });
    }
    return null;
  }

  /* ---------------------- 渲染 ---------------------- */
  function render() {
    if (disposed) return;
    publishDebug();
    const bodyChildren = [];
    const bannerEl = errorBanner();
    if (bannerEl) bodyChildren.push(bannerEl);

    if (!sid) {
      bodyChildren.push(pickCard(), tipsCard());
    } else {
      bodyChildren.push(videoCard());
      if (result) {
        bodyChildren.push(resultCard());
        const thumbs = thumbsCard();
        if (thumbs) bodyChildren.push(thumbs);
      } else if (busy === 'processing') {
        bodyChildren.push(processingCard());
      } else {
        bodyChildren.push(optionsCard(), startCard());
      }
    }

    const page = h('div', { class: 'page video-page', 'data-page': 'video' },
      pageHead(),
      h('div', { class: 'video-stack' }, ...bodyChildren));
    root.replaceChildren(page);
  }

  /* ---------------------- 生命周期 ---------------------- */
  async function init() {
    loadEngine().then(() => render());
    render();
  }

  init();

  return function cleanup() {
    disposed = true;
    cancelled = true;
    ctx.clearLeaveGuard && ctx.clearLeaveGuard();
    const oldSid = sid;
    sid = '';
    if (oldSid) api('session.drop', { sid: oldSid }, { silent: true }).catch(() => {});
  };
}
