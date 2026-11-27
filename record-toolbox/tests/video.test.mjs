// video.js 单元测试：analyzeVideo 编排 + renderVideoPages 分页接线
// （用假 provider/假 document，不依赖真实 <video>；真实 seek 需在 WebView 里手工验证）
// 运行：node --test tests\video.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import * as S from '../web/js/stitch.js';
import { analyzeVideo, renderVideoPages } from '../web/js/video.js';

const AW = 144;

function synthStrip(h, w = AW) {
  const g = new Uint8Array(w * h);
  let seed = 0x55667788;
  const rnd = () => {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    return (seed >>> 24) & 0xff;
  };
  for (let i = 0; i < g.length; i++) g[i] = rnd();
  return g;
}

/** 假录屏：t -> 第 floor(t/frameDur) 帧（内容按 step 向上滚动）。 */
function makeFakeVideo({ step = 80, viewH = 256, count = 6, width = 540, height = 960 } = {}) {
  const fps = 10;
  const frameDur = 1 / fps;
  const strip = synthStrip((count - 1) * step + viewH);
  const frames = [];
  for (let k = 0; k < count; k++) {
    frames.push({
      gray: strip.slice(k * step * AW, (k * step + viewH) * AW),
      width: AW,
      height: viewH,
    });
  }
  const provider = {
    kind: 'video',
    duration: count / fps,
    _opened: 0,
    _frameCalls: [],
    async open() {
      this._opened += 1;
      return { width, height, duration: this.duration, name: 'fake.mp4' };
    },
    async sample(times) {
      return times.map((t) => {
        const i = Math.max(0, Math.min(count - 1, Math.floor(t / frameDur)));
        const f = frames[i];
        return { timeUs: Math.round(t * 1e6), gray: f.gray, width: AW, height: viewH };
      });
    },
    frameWidth: () => width,
    frameHeight: () => height,
    async frameCanvas(timeUs, topPx, cropHpx) {
      this._frameCalls.push({ timeUs, topPx, cropHpx });
      return { width, height: cropHpx, naturalHeight: cropHpx, topPx };
    },
  };
  return { provider, strip, frames, step, viewH, width, height };
}

function installFakeDocument() {
  const prev = globalThis.document;
  let created = 0;
  globalThis.document = {
    createElement(tag) {
      if (tag !== 'canvas') throw new Error(`unexpected element: ${tag}`);
      created += 1;
      const canvas = {
        width: 0,
        height: 0,
        getContext() {
          return {
            fillStyle: '',
            fillRect() {},
            drawImage() {},
          };
        },
      };
      return canvas;
    },
  };
  return {
    created: () => created,
    restore() {
      if (prev === undefined) delete globalThis.document;
      else globalThis.document = prev;
    },
  };
}

test('analyzeVideo：打开 metadata、恢复位移、planMaster 主图高度正确', async () => {
  const fake = makeFakeVideo({ step: 80, viewH: 256, count: 6 });
  const analysis = await analyzeVideo(fake.provider, { refineRounds: 1, analysisWidth: AW });
  assert.equal(fake.provider._opened, 1, 'analyzeVideo 应打开一次 metadata');
  assert.equal(analysis.kind, 'video');
  for (let i = 0; i < fake.frames.length; i++) {
    assert.ok(
      Math.abs(analysis.track.pos[i] - i * fake.step) <= 1,
      `第 ${i} 帧位移 ${analysis.track.pos[i]}`,
    );
  }
  const plan = S.planMaster(analysis, 0, fake.height, 800);
  const scale = fake.height / fake.viewH; // 3.75
  const expected = ((fake.frames.length - 1) * fake.step + fake.viewH) * scale;
  assert.ok(Math.abs(plan.masterH - expected) <= 3, `masterH=${plan.masterH} 期望≈${expected}`);
  assert.ok(plan.pageCount >= 2);
});

test('renderVideoPages：逐页惰性渲染，页宽=原视频宽、页高来自 cuts', async () => {
  const fake = makeFakeVideo({ step: 80, viewH: 256, count: 6 });
  const analysis = await analyzeVideo(fake.provider, { refineRounds: 0, analysisWidth: AW });
  const plan = S.planMaster(analysis, 0, fake.height, 800);
  const doc = installFakeDocument();
  const pages = [];
  const progress = [];
  try {
    const n = await renderVideoPages(
      plan,
      fake.provider,
      { cropTopPx: 0, cropBotPx: fake.height, tileH: 800 },
      (page, i) => {
        assert.equal(page.index, i);
        assert.equal(page.y1, page.canvas.height + page.y0);
        pages.push(page);
      },
      (f) => progress.push(f),
    );
    assert.equal(n, plan.pageCount, '页数应等于 plan.pageCount');
    assert.equal(pages.length, plan.pageCount);
    assert.equal(doc.created(), plan.pageCount, '每页只建一张 canvas（惰性）');
    let sum = 0;
    for (let i = 0; i < pages.length; i++) {
      assert.equal(pages[i].y0, plan.cuts[i]);
      assert.equal(pages[i].y1, plan.cuts[i + 1]);
      assert.equal(pages[i].width, fake.width);
      assert.equal(pages[i].canvas.width, fake.width);
      assert.equal(pages[i].height, pages[i].y1 - pages[i].y0);
      sum += pages[i].canvas.height;
    }
    assert.equal(sum, plan.masterH);
    assert.equal(progress[progress.length - 1], 1);
    // 帧 canvas 按裁剪区请求（topPx=0, 高=裁剪区高）
    for (const c of fake.provider._frameCalls) {
      assert.equal(c.topPx, 0);
      assert.equal(c.cropHpx, fake.height);
    }
  } finally {
    doc.restore();
  }
});

test('renderVideoPages：参数缺失时抛中文错误而不是静默失败', async () => {
  await assert.rejects(() => renderVideoPages(null, {}, {}, null, null), S.StitchError);
  await assert.rejects(
    () => renderVideoPages({ ops: [], cuts: Int32Array.from([0, 1]), masterH: 1 }, {}, {}, null, null),
    S.StitchError,
  );
});
