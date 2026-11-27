/**
 * ui.js —— 通用 UI 组件与工具（原生 DOM，无框架）。
 * 设计语言见 docs/CONTRACTS.md：iOS 风、圆角卡片、胶囊、轻阴影。
 */

/* ------------------------------------------------------------------ 工具 */
export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [key, value] of Object.entries(attrs)) {
      if (value === null || value === undefined || value === false) continue;
      if (key === 'class') el.className = value;
      else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
      else if (key === 'html') el.innerHTML = value;
      else if (key === 'dataset') Object.assign(el.dataset, value);
      else if (key === 'text') el.textContent = value;
      else if (key.startsWith('on') && typeof value === 'function') {
        el.addEventListener(key.slice(2).toLowerCase(), value);
      } else if (key === 'value') el.value = value;
      else if (key === 'checked' || key === 'disabled' || key === 'selected') el[key] = Boolean(value);
      else if (value === true) el.setAttribute(key, '');
      else el.setAttribute(key, String(value));
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    if (Array.isArray(child)) append(el, child);
    else if (child instanceof Node) el.appendChild(child);
    else el.appendChild(document.createTextNode(String(child)));
  }
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
export const raf = () => new Promise((resolve) => requestAnimationFrame(() => resolve()));
export const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
export const pad3 = (value) => String(value).padStart(3, '0');
export const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (ch) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[ch]));

export function formatBytes(bytes) {
  const n = Number(bytes) || 0;
  if (n <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const index = Math.min(units.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
  const value = n / 1024 ** index;
  return (value >= 100 || index === 0 ? Math.round(value) : value.toFixed(1)) + ' ' + units[index];
}

export function formatMs(ms) {
  const n = Math.max(0, Math.round(Number(ms) || 0));
  if (n < 1000) return n + ' 毫秒';
  const seconds = n / 1000;
  if (seconds < 60) return (seconds >= 10 ? seconds.toFixed(0) : seconds.toFixed(1)) + ' 秒';
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  return minutes + ' 分 ' + (rest >= 10 ? rest : '0' + rest) + ' 秒';
}

export function formatDate(input) {
  const date = input instanceof Date ? input : new Date(input || Date.now());
  if (Number.isNaN(date.getTime())) return '';
  const p = (v) => String(v).padStart(2, '0');
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())} ${p(date.getHours())}:${p(date.getMinutes())}`;
}

export function formatClock(input = Date.now()) {
  const date = input instanceof Date ? input : new Date(input);
  const p = (v) => String(v).padStart(2, '0');
  return `${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`;
}

/** 尝试动态加载一个 ES 模块；不存在时返回 null（不抛错）。 */
export async function tryImport(url) {
  try {
    return await import(url);
  } catch (error) {
    return null;
  }
}

/* ------------------------------------------------------------------ 图标 */
const ICONS = {
  home: '<path d="M3.2 10.4 12 3l8.8 7.4"/><path d="M5.3 9.3V19a1.6 1.6 0 0 0 1.6 1.6h3.4v-5.4h3.4v5.4h3.4A1.6 1.6 0 0 0 18.7 19V9.3"/>',
  clock: '<circle cx="12" cy="12" r="8.6"/><path d="M12 7.4v4.9l3.2 1.9"/>',
  gear: '<circle cx="12" cy="12" r="3.1"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.86l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.7 1.7 0 0 0-1.86-.34 1.7 1.7 0 0 0-1 1.55V21a2 2 0 1 1-4 0v-.11a1.7 1.7 0 0 0-1.1-1.55 1.7 1.7 0 0 0-1.87.34l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.7 1.7 0 0 0 .34-1.86 1.7 1.7 0 0 0-1.55-1H3a2 2 0 1 1 0-4h.11a1.7 1.7 0 0 0 1.55-1.1 1.7 1.7 0 0 0-.34-1.87l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.7 1.7 0 0 0 1.86.34H9a1.7 1.7 0 0 0 1-1.55V3a2 2 0 1 1 4 0v.11a1.7 1.7 0 0 0 1 1.55 1.7 1.7 0 0 0 1.86-.34l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.7 1.7 0 0 0-.34 1.86V9a1.7 1.7 0 0 0 1.55 1H21a2 2 0 1 1 0 4h-.11a1.7 1.7 0 0 0-1.49 1Z"/>',
  smartphone: '<rect x="6.6" y="2.4" width="10.8" height="19.2" rx="2.6"/><path d="M10.9 18.3h2.2"/>',
  film: '<rect x="3" y="4.4" width="18" height="15.2" rx="2.4"/><path d="M7.6 4.4v15.2M16.4 4.4v15.2M3 9.2h4.6M3 14.8h4.6M16.4 9.2H21M16.4 14.8H21"/>',
  usb: '<path d="M12 21.2v-6.4"/><path d="M8.8 8.2V2.8M15.2 2.8v5.4M18.2 8.2v4.4a3.6 3.6 0 0 1-3.6 3.6h-5.2a3.6 3.6 0 0 1-3.6-3.6V8.2Z"/>',
  wifi: '<path d="M4.4 9.4a11 11 0 0 1 15.2 0"/><path d="M7.7 13a6.3 6.3 0 0 1 8.6 0"/><path d="M10.8 16.5a2.1 2.1 0 0 1 2.4 0"/><circle cx="12" cy="19.6" r="1" fill="currentColor" stroke="none"/>',
  refresh: '<path d="M20.4 12.4a8.4 8.4 0 1 1-2.5-6.1"/><path d="M20.6 3.6v6h-6"/>',
  retry: '<path d="M3.6 11.6a8.4 8.4 0 1 1 2.5 6.1"/><path d="M3.4 20.4v-6h6"/>',
  chevronRight: '<path d="M9 5.5 15.5 12 9 18.5"/>',
  chevronLeft: '<path d="M15 5.5 8.5 12 15 18.5"/>',
  chevronDown: '<path d="M5.5 9 12 15.5 18.5 9"/>',
  check: '<path d="M4.8 12.6l4.5 4.5 9.9-10.2"/>',
  checkCircle: '<circle cx="12" cy="12" r="8.8"/><path d="M8.3 12.2l2.5 2.5 4.9-5.1"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  stop: '<rect x="6.5" y="6.5" width="11" height="11" rx="2.2" fill="currentColor" stroke="none"/>',
  play: '<path d="M8 5.4v13.2L19 12 8 5.4Z"/>',
  folder: '<path d="M3.4 7.6a2 2 0 0 1 2-2h3.3l2 2.5h7.9a2 2 0 0 1 2 2v7.9a2 2 0 0 1-2 2H5.4a2 2 0 0 1-2-2V7.6Z"/>',
  save: '<path d="M12 3.4v11.8"/><path d="M7.7 11.3 12 15.6l4.3-4.3"/><path d="M4.4 19.8h15.2"/>',
  image: '<rect x="3.5" y="4.4" width="17" height="15.2" rx="2.4"/><circle cx="8.6" cy="9.9" r="1.5"/><path d="m4 17.3 4.3-4.3a2 2 0 0 1 2.8 0l5.9 5.9"/>',
  sparkles: '<path d="M12 3.2 13.7 8l4.8 1.7-4.8 1.7L12 16.2l-1.7-4.8L5.5 9.7 10.3 8 12 3.2Z"/><path d="M18.5 15.2l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8.8-2.2Z"/>',
  shield: '<path d="M12 3.2 19 5.9v5.3c0 4.2-2.9 7.9-7 9.3-4.1-1.4-7-5.1-7-9.3V5.9L12 3.2Z"/><path d="M8.7 11.9l2.3 2.3 4.4-4.4"/>',
  bolt: '<path d="M13.2 2.4 4.6 13.8h6.1L10.8 21.6l8.6-11.4h-6.1l-.1-7.8Z"/>',
  link: '<path d="M10.1 13.2a4.8 4.8 0 0 0 7.2.4l2.5-2.5a4.8 4.8 0 0 0-6.8-6.8l-1.4 1.4"/><path d="M13.9 10.8a4.8 4.8 0 0 0-7.2-.4l-2.5 2.5a4.8 4.8 0 0 0 6.8 6.8l1.4-1.4"/>',
  alert: '<path d="M12 3.6 21.5 20H2.5L12 3.6Z"/><path d="M12 9.5v4.6"/><circle cx="12" cy="17.2" r="1" fill="currentColor" stroke="none"/>',
  alertCircle: '<circle cx="12" cy="12" r="8.8"/><path d="M12 8v5.2"/><circle cx="12" cy="16.6" r="1" fill="currentColor" stroke="none"/>',
  info: '<circle cx="12" cy="12" r="8.8"/><path d="M12 11v5.2"/><circle cx="12" cy="7.8" r="1" fill="currentColor" stroke="none"/>',
  trash: '<path d="M4.6 6.9h14.8"/><path d="M9.6 6.9V4.6a1 1 0 0 1 1-1h2.8a1 1 0 0 1 1 1v2.3"/><path d="M6.6 6.9l.8 12.4a1.6 1.6 0 0 0 1.6 1.5h6a1.6 1.6 0 0 0 1.6-1.5l.8-12.4"/>',
  external: '<path d="M14 4.2h5.8V10"/><path d="M19.8 4.2 10.6 13.4"/><path d="M17.6 14.2v4.4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-10a2 2 0 0 1 2-2h4.4"/>',
  monitor: '<rect x="3" y="4.4" width="18" height="12.4" rx="2"/><path d="M8.4 20.8h7.2M12 16.8v4"/>',
  scan: '<path d="M4 8.2V5.6A1.6 1.6 0 0 1 5.6 4h2.6M15.8 4h2.6A1.6 1.6 0 0 1 20 5.6v2.6M20 15.8v2.6a1.6 1.6 0 0 1-1.6 1.6h-2.6M8.2 20H5.6A1.6 1.6 0 0 1 4 18.4v-2.6"/><path d="M7.4 12h9.2"/>',
  copy: '<rect x="9" y="9" width="11.2" height="11.2" rx="2.2"/><path d="M15.2 9V6.2a2.2 2.2 0 0 0-2.2-2.2H6.2A2.2 2.2 0 0 0 4 6.2V13a2.2 2.2 0 0 0 2.2 2.2H9"/>',
  search: '<circle cx="11" cy="11" r="7.2"/><path d="M20.4 20.4 16.2 16.2"/>',
  file: '<path d="M14 3.2H7.4a2 2 0 0 0-2 2v13.6a2 2 0 0 0 2 2h9.2a2 2 0 0 0 2-2V8L14 3.2Z"/><path d="M14 3.2V8h5.2"/>',
  bubble: '<path d="M20.5 11.2c0 3.9-3.8 7.1-8.5 7.1-.9 0-1.8-.1-2.6-.3L4.2 20l1.3-3.6C4.1 15.1 3.2 13.2 3.2 11.2c0-3.9 3.8-7.1 8.5-7.1s8.8 3.2 8.8 7.1Z"/>',
  arrowRight: '<path d="M4.5 12h14.6"/><path d="M13.3 6.1 19.1 12l-5.8 5.9"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  key: '<circle cx="8" cy="15.5" r="4.2"/><path d="m11 12.5 8.4-8.4M16.6 6.9l2.4 2.4M14.2 9.3l2.2 2.2"/>',
  pixel: '<rect x="3.8" y="3.8" width="7" height="7" rx="1.4"/><rect x="13.2" y="3.8" width="7" height="7" rx="1.4"/><rect x="3.8" y="13.2" width="7" height="7" rx="1.4"/><path d="M16.7 13.2v7M13.2 16.7h7"/>',
};

export function icon(name, size = 20, extraClass = '') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.8');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  if (extraClass) svg.setAttribute('class', extraClass);
  svg.innerHTML = ICONS[name] || ICONS.info;
  return svg;
}

/* ------------------------------------------------------------------ 基础组件 */
export function button(options = {}) {
  const {
    label, icon: iconName, iconRight, variant = 'default', size = 'md',
    block = false, disabled = false, onClick, title, type = 'button',
  } = options;
  const el = h('button', {
    class: ['btn', 'btn-' + variant, 'btn-' + size, block ? 'btn-block' : ''].filter(Boolean).join(' '),
    type,
    disabled,
    title: title || null,
    onclick: onClick || null,
  });
  if (iconName) el.appendChild(icon(iconName, size === 'sm' ? 15 : 17));
  if (label) el.appendChild(h('span', { class: 'btn-label' }, label));
  if (iconRight) el.appendChild(icon(iconRight, size === 'sm' ? 15 : 17));
  return el;
}

export function iconButton(iconName, { label = '', size = 18, variant = 'ghost', onClick, disabled = false } = {}) {
  const el = h('button', {
    class: 'icon-btn icon-btn-' + variant,
    type: 'button',
    title: label || null,
    'aria-label': label || null,
    disabled,
    onclick: onClick || null,
  });
  el.appendChild(icon(iconName, size));
  return el;
}

export function segmented({ items, value, onChange, small = false }) {
  const el = h('div', { class: 'segmented' + (small ? ' segmented-sm' : '') });
  const buttons = items.map((item) => {
    const btn = h('button', {
      class: 'segmented-item' + (item.value === value ? ' active' : ''),
      type: 'button',
      disabled: Boolean(item.disabled),
      title: item.title || null,
      onclick: () => {
        if (item.value === current) return;
        current = item.value;
        buttons.forEach((b) => b.classList.toggle('active', b.dataset.value === String(current)));
        onChange && onChange(item.value);
      },
    }, item.label);
    btn.dataset.value = String(item.value);
    return btn;
  });
  let current = value;
  el.append(...buttons);
  el.setValue = (next) => {
    current = next;
    buttons.forEach((b) => b.classList.toggle('active', b.dataset.value === String(next)));
  };
  return el;
}

export function switchRow({ label, desc, checked = false, disabled = false, onChange }) {
  const input = h('input', { class: 'switch-input', type: 'checkbox', checked });
  input.disabled = disabled;
  input.addEventListener('change', () => onChange && onChange(input.checked));
  return h('label', { class: 'switch-row' + (disabled ? ' is-disabled' : '') },
    h('span', { class: 'switch-text' },
      h('span', { class: 'switch-label' }, label),
      desc ? h('span', { class: 'switch-desc' }, desc) : null),
    h('span', { class: 'switch' }, input, h('span', { class: 'switch-track' }, h('span', { class: 'switch-thumb' }))));
}

export function progressBar({ value = 0, indeterminate = false } = {}) {
  const fill = h('i', { class: 'progress-fill', style: { width: (clamp(value, 0, 1) * 100).toFixed(2) + '%' } });
  const el = h('div', { class: 'progress' + (indeterminate ? ' progress-indeterminate' : ''), role: 'progressbar' }, fill);
  return {
    el,
    set(next) {
      if (indeterminate) return;
      el.style.setProperty('--progress', clamp(next, 0, 1));
      fill.style.width = (clamp(next, 0, 1) * 100).toFixed(2) + '%';
    },
  };
}

export function spinner(label = '', size = 16) {
  const el = h('span', { class: 'spinner', style: { '--spinner-size': size + 'px' } });
  if (!label) return el;
  return h('span', { class: 'spinner-line' }, el, h('span', { class: 'spinner-label' }, label));
}

export function skeleton(lines = 3) {
  return h('div', { class: 'skeleton', 'aria-hidden': 'true' },
    ...Array.from({ length: lines }, (_, index) => h('i', {
      class: 'skel-line',
      style: { width: [92, 78, 64, 84][index % 4] + '%' },
    })));
}

export function emptyState({ icon: iconName = 'sparkles', title = '', desc = '', actions = [] } = {}) {
  return h('div', { class: 'empty' },
    h('div', { class: 'empty-icon' }, icon(iconName, 26)),
    title ? h('div', { class: 'empty-title' }, title) : null,
    desc ? h('div', { class: 'empty-desc' }, desc) : null,
    actions.length ? h('div', { class: 'empty-actions' }, actions) : null);
}

export function sectionCard({ title, desc, icon: iconName, extra, children, class: extraClass = '', compact = false } = {}) {
  return h('section', { class: 'card section ' + extraClass },
    title || desc || extra
      ? h('div', { class: 'section-head' },
        iconName ? h('span', { class: 'section-icon' }, icon(iconName, 18)) : null,
        h('div', { class: 'section-heading' },
          title ? h('h2', { class: 'section-title' }, title) : null,
          desc ? h('p', { class: 'section-desc' }, desc) : null),
        extra || null)
      : null,
    h('div', { class: 'section-body' + (compact ? ' compact' : '') }, children));
}

export function banner({ kind = 'info', title = '', message = '', actions = [], dismissible = false } = {}) {
  const el = h('div', { class: 'banner banner-' + kind, role: kind === 'danger' || kind === 'warn' ? 'alert' : null });
  const iconName = kind === 'danger' ? 'alertCircle' : kind === 'warn' ? 'alert' : kind === 'success' ? 'checkCircle' : 'info';
  el.appendChild(h('span', { class: 'banner-icon' }, icon(iconName, 18)));
  el.appendChild(h('div', { class: 'banner-body' },
    title ? h('div', { class: 'banner-title' }, title) : null,
    message ? h('div', { class: 'banner-message' }, message) : null,
    actions.length ? h('div', { class: 'banner-actions' }, actions) : null));
  if (dismissible) {
    el.appendChild(iconButton('close', { label: '关闭提示', size: 15, onClick: () => el.remove() }));
  }
  return el;
}

export function statTile({ label, value, unit = '', sub = '' }) {
  return h('div', { class: 'stat' },
    h('div', { class: 'stat-label' }, label),
    h('div', { class: 'stat-value' }, String(value), unit ? h('span', { class: 'stat-unit' }, unit) : null),
    sub ? h('div', { class: 'stat-sub' }, sub) : null);
}

export function chips(items) {
  return h('div', { class: 'chips' }, ...items.map((item) => h('span', { class: 'chip' }, item)));
}

export function stepList(steps, { open = true } = {}) {
  const el = h('ol', { class: 'steps' });
  steps.forEach((step, index) => {
    el.appendChild(h('li', { class: 'step' },
      h('span', { class: 'step-num' }, String(index + 1)),
      h('span', { class: 'step-text' },
        h('span', { class: 'step-title' }, step.title),
        step.desc ? h('span', { class: 'step-desc' }, step.desc) : null)));
  });
  return el;
}

export function collapsible({ title, desc = '', icon: iconName = null, open = false, children }) {
  let isOpen = open;
  const body = h('div', { class: 'collapse-body' }, children);
  const chevron = h('span', { class: 'collapse-chevron' }, icon('chevronDown', 17));
  const head = h('button', {
    class: 'collapse-head', type: 'button',
    onclick: () => { isOpen = !isOpen; setOpen(isOpen); },
  },
  iconName ? h('span', { class: 'collapse-icon' }, icon(iconName, 18)) : null,
  h('span', { class: 'collapse-text' },
    h('span', { class: 'collapse-title' }, title),
    desc ? h('span', { class: 'collapse-desc' }, desc) : null),
  chevron);
  const el = h('div', { class: 'collapse card' }, head, body);
  function setOpen(next) {
    isOpen = next;
    el.classList.toggle('open', isOpen);
    body.style.display = isOpen ? '' : 'none';
  }
  setOpen(open);
  return { el, setOpen, get open() { return isOpen; } };
}

export function field({ label, hint, type = 'text', value = '', placeholder = '', inputmode, maxlength, onInput, onEnter, readonly = false, mono = false, suffix = null }) {
  const input = h('input', {
    class: 'input' + (mono ? ' input-mono' : ''),
    type, value, placeholder,
    inputmode: inputmode || null,
    maxlength: maxlength || null,
    readonly,
  });
  if (onInput) input.addEventListener('input', () => onInput(input.value));
  if (onEnter) input.addEventListener('keydown', (event) => { if (event.key === 'Enter') onEnter(input.value); });
  const el = h('label', { class: 'field' },
    label ? h('span', { class: 'field-label' }, label) : null,
    h('span', { class: 'field-control' }, input, suffix || null),
    hint ? h('span', { class: 'field-hint' }, hint) : null);
  el.input = input;
  return el;
}

export function row({ icon: iconName, title, desc, extra, onClick, class: extraClass = '' }) {
  const tag = onClick ? 'button' : 'div';
  const el = h(tag, {
    class: 'row ' + extraClass + (onClick ? ' row-clickable' : ''),
    type: onClick ? 'button' : null,
    onclick: onClick || null,
  });
  if (iconName) el.appendChild(h('span', { class: 'row-icon' }, icon(iconName, 19)));
  el.appendChild(h('span', { class: 'row-text' },
    h('span', { class: 'row-title' }, title),
    desc ? h('span', { class: 'row-desc' }, desc) : null));
  if (extra) el.appendChild(h('span', { class: 'row-extra' }, extra));
  return el;
}

/* ------------------------------------------------------------------ Toast */
const toastRoot = () => document.getElementById('toast-root');

export function toast(message, kind = 'info', { timeout = 2800 } = {}) {
  const root = toastRoot();
  if (!root) return;
  while (root.children.length >= 4) root.firstElementChild.remove();
  const iconName = kind === 'success' ? 'checkCircle' : kind === 'error' ? 'alertCircle' : 'info';
  const el = h('div', { class: 'toast toast-' + kind },
    h('span', { class: 'toast-icon' }, icon(iconName, 17)),
    h('span', { class: 'toast-text' }, message));
  root.appendChild(el);
  requestAnimationFrame(() => el.classList.add('show'));
  const remove = () => {
    el.classList.remove('show');
    el.classList.add('hide');
    setTimeout(() => el.remove(), 220);
  };
  const timer = setTimeout(remove, timeout);
  el.addEventListener('click', () => { clearTimeout(timer); remove(); });
}

/* ------------------------------------------------------------------ 对话框 */
const dialogStack = [];
let dialogKeysBound = false;

function ensureDialogKeys() {
  if (dialogKeysBound) return;
  dialogKeysBound = true;
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape' || dialogStack.length === 0) return;
    event.preventDefault();
    dialogStack[dialogStack.length - 1].close();
  });
}

export function dialog({ title = '', body, actions = [], wide = false, onClose = null } = {}) {
  ensureDialogKeys();
  const root = document.getElementById('modal-root') || document.body;
  let closed = false;
  const handle = { close };
  const backdrop = h('div', { class: 'dialog-backdrop' });
  const panel = h('div', { class: 'dialog' + (wide ? ' dialog-wide' : ''), role: 'dialog', 'aria-modal': 'true' });

  const head = h('div', { class: 'dialog-head' },
    h('h3', { class: 'dialog-title' }, title),
    iconButton('close', { label: '关闭', size: 18, onClick: close }));
  panel.appendChild(head);
  if (body) panel.appendChild(h('div', { class: 'dialog-body' }, body));
  if (actions.length) {
    panel.appendChild(h('div', { class: 'dialog-foot' },
      actions.map((action) => button({
        label: action.label,
        variant: action.variant || 'default',
        onClick: async () => {
          let result = true;
          try {
            result = await action.onClick?.();
          } catch (error) {
            console.error(error);
            return;
          }
          if (result !== false) close();
        },
      }))));
  }
  backdrop.appendChild(panel);
  backdrop.addEventListener('mousedown', (event) => {
    if (event.target === backdrop) close();
  });
  root.appendChild(backdrop);
  requestAnimationFrame(() => backdrop.classList.add('open'));

  function close() {
    if (closed) return;
    closed = true;
    backdrop.classList.remove('open');
    backdrop.classList.add('closing');
    const index = dialogStack.indexOf(handle);
    if (index >= 0) dialogStack.splice(index, 1);
    setTimeout(() => backdrop.remove(), 180);
    onClose && onClose();
  }

  dialogStack.push(handle);
  setTimeout(() => {
    const focusable = panel.querySelector('.btn-primary, .btn, input, button');
    focusable && focusable.focus({ preventScroll: true });
  }, 60);
  return handle;
}

export function confirm({ title = '确认操作', message = '', okText = '确定', cancelText = '取消', danger = false } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (value) => {
      if (done) return;
      done = true;
      resolve(value);
    };
    dialog({
      title,
      body: h('p', { class: 'dialog-message' }, message),
      actions: [
        { label: cancelText, onClick: () => finish(false) },
        { label: okText, variant: danger ? 'danger' : 'primary', onClick: () => finish(true) },
      ],
      onClose: () => finish(false),
    });
  });
}

/* ------------------------------------------------------------------ 缩略图 */
export function thumbsGrid(items, { limit = 30, emptyText = '暂无缩略图' } = {}) {
  if (!items || items.length === 0) {
    return emptyState({ icon: 'image', title: emptyText, desc: '完成导出后会在这里预览每一页。' });
  }
  const shown = items.slice(0, limit);
  const grid = h('div', { class: 'thumb-grid' },
    ...shown.map((item, index) => h('figure', { class: 'thumb' },
      h('div', { class: 'thumb-img' }, h('img', { src: item.src, alt: '第 ' + (index + 1) + ' 页', loading: 'lazy' })),
      h('figcaption', { class: 'thumb-cap' }, item.label || pad3(index + 1) + ' 页'))));
  if (items.length > limit) {
    grid.appendChild(h('div', { class: 'thumb-more' }, `共 ${items.length} 页，仅预览前 ${limit} 页`));
  }
  return grid;
}

/* ------------------------------------------------------------------ 小工具：复制文本 */
export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(String(text));
    toast('已复制', 'success', { timeout: 1500 });
    return true;
  } catch (error) {
    return false;
  }
}
