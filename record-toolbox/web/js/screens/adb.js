/**
 * screens/adb.js —— 方案 A：连接手机导出截图。
 *
 * 流程：连接设备（USB / 无线）→ 预览 → 自动滑动截图 → 拼接 → 保存。
 * 截图循环与拼接引擎解耦：截图使用后端 /api/screencap + /api/swipe，
 * 拼接依赖并行的算法模块（/js/stitch.js、/js/adbcap.js），缺失时降级为「引擎加载中」。
 */
import {
  h, icon, sleep, tryImport, formatMs, formatClock, pad3,
  button, segmented, switchRow, progressBar, spinner, skeleton, emptyState,
  sectionCard, banner, statTile, field, toast, confirm, thumbsGrid, copyText,
} from '../ui.js';
import { api, shotUrl, loadImage, fetchAsDataUrl } from '../api.js';
import { openUsbGuide } from './home.js';
import { appendRecord } from './records.js';

const TILE_HEIGHTS = { short: 1400, standard: 2000, tall: 2600 };
const TILE_FACTORS = { short: 0.85, standard: 1, tall: 1.15 };
const CHAT_TOP_RATIO = 0.055;   // 预估状态栏高度（仅用于粗略统计）
const CHAT_BOTTOM_RATIO = 0.10; // 预估输入框高度（仅用于粗略统计）
/* 手势起点必须落在聊天列表内部：0.055 只够状态栏，标题栏还会占掉约 8%，
   起点落进标题栏（不可滚动区域）时整段滑动会被系统忽略、页面纹丝不动。 */
const SWIPE_START_RATIO = 0.14;    // 手势起点（状态栏 + 标题栏以下）
const SWIPE_BOTTOM_RATIO = 0.12;   // 手势终点距屏幕底部的安全距离（输入框区域）
const SWIPE_STEP_RATIO = 0.85;     // 单次滑动覆盖聊天区高度的比例（约 15% 重叠）
const SWIPE_RETRY_START_RATIO = 0.3; // 重试时换更靠下的起点，避开任何标题栏
const SWIPE_DURATION = 900;        // 常规滑动时长（越慢越不容易丢帧）
const SWIPE_SETTLE = 800;          // 滑动后固定等待，等页面停稳再截图
const SWIPE_RETRY_DURATION = 1100; // 「未滚动」时换更慢的滑动重试
const SAME_SCREEN_RETRIES = 2;     // 「未滚动」最多重试次数
const SAMPLE_STEP = 48;            // 轻量比对采样间隔（每 48 行/列取一点）
const SAME_SAMPLE_RATIO = 0.995;   // 采样点一致率超过该比例视为同一屏

/** e2e 测试旁路：?e2e=1 且注入 window.__RT_E2E_SHOTS（PNG dataURL，最旧→最新）。 */
const E2E_MODE = /(?:^|[?&])e2e=1(?:&|$)/.test(location.search);
const E2E_SHOT_SID = '__rtb_e2e__';

/* ------------------------------------------------------------------ 小工具 */
function formatPx(value) {
  const n = Math.round(Number(value) || 0);
  return n.toLocaleString('zh-CN') + ' px';
}

function average(list) {
  if (!list || list.length === 0) return 0;
  const sum = list.reduce((acc, value) => acc + (Number(value) || 0), 0);
  return sum / list.length;
}

/** 防御性解析进度回调（不同引擎可能传 (phase, p) / {phase, progress} / (p)）。 */
function parseProgress(a, b) {
  let phase = '';
  let fraction = null;
  const grab = (value) => {
    if (value === null || value === undefined) return;
    if (typeof value === 'string') {
      if (!phase) phase = value;
      return;
    }
    if (typeof value === 'number') {
      if (Number.isFinite(value)) fraction = value > 1 ? value / 100 : value;
      return;
    }
    if (typeof value === 'object') {
      if (!phase) phase = value.phase || value.stage || value.label || value.message || '';
      const n = value.progress ?? value.value ?? value.percent ?? value.ratio ?? value.fraction;
      if (typeof n === 'number' && Number.isFinite(n)) fraction = n > 1 ? n / 100 : n;
    }
  };
  grab(a);
  grab(b);
  if (fraction !== null) fraction = Math.max(0, Math.min(1, fraction));
  return { phase, fraction };
}

/* ------------------------------------------------------------------ e2e 旁路截图源 */
/** 返回注入的 dataURL 列表（最旧→最新）；未开启旁路时返回 null。 */
function e2eShotDataUrls() {
  if (!E2E_MODE) return null;
  const list = window.__RT_E2E_SHOTS;
  if (!Array.isArray(list)) return null;
  const urls = list.filter((item) => typeof item === 'string' && item.startsWith('data:image/'));
  return urls.length >= 3 ? urls : null;
}

/** 拦截引擎对 /media/shot/__rtb_e2e__/<id>.png 的取图，改为读取注入的 dataURL。 */
function installE2EShotFetch(urls) {
  const originalFetch = window.fetch.bind(window);
  window.fetch = (input, init) => {
    const rawUrl = typeof input === 'string' ? input : (input && input.url) || '';
    const match = /\/media\/shot\/__rtb_e2e__\/(\d+)\.png(?:[?#].*)?$/.exec(String(rawUrl));
    if (match) {
      const dataUrl = urls[Number(match[1])];
      if (dataUrl) return originalFetch(dataUrl, init);
    }
    return originalFetch(input, init);
  };
  return () => { window.fetch = originalFetch; };
}

/** 把注入的 dataURL 转成引擎需要的 shots（最新→最旧），按最旧→最新编号供拦截器取图。 */
async function collectE2EShots(urls) {
  const oldestFirst = [];
  for (let index = 0; index < urls.length; index += 1) {
    const image = await loadImage(urls[index]);
    oldestFirst.push({
      id: index,
      width: image.naturalWidth || image.width || 0,
      height: image.naturalHeight || image.height || 0,
      dataUrl: urls[index],
    });
  }
  return oldestFirst.slice().reverse();
}

/* ------------------------------------------------------------------ 截图比较（不依赖引擎） */
async function frameSignature(sid, shot) {
  const image = await loadImage(shot.url || shot.dataUrl || shotUrl(sid, shot.id));
  const width = 72;
  const height = Math.max(24, Math.round(width * (image.naturalHeight || shot.height || 1) / (image.naturalWidth || shot.width || 1)));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  context.drawImage(image, 0, 0, width, height);
  const data = context.getImageData(0, 0, width, height).data;
  const rows = new Float32Array(height);
  for (let y = 0; y < height; y += 1) {
    let sum = 0;
    for (let x = 0; x < width; x += 1) {
      const index = (y * width + x) * 4;
      sum += data[index] * 0.299 + data[index + 1] * 0.587 + data[index + 2] * 0.114;
    }
    rows[y] = sum / width;
  }

  // 轻量采样：每 SAMPLE_STEP 行/列取一点，用于判断「这一屏有没有滚动」。
  const nativeWidth = image.naturalWidth || shot.width || 0;
  const nativeHeight = image.naturalHeight || shot.height || 0;
  const cols = Math.max(2, Math.ceil(nativeWidth / SAMPLE_STEP));
  const sampleRows = Math.max(2, Math.ceil(nativeHeight / SAMPLE_STEP));
  const sampleCanvas = document.createElement('canvas');
  sampleCanvas.width = cols;
  sampleCanvas.height = sampleRows;
  const sampleContext = sampleCanvas.getContext('2d', { willReadFrequently: true });
  sampleContext.drawImage(image, 0, 0, cols, sampleRows);
  const sampleData = sampleContext.getImageData(0, 0, cols, sampleRows).data;
  const samples = new Float32Array(cols * sampleRows);
  for (let i = 0; i < samples.length; i += 1) {
    const index = i * 4;
    samples[i] = sampleData[index] * 0.299 + sampleData[index + 1] * 0.587 + sampleData[index + 2] * 0.114;
  }

  return { rows, height, samples, nativeWidth, nativeHeight };
}

/** 采样点一致率 > SAME_SAMPLE_RATIO 视为同一屏（没有滚动）。 */
function sameScreen(prev, current) {
  if (!prev || !current || !prev.samples || !current.samples) return false;
  const total = Math.min(prev.samples.length, current.samples.length);
  if (total === 0) return false;
  let same = 0;
  for (let i = 0; i < total; i += 1) {
    if (Math.abs(prev.samples[i] - current.samples[i]) <= 8) same += 1;
  }
  return same / total > SAME_SAMPLE_RATIO;
}

function compareSignatures(prev, current) {
  const start = Math.floor(Math.min(prev.height, current.height) * 0.06);
  const end = Math.ceil(Math.min(prev.height, current.height) * 0.94);
  const span = Math.max(1, end - start);
  let diffSum = 0;
  let diffCount = 0;
  let bigCount = 0;
  for (let y = start; y < end; y += 1) {
    const diff = Math.abs(prev.rows[y] - current.rows[y]);
    diffSum += diff;
    diffCount += 1;
    if (diff > 8) bigCount += 1;
  }
  const meanDiff = diffSum / Math.max(1, diffCount);
  const same = meanDiff < 1.6 && bigCount / span < 0.02;

  // 估计位移：手指向下滑后，旧内容整体下移 d 行 → prev[y] ≈ current[y + d]。
  let bestShift = 0;
  let bestScore = Infinity;
  const maxShift = Math.floor(prev.height * 0.85);
  for (let shift = 0; shift <= maxShift; shift += 1) {
    let score = 0;
    let count = 0;
    for (let y = start; y + shift < end; y += 3) {
      score += Math.abs(prev.rows[y] - current.rows[y + shift]);
      count += 1;
    }
    if (count < 20) continue;
    const averageScore = score / count;
    if (averageScore < bestScore) {
      bestScore = averageScore;
      bestShift = shift;
    }
  }
  const nativePixelsPerRow = prev.height > 0 ? (prev.nativeHeight || 0) / prev.height : 0;
  const shiftNative = Math.max(0, bestShift) * nativePixelsPerRow;
  const chatNative = (prev.nativeHeight || 0) * (1 - SWIPE_START_RATIO - SWIPE_BOTTOM_RATIO);
  const overlap = chatNative > 0 ? Math.max(0, Math.min(1, 1 - shiftNative / chatNative)) : null;
  return { same, meanDiff, shiftNative, overlap, bestScore };
}

/* ------------------------------------------------------------------ 页面图 → dataURL */
function loadHTMLImage(src) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('图片加载失败'));
    image.src = src;
  });
}

async function pageToDataUrl(...args) {
  for (const arg of args) {
    if (!arg) continue;
    if (typeof arg === 'string') {
      if (arg.startsWith('data:')) return arg;
      continue;
    }
    if (arg instanceof HTMLCanvasElement) {
      try { return arg.toDataURL('image/png'); } catch (error) { continue; }
    }
    if (typeof Blob !== 'undefined' && arg instanceof Blob) {
      return await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result || ''));
        reader.onerror = () => reject(new Error('读取图片失败'));
        reader.readAsDataURL(arg);
      });
    }
    if (arg && typeof arg === 'object') {
      if (typeof arg.dataUrl === 'string') return arg.dataUrl;
      if (typeof arg.dataURL === 'string') return arg.dataURL;
      if (typeof arg.url === 'string' && arg.url.startsWith('data:')) return arg.url;
      if (typeof arg.src === 'string' && arg.src.startsWith('data:')) return arg.src;
      if (arg.canvas instanceof HTMLCanvasElement) {
        try { return arg.canvas.toDataURL('image/png'); } catch (error) { continue; }
      }
      if (arg.image instanceof HTMLImageElement && arg.image.src) {
        const canvas = document.createElement('canvas');
        canvas.width = arg.image.naturalWidth || arg.image.width;
        canvas.height = arg.image.naturalHeight || arg.image.height;
        canvas.getContext('2d').drawImage(arg.image, 0, 0);
        return canvas.toDataURL('image/png');
      }
      const nested = await pageToDataUrl(arg.page, arg.image, arg.canvas);
      if (nested) return nested;
    }
  }
  return null;
}

/* ------------------------------------------------------------------ 页面 */
export function mount(root, ctx) {
  /* ---------------- 页面状态 ---------------- */
  let tab = 'connect';
  let busy = null; // null | 'running' | 'processing'
  let stopRequested = false;
  let cancelled = false;
  let sid = null;
  let previewSid = null;
  let previewShot = null;
  let previewLoading = false;
  let previewTried = false;
  let previewError = '';
  let shots = [];
  let capture = { overlaps: [], startedAt: 0, stopReason: '', chatHeight: 0, lastOverlap: null };
  let result = null; // { pages, files, pageCount, masterH, overlap, elapsed, stopReason, mode }
  let saved = null;
  let saving = false;
  let setupError = '';
  let procPhase = '正在准备…';
  let procProgress = 0;
  let procLog = [];
  let procEngineMissing = false;
  let deviceLoading = false;

  let engine = { stitch: null, adbcap: null, status: 'loading' };
  const exportOpts = { contact: '', stopMode: 'auto', zip: false };
  const wireless = {
    ip: '', connectHost: '', connectPort: '5555',
    pairHost: '', pairPort: '', pairCode: '',
    services: [], message: '', scanLoading: false,
  };

  let runningRefs = null;
  let processingRefs = null;

  const settings = () => ctx.state.settings || {};
  const getActiveDevice = () => (typeof ctx.activeDevice !== 'undefined' ? ctx.activeDevice : null);
  const getDevices = () => ((ctx.state.adb && ctx.state.adb.devices) || []);

  function publishDebug(extra = {}) {
    if (typeof ctx.setDebug !== 'function') return;
    const device = getActiveDevice();
    ctx.setDebug('adb', {
      connected: Boolean(device),
      serial: device ? device.serial : '',
      model: device ? (device.model || '') : '',
      devices: getDevices().length,
      tab,
      busy: busy || null,
      running: busy === 'running',
      processing: busy === 'processing',
      shots: shots.length,
      phase: procPhase || '',
      progress: procProgress || 0,
      resultPages: result ? result.pageCount : 0,
      saved: Boolean(saved),
      error: setupError || '',
      ...extra,
    });
  }

  /* ---------------- 外壳 ---------------- */
  function buildTabs() {
    const seg = segmented({
      items: [
        { value: 'connect', label: '连接设备' },
        { value: 'export', label: '导出截图' },
      ],
      value: tab,
      onChange: (value) => {
        if (busy) return;
        tab = value;
        if (value === 'export') ensurePreview();
        render();
      },
    });
    seg.querySelectorAll('.segmented-item').forEach((item) => {
      item.setAttribute('data-testid', item.dataset.value === 'connect' ? 'adb-tab-connect' : 'adb-tab-export');
      if (busy) item.disabled = true;
    });
    return seg;
  }

  function pageHead() {
    const device = getActiveDevice();
    return h('div', { class: 'page-head' },
      h('div', { class: 'page-heading' },
        h('h1', { class: 'page-title' }, '手机截图导出'),
        h('p', { class: 'page-sub' }, device
          ? `已连接 ${device.model || '手机'}，可以开始导出。`
          : '连接手机后自动滚动截图，自动去重并分页输出。')));
  }

  function shell(children) {
    const page = h('div', { class: 'page adb-page', 'data-page': 'adb' },
      pageHead(),
      buildTabs(),
      ...children);
    root.replaceChildren(page);
    return page;
  }

  function render() {
    if (busy === 'running') return;
    if (busy === 'processing') return;
    if (result) {
      renderResult();
      return;
    }
    if (tab === 'connect') renderConnect();
    else renderExport();
  }

  /* ---------------- 连接页 ---------------- */
  function renderConnect() {
    publishDebug();

    const adb = ctx.state.adb || {};
    const devices = getDevices();
    const list = h('div', { class: 'device-list', 'data-testid': 'adb-devices' });

    if (!adb.adbFound) {
      list.appendChild(banner({
        kind: 'warn',
        title: '还没有找到调试组件',
        message: adb.error || '请把 platform-tools 放到程序目录下的 adb 文件夹，或在「设置」中指定 adb.exe 路径。',
        actions: [
          button({ label: '去设置', size: 'sm', onClick: () => ctx.navigate('settings') }),
          button({ label: '如何开启 USB 调试', size: 'sm', onClick: () => openUsbGuide() }),
        ],
      }));
    } else if (devices.length === 0) {
      list.appendChild(emptyState({
        icon: 'smartphone',
        title: '暂未发现手机',
        desc: '用数据线把手机连接到电脑，手机上弹出「允许 USB 调试吗？」时点「允许」。',
        actions: [
          button({ label: '刷新设备', icon: 'refresh', size: 'sm', onClick: () => refreshDevices(true) }),
          button({ label: '查看连接步骤', size: 'sm', onClick: () => openUsbGuide() }),
        ],
      }));
    } else {
      for (const device of devices) {
        list.appendChild(deviceRow(device));
      }
      const active = getActiveDevice();
      if (active) {
        list.appendChild(h('div', { class: 'device-connected' },
          h('span', { class: 'device-connected-text' },
            icon('checkCircle', 16),
            h('span', {}, `已连接 ${active.model || '手机'}，可以开始导出。`)),
          button({ label: '下一步：导出截图', icon: 'arrowRight', size: 'sm', variant: 'primary', onClick: () => { tab = 'export'; ensurePreview(); render(); } })));
      }
    }

    const refreshBtn = button({ label: '刷新', icon: 'refresh', size: 'sm', onClick: () => refreshDevices(true) });
    refreshBtn.setAttribute('data-testid', 'adb-refresh');

    const deviceCard = sectionCard({
      title: '连接手机',
      desc: '支持数据线连接，也支持同一 Wi-Fi 下的无线连接。',
      icon: 'smartphone',
      extra: refreshBtn,
      children: [list],
    });

    const guideCard = h('div', { class: 'card connect-guide' },
      h('span', { class: 'connect-guide-icon' }, icon('usb', 22)),
      h('span', { class: 'connect-guide-text' },
        h('span', { class: 'connect-guide-title' }, '第一次连接手机？'),
        h('span', { class: 'connect-guide-desc' }, '跟着 7 个步骤打开「USB 调试」，大约 1 分钟就能连上。')),
      button({ label: '查看步骤', size: 'sm', onClick: () => openUsbGuide() }));

    shell([deviceCard, guideCard, renderWirelessCard()]);
  }

  function deviceRow(device) {
    const active = device.serial === ctx.state.activeSerial && device.online;
    const authorized = device.state === 'device';
    const label = device.state === 'unauthorized' ? '未授权' : device.online ? '在线' : '离线';
    const row = h('button', {
      class: 'device-row' + (active ? ' active' : '') + (authorized && device.online ? '' : ' is-muted'),
      type: 'button',
      onclick: () => selectDevice(device),
    },
    h('span', { class: 'device-radio' }, active ? icon('check', 13) : null),
    h('span', { class: 'device-info' },
      h('span', { class: 'device-model' },
        device.model || device.serial,
        device.wireless ? h('span', { class: 'tag tag-blue' }, '无线') : null),
      h('span', { class: 'device-serial' }, device.serial)),
    h('span', { class: 'device-state state-' + (active ? 'ok' : authorized && device.online ? 'ok' : device.state === 'unauthorized' ? 'warn' : 'idle') }, label));
    return row;
  }

  function selectDevice(device) {
    if (!device.online) {
      toast('设备已离线，请重新插拔数据线后刷新', 'error');
      return;
    }
    if (device.state === 'unauthorized') {
      toast('请在手机屏幕上点「允许」，再刷新设备列表', 'error');
      return;
    }
    ctx.setActiveSerial(device.serial);
    render();
    if (tab === 'export') ensurePreview();
  }

  async function refreshDevices(showToast = false) {
    if (deviceLoading) return;
    deviceLoading = true;
    try {
      const info = await api('devices', {}, { silent: !showToast });
      ctx.setDevices(info);
      if (showToast) {
        const devices = (info && info.devices) || [];
        if (!info.adbFound) toast('未找到调试组件', 'error');
        else toast(devices.length ? `发现 ${devices.length} 台设备` : '暂未发现手机', devices.length ? 'success' : 'info');
      }
    } catch (error) {
      if (showToast) toast(error.message || '刷新失败', 'error');
    } finally {
      deviceLoading = false;
      if (busy === null && !result) render();
      ensurePreview();
    }
  }

  /* ---------------- 无线连接 ---------------- */
  function renderWirelessCard() {
    const device = getActiveDevice();

    const enableBtn = button({ label: '开启无线调试', icon: 'wifi', size: 'sm', disabled: !device, onClick: enableWireless });
    enableBtn.setAttribute('data-testid', 'adb-wireless-tcpip');

    const ipChip = wireless.ip
      ? h('div', { class: 'wireless-ip' },
        h('span', { class: 'wireless-ip-label' }, '手机无线地址'),
        h('code', { class: 'wireless-ip-value' }, wireless.ip + ':5555'),
        button({ label: '复制', size: 'sm', variant: 'ghost', onClick: () => copyText(wireless.ip + ':5555') }))
      : null;

    const method1 = h('div', { class: 'wireless-block' },
      h('div', { class: 'wireless-title' }, '方式一：USB 转无线'),
      h('p', { class: 'wireless-desc' }, '先用数据线连接手机，点「开启无线调试」，之后拔掉数据线也能继续导出。'),
      h('div', { class: 'wireless-actions' }, enableBtn),
      ipChip);

    const pairHostField = field({ label: '手机 IP', value: wireless.pairHost, placeholder: '例如 192.168.1.5', onInput: (value) => { wireless.pairHost = value; } });
    const pairPortField = field({ label: '配对端口', value: wireless.pairPort, placeholder: '配对弹窗里的端口', inputmode: 'numeric', maxlength: 5, onInput: (value) => { wireless.pairPort = value; } });
    const pairCodeField = field({ label: '配对码', value: wireless.pairCode, placeholder: '6 位数字', inputmode: 'numeric', maxlength: 6, onInput: (value) => { wireless.pairCode = value; } });
    const pairBtn = button({ label: '配对并连接', icon: 'key', size: 'sm', variant: 'primary', onClick: submitPair });
    pairBtn.setAttribute('data-testid', 'adb-pair-submit');
    const scanBtn = button({ label: '扫描附近设备', icon: 'search', size: 'sm', onClick: scanMdns });

    const method2 = h('div', { class: 'wireless-block' },
      h('div', { class: 'wireless-title' }, '方式二：配对码配对'),
      h('p', { class: 'wireless-desc' }, '在手机「开发者选项 → 无线调试」中点「使用配对码配对设备」，把弹窗里的 IP、端口和 6 位配对码填到下面。'),
      h('div', { class: 'pair-grid' }, pairHostField, pairPortField, pairCodeField),
      h('div', { class: 'wireless-actions' }, scanBtn, pairBtn),
      wireless.scanLoading ? h('div', { class: 'wireless-loading' }, spinner('正在扫描附近设备…')) : null,
      wireless.services.length
        ? h('div', { class: 'mdns-list' }, ...wireless.services.map((service) => h('button', {
          class: 'mdns-item', type: 'button',
          onclick: () => useMdnsService(service),
        },
        icon(service.type === 'pairing' ? 'key' : 'wifi', 15),
        h('span', { class: 'mdns-name' }, service.name || service.host),
        h('span', { class: 'mdns-addr' }, service.host + ':' + service.port),
        h('span', { class: 'mdns-use' }, service.type === 'pairing' ? '用于配对' : '用于连接'))))
        : null,
      wireless.message ? h('div', { class: 'wireless-message' }, icon('info', 15), h('span', {}, wireless.message)) : null);

    const connectHostField = field({ label: '手机 IP', value: wireless.connectHost, placeholder: '例如 192.168.1.5', onInput: (value) => { wireless.connectHost = value; } });
    const connectPortField = field({ label: '端口', value: wireless.connectPort, placeholder: '5555', inputmode: 'numeric', maxlength: 5, onInput: (value) => { wireless.connectPort = value; } });
    const connectBtn = button({ label: '连接无线设备', icon: 'link', size: 'sm', onClick: connectWireless });

    const method3 = h('div', { class: 'wireless-block' },
      h('div', { class: 'wireless-title' }, '连接无线设备'),
      h('p', { class: 'wireless-desc' }, '填写手机「无线调试」页面上显示的 IP 和端口，点连接即可。手机和电脑要连同一个 Wi-Fi。'),
      h('div', { class: 'pair-grid pair-grid-two' }, connectHostField, connectPortField),
      h('div', { class: 'wireless-actions' }, connectBtn));

    return sectionCard({
      title: '无线连接',
      desc: '适合不方便长时间插线的情况，手机和电脑需要连接同一个 Wi-Fi。',
      icon: 'wifi',
      children: [method1, h('div', { class: 'wireless-divider' }), method2, h('div', { class: 'wireless-divider' }), method3],
    });
  }

  async function enableWireless() {
    const device = getActiveDevice();
    if (!device) {
      toast('请先用数据线连接手机并允许调试', 'error');
      return;
    }
    try {
      const response = await api('enable_tcpip', { serial: device.serial, port: 5555 });
      wireless.ip = response.ip || '';
      if (!wireless.ip) {
        try {
          const ipResponse = await api('wlan_ip', { serial: device.serial }, { silent: true });
          wireless.ip = ipResponse.ip || '';
        } catch (error) { /* 忽略 */ }
      }
      if (wireless.ip) {
        wireless.connectHost = wireless.ip;
        wireless.connectPort = '5555';
      }
      render();
      toast('已开启无线调试，可以拔掉数据线了', 'success');
    } catch (error) { /* api 已提示 */ }
  }

  async function connectWireless() {
    const host = (wireless.connectHost || '').trim();
    const port = Number(wireless.connectPort) || 5555;
    if (!host) {
      toast('请填写手机 IP 地址', 'error');
      return;
    }
    try {
      const response = await api('connect', { host, port });
      toast(response.message || '已连接无线设备', 'success');
      await refreshDevices(false);
      const match = getDevices().find((item) => item.online && String(item.serial).indexOf(host) === 0);
      if (match) ctx.setActiveSerial(match.serial);
      render();
    } catch (error) { /* api 已提示 */ }
  }

  async function submitPair() {
    const host = (wireless.pairHost || '').trim();
    const port = Number(wireless.pairPort) || 0;
    const code = (wireless.pairCode || '').replace(/\D/g, '');
    if (!host || !port) {
      toast('请填写配对弹窗里的 IP 和端口', 'error');
      return;
    }
    if (code.length !== 6) {
      toast('配对码是 6 位数字，请重新输入', 'error');
      return;
    }
    try {
      await api('pair', { host, port, code });
      wireless.message = '配对成功！请在手机「无线调试」页面查看 IP 和端口，填入下面的「连接无线设备」后点「连接」。';
      wireless.connectHost = host;
      if (!wireless.connectPort) wireless.connectPort = '5555';
      render();
      toast('配对成功', 'success');
    } catch (error) { /* api 已提示 */ }
  }

  async function scanMdns() {
    if (wireless.scanLoading) return;
    wireless.scanLoading = true;
    render();
    try {
      const response = await api('mdns');
      wireless.services = response.services || [];
      if (wireless.services.length === 0) {
        toast('没有发现无线调试设备，请确认手机与电脑在同一 Wi-Fi', 'info');
      }
    } catch (error) {
      wireless.services = [];
    } finally {
      wireless.scanLoading = false;
      render();
    }
  }

  function useMdnsService(service) {
    if (service.type === 'pairing') {
      wireless.pairHost = service.host;
      wireless.pairPort = String(service.port);
    } else {
      wireless.connectHost = service.host;
      wireless.connectPort = String(service.port);
    }
    render();
    toast('已填入发现的设备地址', 'success');
  }

  /* ---------------- 导出页 ---------------- */
  function renderExport() {
    publishDebug();
    const device = getActiveDevice();
    const e2eBypass = Boolean(e2eShotDataUrls());

    if (device) {
      ensurePreview();
    }

    const children = [];
    if (e2eBypass) {
      children.push(banner({
        kind: 'info',
        title: 'E2E 测试模式',
        message: '已注入测试截图，开始导出时会跳过手机截图，直接进行重叠分析与拼接。',
      }));
    }
    if (setupError) {
      children.push(banner({
        kind: 'danger',
        title: '导出失败',
        message: setupError,
        actions: [button({ label: '知道了', size: 'sm', onClick: () => { setupError = ''; render(); } })],
      }));
    }
    if (!device) {
      children.push(banner({
        kind: 'warn',
        title: '还没有连接手机',
        message: '请先到「连接设备」选择一台已授权的手机。',
        actions: [button({ label: '去连接设备', size: 'sm', onClick: () => { tab = 'connect'; render(); } })],
      }));
    }

    /* 预览 */
    let previewBody;
    if (previewLoading) {
      previewBody = h('div', { class: 'preview-loading' }, skeleton(4), h('div', { class: 'preview-loading-text' }, spinner('正在读取手机画面…')));
    } else if (previewShot) {
      previewBody = h('div', { class: 'preview-frame' },
        h('img', { src: shotUrl(previewSid || sid, previewShot.id), alt: '手机当前画面' }),
        h('div', { class: 'preview-meta' },
          h('span', {}, `${previewShot.width || 0} × ${previewShot.height || 0}`),
          h('span', { class: 'preview-dot' }, '·'),
          h('span', {}, `截图耗时 ${formatMs(previewShot.elapsedMs)}`)));
    } else if (previewError) {
      previewBody = emptyState({ icon: 'alertCircle', title: '读取画面失败', desc: previewError });
    } else {
      previewBody = emptyState({
        icon: 'smartphone',
        title: '预览手机当前画面',
        desc: '请先在手机上打开要导出的聊天窗口，并停留在最新消息处。',
      });
    }
    const previewBtn = button({ label: '刷新预览', icon: 'refresh', size: 'sm', disabled: !device || previewLoading, onClick: () => refreshPreview(true) });
    previewBtn.setAttribute('data-testid', 'export-preview');
    const previewCard = sectionCard({
      title: '确认画面',
      desc: '点击刷新预览，确认手机停留在要导出的聊天记录页面。',
      icon: 'image',
      extra: previewBtn,
      children: [previewBody],
    });

    /* 提示步骤 */
    const steps = h('div', { class: 'start-steps' },
      h('div', { class: 'start-step' }, h('span', { class: 'start-step-num' }, '1'), '打开微信聊天窗口'),
      h('div', { class: 'start-step' }, h('span', { class: 'start-step-num' }, '2'), '停留在最新消息'),
      h('div', { class: 'start-step' }, h('span', { class: 'start-step-num' }, '3'), '导出期间不要锁屏'));

    /* 选项 */
    const notes = [];
    const contactField = field({
      label: '聊天对象（可选）',
      value: exportOpts.contact,
      placeholder: '例如：张三，用于文件夹命名',
      maxlength: 40,
      hint: '留空时会生成「聊天记录_日期时间」文件夹。',
      onInput: (value) => { exportOpts.contact = value; },
    });
    const stopSeg = segmented({
      items: [
        { value: 'auto', label: '自动判断到顶' },
        { value: 'manual', label: '手动停止' },
      ],
      value: exportOpts.stopMode,
      small: true,
      onChange: (value) => { exportOpts.stopMode = value; },
    });
    const zipSwitch = switchRow({
      label: '同时打包成 ZIP',
      desc: '保存时额外生成一个压缩包，方便发送。',
      checked: exportOpts.zip,
      onChange: (checked) => { exportOpts.zip = checked; },
    });
    const cropSwitch = switchRow({
      label: '自动裁掉状态栏与输入框',
      desc: '可在「设置」中修改，默认开启。',
      checked: settings().crop_status_input !== false,
      disabled: true,
    });

    const optionsCard = sectionCard({
      title: '导出选项',
      desc: '截图之间保留少量重叠（约 10%～25%，自动调整），输出时自动去重，保证内容连续不重复。',
      icon: 'scan',
      children: [
        notes.length ? h('div', { class: 'export-notes' }, ...notes) : null,
        contactField,
        h('div', { class: 'setting-row' },
          h('div', { class: 'setting-text' },
            h('div', { class: 'setting-label' }, '结束条件'),
            h('div', { class: 'setting-desc' }, '自动判断：连续两屏内容几乎相同就停止；手动：由你点停止。')),
          h('div', { class: 'setting-control' }, stopSeg)),
        h('div', { class: 'settings-divider' }),
        zipSwitch,
        cropSwitch,
      ],
    });

    const startBtn = button({
      label: '开始导出',
      icon: 'play',
      variant: 'primary',
      block: true,
      disabled: !device && !e2eBypass,
      onClick: () => startExport(),
    });
    startBtn.setAttribute('data-testid', 'export-start');
    const startCard = h('div', { class: 'card start-card' },
      h('div', { class: 'start-head' },
        h('div', { class: 'start-title' }, '开始自动截图'),
        h('div', { class: 'start-desc' }, '程序会从最新消息开始，自动向下滑动截取更早的聊天记录，然后自动拼接去重。')),
      steps,
      startBtn,
      h('p', { class: 'start-hint' },
        icon('info', 14),
        h('span', {}, '导出过程中请保持手机亮屏，不要锁屏或操作手机；一屏大约需要 2 秒。')));

    shell([...children, previewCard, optionsCard, startCard]);
  }

  function ensurePreview() {
    if (tab !== 'export' || busy || result) return;
    if (!getActiveDevice()) return;
    if (previewShot || previewLoading || previewTried || previewError) return;
    refreshPreview(false);
  }

  async function refreshPreview(showToast = false) {
    if (previewLoading) return;
    const device = getActiveDevice();
    if (!device) {
      if (showToast) toast('请先连接手机', 'error');
      return;
    }
    previewLoading = true;
    previewTried = true;
    previewError = '';
    if (busy === null && !result) render();
    try {
      const response = await api('screencap', {
        serial: device.serial,
        sid: previewSid || undefined,
        kind: 'adb',
      }, { silent: !showToast });
      previewSid = response.sid;
      if (!sid) sid = response.sid;
      previewShot = response.shot;
      if (showToast) toast('已刷新预览', 'success', { timeout: 1500 });
    } catch (error) {
      previewError = error.message || '读取手机画面失败';
      previewShot = null;
      if (showToast) toast(previewError, 'error');
    } finally {
      previewLoading = false;
      if (busy === null && !result) render();
    }
  }

  /* ---------------- 开始导出 ---------------- */
  async function startExport() {
    if (busy) return;
    const e2eUrls = e2eShotDataUrls();
    const device = getActiveDevice();
    if (!device && !e2eUrls) {
      toast('请先连接手机', 'error');
      tab = 'connect';
      render();
      return;
    }
    tab = 'export';
    busy = 'running';
    stopRequested = false;
    cancelled = false;
    shots = [];
    result = null;
    saved = null;
    sid = previewSid || sid;
    capture = { overlaps: [], startedAt: Date.now(), stopReason: '', chatHeight: 0, lastOverlap: null };
    setupError = '';
    previewTried = true;
    setGuard();
    renderRunning();
    publishDebug();
    try {
      if (e2eUrls) {
        /* e2e 测试旁路：不驱动手机，直接把注入的 dataURL 当作逐屏截图。 */
        updateRunningPhase('E2E 测试模式 · 读取注入的截图');
        appendRunLog(`E2E 测试模式：跳过手机截图，加载 ${e2eUrls.length} 张注入截图（顺序：最旧 → 最新）。`);
        shots = await collectE2EShots(e2eUrls);
        sid = E2E_SHOT_SID;
        previewSid = E2E_SHOT_SID;
        const newest = shots[0];
        capture.lastShot = newest || null;
        capture.chatHeight = newest ? Math.max(200, (newest.height || 0) * (1 - 0.11 - 0.10)) : 0;
        appendRunLog(`已准备好 ${shots.length} 屏截图，开始分析拼接。`);
        updateRunningUI();
        capture.elapsed = Date.now() - capture.startedAt;
        const restoreFetch = installE2EShotFetch(e2eUrls);
        try {
          await runProcessing();
        } finally {
          restoreFetch();
        }
        return;
      }
      const serial = device.serial;
      try { await api('wake', { serial }, { silent: true }); } catch (error) { /* 忽略 */ }
      let dims = null;
      try {
        const infoResponse = await api('info', { serial }, { silent: true });
        dims = infoResponse && infoResponse.info;
      } catch (error) { dims = null; }
      const width = (dims && dims.width) || (previewShot && previewShot.width) || 1080;
      const height = (dims && dims.height) || (previewShot && previewShot.height) || 2400;
      const chatTop = Math.round(height * SWIPE_START_RATIO);
      const chatBottom = Math.round(height * SWIPE_BOTTOM_RATIO);
      const chatHeight = Math.max(200, height - chatTop - chatBottom);
      capture.chatHeight = chatHeight;
      const step = Math.round(chatHeight * SWIPE_STEP_RATIO);
      const x = Math.round(width / 2);
      const y1 = chatTop;
      const y2 = Math.min(height - chatBottom, y1 + step);
      /* 第一次重试换更靠下的起点：有些机型标题栏/搜索栏比预估更高。 */
      const retryY1 = Math.round(height * SWIPE_RETRY_START_RATIO);
      const retryY2 = Math.min(height - chatBottom, retryY1 + step);
      updateRunningPhase(`开始截图 · 手机分辨率 ${width} × ${height}`);
      appendRunLog(`开始截图：手指向下滑动 ${y2 - y1} px（约占聊天区 ${Math.round(SWIPE_STEP_RATIO * 100)}%，约 ${Math.max(0, Math.round((1 - (y2 - y1) / chatHeight) * 100))}% 重叠）`);

      const first = await api('screencap', { serial, sid: sid || undefined, kind: 'adb' });
      if (cancelled) return;
      sid = first.sid;
      previewSid = first.sid;
      shots.push(first.shot);
      let previous = await frameSignature(sid, first.shot);
      capture.lastShot = first.shot;
      updateRunningUI();
      appendRunLog(`第 1 屏 · 耗时 ${formatMs(first.shot.elapsedMs)}`);

      const maxShots = Math.max(10, Math.min(2000, Number(settings().max_shots) || 500));
      let stagnant = 0;
      while (!stopRequested && !cancelled) {
        if (shots.length >= maxShots) {
          capture.stopReason = `已达到最多 ${maxShots} 屏上限`;
          break;
        }

        /* 滑动 → 等页面停稳 → 截图；若截图和上一屏几乎相同则换更慢的滑动重试。 */
        let shot = null;
        let signature = null;
        let scrolled = false;
        for (let attempt = 0; attempt <= SAME_SCREEN_RETRIES; attempt += 1) {
          const duration = attempt === 0 ? SWIPE_DURATION : SWIPE_RETRY_DURATION;
          const useRetryStart = attempt === 1;
          await api('swipe', {
            serial,
            x1: x,
            y1: useRetryStart ? retryY1 : y1,
            x2: x,
            y2: useRetryStart ? retryY2 : y2,
            duration,
          }, { silent: true });
          await sleep(SWIPE_SETTLE);
          if (stopRequested || cancelled) break;
          const response = await api('screencap', { serial, sid, kind: 'adb' }, { silent: true });
          if (cancelled) break;
          shot = response.shot;
          signature = await frameSignature(sid, shot);
          if (cancelled) break;
          if (!sameScreen(previous, signature)) {
            scrolled = true;
            break;
          }
          if (attempt < SAME_SCREEN_RETRIES) {
            appendRunLog(`画面没有变化（未滚动），第 ${attempt + 1} 次重试：改用 ${SWIPE_RETRY_DURATION}ms 慢速滑动…`);
          }
        }
        if (stopRequested || cancelled) break;
        if (!scrolled) {
          capture.stopReason = '已到达聊天记录顶部（连续两屏内容几乎相同）';
          appendRunLog('连续两屏内容几乎相同，判定已到达聊天记录顶部或内容过短，自动停止。');
          break;
        }

        const compared = compareSignatures(previous, signature);
        shots.push(shot);
        capture.lastShot = shot;
        previous = signature;
        if (compared.overlap !== null) {
          capture.overlaps.push(compared.overlap);
          capture.lastOverlap = compared.overlap;
        }
        if (compared.shiftNative < 6) stagnant += 1;
        else stagnant = 0;
        if (exportOpts.stopMode === 'auto' && stagnant >= 3) {
          capture.stopReason = '连续多屏没有出现新内容，已自动停止';
          appendRunLog('多屏没有新内容，自动停止。');
          break;
        }
        if (exportOpts.stopMode === 'manual' && stagnant >= 8) {
          capture.stopReason = '连续多屏没有出现新内容，已自动停止';
          break;
        }
        appendRunLog(`第 ${shots.length} 屏 · 耗时 ${formatMs(shot.elapsedMs)}` + (compared.overlap !== null ? ` · 重叠约 ${(compared.overlap * 100).toFixed(0)}%` : ''));
        updateRunningUI();
      }
      capture.elapsed = Date.now() - capture.startedAt;
      runProcessing();
    } catch (error) {
      cancelled = true;
      busy = null;
      ctx.clearLeaveGuard && ctx.clearLeaveGuard();
      setupError = (error && error.message) || String(error);
      if (sid) api('session.drop', { sid }, { silent: true }).catch(() => {});
      tab = 'export';
      renderExport();
    }
  }

  /* ---------------- 运行态 ---------------- */
  function renderRunning() {
    const progress = progressBar({ indeterminate: true });
    progress.el.setAttribute('data-testid', 'export-progress');
    const stopBtn = button({ label: '停止', icon: 'stop', variant: 'danger', size: 'sm', onClick: requestStop });
    stopBtn.setAttribute('data-testid', 'export-stop');
    const previewImg = h('img', { class: 'running-preview-img', alt: '最新截图' });
    const logEl = h('div', { class: 'run-log', 'data-testid': 'export-log' });
    const statShots = statTile({ label: '已截取', value: '0', unit: '屏' });
    const statOverlap = statTile({ label: '实测重叠', value: '—' });
    const statHeight = statTile({ label: '估算长图', value: '—' });
    const statPages = statTile({ label: '预计页数', value: '—', unit: '页' });
    const phaseEl = h('div', { class: 'running-phase' }, '正在读取第一屏…');

    const runningCard = h('section', { class: 'card section running-card' },
      h('div', { class: 'running-head' },
        h('div', { class: 'running-icon' }, spinner('', 20)),
        h('div', { class: 'running-text' },
          h('div', { class: 'running-title' }, '正在自动截取聊天记录'),
          phaseEl),
        stopBtn),
      h('div', { class: 'running-progress' }, progress.el),
      h('div', { class: 'stat-grid' }, statShots, statOverlap, statHeight, statPages),
      h('div', { class: 'running-preview' },
        h('div', { class: 'running-preview-label' }, '最新截图'),
        previewImg,
        h('div', { class: 'running-preview-hint' }, '请保持手机亮屏，不要操作手机。')),
      h('p', { class: 'running-tip' },
        icon('info', 14),
        h('span', {}, '停止后会立即开始自动拼接；到顶时会自动停止。')));

    const logCard = h('section', { class: 'card section log-card' },
      h('div', { class: 'section-head' },
        h('div', { class: 'section-heading' },
          h('h2', { class: 'section-title' }, '运行日志'),
          h('p', { class: 'section-desc' }, '每一屏的耗时和重叠情况会显示在这里。'))),
      logEl);

    runningRefs = {
      progressEl: progress.el,
      phaseEl,
      statShots,
      statOverlap,
      statHeight,
      statPages,
      previewImg,
      logEl,
    };
    shell([runningCard, logCard]);
    updateRunningUI();
  }

  function updateRunningPhase(text) {
    if (runningRefs && runningRefs.phaseEl) runningRefs.phaseEl.textContent = text;
  }

  function updateRunningUI() {
    if (!runningRefs) return;
    const count = shots.length;
    const overlap = capture.overlaps.length ? average(capture.overlaps) : null;
    const chatHeight = capture.chatHeight || 0;
    const step = overlap !== null ? chatHeight * (1 - overlap) : chatHeight * 0.9;
    const totalHeight = chatHeight + Math.max(0, count - 1) * step;
    const tileH = TILE_HEIGHTS[settings().page_height] || 2000;
    const pageCount = totalHeight > 0 ? Math.max(1, Math.ceil(totalHeight / tileH)) : 0;
    setStatValue(runningRefs.statShots, String(count));
    setStatValue(runningRefs.statOverlap, overlap !== null ? (overlap * 100).toFixed(0) + '%' : '—');
    setStatValue(runningRefs.statHeight, totalHeight > 0 ? formatPx(totalHeight) : '—');
    setStatValue(runningRefs.statPages, pageCount ? String(pageCount) : '—');
    if (capture.lastShot && runningRefs.previewImg) {
      const src = capture.lastShot.dataUrl || shotUrl(sid, capture.lastShot.id);
      if (runningRefs.previewImg.getAttribute('src') !== src) runningRefs.previewImg.src = src;
    }
    publishDebug();
  }

  function setStatValue(tile, value) {
    if (!tile) return;
    const el = tile.querySelector('.stat-value');
    if (!el) return;
    if (el.firstChild && el.firstChild.nodeType === 3) el.firstChild.nodeValue = String(value);
    else el.textContent = String(value);
  }

  function appendRunLog(text) {
    const line = h('div', { class: 'run-log-line' },
      h('span', { class: 'run-log-time' }, formatClock()),
      h('span', { class: 'run-log-text' }, text));
    if (runningRefs && runningRefs.logEl) {
      runningRefs.logEl.appendChild(line);
      while (runningRefs.logEl.children.length > 200) runningRefs.logEl.firstElementChild.remove();
      runningRefs.logEl.scrollTop = runningRefs.logEl.scrollHeight;
    } else if (processingRefs && processingRefs.logEl) {
      processingRefs.logEl.appendChild(line);
      processingRefs.logEl.scrollTop = processingRefs.logEl.scrollHeight;
    }
  }

  function requestStop() {
    if (!busy) return;
    stopRequested = true;
    if (busy === 'processing') cancelled = true;
    updateRunningPhase('正在停止…');
    appendRunLog('已请求停止，正在结束当前截图…');
    if (busy === 'processing') {
      busy = null;
      result = null;
      shots = [];
      render();
      toast('已取消处理', 'info');
    }
  }

  /* ---------------- 处理态 ---------------- */
  async function runProcessing() {
    if (shots.length < 3) {
      busy = null;
      ctx.clearLeaveGuard && ctx.clearLeaveGuard();
      setupError = `这次只截到 ${shots.length} 屏就停止了（${capture.stopReason || '连续两屏内容几乎没有变化'}）。请确认微信聊天窗口可以正常上下滑动，并让相邻截图保留少量重叠（约 10%～25%）后再试。`;
      if (typeof ctx.reportError === 'function') ctx.reportError('截图数量不足：' + setupError);
      publishDebug({ error: setupError });
      renderResultError({
        title: '截图数量不足',
        actions: [
          button({ label: '重新导出', size: 'sm', variant: 'primary', onClick: resetExport }),
          button({ label: '返回导出设置', size: 'sm', onClick: () => { setupError = ''; tab = 'export'; renderExport(); } }),
        ],
      });
      return;
    }
    busy = 'processing';
    stopRequested = false;
    cancelled = false;
    procPhase = '正在分析截图重叠…';
    procProgress = 0.02;
    procLog = [];
    procEngineMissing = false;
    renderProcessing();
    publishDebug();
    const modules = await loadEngine();
    if (cancelled) return;
    if (!modules.stitch || !modules.adbcap) {
      procEngineMissing = true;
      procPhase = '分析引擎加载中…';
      procProgress = 0;
      renderProcessing();
      appendRunLog('分析引擎还没有就绪，可以重试或先保存原始截图。');
      return;
    }
    try {
      addProcLog(`开始分析 ${shots.length} 张截图…`);
      const analysis = await modules.adbcap.analyzeShots(sid, shots, {
        cropStatusInput: settings().crop_status_input !== false,
      }, (a, b) => {
        if (cancelled) return;
        const parsed = parseProgress(a, b);
        if (parsed.phase) procPhase = parsed.phase;
        if (parsed.fraction !== null) procProgress = Math.max(procProgress, 0.04 + parsed.fraction * 0.52);
        updateProcessing();
      });
      if (cancelled) return;
      procProgress = Math.max(procProgress, 0.56);
      procPhase = '正在拼接长图…';
      updateProcessing();
      addProcLog('重叠分析完成，开始拼接长图。');
      const crop = computeCrop(analysis, settings().crop_status_input !== false);
      const cropHpx = Math.max(360, crop.botPx - crop.topPx);
      const factor = TILE_FACTORS[settings().page_height] || TILE_FACTORS.standard;
      const tileH = Math.max(240, Math.round(cropHpx * factor));
      addProcLog(`内容区域：y=${Math.round(crop.topPx)}–${Math.round(crop.botPx)} px，每页高度 ${tileH} px`);
      const plan = modules.stitch.planMaster(analysis, crop.topPx, crop.botPx, tileH);
      const planStats = (plan && plan.stats) || {};
      const planOverlap = Number.isFinite(planStats.medianOverlapRatio) ? planStats.medianOverlapRatio : null;
      const pages = [];
      const files = [];
      await modules.adbcap.renderShotPages(plan, sid, shots, tileH, async (...args) => {
        if (cancelled) return;
        const dataUrl = await pageToDataUrl(...args);
        if (!dataUrl) return;
        const index = pages.length + 1;
        pages.push({ src: dataUrl, label: pad3(index) + ' 页' });
        files.push({ name: pad3(index) + '.png', data: dataUrl });
        const expected = (plan && plan.pageCount) || pages.length + 1;
        procProgress = Math.min(0.96, Math.max(procProgress, 0.6 + (pages.length / Math.max(1, expected)) * 0.34));
        procPhase = `正在生成第 ${pages.length} 页…`;
        updateProcessing();
      }, (a, b) => {
        if (cancelled) return;
        const parsed = parseProgress(a, b);
        if (parsed.fraction !== null) {
          procProgress = Math.min(0.96, Math.max(procProgress, 0.6 + parsed.fraction * 0.34));
          updateProcessing();
        }
      });
      if (cancelled) return;
      if (pages.length === 0) throw new Error('没有生成任何截图页面，请返回后重新导出。');
      procProgress = 1;
      procPhase = '处理完成';
      updateProcessing();
      result = {
        pages,
        files,
        pageCount: pages.length,
        masterH: plan && plan.masterH ? plan.masterH : null,
        overlap: planOverlap !== null ? planOverlap : (capture.overlaps.length ? average(capture.overlaps) : null),
        elapsed: capture.elapsed || Date.now() - capture.startedAt,
        stopReason: capture.stopReason,
        mode: 'pages',
      };
      busy = null;
      ctx.clearLeaveGuard && ctx.clearLeaveGuard();
      renderResult();
      toast('拼接完成，可以保存了', 'success');
    } catch (error) {
      console.error(error);
      if (cancelled) return;
      busy = null;
      ctx.clearLeaveGuard && ctx.clearLeaveGuard();
      setupError = (error && error.message) || '拼接失败，请重试。';
      if (typeof ctx.reportError === 'function') ctx.reportError('拼接失败：' + setupError);
      publishDebug({ error: setupError });
      renderResultError();
    }
  }

  function renderProcessing() {
    const progress = progressBar({ value: procProgress });
    progress.el.setAttribute('data-testid', 'export-progress');
    const logEl = h('div', { class: 'run-log', 'data-testid': 'export-log' });
    for (const entry of procLog) {
      logEl.appendChild(h('div', { class: 'run-log-line' },
        h('span', { class: 'run-log-time' }, entry.time),
        h('span', { class: 'run-log-text' }, entry.text)));
    }
    const percentEl = h('div', { class: 'processing-percent' }, Math.round(procProgress * 100) + '%');
    const phaseEl = h('div', { class: 'processing-phase' }, procPhase);
    const cancelBtn = button({ label: '取消', icon: 'close', size: 'sm', onClick: requestStop });

    const children = [
      h('div', { class: 'processing-head' },
        spinner('', 20),
        h('div', { class: 'processing-text' }, h('div', { class: 'processing-title' }, '正在拼接长图'), phaseEl)),
      progress.el,
      h('div', { class: 'processing-foot' }, percentEl, cancelBtn),
    ];
    if (procEngineMissing) {
      children.push(banner({
        kind: 'warn',
        title: '分析引擎加载中',
        message: '拼接模块还没有准备好。你可以点击「重试」重新加载，或先把原始截图保存下来。',
        actions: [
          button({
            label: '重试', size: 'sm', variant: 'primary',
            onClick: async () => {
              if (cancelled) return;
              procEngineMissing = false;
              procPhase = '重新加载分析引擎…';
              renderProcessing();
              const modules = await loadEngine();
              if (modules.stitch && modules.adbcap) {
                busy = null;
                runProcessing();
              } else {
                renderProcessing();
                toast('分析引擎仍未就绪，请稍后再试', 'error');
              }
            },
          }),
          button({ label: '保存原始截图', size: 'sm', onClick: () => saveRawShots() }),
        ],
      }));
    }

    const processingCard = h('section', { class: 'card section processing-card' }, ...children);
    const logCard = h('section', { class: 'card section log-card' },
      h('div', { class: 'section-head' },
        h('div', { class: 'section-heading' },
          h('h2', { class: 'section-title' }, '处理日志'))),
      logEl);
    processingRefs = { progress, progressEl: progress.el, phaseEl, percentEl, logEl };
    shell([processingCard, logCard]);
  }

  function updateProcessing() {
    if (!processingRefs) return;
    processingRefs.progress.set(procProgress);
    if (processingRefs.percentEl) processingRefs.percentEl.textContent = Math.round(procProgress * 100) + '%';
    if (processingRefs.phaseEl) processingRefs.phaseEl.textContent = procPhase;
    publishDebug();
  }

  function addProcLog(text) {
    const entry = { time: formatClock(), text };
    procLog.push(entry);
    if (procLog.length > 300) procLog.shift();
    appendRunLog(text);
  }

  async function loadEngine() {
    const [stitch, adbcap] = await Promise.all([
      tryImport('/js/stitch.js'),
      tryImport('/js/adbcap.js'),
    ]);
    engine = { stitch: stitch || null, adbcap: adbcap || null, status: stitch && adbcap ? 'ready' : 'missing' };
    return engine;
  }

  // 返回裁剪后的内容区域边界：topPx 为上边界，botPx 为下边界（原始帧像素坐标）。
  function computeCrop(analysis, enabled) {
    const frameHeight = (analysis && (analysis.frameHeight || analysis.height)) || (previewShot && previewShot.height) || 2400;
    if (!enabled) return { topPx: 0, botPx: frameHeight, source: 'none' };
    const detected = analysis && analysis.crop;
    if (detected && Number.isFinite(detected.topPx) && Number.isFinite(detected.botPx)) {
      const detectedCropH = detected.botPx - detected.topPx;
      if (detectedCropH > frameHeight * 0.3 && detected.topPx >= 0 && detected.botPx <= frameHeight) {
        return { topPx: Math.round(detected.topPx), botPx: Math.round(detected.botPx), source: 'engine' };
      }
    }
    const fallback = {
      /* 裁剪兜底：上边界要盖住状态栏和标题栏（约 11%），下边界留出输入框（约 10%）。 */
      topPx: Math.round(frameHeight * 0.11),
      botPx: frameHeight - Math.round(frameHeight * 0.10),
      source: 'estimate',
    };
    const band = analysis && analysis.band;
    const analysisHeight = analysis && analysis.ah;
    if (!band || !analysisHeight) return fallback;
    let start = null;
    let end = null;
    if (typeof band.top === 'number' && typeof band.bottom === 'number') {
      start = band.top;
      end = band.bottom;
    } else if (typeof band.start === 'number' && typeof band.end === 'number') {
      start = band.start;
      end = band.end;
    } else if (typeof band.start === 'number' && typeof band.height === 'number') {
      start = band.start;
      end = band.start + band.height;
    }
    if (start === null || end === null) return fallback;
    const scale = frameHeight / analysisHeight;
    const topPx = Math.max(0, Math.round(start * scale));
    const botPx = Math.min(frameHeight, Math.round(end * scale));
    const cropHeight = botPx - topPx;
    if (cropHeight < frameHeight * 0.3 || topPx > frameHeight * 0.3 || frameHeight - botPx > frameHeight * 0.35) return fallback;
    return { topPx, botPx, source: 'detected' };
  }

  async function saveRawShots() {
    try {
      const files = [];
      for (let index = 0; index < shots.length; index += 1) {
        const dataUrl = await fetchAsDataUrl(shotUrl(sid, shots[index].id));
        if (!dataUrl) continue;
        files.push({ name: pad3(index + 1) + '.png', data: dataUrl });
      }
      if (files.length === 0) throw new Error('没有可保存的截图');
      result = {
        pages: files.map((file, index) => ({ src: file.data, label: pad3(index + 1) + ' 页' })),
        files,
        pageCount: files.length,
        masterH: null,
        overlap: capture.overlaps.length ? average(capture.overlaps) : null,
        elapsed: capture.elapsed || Date.now() - capture.startedAt,
        stopReason: capture.stopReason,
        mode: 'raw',
      };
      busy = null;
      ctx.clearLeaveGuard && ctx.clearLeaveGuard();
      renderResult();
    } catch (error) {
      setupError = error.message || '保存原始截图失败';
      busy = null;
      render();
    }
  }

  /* ---------------- 结果页 ---------------- */
  function buildFolderName() {
    const date = new Date();
    const p = (value) => String(value).padStart(2, '0');
    const stamp = `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}_${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`;
    const contact = (exportOpts.contact || '').trim();
    return `${contact ? contact + '_' : ''}聊天记录_${stamp}`;
  }

  async function saveResult() {
    if (!result || saving) return;
    saving = true;
    renderResult();
    try {
      const dir = (settings().output_dir || '').trim();
      const response = await api('export.save', {
        sid: sid || undefined,
        dir: dir || undefined,
        noOpen: E2E_MODE,
        zip: Boolean(exportOpts.zip),
        files: result.files,
        meta: {
          contact: exportOpts.contact || '',
          method: result.mode === 'raw' ? '手机截图（原始截图）' : '手机截图',
          range: '从最新消息到更早记录',
          pageCount: result.pageCount,
          folderName: buildFolderName(),
          stats: {
            shots: shots.length,
            overlapPercent: result.overlap !== null ? Math.round(result.overlap * 1000) / 10 : null,
          },
        },
      });
      if (!response || response.ok === false) {
        toast('已取消保存', 'info');
        return;
      }
      saved = response;
      appendRecord({
        name: response.dir ? response.dir.split('\\').pop() : buildFolderName(),
        contact: exportOpts.contact || '',
        method: '手机截图',
        pageCount: result.pageCount,
        dir: response.dir || '',
        zip: response.zip || '',
      });
      toast(response.message || '已保存截图', 'success');
    } catch (error) {
      if (error && error.canceled) toast('已取消保存', 'info');
      else toast(error.message || '保存失败', 'error');
    } finally {
      saving = false;
      renderResult();
    }
  }

  async function openSavedFolder() {
    if (!saved || !saved.dir) {
      toast('请先保存到文件夹', 'info');
      return;
    }
    try {
      await api('open_folder', { path: saved.dir });
    } catch (error) {
      toast('打开文件夹失败', 'error');
    }
  }

  function resetExport() {
    result = null;
    saved = null;
    shots = [];
    busy = null;
    previewShot = null;
    previewTried = false;
    if (sid) api('session.drop', { sid }, { silent: true }).catch(() => {});
    sid = null;
    previewSid = null;
    ctx.clearLeaveGuard && ctx.clearLeaveGuard();
    renderExport();
  }

  function renderResult() {
    publishDebug();
    if (!result) {
      render();
      return;
    }
    const isRaw = result.mode === 'raw';
    const statGrid = h('div', { class: 'stat-grid' },
      statTile({ label: '输出页数', value: String(result.pageCount), unit: '页' }),
      statTile({ label: '拼接高度', value: result.masterH ? formatPx(result.masterH) : (isRaw ? '原始截图' : '—') }),
      statTile({ label: '实测重叠', value: result.overlap !== null ? (result.overlap * 100).toFixed(0) + '%' : '—' }),
      statTile({ label: '截图耗时', value: formatMs(result.elapsed), sub: `${shots.length} 屏` }));

    const saveBtn = button({
      label: saving ? '正在保存…' : '保存到文件夹',
      icon: saving ? null : 'save',
      variant: 'primary',
      disabled: saving,
      onClick: saveResult,
    });
    saveBtn.setAttribute('data-testid', 'export-save');
    const openBtn = button({
      label: '打开文件夹',
      icon: 'folder',
      variant: 'default',
      disabled: !saved || !saved.dir,
      onClick: openSavedFolder,
    });
    openBtn.setAttribute('data-testid', 'export-open');
    const againBtn = button({ label: '重新导出', icon: 'refresh', variant: 'ghost', onClick: resetExport });

    const header = h('div', { class: 'result-head' },
      h('div', { class: 'result-icon' }, icon('checkCircle', 34)),
      h('div', { class: 'result-text' },
        h('h2', { class: 'result-title' }, '导出完成'),
        h('p', { class: 'result-sub' }, `共生成 ${result.pageCount} 页截图` + (exportOpts.contact ? `，导出对象：${exportOpts.contact}` : ''))));

    const metaRows = result.stopReason
      ? banner({ kind: 'info', title: '提前结束', message: result.stopReason })
      : (isRaw ? banner({ kind: 'warn', title: '当前是原始截图', message: '分析引擎未就绪，保存的是未经裁剪和去重的原始截图。' }) : null);

    const savedRow = saved && saved.dir
      ? h('div', { class: 'result-saved' },
        icon('folder', 16),
        h('span', { class: 'result-saved-path', title: saved.dir }, saved.dir))
      : null;

    const resultCard = h('section', { class: 'card result-card', 'data-testid': 'export-result' },
      header,
      metaRows,
      statGrid,
      savedRow,
      h('div', { class: 'result-actions' }, saveBtn, openBtn, againBtn),
      h('p', { class: 'result-hint' }, '保存时会自动打开文件夹；截图按聊天时间从旧到新编号。'));

    const thumbsCard = sectionCard({
      title: '页面预览',
      desc: `共 ${result.pages.length} 页` + (result.pages.length > 30 ? '，仅预览前 30 页' : ''),
      icon: 'image',
      children: [thumbsGrid(result.pages, { limit: 30 })],
    });

    shell([resultCard, thumbsCard]);
  }

  function renderResultError(options = {}) {
    busy = null;
    const title = options.title || '拼接失败';
    const actions = options.actions || [
      button({ label: '重试拼接', size: 'sm', variant: 'primary', onClick: () => { setupError = ''; runProcessing(); } }),
      button({ label: '返回导出设置', size: 'sm', onClick: () => { setupError = ''; tab = 'export'; renderExport(); } }),
    ];
    const card = h('section', { class: 'card section error-card' },
      banner({
        kind: 'danger',
        title,
        message: setupError || '导出过程中出现错误。',
        actions,
      }));
    shell([card]);
  }

  /* ---------------- 离开保护 ---------------- */
  function setGuard() {
    if (typeof ctx.setLeaveGuard !== 'function') return;
    ctx.setLeaveGuard(async () => {
      const ok = await confirm({
        title: '导出还没完成',
        message: '离开会停止当前导出，未保存的结果将会丢失。确定要离开吗？',
        okText: '停止并离开',
        danger: true,
      });
      if (ok) {
        stopRequested = true;
        cancelled = true;
      }
      return ok;
    });
  }

  /* ---------------- 启动 ---------------- */
  loadEngine(); // 预热引擎，避免处理时才发现缺失
  render();
  publishDebug();

  ctx.onCleanup(() => {
    cancelled = true;
    stopRequested = true;
    if (typeof ctx.clearLeaveGuard === 'function') ctx.clearLeaveGuard();
    if (sid) api('session.drop', { sid }, { silent: true }).catch(() => {});
  });
}
