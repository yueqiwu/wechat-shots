/**
 * screens/settings.js —— 设置：调试组件、输出目录、外观、导出参数、关于。
 */
import { h, icon, sectionCard, field, segmented, switchRow, button, toast, banner, row } from '../ui.js';
import { api } from '../api.js';

const PAGE_HEIGHTS = [
  { value: 'short', label: '更短' },
  { value: 'standard', label: '标准' },
  { value: 'tall', label: '更长' },
];

function settingRow(label, desc, control) {
  return h('div', { class: 'setting-row' },
    h('div', { class: 'setting-text' },
      h('div', { class: 'setting-label' }, label),
      desc ? h('div', { class: 'setting-desc' }, desc) : null),
    h('div', { class: 'setting-control' }, control));
}

export function mount(root, ctx) {
  const state = ctx.state;
  const settings = state.settings || {};

  function render() {
    const adbInfo = ctx.state.adb || {};
    const currentPath = (ctx.state.settings && ctx.state.settings.adb_path) || '';
    const effectivePath = currentPath || adbInfo.adbPath || '';

    /* ---------------- 调试组件 ---------------- */
    const pathField = field({
      label: '调试组件路径',
      value: effectivePath,
      placeholder: '留空则自动查找（程序目录下 adb\\ 文件夹）',
      hint: '填写调试组件的完整路径；留空时程序会自动查找。',
      mono: true,
      onEnter: () => saveAdbPath(),
    });
    pathField.input.setAttribute('data-testid', 'settings-adb-path');
    pathField.input.setAttribute('spellcheck', 'false');
    const statusWrap = h('div', { class: 'settings-adb-status' });
    function shortVersion(version) {
      const match = String(version || '').match(/(\d+(?:\.\d+){1,3})/);
      return match ? match[1] : '';
    }
    function updateAdbStatus() {
      const info = ctx.state.adb || {};
      if (info.adbFound) {
        const version = shortVersion(info.adbVersion);
        statusWrap.replaceChildren(h('div', { class: 'inline-status inline-status-ok' },
          icon('checkCircle', 15),
          h('span', {}, '调试组件可用' + (version ? '（版本 ' + version + '）' : ''))));
      } else {
        statusWrap.replaceChildren(h('div', { class: 'inline-status inline-status-warn' },
          icon('alertCircle', 15),
          h('span', {}, '还没有找到调试组件，可以留空让程序自动查找，或手动指定完整路径。')));
      }
    }
    updateAdbStatus();

    async function saveAdbPath() {
      const value = pathField.input.value.trim();
      try {
        await ctx.saveSettings({ adb_path: value });
        await ctx.refreshState({ silent: true });
        updateAdbStatus();
        toast('已保存调试组件路径', 'success');
      } catch (error) {
        toast(error.message || '保存失败', 'error');
      }
    }

    /** 用给定路径（空则自动查找）检测一次调试组件，并刷新状态。 */
    async function detectAdb(pathValue) {
      try {
        const info = await api('devices', { adbPath: pathValue || null }, { silent: false });
        ctx.setDevices(info);
        updateAdbStatus();
        toast(info.adbFound ? '已找到调试组件' : '未找到调试组件', info.adbFound ? 'success' : 'error');
        return info;
      } catch (error) {
        toast(error.message || '检测失败', 'error');
        return null;
      }
    }

    /** 浏览选择调试组件文件；取消时什么都不做。 */
    async function browseAdb() {
      let picked = null;
      try {
        picked = await api('pick_file', {
          title: '选择 adb.exe',
          label: '程序文件',
          exts: ['*.exe'],
        });
      } catch (error) {
        toast(error.message || '无法打开文件选择窗口', 'error');
        return;
      }
      if (!picked || !picked.path) return; // 用户取消
      pathField.input.value = picked.path;
      await detectAdb(picked.path);
    }

    const saveButton = button({
      label: '保存',
      icon: 'save',
      variant: 'primary',
      size: 'sm',
      onClick: saveAdbPath,
    });
    saveButton.setAttribute('data-testid', 'settings-save');

    const browseButton = button({
      label: '浏览…',
      icon: 'folder',
      variant: 'default',
      size: 'sm',
      onClick: browseAdb,
    });
    browseButton.setAttribute('data-testid', 'settings-adb-browse');
    const pathControl = pathField.querySelector('.field-control');
    if (pathControl) pathControl.appendChild(browseButton);

    const adbCard = sectionCard({
      title: '手机连接',
      desc: '「手机截图导出」通过手机系统的 USB 调试连接，程序会自动查找调试组件。',
      icon: 'smartphone',
      children: [
        pathField,
        h('div', { class: 'settings-actions' },
          button({
            label: '自动检测',
            icon: 'refresh',
            size: 'sm',
            onClick: () => detectAdb(pathField.input.value.trim()),
          }),
          saveButton),
        statusWrap,
      ],
    });

    /* ---------------- 输出位置 ---------------- */
    const outputPath = ctx.state.settings.output_dir || '';
    const outputCard = sectionCard({
      title: '导出位置',
      desc: '导出完成后自动打开该文件夹；留空时每次保存都会询问。',
      icon: 'folder',
      children: [
        h('div', { class: 'output-path-row' },
          h('span', { class: 'output-path' + (outputPath ? '' : ' is-empty'), title: outputPath },
            icon('folder', 15),
            h('span', {}, outputPath || '每次保存时询问')),
          h('div', { class: 'output-actions' },
            button({
              label: '选择文件夹',
              icon: 'folder',
              size: 'sm',
              onClick: async () => {
                try {
                  const result = await api('pick_folder', { title: '选择默认导出文件夹' }, { silent: true });
                  if (!result || !result.path) return;
                  await ctx.saveSettings({ output_dir: result.path });
                  toast('已设置默认导出位置', 'success');
                  render();
                } catch (error) { /* 用户取消 */ }
              },
            }),
            outputPath
              ? button({
                label: '清除',
                variant: 'ghost',
                size: 'sm',
                onClick: async () => {
                  await ctx.saveSettings({ output_dir: '' });
                  toast('已清除默认导出位置', 'success');
                  render();
                },
              })
              : null)),
      ],
    });

    /* ---------------- 外观 ---------------- */
    const themeSeg = segmented({
      items: [
        { value: 'auto', label: '自动' },
        { value: 'light', label: '浅色' },
        { value: 'dark', label: '深色' },
      ],
      value: ctx.state.settings.theme || 'auto',
      small: true,
      onChange: async (value) => {
        try {
          await ctx.saveSettings({ theme: value });
          toast('外观已更新', 'success', { timeout: 1500 });
        } catch (error) {
          themeSeg.setValue(ctx.state.settings.theme || 'auto');
        }
      },
    });

    const appearanceCard = sectionCard({
      title: '外观',
      desc: '深色模式会立即生效，可以在下方随时切换。',
      icon: 'gear',
      children: [
        settingRow('主题', '跟随系统时会自动切换浅色与深色。', themeSeg),
      ],
    });

    /* ---------------- 导出参数 ---------------- */
    const pageHeightSeg = segmented({
      items: PAGE_HEIGHTS,
      value: ctx.state.settings.page_height || 'standard',
      small: true,
      onChange: async (value) => {
        try { await ctx.saveSettings({ page_height: value }); } catch (error) { /* 已提示 */ }
      },
    });
    const fpsSeg = segmented({
      items: [
        { value: 5, label: '5 帧/秒' },
        { value: 10, label: '10 帧/秒' },
        { value: 15, label: '15 帧/秒' },
      ],
      value: ctx.state.settings.video_analysis_fps || 10,
      small: true,
      onChange: async (value) => {
        try { await ctx.saveSettings({ video_analysis_fps: value }); } catch (error) { /* 已提示 */ }
      },
    });
    const refineSeg = segmented({
      items: [
        { value: 1, label: '1 轮' },
        { value: 2, label: '2 轮' },
        { value: 3, label: '3 轮' },
      ],
      value: ctx.state.settings.video_refine_rounds || 2,
      small: true,
      onChange: async (value) => {
        try { await ctx.saveSettings({ video_refine_rounds: value }); } catch (error) { /* 已提示 */ }
      },
    });

    const maxShotsField = field({
      label: '最多截取屏数',
      type: 'number',
      value: String(ctx.state.settings.max_shots || 500),
      hint: '达到该数量会自动停止，避免聊天记录过长时占用过多时间。',
    });
    maxShotsField.input.min = '10';
    maxShotsField.input.max = '2000';
    maxShotsField.input.style.maxWidth = '120px';
    maxShotsField.input.addEventListener('change', async () => {
      const value = Math.round(Number(maxShotsField.input.value) || 500);
      const clamped = Math.min(2000, Math.max(10, value));
      maxShotsField.input.value = String(clamped);
      try { await ctx.saveSettings({ max_shots: clamped }); } catch (error) { /* 已提示 */ }
    });

    const exportCard = sectionCard({
      title: '导出与性能',
      desc: '这些参数同时作用于手机截图导出和录屏转截图。',
      icon: 'bolt',
      children: [
        settingRow('每页高度', '每页截图的高度，越长页数越少。', pageHeightSeg),
        settingRow('视频分析帧率', '帧率越高，滚动定位越准，分析也越慢。', fpsSeg),
        settingRow('视频补采轮数', '发现漏帧时会补采，轮数越多越不容易漏内容。', refineSeg),
        h('div', { class: 'settings-divider' }),
        switchRow({
          label: '自动裁掉状态栏与输入框',
          desc: '导出的截图只保留聊天内容区域。',
          checked: ctx.state.settings.crop_status_input !== false,
          onChange: async (checked) => {
            try { await ctx.saveSettings({ crop_status_input: checked }); } catch (error) { /* 已提示 */ }
          },
        }),
        switchRow({
          label: '保留原始截图',
          desc: '保存后保留临时截图文件，方便排查问题，会占用更多磁盘空间。',
          checked: Boolean(ctx.state.settings.keep_raw_shots),
          onChange: async (checked) => {
            try { await ctx.saveSettings({ keep_raw_shots: checked }); } catch (error) { /* 已提示 */ }
          },
        }),
        maxShotsField,
      ],
    });

    /* ---------------- 关于 ---------------- */
    const app = ctx.state.app || {};
    const aboutCard = sectionCard({
      title: '关于',
      icon: 'info',
      children: [
        row({ icon: 'bubble', title: '笔录工具箱', desc: '聊天记录导出工具', extra: h('span', { class: 'row-value' }, 'v' + (app.version || '1.0.0')) }),
        row({
          icon: 'link',
          title: '项目主页',
          desc: '查看使用说明与版本更新。',
          extra: h('span', { class: 'row-value' }, '打开'),
          onClick: async () => {
            try { await api('open_url', { url: 'https://github.com/yueqiwu/wechat-shots' }); } catch (error) { toast('打开链接失败', 'error'); }
          },
        }),
        row({
          icon: 'folder',
          title: '数据目录',
          desc: app.dataDir || '',
          extra: h('span', { class: 'row-value' }, '打开'),
          onClick: async () => {
            try { await api('open_folder', { path: app.dataDir }); } catch (error) { toast('打开失败', 'error'); }
          },
        }),
        row({
          icon: 'file',
          title: '运行日志',
          desc: '遇到问题时可以把日志发给开发者排查。',
          extra: h('span', { class: 'row-value' }, '打开'),
          onClick: async () => {
            try { await api('open_folder', { path: (app.dataDir || '') + '\\logs' }); } catch (error) { toast('日志文件夹不存在', 'error'); }
          },
        }),
        h('p', { class: 'settings-footnote' }, '本工具全程在本机处理，聊天记录与截图不会上传到网络。'),
      ],
    });

    const page = h('div', { class: 'page settings-page' },
      h('div', { class: 'page-head' },
        h('div', { class: 'page-heading' },
          h('h1', { class: 'page-title' }, '设置'),
          h('p', { class: 'page-sub' }, '调整连接方式、导出位置和外观偏好，修改会立即保存。'))),
      h('div', { class: 'settings-stack' },
        adbCard,
        outputCard,
        appearanceCard,
        exportCard,
        aboutCard));

    root.replaceChildren(page);
  }

  render();
  ctx.onCleanup(() => { /* 无异步任务 */ });
}
