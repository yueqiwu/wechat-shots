// adbcap.js 单元测试：固定 UI 栏检测（纯算法，无需 DOM）
// 运行：node --test tests\adbcap.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import * as S from '../web/js/stitch.js';
import { detectCropFromShots } from '../web/js/adbcap.js';

const W = 144;

function synthStrip(h, w = W) {
  const g = new Uint8Array(w * h);
  let seed = 0x1020304 + h;
  const rnd = () => {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    return (seed >>> 24) & 0xff;
  };
  for (let i = 0; i < g.length; i++) g[i] = rnd();
  return g;
}

/** 构造带固定顶栏/底栏的滚动帧序列（分析分辨率），返回 {frames, contentStripH, step}。 */
function makeBandedFrames({ topBar = 40, botBar = 32, viewH = 320, step = 223, count = 3, contentStep }) {
  const contentH = viewH - topBar - botBar;
  const s = contentStep || step || Math.round(contentH * 0.9);
  const contentStripH = (count - 1) * s + contentH;
  const strip = synthStrip(contentStripH, W);
  const frames = [];
  for (let k = 0; k < count; k++) {
    const gray = new Uint8Array(W * viewH);
    gray.fill(210, 0, topBar * W); // 顶部固定栏
    gray.set(strip.slice(k * s * W, (k * s + contentH) * W), topBar * W);
    gray.fill(240, (viewH - botBar) * W, viewH * W); // 底部固定栏
    frames.push({ gray, width: W, height: viewH });
  }
  return { frames, contentStripH, step: s, contentH, viewH, topBar, botBar };
}

test('detectCropFromShots：识别顶部状态栏 + 底部输入栏，并按原图像素换算', () => {
  const { frames, topBar, botBar, viewH } = makeBandedFrames({ count: 3 });
  const pxH = 2400;
  const items = frames.map((f) => ({ ...f, pxWidth: 1080, pxHeight: pxH }));
  const crop = detectCropFromShots(items);
  assert.ok(crop, '应检测到固定栏');
  const scale = pxH / viewH; // 7.5
  assert.ok(Math.abs(crop.topPx - topBar * scale) <= 2, `topPx=${crop.topPx}`);
  assert.ok(Math.abs(crop.botPx - (viewH - botBar) * scale) <= 2, `botPx=${crop.botPx}`);
  assert.ok(crop.botPx > crop.topPx);
  assert.equal(crop.scaleY, scale);
});

test('detectCropFromShots：只有顶部固定栏时 botPx = 帧高', () => {
  const { frames, viewH, topBar } = makeBandedFrames({ count: 2, botBar: 0, step: 240, topBar: 48 });
  const items = frames.map((f) => ({ ...f, pxHeight: viewH, pxWidth: W }));
  const crop = detectCropFromShots(items);
  assert.ok(crop);
  assert.ok(Math.abs(crop.topPx - topBar) <= 2);
  assert.equal(crop.botPx, viewH);
});

test('detectCropFromShots：全屏滚动内容（无固定栏）返回 null', () => {
  const step = 100;
  const viewH = 200;
  const strip = synthStrip((2 - 1) * step + viewH, W);
  const frames = [0, 1].map((k) => ({
    gray: strip.slice(k * step * W, (k * step + viewH) * W),
    width: W,
    height: viewH,
  }));
  assert.equal(detectCropFromShots(frames), null);
});

test('detectCropFromShots：重复截图（内容相同）返回 null', () => {
  const { frames } = makeBandedFrames({ count: 3 });
  const same = frames.map((f) => ({ ...f, gray: frames[0].gray.slice() }));
  assert.equal(detectCropFromShots(same), null);
  assert.equal(detectCropFromShots([frames[0]]), null);
});

test('detectCropFromShots：支持裸 Uint8Array + opts 尺寸', () => {
  const { frames, topBar, botBar, viewH } = makeBandedFrames({ count: 2, step: 240 });
  const crop = detectCropFromShots(
    frames.map((f) => f.gray),
    { width: W, height: viewH },
  );
  assert.ok(crop);
  assert.ok(Math.abs(crop.topPx - topBar) <= 1);
  assert.ok(Math.abs(crop.botPx - (viewH - botBar)) <= 1);
});

test('检测到的裁剪框 + planMaster：主图高度≈内容长图（按原图像素缩放）', async () => {
  const band = makeBandedFrames({ count: 6, viewH: 320, step: 223 });
  const pxW = 1080;
  const pxH = 2400;
  // 模拟 analyze 的 static 分支（provider 直接吐分析灰度）
  const provider = {
    kind: 'static',
    frameCount: band.frames.length,
    async sample(items) {
      return items.map((i) => ({ ...band.frames[i], timeUs: i }));
    },
    frameWidth: () => pxW,
    frameHeight: () => pxH,
  };
  const analysis = await S.analyze(provider, { refineRounds: 0, analysisWidth: W });
  assert.equal(analysis.track.bad.length, 0);
  for (let i = 0; i < band.frames.length; i++) {
    assert.ok(Math.abs(analysis.track.pos[i] - i * band.step) <= 2, `第 ${i} 帧位移`);
  }
  const crop = detectCropFromShots(
    band.frames.map((f) => ({ ...f, pxWidth: pxW, pxHeight: pxH })),
  );
  assert.ok(crop);
  const plan = S.planMaster(analysis, crop.topPx, crop.botPx, 2000);
  const scale = pxH / band.viewH; // 7.5
  const expected = band.contentStripH * scale;
  assert.ok(Math.abs(plan.masterH - expected) <= 4, `masterH=${plan.masterH} 期望≈${expected}`);
  assert.equal(plan.topPx, crop.topPx);
  assert.equal(plan.botPx, crop.botPx);
  assert.ok(Math.abs(plan.stats.medianOverlapRatio - 0.1) < 0.05, `实测重叠率=${plan.stats.medianOverlapRatio}`);
  // 分页覆盖整个主图、页间无重叠
  assert.equal(plan.cuts[0], 0);
  assert.equal(plan.cuts[plan.cuts.length - 1], plan.masterH);
  for (let i = 1; i < plan.cuts.length; i++) assert.ok(plan.cuts[i] > plan.cuts[i - 1]);
});
