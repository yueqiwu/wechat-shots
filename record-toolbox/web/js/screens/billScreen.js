/**
 * screens/billScreen.js —— 交易明细格式化（微信支付 / 支付宝 → Word）。
 *
 * 流程：导入两份「交易明细证明」PDF → 预览并勾选要导出的明细 → 按模板导出支出/收入两个 DOCX。
 * 模板支持自定义（下载默认模板 → 修改 → 上传），明细行占位符 {{序号}} 会按条重复。
 * 全部解析与导出都在本机完成，不上传任何数据。
 */
import { api } from '../api.js';
import * as ui from '../ui.js';

const { h, icon } = ui;

const SOURCES = [
  { kind: 'wechat', label: '微信支付', desc: '微信「我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账」', accept: '.pdf,application/pdf', icon: 'bubble' },
  { kind: 'alipay', label: '支付宝', desc: '支付宝「我的 → 账单 → 更多 → 开具交易流水证明 → 用于个人对账」', accept: '.pdf,application/pdf', icon: 'shield' },
];
const FILTERS = [
  { value: 'all', label: '全部' },
  { value: 'wechat', label: '微信支付' },
  { value: 'alipay', label: '支付宝' },
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
];
const MAX_VISIBLE_ROWS = 300;
const E2E_MODE = /(?:^|[?&])e2e=1(?:&|$)/.test(window.location.search);

function formatTimeShort(row) {
  const text = row && row.time ? String(row.time) : '';
  const m = text.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}:\d{2}:\d{2})/);
  if (!m) return text || '—';
  return `${Number(m[2])}月${Number(m[3])}日 ${m[4]}`;
}

function directionMeta(row) {
  const direction = row && row.direction ? row.direction : 'other';
  if (direction === 'income') return { label: '收入', tone: 'income', glyph: '+' };
  if (direction === 'expense') return { label: '支出', tone: 'expense', glyph: '-' };
  return { label: '不计收支', tone: 'other', glyph: '·' };
}

export async function mount(wrapper, ctx) {
  const state = {
    loading: true,
    data: null,
    error: '',
    filter: 'all',
    query: '',
    selected: new Set(),
    busy: false,
    uploading: '',       // 'wechat' | 'alipay' | 'template-收入' ...
    uploadPercent: 0,
    exporting: '',
    result: null,
    resultError: '',
    templateOpen: false,
  };
  const inputs = {};
  let disposed = false;

  /* ---------------------- 数据刷新 ---------------------- */
  function applyState(next, { resetSelection = true } = {}) {
    if (!next || typeof next !== 'object') return;
    state.data = next;
    state.error = '';
    if (resetSelection) {
      state.selected = new Set((next.rows || []).filter((row) => row.direction !== 'other').map((row) => row.id));
    } else {
      const ids = new Set((next.rows || []).map((row) => row.id));
      state.selected = new Set([...state.selected].filter((id) => ids.has(id)));
    }
  }

  function summary() {
    const data = state.data || {};
    return data.summary || { total: 0, incomeCount: 0, incomeAmount: '0.00', expenseCount: 0, expenseAmount: '0.00', otherCount: 0 };
  }

  function sourceMeta(kind) {
    const list = (state.data && state.data.sources) || [];
    return list.find((item) => item.kind === kind) || null;
  }

  function hasData() {
    return Boolean(state.data && state.data.hasData && (state.data.rows || []).length);
  }

  async function refresh({ resetSelection = true } = {}) {
    try {
      const next = await api('bill.state', {}, { silent: true });
      if (disposed) return;
      applyState(next, { resetSelection });
      if (resetSelection) state.result = null;
    } catch (error) {
      if (disposed) return;
      state.error = (error && error.message) || '读取交易明细状态失败';
    } finally {
      state.loading = false;
      if (!disposed) render();
    }
  }

  /* ---------------------- 上传 ---------------------- */
  function ensureInput(kind, accept) {
    if (inputs[kind]) return inputs[kind];
    const input = h('input', { type: 'file', accept, style: { display: 'none' }, 'data-testid': 'bill-file-' + kind });
    input.addEventListener('change', () => {
      const file = input.files && input.files[0];
      input.value = '';
      if (file) uploadFile(kind, file);
    });
    inputs[kind] = input;
    return input;
  }

  function uploadFile(kind, file) {
    if (state.busy || !file) return;
    if (!/\.pdf$/i.test(file.name || '') && !/application\/pdf/.test(file.type || '')) {
      ui.toast('请选择「交易明细证明」的 PDF 文件', 'error');
      return;
    }
    state.busy = true;
    state.uploading = kind;
    state.uploadPercent = 0;
    state.result = null;
    state.resultError = '';
    render();
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/upload_bill');
    xhr.setRequestHeader('X-Bill-Kind', kind);
    xhr.setRequestHeader('X-File-Name', encodeURIComponent(file.name || 'bill.pdf'));
    xhr.upload.addEventListener('progress', (event) => {
      if (!event.lengthComputable) return;
      state.uploadPercent = event.total ? event.loaded / event.total : 0;
      const bar = document.querySelector('[data-bill-progress]');
      if (bar) {
        bar.style.width = Math.round(state.uploadPercent * 100) + '%';
        bar.parentElement && (bar.parentElement.hidden = false);
      }
      const label = document.querySelector('[data-bill-progress-label]');
      if (label) label.textContent = '正在导入 ' + Math.round(state.uploadPercent * 100) + '%';
    });
    const finish = (fn) => {
      state.busy = false;
      state.uploading = '';
      state.uploadPercent = 0;
      if (!disposed) {
        render();
        fn();
      }
    };
    xhr.addEventListener('load', () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText || '{}'); } catch (error) { /* 忽略 */ }
      if (data && data.ok && data.state) {
        applyState(data.state, { resetSelection: true });
        finish(() => {
          let message = '交易明细已导入';
          if (data.imported) {
            const label = data.imported.format === 'alipay' ? '支付宝' : '微信支付';
            message = `${label}明细已导入，共 ${data.imported.rows} 条。`;
          }
          ui.toast(message, 'success');
        });
      } else {
        state.error = (data && (data.error || data.message)) || '导入失败，请确认这是官方的「交易明细证明」PDF';
        finish(() => ui.toast(state.error, 'error'));
      }
    });
    xhr.addEventListener('error', () => {
      state.error = '导入失败：无法连接本地服务';
      finish(() => ui.toast(state.error, 'error'));
    });
    xhr.send(file);
  }

  async function removeSource(kind) {
    if (state.busy) return;
    try {
      const data = await api('bill.source.remove', { kind });
      applyState(data.state || {}, { resetSelection: true });
      state.result = null;
      render();
    } catch (error) { /* api 已 toast */ }
  }

  /* ---------------------- 选择 ---------------------- */
  function rows() {
    return (state.data && state.data.rows) || [];
  }

  function visibleRows() {
    const query = state.query.trim().toLowerCase();
    return rows().filter((row) => {
      if (state.filter === 'wechat' && !String(row.platform || '').includes('微信')) return false;
      if (state.filter === 'alipay' && String(row.platform || '').includes('微信')) return false;
      if (state.filter === 'expense' && row.direction !== 'expense') return false;
      if (state.filter === 'income' && row.direction !== 'income') return false;
      if (!query) return true;
      const haystack = [
        row.counterparty, row.order_id, row.merchant_order, row.product,
        row.type, row.method, row.amount, row.timeDisplay,
        row.direction === 'income' ? '收入' : row.direction === 'expense' ? '支出' : '不计收支',
      ].join(' ').toLowerCase();
      return haystack.includes(query);
    });
  }

  function selectableRows() {
    return visibleRows().filter((row) => row.direction !== 'other');
  }

  function selectedCount() {
    return state.selected.size;
  }

  function selectedIds(direction) {
    return rows()
      .filter((row) => row.direction === direction && state.selected.has(row.id))
      .map((row) => row.id);
  }

  function toggleRow(row, checked, element) {
    if (row.direction === 'other') return;
    if (checked) state.selected.add(row.id);
    else state.selected.delete(row.id);
    if (element) element.classList.toggle('bill-row-checked', checked);
    updateCounterBar();
    updateExportButtons();
  }

  function toggleAll() {
    const selectable = selectableRows();
    const allSelected = selectable.length > 0 && selectable.every((row) => state.selected.has(row.id));
    selectable.forEach((row) => {
      if (allSelected) state.selected.delete(row.id);
      else state.selected.add(row.id);
    });
    render();
  }

  /* ---------------------- 导出 ---------------------- */
  async function exportDocuments(directions) {
    if (state.busy || state.exporting) return;
    if (!hasData()) {
      ui.toast('请先导入交易明细', 'info');
      return;
    }
    const ids = { expense: selectedIds('expense'), income: selectedIds('income') };
    const chosen = directions.filter((direction) => ids[direction].length);
    if (!chosen.length) {
      ui.toast('没有勾选要导出的明细', 'info');
      return;
    }
    const excludeIds = rows().filter((row) => !state.selected.has(row.id)).map((row) => row.id);
    state.exporting = chosen.join('-');
    state.result = null;
    state.resultError = '';
    render();
    try {
      const data = await api('bill.export', {
        directions: chosen,
        excludeIds,
        ...(E2E_MODE ? { noOpen: true } : {}),
      });
      state.result = data;
      state.exporting = '';
      render();
      ui.toast('Word 文档已导出', 'success');
    } catch (error) {
      state.exporting = '';
      state.resultError = (error && error.message) || '导出失败';
      render();
    }
  }

  async function openFolder(path) {
    if (!path) return;
    try { await api('open_folder', { path }); } catch (error) { /* api 已 toast */ }
  }

  /* ---------------------- 模板 ---------------------- */
  function templateMeta(direction) {
    const templates = (state.data && state.data.templates) || {};
    return templates[direction] || { builtin: true, custom: false, updatedAt: '' };
  }

  async function downloadTemplate(direction) {
    try {
      const data = await api('bill.template.download', { direction }, { silent: false });
      ui.toast('默认模板已保存到 ' + (data.path || '导出目录'), 'success', { timeout: 3600 });
    } catch (error) { /* api 已 toast */ }
  }

  function uploadTemplate(direction, file) {
    if (state.busy || !file) return;
    if (!/\.docx$/i.test(file.name || '')) {
      ui.toast('请选择 .docx 格式的模板文件', 'error');
      return;
    }
    state.busy = true;
    state.uploading = 'template-' + direction;
    render();
    const prefix = direction === 'income' ? '收入' : '支出';
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/upload_bill');
    xhr.setRequestHeader('X-Bill-Kind', 'template');
    xhr.setRequestHeader('X-File-Name', encodeURIComponent(prefix + '_' + file.name));
    xhr.addEventListener('load', () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText || '{}'); } catch (error) { /* 忽略 */ }
      state.busy = false;
      state.uploading = '';
      if (data && data.ok && data.state) {
        applyState(data.state, { resetSelection: false });
        render();
        ui.toast((direction === 'income' ? '收入' : '支出') + '模板已更新', 'success');
      } else {
        render();
        ui.toast((data && (data.error || data.message)) || '模板上传失败', 'error');
      }
    });
    xhr.addEventListener('error', () => {
      state.busy = false;
      state.uploading = '';
      render();
      ui.toast('模板上传失败：无法连接本地服务', 'error');
    });
    xhr.send(file);
  }

  async function resetTemplate(direction) {
    if (state.busy) return;
    try {
      const data = await api('bill.template.reset', { direction });
      applyState(data.state || {}, { resetSelection: false });
      render();
      ui.toast('已恢复默认模板', 'success');
    } catch (error) { /* api 已 toast */ }
  }

  async function clearAll() {
    if (state.busy) return;
    const ok = await ui.confirm({
      title: '清空已导入的明细？',
      message: '清空后需要重新导入 PDF，已勾选的内容也会一起清除。',
      okText: '清空',
      danger: true,
    });
    if (!ok) return;
    try {
      const data = await api('bill.clear');
      applyState({ ...(data.state || {}), rows: [], sources: [], hasData: false }, { resetSelection: true });
      state.result = null;
      state.resultError = '';
      render();
      ui.toast('已清空', 'info');
    } catch (error) { /* api 已 toast */ }
  }

  /* ---------------------- 界面 ---------------------- */
  function pageHead() {
    return h('div', { class: 'page-head' },
      h('div', { class: 'page-heading' },
        h('h1', { class: 'page-title' }, '交易明细格式化'),
        h('p', { class: 'page-sub' }, '把微信支付 / 支付宝导出的「交易明细证明」整理成可直接提交的 Word 文档。')),
      h('div', { class: 'page-head-actions' }));
  }

  function sourceTile(source) {
    const meta = sourceMeta(source.kind);
    const input = ensureInput(source.kind, source.accept);
    const uploading = state.uploading === source.kind;
    const card = h('div', {
      class: 'bill-source' + (meta ? ' bill-source-loaded' : '') + (uploading ? ' bill-source-uploading' : ''),
      'data-testid': 'bill-source-' + source.kind,
    });
    card.appendChild(h('span', { class: 'bill-source-icon' }, icon(source.icon, 22)));
    const text = h('div', { class: 'bill-source-text' },
      h('div', { class: 'bill-source-title' },
        h('span', {}, source.label + '明细'),
        meta ? h('span', { class: 'bill-source-count' }, `${meta.rows} 条`) : null),
      h('div', { class: 'bill-source-desc' }, meta ? (meta.name || '已导入') : source.desc));
    if (uploading && state.uploadPercent > 0) {
      text.appendChild(h('div', { class: 'bill-upload', hidden: false },
        h('span', { class: 'bill-upload-label', 'data-bill-progress-label': null }, '正在导入 ' + Math.round(state.uploadPercent * 100) + '%'),
        h('span', { class: 'bill-upload-track' }, h('span', { class: 'bill-upload-fill', 'data-bill-progress': null, style: `width:${Math.round(state.uploadPercent * 100)}%` }))));
    }
    card.appendChild(text);
    const actions = h('div', { class: 'bill-source-actions' });
    actions.appendChild(ui.button({
      label: meta ? '重新导入' : '选择 PDF',
      icon: meta ? 'refresh' : 'plus',
      variant: meta ? 'ghost' : 'default',
      size: 'sm',
      disabled: state.busy,
      onClick: () => input.click(),
    }));
    if (meta) {
      actions.appendChild(ui.iconButton('trash', { label: '移除' + source.label + '明细', size: 16, variant: 'ghost', disabled: state.busy, onClick: () => removeSource(source.kind) }));
    }
    card.appendChild(actions);
    card.appendChild(input);
    const stop = (event) => { event.preventDefault(); event.stopPropagation(); };
    card.addEventListener('dragover', (event) => { stop(event); if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'; card.classList.add('dragover'); });
    card.addEventListener('dragleave', (event) => { stop(event); card.classList.remove('dragover'); });
    card.addEventListener('drop', (event) => {
      stop(event);
      card.classList.remove('dragover');
      const file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
      if (file) uploadFile(source.kind, file);
    });
    return card;
  }

  function importCard() {
    const summaryData = summary();
    return ui.sectionCard({
      title: '导入交易明细',
      desc: '支持微信支付与支付宝导出的「交易明细证明」PDF，本机解析，不上传。',
      icon: 'file',
      extra: state.data && state.data.hasData
        ? ui.button({ label: '清空', icon: 'trash', variant: 'ghost', size: 'sm', disabled: state.busy, onClick: clearAll })
        : null,
      children: [
        h('div', { class: 'bill-sources' }, ...SOURCES.map(sourceTile)),
        state.data && state.data.hasData
          ? h('div', { class: 'bill-summary' },
            ui.statTile({ label: '全部明细', value: String(summaryData.total), unit: '条' }),
            ui.statTile({ label: '支出', value: summaryData.expenseCount, unit: '条', sub: '¥' + summaryData.expenseAmount }),
            ui.statTile({ label: '收入', value: summaryData.incomeCount, unit: '条', sub: '¥' + summaryData.incomeAmount }),
            ui.statTile({ label: '不计收支', value: summaryData.otherCount, unit: '条', sub: '默认不导出' }))
          : null,
      ],
    });
  }

  function counterBar() {
    const total = rows().length;
    const count = selectedCount();
    return h('div', { class: 'bill-counter' },
      h('span', { class: 'bill-counter-main' }, `已勾选 ${count} 条 / 共 ${total} 条`),
      h('span', { class: 'bill-counter-hint' }, '取消勾选的明细不会导出；不计收支默认不导出。'));
  }

  function updateCounterBar() {
    const node = wrapper.querySelector('.bill-counter');
    if (!node) return;
    node.replaceWith(counterBar());
  }

  function updateExportButtons() {
    ['expense', 'income'].forEach((direction) => {
      const count = selectedIds(direction).length;
      const counter = wrapper.querySelector(`[data-bill-export-count="${direction}"]`);
      if (counter) counter.textContent = count + ' 条';
      const btn = wrapper.querySelector(`[data-testid="bill-export-${direction}"]`);
      if (btn) btn.disabled = state.busy || Boolean(state.exporting) || !count;
    });
    const both = wrapper.querySelector('[data-bill-export-both]');
    if (both) {
      const count = selectedIds('expense').length + selectedIds('income').length;
      both.disabled = state.busy || Boolean(state.exporting) || !count;
    }
  }

  function previewList() {
    const all = visibleRows();
    const shown = all.slice(0, MAX_VISIBLE_ROWS);
    const list = h('div', { class: 'bill-rows', 'data-testid': 'bill-rows' });
    if (!shown.length) {
      list.appendChild(ui.emptyState({ icon: 'search', title: '没有符合条件的明细', desc: '换个关键词或切换筛选条件试试。' }));
      return list;
    }
    shown.forEach((row) => list.appendChild(billRow(row)));
    if (all.length > shown.length) {
      list.appendChild(h('div', { class: 'bill-more' }, `还有 ${all.length - shown.length} 条未显示，请用搜索或筛选缩小范围。`));
    }
    return list;
  }

  function billRow(row) {
    const dir = directionMeta(row);
    const checked = state.selected.has(row.id);
    const disabled = row.direction === 'other';
    const input = h('input', { type: 'checkbox', class: 'bill-check', checked, disabled, 'data-testid': 'bill-row-check' });
    input.addEventListener('change', () => toggleRow(row, input.checked, el));
    const el = h('label', {
      class: 'bill-row' + (disabled ? ' bill-row-other' : '') + (checked ? ' bill-row-checked' : ''),
      'data-testid': 'bill-row',
      'data-id': String(row.id),
      'data-direction': row.direction,
    });
    el.appendChild(input);
    el.appendChild(h('span', { class: 'bill-time', title: row.timeDisplay || row.time || '' }, formatTimeShort(row)));
    el.appendChild(h('span', { class: 'bill-platform' }, String(row.platform || '').includes('微信') ? '微信' : '支付宝'));
    el.appendChild(h('span', { class: 'bill-dir bill-dir-' + dir.tone }, dir.label));
    el.appendChild(h('span', { class: 'bill-amount bill-amount-' + dir.tone }, (dir.glyph === '+' ? '+' : dir.glyph === '-' ? '-' : '') + '¥' + (row.amount || '0.00')));
    el.appendChild(h('span', { class: 'bill-party', title: row.counterparty || '' },
      row.counterparty || '—',
      row.product ? h('span', { class: 'bill-product' }, row.product) : null));
    el.appendChild(h('span', { class: 'bill-meta' },
      h('span', {}, '单号 ' + (row.order_id || '—')),
      row.method ? h('span', {}, '方式 ' + row.method) : null,
      row.type ? h('span', {}, '类型 ' + row.type) : null));
    return el;
  }

  function previewCard() {
    const selectable = selectableRows();
    const allSelected = selectable.length > 0 && selectable.every((row) => state.selected.has(row.id));
    const search = ui.field({
      type: 'search',
      placeholder: '搜索交易对方、单号、说明…',
      value: state.query,
      onInput: (value) => { state.query = value; render(); },
    });
    return ui.sectionCard({
      title: '预览与筛选',
      desc: '默认全部勾选，取消勾选的明细不会出现在导出的文档里。',
      icon: 'scan',
      children: [
        h('div', { class: 'bill-toolbar' },
          ui.segmented({
            items: FILTERS,
            value: state.filter,
            small: true,
            onChange: (value) => { state.filter = value; render(); },
          }),
          h('div', { class: 'bill-toolbar-search' }, search),
          h('button', {
            class: 'bill-select-all', type: 'button',
            'data-testid': 'bill-select-all',
            onclick: () => { toggleAll(); },
          }, allSelected ? '取消全选' : '全选'),
          h('span', { class: 'bill-view-count' }, `当前列表 ${visibleRows().length} 条`)),
        counterBar(),
        previewList(),
      ],
    });
  }

  function exportCard() {
    if (!hasData()) return null;
    const expenseCount = selectedIds('expense').length;
    const incomeCount = selectedIds('income').length;
    const exportButton = (direction, label, iconName) => {
      const btn = ui.button({
        label: (label + '（') ,
        icon: iconName,
        variant: 'primary',
        disabled: state.busy || Boolean(state.exporting) || !selectedIds(direction).length,
        onClick: () => exportDocuments([direction]),
      });
      if (!selectedIds(direction).length) btn.disabled = true;
      const count = h('span', { class: 'bill-btn-count', 'data-bill-export-count': direction }, (selectedIds(direction).length || 0) + ' 条');
      btn.appendChild(count);
      btn.appendChild(h('span', {}, '）'));
      btn.setAttribute('data-testid', 'bill-export-' + direction);
      return btn;
    };
    const bothBtn = ui.button({
      label: '导出两份 Word',
      icon: 'save',
      variant: 'default',
      disabled: !expenseCount && !incomeCount,
      onClick: () => exportDocuments(['expense', 'income']),
    });
    bothBtn.setAttribute('data-bill-export-both', '');
    bothBtn.setAttribute('data-testid', 'bill-export-both');
    return ui.sectionCard({
      title: '导出 Word',
      desc: '收入与支出分开导出，文件名自动带上导出时间；导出后可直接打开所在文件夹。',
      icon: 'save',
      children: [
        h('div', { class: 'bill-export-actions' },
          exportButton('expense', '导出支出明细', 'monitor'),
          exportButton('income', '导出收入明细', 'monitor'),
          bothBtn),
        h('div', { class: 'bill-export-hint' }, '提示：文档内容按模板生成，收入、支出各一个 Word 文件。'),
        state.resultError ? ui.banner({ kind: 'danger', title: '导出失败', message: state.resultError }) : null,
        state.result ? resultCard() : null,
      ],
    });
  }

  function resultCard() {
    const result = state.result || {};
    const files = result.files || [];
    const list = h('div', { class: 'bill-result-files', 'data-testid': 'bill-result' },
      ...files.map((file) => h('div', { class: 'bill-result-file' },
        h('span', { class: 'bill-result-icon' }, icon('file', 18)),
        h('span', { class: 'bill-result-text' },
          h('span', { class: 'bill-result-name' }, file.name || ''),
          h('span', { class: 'bill-result-sub' }, `${file.label || ''} · ${file.count || 0} 条 · ¥${file.amount || '0.00'}`)),
        ui.button({ label: '打开', icon: 'external', variant: 'ghost', size: 'sm', onClick: () => openFolder(result.dir) }))));
    return h('div', { class: 'bill-result' },
      h('div', { class: 'bill-result-head' },
        h('span', { class: 'bill-result-title' }, icon('checkCircle', 18), '导出完成'),
        result.dir ? h('span', { class: 'bill-result-path', title: result.dir }, result.dir) : null),
      list,
      h('div', { class: 'bill-result-actions' },
        ui.button({ label: '打开所在文件夹', icon: 'folder', variant: 'default', size: 'sm', onClick: () => openFolder(result.dir) })));
  }

  function templateCard() {
    if (!state.data) return null;
    const row = (direction, label) => {
      const meta = templateMeta(direction);
      const uploadInput = ensureInput('template-' + direction, '.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document');
      uploadInput.addEventListener('change', () => {
        const file = uploadInput.files && uploadInput.files[0];
        uploadInput.value = '';
        if (file) uploadTemplate(direction, file);
      });
      return h('div', { class: 'bill-template-row' },
        h('span', { class: 'bill-template-text' },
          h('span', { class: 'bill-template-name' }, label),
          h('span', { class: 'bill-template-desc' },
            meta.custom ? '自定义模板' + (meta.updatedAt ? ' · ' + meta.updatedAt : '') : '默认模板')),
        h('span', { class: 'bill-template-actions' },
          ui.button({ label: '下载模板', icon: 'save', variant: 'ghost', size: 'sm', onClick: () => downloadTemplate(direction) }),
          ui.button({ label: meta.custom ? '更换' : '上传', icon: 'plus', variant: 'ghost', size: 'sm', disabled: state.busy, onClick: () => uploadInput.click() }),
          meta.custom
            ? ui.button({ label: '恢复默认', icon: 'refresh', variant: 'ghost', size: 'sm', disabled: state.busy, onClick: () => resetTemplate(direction) })
            : null),
        uploadInput);
    };
    const body = h('div', { class: 'bill-templates' },
      row('expense', '支出模板'),
      row('income', '收入模板'),
      h('div', { class: 'bill-template-tip' },
        h('span', {}, '模板里含 '),
        h('code', {}, '{{序号}}'),
        h('span', {}, ' 的段落会按每条明细重复，其余段落只出现一次。可用 Word 修改下载的模板后重新上传，字体、字号、页边距都会保留。')));
    const collapse = ui.collapsible({
      title: '自定义模板',
      desc: '想换说法或改排版？下载默认模板，用 Word 改完再上传即可。',
      icon: 'file',
      open: state.templateOpen,
      children: [body],
    });
    collapse.el.setAttribute('data-testid', 'bill-template');
    return collapse.el;
  }

  function render() {
    if (disposed) return;
    const body = h('div', { class: 'bill-page' });
    body.appendChild(pageHead());
    if (state.loading) {
      body.appendChild(h('div', { class: 'card section' }, h('div', { class: 'section-body' }, ui.skeleton(4))));
    } else {
      if (state.error) {
        body.appendChild(ui.banner({
          kind: 'danger', title: '交易明细读取失败', message: state.error,
          actions: [ui.button({ label: '重试', variant: 'default', size: 'sm', onClick: () => refresh({}) })],
        }));
      }
      body.appendChild(importCard());
      if (hasData()) {
        const exp = exportCard();
        if (exp) body.appendChild(exp);
        body.appendChild(previewCard());
        const tpl = templateCard();
        if (tpl) body.appendChild(tpl);
      } else if (!state.loading) {
        body.appendChild(ui.emptyState({
          icon: 'file',
          title: '还没有导入交易明细',
          desc: '在两份 PDF 都导入后，这里会显示可勾选的明细和导出按钮。',
        }));
      }
    }
    wrapper.replaceChildren(body);
  }

  /* ---------------------- 生命周期 ---------------------- */
  render();
  await refresh({ resetSelection: true });
  if (E2E_MODE) {
    window.__RT_E2E_BILL = {
      state: () => ({
        rows: rows().length,
        selected: selectedCount(),
        other: rows().filter((row) => row.direction === 'other').length,
        visible: visibleRows().length,
        hasData: hasData(),
        result: state.result,
        resultError: state.resultError,
        exporting: state.exporting,
        busy: state.busy,
      }),
    };
  }
  return () => {
    disposed = true;
    if (window.__RT_E2E_BILL) window.__RT_E2E_BILL = null;
  };
}
