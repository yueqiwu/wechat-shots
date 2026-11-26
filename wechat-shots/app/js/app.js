// app.js - 微信录屏 → 聊天截图 的界面逻辑。
import * as pipeline from './pipeline.js';
import { makeZip } from './zip.js';

const $ = (id) => document.getElementById(id);
const nextFrame = () => new Promise((r) => requestAnimationFrame(() => r()));

const state = {
  objectUrl: null,
  fileName: '',
  busy: false,
  stop: false,
  sample: null,
  plan: null,
  tiles: [],
  longSegs: [],
  selected: new Set(),
  shotUrls: [],
};

function setBar(f) {
  const p = Math.max(0, Math.min(1, f || 0));
  $('bar').style.width = Math.round(p * 100) + '%';
}

function setStatus(t) { $('status').textContent = t; }

let toastTimer = 0;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3000);
}

function fmtDur(s) {
  if (!isFinite(s)) return '--';
  const m = Math.floor(s / 60);
  const r = Math.floor(s % 60);
  return m + ':' + String(r).padStart(2, '0');
}

function num(n) { return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }

function download(blob, name) {
  const a = document.createElement('a');
  const u = URL.createObjectURL(blob);
  a.href = u;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(u), 30000);
}

function revokeShots() {
  for (const u of state.shotUrls) URL.revokeObjectURL(u);
  state.shotUrls = [];
}

function resetAll() {
  revokeShots();
  state.sample = null;
  state.plan = null;
  state.tiles = [];
  state.longSegs = [];
  state.selected = new Set();
  $('grid').textContent = '';
  $('gallery').hidden = true;
  const lw = $('longWrap');
  if (lw) { lw.hidden = true; lw.textContent = ''; }
  setBar(0);
  setStatus('等待开始');
}

function addMeta(txt) {
  const s = document.createElement('span');
  s.textContent = txt;
  $('meta').appendChild(s);
}

async function loadFile(f) {
  if (!f) return;
  const isVideo = /^video\//.test(f.type || '') || /\.(mp4|mov|m4v|webm|mkv|avi)$/i.test(f.name || '');
  if (!isVideo) { toast('这不是视频文件'); return; }
  if (state.objectUrl) URL.revokeObjectURL(state.objectUrl);
  state.objectUrl = URL.createObjectURL(f);
  await openSource(state.objectUrl, f.name, f.size);
}

async function loadSample() {
  if (state.objectUrl) { URL.revokeObjectURL(state.objectUrl); state.objectUrl = null; }
  await openSource('/sample/input.mp4', '录屏样例.mp4', 0);
}

async function openSource(src, name, size) {
  resetAll();
  state.fileName = name;
  const v = $('video');
  v.src = src;
  $('meta').textContent = '';
  $('dropzone').hidden = true;
  $('work').hidden = false;

  const ready = new Promise((res, rej) => {
    v.onloadedmetadata = () => res();
    v.onerror = () => rej(new Error('视频解码失败：浏览器可能不支持这种编码'));
  });
  try {
    await ready;
  } catch (e) {
    toast(e.message);
    setStatus(e.message);
    return;
  }
  addMeta('分辨率 ' + v.videoWidth + 'x' + v.videoHeight);
  addMeta('时长 ' + fmtDur(v.duration));
  if (size) addMeta('大小 ' + (size / 1048576).toFixed(1) + ' MB');
  try { await pipeline.seekTo(v, Math.min(0.2, (v.duration || 1) / 3)); } catch (e) {}
  drawCrop();
  setStatus('已载入，点「开始生成截图」');
}

function cropPx() {
  const v = $('video');
  const vh = v.videoHeight || 1;
  const top = Math.round(vh * (Number($('topCut').value) / 100));
  const bot = Math.round(vh * (1 - Number($('botCut').value) / 100));
  return { topPx: top, botPx: bot };
}

function drawCrop() {
  const v = $('video');
  if (!v || !v.videoWidth) return;
  const canvas = $('cropCanvas');
  const W = 220;
  const H = Math.round(W * v.videoHeight / v.videoWidth);
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d');
  ctx.drawImage(v, 0, 0, W, H);
  const c = cropPx();
  const top = H * c.topPx / v.videoHeight;
  const bot = H * c.botPx / v.videoHeight;
  ctx.fillStyle = 'rgba(255,90,90,0.38)';
  ctx.fillRect(0, 0, W, top);
  ctx.fillRect(0, bot, W, H - bot);
  ctx.strokeStyle = '#07c160';
  ctx.lineWidth = 1.5;
  ctx.strokeRect(0.75, top + 0.75, W - 1.5, Math.max(1, bot - top - 1.5));
}

function applyAutoCrop(band, AH) {
  if (!band || band.h < AH * 0.25 || band.h >= AH) return false;
  const topPct = Math.max(0, Math.min(40, Math.round(band.top / AH * 100)));
  const botPct = Math.max(0, Math.min(40, Math.round((1 - band.bot / AH) * 100)));
  $('topCut').value = String(topPct);
  $('botCut').value = String(botPct);
  $('topVal').textContent = topPct + '%';
  $('botVal').textContent = botPct + '%';
  return true;
}

async function run() {
  if (state.busy) return;
  const v = $('video');
  if (!v || !v.videoWidth) { toast('先选择一个录屏视频'); return; }
  state.busy = true;
  state.stop = false;
  $('btnRun').disabled = true;
  $('btnStop').hidden = false;
  $('gallery').hidden = true;
  revokeShots();
  state.tiles = [];
  state.longSegs = [];
  state.plan = null;
  const lw = $('longWrap');
  if (lw) { lw.hidden = true; lw.textContent = ''; }

  const fps = Number($('fps').value);
  const fmt = $('fmt').value;
  const dur = v.duration || 1;
  let fpsEff = fps;
  let note = '';
  if (dur * fps > 2400) {
    fpsEff = Math.max(1.5, 2400 / dur);
    note = '（视频较长，自动降到 ' + fpsEff.toFixed(1) + ' 帧/秒）';
  }

  try {
    setBar(0);
    setStatus('抽帧中' + note + '…');
    const sample = await pipeline.analyze(v, { fps: fpsEff, analysisWidth: 144 }, {
      progress: (f, t) => { setBar(f * 0.55); if (t) setStatus(t + note); },
      stop: () => state.stop,
    });
    if (state.stop) throw new Error('已取消');
    state.sample = sample;

    if (applyAutoCrop(sample.band, sample.AH)) drawCrop();

    setBar(0.58);
    setStatus('重建整段聊天内容…');
    await nextFrame();
    const plan = pipeline.planMaster(sample, sample.track, cropPx());
    state.plan = plan;

    setBar(0.62);
    const tiles = await pipeline.exportTiles(v, sample, plan, {
      tileH: plan.cropHpx, format: fmt, quality: 0.94,
    }, {
      progress: (f, t) => { setBar(0.62 + f * 0.38); if (t) setStatus(t); },
      stop: () => state.stop,
    });
    if (state.stop) throw new Error('已取消');
    state.tiles = tiles;

    renderGallery(tiles);
    setBar(1);
    const msg = '完成：' + tiles.length + ' 张截图 · 内容总高 ' + num(plan.MP) + 'px · 每张约 ' +
      tiles[0].w + '×' + plan.cropHpx;
    setStatus(msg + (plan.bad ? ' · 有 ' + plan.bad + ' 处跳变已自动补帧' : ''));
    if (plan.missingPx > 4) {
      toast('有约 ' + plan.missingPx + 'px 内容没抓到：滑动太快，画面直接跳过了一屏多');
    }
    $('gallery').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (e) {
    setStatus(e && e.message ? e.message : String(e));
    if (!/取消/.test(String(e && e.message))) toast(String(e && e.message || e));
  } finally {
    state.busy = false;
    $('btnRun').disabled = false;
    $('btnStop').hidden = true;
  }
}

function renderGallery(tiles) {
  const grid = $('grid');
  grid.textContent = '';
  state.selected = new Set();
  tiles.forEach((s, i) => {
    const url = URL.createObjectURL(s.blob);
    state.shotUrls.push(url);
    const card = document.createElement('div');
    card.className = 'card-shot on';
    const img = document.createElement('img');
    img.src = url;
    img.alt = s.name;
    img.loading = 'lazy';
    const tick = document.createElement('span');
    tick.className = 'tick';
    tick.textContent = '✓';
    const cap = document.createElement('span');
    cap.className = 'cap';
    cap.textContent = String(i + 1).padStart(String(tiles.length).length, '0') + ' · ' + s.h + 'px';
    card.appendChild(img);
    card.appendChild(tick);
    card.appendChild(cap);
    card.addEventListener('click', () => {
      if (state.selected.has(i)) { state.selected.delete(i); card.classList.remove('on'); card.classList.add('off'); }
      else { state.selected.add(i); card.classList.add('on'); card.classList.remove('off'); }
      updateCount();
    });
    grid.appendChild(card);
    state.selected.add(i);
  });
  $('gallery').hidden = false;
  updateCount();
}

function updateCount() {
  const total = state.tiles.length;
  $('count').textContent = state.selected.size + ' / ' + total + ' 张已选';
}

function selectedIdx() {
  return Array.from(state.selected).sort((a, b) => a - b);
}

async function doZip() {
  if (!state.tiles.length) { toast('先跑一次生成截图'); return; }
  const idx = selectedIdx();
  if (!idx.length) { toast('至少选一张截图'); return; }
  $('btnZip').disabled = true;
  setStatus('打包 ZIP 中…');
  try {
    const entries = [];
    for (const i of idx) {
      const s = state.tiles[i];
      const buf = new Uint8Array(await s.blob.arrayBuffer());
      entries.push({ name: s.name, data: buf });
    }
    if (state.longSegs.length) {
      for (const g of state.longSegs) {
        const buf = new Uint8Array(await g.blob.arrayBuffer());
        entries.push({ name: g.name, data: buf, store: true });
      }
    }
    const zip = makeZip(entries);
    const base = (state.fileName || 'wechat').replace(/\.[^.]+$/, '');
    download(zip, base + '_聊天截图.zip');
    setStatus('已打包 ' + entries.length + ' 个文件');
  } catch (e) {
    setStatus('打包失败：' + e.message);
  } finally {
    $('btnZip').disabled = false;
  }
}

async function doLong() {
  if (!state.plan) { toast('先跑一次生成截图'); return; }
  $('btnLong').disabled = true;
  setStatus('拼接长图中…');
  try {
    const fmt = $('fmt').value;
    const out = await pipeline.exportLong($('video'), state.sample, state.plan, {
      format: fmt === 'jpeg' ? 'jpeg' : 'png', quality: 0.94, maxSegH: 12000,
    }, {
      progress: (f, t) => { setBar(f); if (t) setStatus(t + ' ' + Math.round(f * 100) + '%'); },
      stop: () => state.stop,
    });
    state.longSegs = out.segs;
    renderLong(out);
    setBar(1);
    setStatus('长图完成：共 ' + out.segs.length + ' 段，总高 ' + num(out.total) + 'px');
  } catch (e) {
    setStatus('长图失败：' + e.message);
  } finally {
    $('btnLong').disabled = false;
  }
}

function renderLong(out) {
  let wrap = $('longWrap');
  if (!wrap) {
    wrap = document.createElement('section');
    wrap.className = 'gallery';
    wrap.id = 'longWrap';
    $('gallery').parentNode.insertBefore(wrap, $('gallery').nextSibling);
  }
  wrap.textContent = '';
  const head = document.createElement('div');
  head.className = 'gallery-head';
  const h2 = document.createElement('h2');
  h2.textContent = '完整长图';
  const cnt = document.createElement('span');
  cnt.className = 'count';
  cnt.textContent = '总高 ' + num(out.total) + 'px / ' + out.segs.length + ' 段';
  h2.appendChild(document.createTextNode(' '));
  h2.appendChild(cnt);
  head.appendChild(h2);
  wrap.appendChild(head);
  out.segs.forEach((g) => {
    const row = document.createElement('div');
    row.className = 'long-item';
    const img = document.createElement('img');
    const u = URL.createObjectURL(g.blob);
    state.shotUrls.push(u);
    img.src = u;
    const info = document.createElement('div');
    info.className = 'info';
    const b = document.createElement('b');
    b.textContent = g.name;
    const sp = document.createElement('span');
    sp.textContent = g.w + ' x ' + g.h + ' · ' + (g.blob.size / 1048576).toFixed(1) + ' MB';
    info.appendChild(b);
    info.appendChild(sp);
    const btn = document.createElement('button');
    btn.className = 'btn ghost';
    btn.textContent = '下载';
    btn.addEventListener('click', () => download(g.blob, (state.fileName || 'chat').replace(/\.[^.]+$/, '') + '_' + g.name));
    row.appendChild(img);
    row.appendChild(info);
    row.appendChild(btn);
    wrap.appendChild(row);
  });
  wrap.hidden = false;
}

function init() {
  window.addEventListener('error', (e) => { toast('脚本错误：' + (e.message || e.error)); });
  window.addEventListener('unhandledrejection', (e) => {
    const r = e.reason;
    toast('脚本错误：' + ((r && r.message) || r));
  });

  $('btnBrowse').addEventListener('click', (e) => { e.preventDefault(); $('file').click(); });
  $('dropzone').addEventListener('click', (e) => { if (!e.target.closest('a')) $('file').click(); });
  $('file').addEventListener('change', () => { const f = $('file').files[0]; if (f) loadFile(f); $('file').value = ''; });
  const bs = $('btnSample');
  if (bs) bs.addEventListener('click', (e) => { e.preventDefault(); loadSample(); });

  ['dragenter', 'dragover'].forEach((ev) => {
    document.addEventListener(ev, (e) => { e.preventDefault(); $('dropzone').classList.add('over'); });
  });
  ['dragleave', 'drop'].forEach((ev) => {
    document.addEventListener(ev, (e) => { e.preventDefault(); if (ev === 'drop' || !e.relatedTarget) $('dropzone').classList.remove('over'); });
  });
  document.addEventListener('drop', (e) => {
    const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (f) loadFile(f);
  });

  $('topCut').addEventListener('input', () => { $('topVal').textContent = $('topCut').value + '%'; drawCrop(); });
  $('botCut').addEventListener('input', () => { $('botVal').textContent = $('botCut').value + '%'; drawCrop(); });
  $('fps').addEventListener('change', () => {});
  $('video').addEventListener('seeked', () => drawCrop());

  $('btnRun').addEventListener('click', run);
  $('btnStop').addEventListener('click', () => { state.stop = true; setStatus('正在取消…'); });
  $('btnZip').addEventListener('click', doZip);
  $('btnLong').addEventListener('click', doLong);
  $('btnAll').addEventListener('click', () => {
    if (!state.tiles.length) return;
    const cards = Array.from($('grid').children);
    const allOn = state.selected.size === cards.length;
    state.selected = new Set();
    cards.forEach((c, i) => {
      if (allOn) { c.classList.remove('on'); c.classList.add('off'); }
      else { c.classList.add('on'); c.classList.remove('off'); state.selected.add(i); }
    });
    updateCount();
  });
}

init();
