/**
 * screens/home.js —— 首页：三张功能卡 + USB 调试引导入口。
 */
import { h, icon, dialog, button, stepList, chips } from '../ui.js';

const USB_STEPS = [
  { title: '打开手机「设置」', desc: '在手机桌面找到齿轮图标的「设置」应用。' },
  { title: '进入「关于手机」', desc: '部分品牌叫「关于本机」，一般在设置列表最下方。' },
  { title: '连点 7 次「版本号」', desc: '连续快速点击「版本号」，直到提示「您已处于开发者模式」。' },
  { title: '进入「开发者选项」', desc: '返回设置，在「系统」或设置列表底部找到「开发者选项」。' },
  { title: '打开「USB 调试」', desc: '找到「USB 调试」开关并打开（部分手机需同时打开「USB 调试（安全设置）」）。' },
  { title: '用数据线连接电脑', desc: '插上数据线，在手机通知栏把 USB 用途选为「传输文件」。' },
  { title: '在手机上点「允许」', desc: '弹出「允许 USB 调试吗？」时，勾选「始终允许」再点「允许」。' },
];

/** 供方案 A 页面复用的 USB 调试引导弹窗。 */
export function openUsbGuide() {
  dialog({
    title: '如何开启 USB 调试',
    wide: true,
    body: h('div', { class: 'usb-guide-dialog' },
      h('p', { class: 'dialog-message' }, '只有打开「USB 调试」，电脑才能读取手机屏幕。整个设置只需要做一次，之后插上数据线即可。'),
      stepList(USB_STEPS),
      h('div', { class: 'tip-box' },
        icon('info', 16),
        h('span', {}, '小米、OPPO、vivo 等品牌可能还需要在「开发者选项」中额外打开「USB 安装」或「USB 调试（安全设置）」。如果手机一直不弹窗，请换一根支持数据传输的数据线，或更换电脑的 USB 接口。'))),
    actions: [{ label: '我知道了', variant: 'primary' }],
  });
}

function schemeCard({ tone, iconName, title, desc, tags, onClick, badge, testid }) {
  return h('button', { class: 'scheme-card scheme-' + tone, type: 'button', onclick: onClick, 'data-testid': testid || null },
    h('span', { class: 'scheme-glow', 'aria-hidden': 'true' }),
    h('span', { class: 'scheme-top' },
      h('span', { class: 'scheme-icon' }, icon(iconName, 26)),
      badge ? h('span', { class: 'scheme-badge' }, badge) : null),
    h('span', { class: 'scheme-title' }, title),
    h('span', { class: 'scheme-desc' }, desc),
    h('span', { class: 'scheme-tags' }, ...tags.map((tag) => h('span', { class: 'scheme-tag' }, tag))),
    h('span', { class: 'scheme-go' }, icon('arrowRight', 18)));
}

export function mount(root, ctx) {
  const screen = h('div', { class: 'home' },
    h('section', { class: 'hero' },
      h('div', { class: 'hero-eyebrow' }, icon('shield', 14), '全程本机处理 · 图片不会上传'),
      h('h1', { class: 'hero-title' }, '聊天记录与交易明细'),
      h('p', { class: 'hero-sub' }, '把微信聊天记录导出成清晰、连续的长截图；也能把微信支付 / 支付宝交易明细整理成可直接提交的 Word 文档。')),

    h('section', { class: 'scheme-grid', 'aria-label': '选择导出方式' },
      schemeCard({
        tone: 'blue',
        iconName: 'smartphone',
        title: '手机截图导出',
        desc: '连接手机自动滚动截图，相邻截图保留少量重叠（约 10%～25%，自动调整），输出时自动去重。',
        tags: ['USB 调试', '无线调试', '自动拼接'],
        badge: '推荐',
        testid: 'home-card-adb',
        onClick: () => ctx.navigate('adb'),
      }),
      schemeCard({
        tone: 'orange',
        iconName: 'film',
        title: '录屏转截图',
        desc: '导入手机录屏视频，自动抽帧拼接成分页截图，适合不方便连接电脑的手机。',
        tags: ['自动抽帧', '匀速滚动', '分页输出'],
        testid: 'home-card-video',
        onClick: () => ctx.navigate('video'),
      }),
      schemeCard({
        tone: 'violet',
        iconName: 'receipt',
        title: '交易明细格式化',
        desc: '导入微信支付 / 支付宝交易明细 PDF，预览勾选后按模板导出收入、支出两个 Word 文档。',
        tags: ['PDF 导入', '预览勾选', '导出 Word'],
        badge: '新功能',
        testid: 'home-card-bill',
        onClick: () => ctx.navigate('bill'),
      })),

    h('button', { class: 'guide-entry card', type: 'button', 'data-testid': 'home-usb-guide', onclick: () => openUsbGuide() },
      h('span', { class: 'guide-icon' }, icon('usb', 22)),
      h('span', { class: 'guide-text' },
        h('span', { class: 'guide-title' }, '还不会开启 USB 调试？'),
        h('span', { class: 'guide-desc' }, '跟着 7 个步骤操作，大约 1 分钟就能连上手机。')),
      h('span', { class: 'guide-chevron' }, icon('chevronRight', 18))),

    h('section', { class: 'trust-row', 'aria-label': '产品特点' },
      h('div', { class: 'trust-item' }, icon('shield', 16), h('span', {}, '本机处理，不联网')),
      h('div', { class: 'trust-item' }, icon('bolt', 16), h('span', {}, '低配电脑也能跑')),
      h('div', { class: 'trust-item' }, icon('pixel', 16), h('span', {}, '自动去重分页'))));

  root.appendChild(screen);
  return () => { /* 首页无异步任务 */ };
}
