/**
 * app.js —— 应用启动、路由、全局状态与外壳（顶栏 / tab 栏 / 主题 / 错误上报）。
 */
import * as ui from './ui.js';
import { api, reportLog } from './api.js';
import * as homeScreen from './screens/home.js';
import * as adbScreen from './screens/adb.js';
import * as videoScreen from './screens/videoScreen.js';
import * as recordsScreen from './screens/records.js';
import * as settingsScreen from './screens/settings.js';

const { h, icon } = ui;

/* ------------------------------------------------------------------ 路由表 */
const ROUTES = {
  home: { title: '笔录工具箱', module: homeScreen, tab: 'home' },
  adb: { title: '手机截图导出', module: adbScreen, push: true },
  video: { title: '录屏转截图', module: videoScreen, push: true },
  records: { title: '导出记录', module: recordsScreen, tab: 'records' },
  settings: { title: '设置', module: settingsScreen, tab: 'settings' },
};

/* ------------------------------------------------------------------ 全局状态 */
const state = {
  app: { name: '笔录工具箱', version: '1.0.0', dataDir: '', platform: 'win32' },
  settings: {
    adb_path: '',
    output_dir: '',
    theme: 'auto',
    overlap_percent: 10,
    crop_status_input: true,
    page_height: 'standard',
    video_analysis_fps: 10,
    video_refine_rounds: 2,
    max_shots: 500,
    keep_raw_shots: false,
  },
  adb: { adbPath: '', adbFound: false, adbVersion: '', devices: [], error: '' },
  activeSerial: '',
  route: 'home',
  params: {},
  ready: false,
};

let currentCleanups = [];
let leaveGuard = null;
let navToken = 0;
let lastRoute = 'home';

/* 供自动化测试轮询的只读调试状态（不参与界面渲染）。 */
const debugState = {
  page: 'home',
  adb: { connected: false, running: false, processing: false, shots: 0, phase: '', progress: 0 },
  video: { running: false, processing: false, phase: '', progress: 0, pages: 0 },
};

function setDebug(kind, data) {
  if (!kind || !data) return;
  const key = String(kind);
  debugState[key] = { ...(debugState[key] || {}), ...data };
}

function debugSnapshot() {
  const devices = (state.adb && state.adb.devices) || [];
  const active = devices.find((d) => d.online && d.serial === state.activeSerial)
    || devices.find((d) => d.online) || null;
  const adb = debugState.adb || {};
  return {
    page: state.route,
    ready: state.ready,
    theme: document.documentElement.dataset.theme || '',
    devices: devices.length,
    activeSerial: state.activeSerial,
    connected: Boolean(active),
    adb: {
      ...adb,
      connected: Boolean(active),
      serial: active ? active.serial : '',
      model: active ? active.model || '' : '',
      deviceCount: devices.length,
    },
    video: { ...(debugState.video || {}) },
  };
}

try {
  window.__RT = { get state() { return debugSnapshot(); } };
} catch (error) { /* 忽略 */ }

const rootEl = document.getElementById('app');
let topbarLeft = null;
let topbarRight = null;
let statusPillEl = null;
let tabbarEl = null;

/* ------------------------------------------------------------------ 设备状态 */
function deviceSummary() {
  const adb = state.adb || {};
  const devices = Array.isArray(adb.devices) ? adb.devices : [];
  const online = devices.filter((d) => d && d.online);
  const unauthorized = devices.filter((d) => d && d.state === 'unauthorized');
  const active = online.find((d) => d.serial === state.activeSerial) || online[0] || null;
  if (active) {
    return {
      kind: 'ok',
      label: '已连接',
      detail: (active.model || '手机') + (online.length > 1 ? `（共 ${online.length} 台）` : ''),
      device: active,
    };
  }
  if (devices.length) {
    return {
      kind: 'warn',
      label: '未连接',
      detail: unauthorized.length ? '手机尚未允许调试' : '设备离线，请重新插拔',
      device: null,
    };
  }
  if (adb.adbFound === false) {
    return { kind: 'idle', label: '未检测', detail: '未找到调试组件，可到设置中指定', device: null };
  }
  return { kind: 'idle', label: '未检测', detail: '未发现设备，请连接手机', device: null };
}

function syncActiveSerial() {
  const devices = (state.adb && state.adb.devices) || [];
  const online = devices.filter((d) => d && d.online);
  if (!online.some((d) => d.serial === state.activeSerial)) {
    state.activeSerial = online.length ? online[0].serial : '';
  }
}

/* ------------------------------------------------------------------ 主题 */
const mediaQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

function resolvedTheme() {
  const pref = state.settings.theme || 'auto';
  if (pref === 'light' || pref === 'dark') return pref;
  return mediaQuery && mediaQuery.matches ? 'dark' : 'light';
}

function applyTheme() {
  const resolved = resolvedTheme();
  document.documentElement.dataset.theme = resolved;
  try {
    localStorage.setItem('rtb.theme', state.settings.theme || 'auto');
  } catch (error) { /* 忽略 */ }
}

if (mediaQuery) {
  mediaQuery.addEventListener('change', () => {
    if ((state.settings.theme || 'auto') === 'auto') applyTheme();
  });
}

/* ------------------------------------------------------------------ 顶端外壳 */
function buildShell() {
  statusPillEl = h('button', { class: 'status-pill status-idle', type: 'button', title: '查看设备连接', onclick: () => navigate('adb') },
    h('span', { class: 'status-dot' }),
    h('span', { class: 'status-label' }, '未检测'));
  topbarLeft = h('div', { class: 'topbar-left' });
  topbarRight = h('div', { class: 'topbar-right' }, statusPillEl);
  const topbar = h('header', { class: 'topbar' }, topbarLeft, topbarRight);

  tabbarEl = h('nav', { class: 'tabbar', 'aria-label': '主导航' },
    tabItem('home', '首页', 'home'),
    tabItem('records', '记录', 'clock'),
    tabItem('settings', '设置', 'gear'));

  const screen = h('main', { class: 'screen', id: 'screen' });
  rootEl.append(topbar, screen, tabbarEl);
  updateStatusPill();
  updateTabbar();
}

function tabItem(route, label, iconName) {
  const button = h('button', {
    class: 'tab-item', type: 'button', dataset: { route }, 'aria-label': label,
    onclick: () => navigate(route),
  },
  h('span', { class: 'tab-icon' }, icon(iconName, 22)),
  h('span', { class: 'tab-label' }, label));
  return button;
}

function brandMarkup() {
  return h('div', { class: 'brand' },
    h('div', { class: 'brand-logo', 'aria-hidden': 'true' }, icon('bubble', 19)),
    h('div', { class: 'brand-text' },
      h('span', { class: 'brand-name' }, '笔录工具箱'),
      h('span', { class: 'brand-tag' }, '微信聊天记录导出')));
}

function backMarkup(title) {
  return h('div', { class: 'nav' },
    h('button', { class: 'nav-back', type: 'button', 'aria-label': '返回', onclick: () => navigate(lastRoute === state.route ? 'home' : lastRoute) },
      icon('chevronLeft', 20), h('span', {}, '返回')),
    h('h1', { class: 'nav-title' }, title));
}

function renderTopbar() {
  const route = ROUTES[state.route] || ROUTES.home;
  if (route.push) {
    topbarLeft.replaceChildren(backMarkup(route.title));
  } else {
    topbarLeft.replaceChildren(brandMarkup());
  }
  document.body.classList.toggle('is-pushed', Boolean(route.push));
  updateStatusPill();
  updateTabbar();
}

function updateStatusPill() {
  if (!statusPillEl) return;
  const summary = deviceSummary();
  statusPillEl.className = 'status-pill status-' + summary.kind;
  statusPillEl.title = summary.detail || '';
  statusPillEl.replaceChildren(
    h('span', { class: 'status-dot' }),
    h('span', { class: 'status-label' }, summary.label));
}

function updateTabbar() {
  if (!tabbarEl) return;
  const route = ROUTES[state.route] || ROUTES.home;
  const visible = !route.push;
  tabbarEl.hidden = !visible;
  tabbarEl.querySelectorAll('.tab-item').forEach((item) => {
    item.classList.toggle('active', item.dataset.route === route.tab);
  });
}

/* ------------------------------------------------------------------ 路由 */
function runCleanups() {
  const list = currentCleanups;
  currentCleanups = [];
  for (const fn of list) {
    try {
      fn();
    } catch (error) {
      console.warn('清理页面时出错', error);
    }
  }
}

async function navigate(route, params = {}) {
  const target = ROUTES[route];
  if (!target) route = 'home';
  if (route === state.route && state.ready && rootEl.dataset.mounted === '1') {
    // 同一页面重复点击 tab 时仅回到顶部
    const screen = document.getElementById('screen');
    if (screen) screen.scrollTo({ top: 0, behavior: 'smooth' });
    return;
  }
  if (leaveGuard) {
    let allowed = false;
    try {
      allowed = await leaveGuard();
    } catch (error) {
      allowed = false;
    }
    if (!allowed) return;
  }
  leaveGuard = null;
  runCleanups();

  const token = ++navToken;
  lastRoute = state.route && ROUTES[state.route] ? state.route : 'home';
  if (route === lastRoute) lastRoute = 'home';
  state.route = route;
  state.params = params || {};
  state.ready = true;
  debugState.page = route;
  rootEl.dataset.mounted = '1';

  const screen = document.getElementById('screen');
  if (!screen) return;
  renderTopbar();
  screen.scrollTop = 0;
  const wrapper = h('div', { class: 'screen-body' });
  wrapper.dataset.page = route;
  screen.dataset.page = route;
  screen.replaceChildren(wrapper);
  requestAnimationFrame(() => wrapper.classList.add('screen-enter'));

  try {
    const mod = (ROUTES[route] || ROUTES.home).module;
    const result = await mod.mount(wrapper, appCtx);
    if (token !== navToken) {
      // 页面在加载期间被切走，清掉它的清理函数。
      if (typeof result === 'function') result();
      return;
    }
    if (typeof result === 'function') currentCleanups.push(result);
    else if (Array.isArray(result)) currentCleanups.push(...result.filter((fn) => typeof fn === 'function'));
  } catch (error) {
    console.error('页面渲染失败', error);
    reportError('页面渲染失败：' + (error && error.message ? error.message : error));
    if (token === navToken) {
      wrapper.replaceChildren(ui.emptyState({
        icon: 'alert',
        title: '页面暂时无法显示',
        desc: String((error && error.message) || error || '未知错误'),
        actions: [ui.button({ label: '重试', variant: 'primary', onClick: () => navigate(route, params) })],
      }));
    }
  }

  try {
    history.replaceState(null, '', '#/' + route);
  } catch (error) { /* 忽略 */ }
}

/* ------------------------------------------------------------------ 状态刷新 */
async function refreshState({ silent = true } = {}) {
  const result = await api('state', {}, { silent });
  if (result && result.app) state.app = result.app;
  if (result && result.settings) state.settings = result.settings;
  if (result && result.adb) state.adb = result.adb;
  syncActiveSerial();
  applyTheme();
  updateStatusPill();
  return result;
}

async function saveSettings(patch) {
  const previous = { ...state.settings };
  Object.assign(state.settings, patch);
  applyTheme();
  try {
    const result = await api('settings.save', { settings: patch }, { silent: false });
    if (result && result.settings) state.settings = result.settings;
    applyTheme();
    updateStatusPill();
    return state.settings;
  } catch (error) {
    state.settings = previous;
    applyTheme();
    throw error;
  }
}

function setDevices(adbInfo) {
  if (!adbInfo) return;
  state.adb = { ...(state.adb || {}), ...adbInfo };
  syncActiveSerial();
  updateStatusPill();
}

/* ------------------------------------------------------------------ 全局错误上报 */
const reported = new Map();
function reportError(message) {
  const text = String(message || '').slice(0, 3000);
  const now = Date.now();
  const last = reported.get(text) || 0;
  if (now - last < 2500) return;
  reported.set(text, now);
  if (reported.size > 80) reported.clear();
  reportLog(text);
}

window.addEventListener('error', (event) => {
  const target = event.target;
  if (target && (target.tagName === 'IMG' || target.tagName === 'VIDEO')) return;
  reportError('JS错误: ' + (event.message || '未知') + ' @ ' + (event.filename || '') + ':' + (event.lineno || 0) + ':' + (event.colno || 0));
});
window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason;
  const message = reason && reason.message ? reason.message : String(reason);
  reportError('未处理的异步异常: ' + message + (reason && reason.stack ? '\n' + String(reason.stack).split('\n').slice(0, 3).join('\n') : ''));
});

/* ------------------------------------------------------------------ 页面上下文 */
const appCtx = {
  get state() { return state; },
  get params() { return state.params || {}; },
  navigate,
  back(fallback = 'home') { navigate(fallback); },
  toast: ui.toast,
  deviceSummary,
  get activeDevice() {
    const devices = (state.adb && state.adb.devices) || [];
    return devices.find((d) => d.serial === state.activeSerial && d.online) || devices.find((d) => d.online) || null;
  },
  setActiveSerial(serial) {
    state.activeSerial = serial || '';
    updateStatusPill();
  },
  setDevices,
  refreshState,
  saveSettings,
  applyTheme,
  reportError,
  setDebug,
  onCleanup(fn) {
    if (typeof fn === 'function') currentCleanups.push(fn);
  },
  setLeaveGuard(fn) { leaveGuard = typeof fn === 'function' ? fn : null; },
  clearLeaveGuard() { leaveGuard = null; },
  setBootText(text) {
    const el = document.getElementById('boot-text');
    if (el) el.textContent = text;
  },
};

/* ------------------------------------------------------------------ 启动 */
function showBootError(error) {
  const boot = document.getElementById('boot');
  if (!boot) return;
  const text = document.getElementById('boot-text');
  if (text) text.textContent = '启动遇到问题：' + String((error && error.message) || error || '未知错误');
  const dots = boot.querySelector('.boot-dots');
  if (dots) dots.remove();
  const retry = ui.button({
    label: '重新加载', variant: 'primary', size: 'sm',
    onClick: () => window.location.reload(),
  });
  boot.appendChild(h('div', { class: 'boot-actions' }, retry));
}

function revealApp() {
  const boot = document.getElementById('boot');
  rootEl.hidden = false;
  requestAnimationFrame(() => {
    rootEl.classList.add('app-visible');
    if (boot) {
      boot.classList.add('boot-leave');
      setTimeout(() => boot.remove(), 380);
    }
  });
}

async function boot() {
  const initial = (location.hash || '').replace(/^#\/?/, '') || 'home';
  try {
    applyTheme();
    buildShell();
    await refreshState({ silent: false });
    await navigate(ROUTES[initial] ? initial : 'home');
    revealApp();
  } catch (error) {
    console.error('启动失败', error);
    reportError('启动失败：' + (error && error.message ? error.message : error));
    if (!topbarLeft) {
      showBootError(error);
    } else {
      revealApp();
    }
  }
}

boot();

// 调试入口（开发时可在控制台查看应用状态）。
try {
  window.__recordToolbox = { state, navigate, refreshState, saveSettings };
} catch (error) { /* 忽略 */ }
