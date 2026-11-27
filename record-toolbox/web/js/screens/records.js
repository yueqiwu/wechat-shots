/**
 * screens/records.js —— 导出记录（保存在 localStorage）。
 */
import { h, icon, formatDate, emptyState, button, iconButton, confirm, toast, sectionCard } from '../ui.js';
import { api } from '../api.js';

export const RECORDS_KEY = 'rtb.records.v1';
const MAX_RECORDS = 200;

export function readRecords() {
  try {
    const raw = localStorage.getItem(RECORDS_KEY);
    if (!raw) return [];
    const list = JSON.parse(raw);
    if (!Array.isArray(list)) return [];
    return list.filter((item) => item && typeof item === 'object' && item.dir);
  } catch (error) {
    return [];
  }
}

function writeRecords(list) {
  try {
    localStorage.setItem(RECORDS_KEY, JSON.stringify(list.slice(0, MAX_RECORDS)));
  } catch (error) {
    console.warn('保存导出记录失败', error);
  }
}

export function appendRecord(record) {
  const list = readRecords();
  const entry = {
    id: record.id || Date.now() + '-' + Math.random().toString(36).slice(2, 8),
    name: record.name || '聊天记录',
    contact: record.contact || '',
    method: record.method || '手机截图',
    time: record.time || new Date().toISOString(),
    pageCount: Number(record.pageCount) || 0,
    dir: record.dir || '',
    zip: record.zip || '',
  };
  // 同一文件夹的旧记录直接替换，避免重复。
  const next = [entry, ...list.filter((item) => item.dir !== entry.dir)];
  writeRecords(next);
  return entry;
}

export function removeRecord(id) {
  writeRecords(readRecords().filter((item) => item.id !== id));
}

export function clearRecords() {
  writeRecords([]);
}

async function openRecordFolder(record) {
  try {
    await api('open_folder', { path: record.dir });
  } catch (error) {
    toast('文件夹不存在或已被移动', 'error');
  }
}

export function mount(root, ctx) {
  function render() {
    const records = readRecords();
    const listEl = h('div', { class: 'record-list' });

    if (records.length === 0) {
      listEl.appendChild(h('div', { class: 'card record-empty-card' }, emptyState({
        icon: 'clock',
        title: '还没有导出记录',
        desc: '完成一次导出后，这里会自动保存记录，方便随时打开查看。',
        actions: [button({ label: '开始导出', icon: 'smartphone', variant: 'primary', onClick: () => ctx.navigate('adb') })],
      })));
    } else {
      for (const record of records) {
        listEl.appendChild(h('article', { class: 'card record-card' },
          h('div', { class: 'record-main' },
            h('div', { class: 'record-icon' }, icon('image', 20)),
            h('div', { class: 'record-info' },
              h('div', { class: 'record-name', title: record.name }, record.name),
              h('div', { class: 'record-meta' },
                h('span', { class: 'record-chip' }, record.method || '手机截图'),
                h('span', {}, String(record.pageCount || 0) + ' 页'),
                h('span', { class: 'record-dot' }, '·'),
                h('span', {}, formatDate(record.time))),
              h('div', { class: 'record-path', title: record.dir }, record.dir))),
          h('div', { class: 'record-actions' },
            button({ label: '打开文件夹', icon: 'folder', size: 'sm', onClick: () => openRecordFolder(record) }),
            iconButton('trash', {
              label: '删除记录',
              size: 16,
              onClick: async () => {
                const ok = await confirm({
                  title: '删除这条记录？',
                  message: '只会删除记录列表中的条目，磁盘上的截图文件不会被删除。',
                  okText: '删除',
                  danger: true,
                });
                if (!ok) return;
                removeRecord(record.id);
                toast('已删除记录', 'success');
                render();
              },
            }))));
      }
    }

    const page = h('div', { class: 'page records-page' },
      h('div', { class: 'page-head' },
        h('div', { class: 'page-heading' },
          h('h1', { class: 'page-title' }, '导出记录'),
          h('p', { class: 'page-sub' }, records.length
            ? `共 ${records.length} 条记录，保存在本机浏览器存储中。`
            : '这里会列出你导出过的聊天记录。')),
        records.length
          ? h('div', { class: 'page-head-actions' },
            button({
              label: '清空记录',
              icon: 'trash',
              size: 'sm',
              variant: 'ghost',
              onClick: async () => {
                const ok = await confirm({
                  title: '清空全部记录？',
                  message: '只会清空记录列表，磁盘上的截图文件不会被删除。此操作无法撤销。',
                  okText: '清空',
                  danger: true,
                });
                if (!ok) return;
                clearRecords();
                toast('已清空记录', 'success');
                render();
              },
            }))
          : null),
      listEl,
      h('p', { class: 'records-footnote' }, '记录仅用于快速打开文件夹，卸载程序后不会保留。'));

    root.replaceChildren(page);
  }

  render();
  ctx.onCleanup(() => { /* 无需清理 */ });
}
