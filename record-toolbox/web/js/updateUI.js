/**
 * updateUI.js —— 检查更新 / 自动更新弹窗（应用启动时静默检查，设置页可手动检查）。
 */
import { api } from './api.js';
import { h, dialog, toast } from './ui.js';

function formatSize(bytes) {
  const size = Number(bytes) || 0;
  if (!size) return '';
  if (size >= 1024 * 1024) return `约 ${(size / 1024 / 1024).toFixed(1)} MB`;
  return `约 ${Math.max(1, Math.round(size / 1024))} KB`;
}

export async function checkForUpdates({ silent = false } = {}) {
  let result;
  try {
    result = await api('update.check');
  } catch (error) {
    if (!silent) toast('检查更新失败，请稍后再试', 'error');
    return null;
  }
  if (!result || !result.ok) {
    if (!silent) toast((result && result.error) || '检查更新失败', 'error');
    return null;
  }
  if (!result.hasUpdate) {
    if (!silent) toast(`当前已是最新版本 v${result.currentVersion}`, 'success');
    return result;
  }
  const latest = result.latest || {};
  const notes = Array.isArray(latest.notes) ? latest.notes : [];
  const size = formatSize(latest.size);
  dialog({
    title: `发现新版本 v${latest.version}`,
    body: h('div', { class: 'update-dialog' },
      h('p', { class: 'update-line' },
        `当前版本 v${result.currentVersion}，最新版本 v${latest.version}${size ? `（${size}）` : ''}。`),
      notes.length ? h('ul', { class: 'update-notes' }, ...notes.map((text) => h('li', null, String(text)))) : null,
      h('p', { class: 'update-hint' }, '更新时会自动下载并替换程序，完成后笔录工具箱会自动重新打开。')),
    actions: [
      { label: '稍后', variant: 'default' },
      {
        label: '立即更新',
        variant: 'primary',
        onClick: async () => {
          toast('正在下载新版本，请不要关闭程序…', 'info');
          let applied;
          try {
            applied = await api('update.apply');
          } catch (error) {
            toast('更新失败：' + ((error && error.message) || error), 'error');
            return false;
          }
          if (!applied || !applied.ok) {
            toast((applied && applied.error) || '更新失败，请稍后再试', 'error');
            return false;
          }
          if (applied.upToDate) {
            toast('当前已经是最新版本', 'success');
            return true;
          }
          toast('正在重启以完成更新…', 'success');
          return false;
        },
      },
    ],
  });
  return result;
}
