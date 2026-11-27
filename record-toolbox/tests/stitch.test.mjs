// 拼接引擎单元测试（Node 24 内置 test runner）
// 运行：node --test tests\stitch.test.mjs
//
// 用「逐像素唯一随机纹理」造合成滚动长图，再按指定重叠率切片成帧序列，
// 验证：位移恢复、主图高度、分页不重叠、切点不劈行、固定 UI 栏裁剪检测等。

import test from 'node:test';
import assert from 'node:assert/strict';
import * as S from '../web/js/stitch.js';

const W = 144;

/** 合成长条灰度图：每行都是独立随机纹理（同一条长图内行与行不同）。 */
function synthStrip(h, w = W) {
  const g = new Uint8Array(w * h);
  let seed = 0x2468ace1;
  const rnd = () => {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    return (seed >>> 24) & 0xff;
  };
  for (let i = 0; i < g.length; i++) g[i] = rnd();
  return g;
}

/** 从长图上按 step 逐帧切出 viewH 高的窗口（模拟连续滚动截图）。 */
function sliceFrames(strip, stripW, viewH, step, count, extra = {}) {
  const frames = [];
  for (let k = 0; k < count; k++) {
    const off = k * step;
    const gray = strip.slice(off * stripW, (off + viewH) * stripW);
    frames.push({ gray, width: stripW, height: viewH, ...extra });
  }
  return frames;
}

/** 构造 provider（帧数组已是分析灰度，避免依赖 DOM）。 */
function makeProvider(frames, { width = W, height, kind = 'static', sampleHook } = {}) {
  const fh = height || (frames[0] && frames[0].height) || 1;
  return {
    kind,
    frameCount: frames.length,
    duration: kind === 'video' ? frames.length : 0,
    async sample(items) {
      const out = items.map((it) => {
        const i = kind === 'static' ? it : Math.min(frames.length - 1, Math.max(0, it));
        const f = frames[i];
        return { timeUs: kind === 'static' ? i : Math.round(items[0]) /* unused */, gray: f.gray, width: f.width, height: f.height };
      });
      if (sampleHook) sampleHook(items, out);
      return out;
    },
    frameWidth: () => width,
    frameHeight: () => fh,
  };
}

// ---------------------------------------------------------------------------
test('基础灰度操作：RGBA→灰度、缩放、裁剪、行签名', () => {
  const rgba = new Uint8ClampedArray([255, 255, 255, 255, 0, 0, 0, 255, 128, 128, 128, 255, 0, 128, 255, 255]);
  const gray = S.grayFromRGBA(rgba, 2, 2);
  assert.equal(gray.length, 4);
  assert.equal(gray[0], 255); // 白
  assert.equal(gray[1], 0); // 黑
  assert.equal(gray[2], 128); // 中灰
  assert.ok(gray[3] > 60 && gray[3] < 160);

  const strip = synthStrip(64, 32);
  const ds = S.downscaleGray(strip, 32, 64, 16);
  assert.equal(ds.width, 16);
  assert.equal(ds.height, 32);
  assert.ok(ds.gray instanceof Uint8Array);

  const crop = S.cropGray(strip, 32, 10, 40);
  assert.equal(crop.width, 32);
  assert.equal(crop.height, 30);
  assert.deepEqual(Array.from(crop.gray), Array.from(strip.slice(10 * 32, 40 * 32)));

  const sig = S.rowSig(strip, 32, 64, S.BINS);
  assert.equal(sig.length, 64 * S.BINS);
  const act = S.sigActivity(sig, 64, S.BINS);
  assert.equal(act.length, 64);
  assert.ok(act.some((v) => v > 0));
});

test('scanShift/alignAt：已知位移可恢复，错位时 ncc 低', () => {
  const viewH = 160;
  const step = 40;
  const strip = synthStrip(viewH + step + 4);
  const sigA = S.rowSig(strip.slice(0, viewH * W), W, viewH, S.BINS);
  const sigB = S.rowSig(strip.slice(step * W, (step + viewH) * W), W, viewH, S.BINS);

  // 帧 B 内容向上滚了 step 行：sigA[y] 对应 sigB[y-step]
  const r = S.alignAt(sigA, sigB, viewH, S.BINS, -step, 16);
  assert.ok(r);
  assert.ok(r.ncc > 0.99, `对齐正确位移时 ncc=${r.ncc}`);
  assert.ok(r.rows >= 16);

  const wrong = S.alignAt(sigA, sigB, viewH, S.BINS, -step + 7, 16);
  assert.ok(wrong == null || wrong.ncc < 0.5);

  const found = S.scanShift(sigA, sigB, viewH, S.BINS, -80, 0, 16);
  assert.ok(Math.abs(found.shift - -step) <= 1, `scanShift 恢复位移 ${found.shift}`);
  assert.ok(found.ncc > 0.99);

  // 重叠行数不足 -> null
  assert.equal(S.alignAt(sigA, sigB, viewH, S.BINS, -viewH + 4, 8), null);
});

test('trackOffsets：50%、10%、5% 重叠下位移恢复误差 ≤1px', () => {
  const viewH = 160;
  for (const overlap of [0.5, 0.1, 0.05]) {
    const step = Math.round(viewH * (1 - overlap));
    const count = 6;
    const stripH = (count - 1) * step + viewH;
    const strip = synthStrip(stripH);
    const frames = sliceFrames(strip, W, viewH, step, count);
    const sharp = frames.map(() => 1);
    const sigs = frames.map((f) => ({
      timeUs: 0,
      sig: S.rowSig(f.gray, W, viewH, S.BINS),
      sharp: sharp[0],
    }));
    const track = S.trackOffsets(sigs, viewH, { minOverlap: 0.05 });
    assert.equal(track.bad.length, 0, `重叠 ${overlap} 出现坏帧: ${JSON.stringify(track.bad[0] || null)}`);
    for (let i = 0; i < count; i++) {
      assert.ok(
        Math.abs(track.pos[i] - i * step) <= 1,
        `重叠 ${overlap} 第 ${i} 帧位置 ${track.pos[i]} 期望 ${i * step}`,
      );
    }
  }
});

test('trackOffsets：重叠不足时标记 jump，不会静默错位', () => {
  const viewH = 160;
  const step = viewH - 2; // 只剩 2 行重叠（< minOverlap 行数）
  const count = 4;
  const strip = synthStrip((count - 1) * step + viewH);
  const frames = sliceFrames(strip, W, viewH, step, count);
  const sigs = frames.map((f) => ({ timeUs: 0, sig: S.rowSig(f.gray, W, viewH, S.BINS), sharp: 1 }));
  const track = S.trackOffsets(sigs, viewH, { minOverlap: 0.05 });
  assert.ok(track.bad.length >= count - 1);
  // 相邻帧重叠不足时会以 jump/nocc/match 之一被标记（不允许静默当成正常匹配）
  assert.ok(track.bad.every((b) => ['jump', 'nocc', 'match'].includes(b.reason)));
  // 真实位移 158 > 搜索上限（约 154），位置不能被盲目累加
  assert.ok(track.pos[track.pos.length - 1] < step * (count - 1));
});

test('buildRowMap/mapToOps：整行映射可精确还原长图且无空洞', () => {
  const viewH = 160;
  const step = 80;
  const count = 5;
  const strip = synthStrip((count - 1) * step + viewH);
  const pos = Float64Array.from([0, 80, 160, 240, 320]);
  const sharp = Float64Array.from([1, 5, 2, 3, 4]); // 第 1 帧最清晰，先覆盖

  const rm = S.buildRowMap(sharp, pos, 0, viewH);
  assert.equal(rm.height, (count - 1) * step + viewH);
  assert.equal(rm.missing, 0);
  assert.ok(rm.map.every((f) => f >= 0));

  const ops = S.mapToOps(rm, pos, 0, rm.posMin);
  assert.equal(ops[0].y0, 0);
  assert.equal(ops[ops.length - 1].y1, rm.height);
  let sum = 0;
  for (let i = 0; i < ops.length; i++) {
    if (i > 0) assert.equal(ops[i].y0, ops[i - 1].y1, 'ops 必须首尾相接');
    sum += ops[i].y1 - ops[i].y0;
    assert.ok(ops[i].src >= 0 && ops[i].src < viewH);
  }
  assert.equal(sum, rm.height);

  // 精确还原：主图第 y 行 = 长图第 y 行（pos 为整数时无取整误差）
  for (let y = 0; y < rm.height; y++) {
    const op = ops.find((o) => y >= o.y0 && y < o.y1);
    const srcRow = op.src + (y - op.y0);
    assert.deepEqual(
      Array.from(strip.slice(y * W, (y + 1) * W)),
      Array.from(strip.slice((pos[op.frame] + srcRow) * W, (pos[op.frame] + srcRow + 1) * W)),
      `第 ${y} 行来源帧/行号错误`,
    );
  }
});

test('analyze+planMaster：50% 重叠长图，主图高度≈原长、分页无重叠', async () => {
  const viewH = 160;
  const step = 80; // 50% 重叠
  const count = 6;
  const stripH = (count - 1) * step + viewH;
  const strip = synthStrip(stripH);
  const frames = sliceFrames(strip, W, viewH, step, count);
  const provider = makeProvider(frames, { width: W, height: viewH });

  const analysis = await S.analyze(provider, { refineRounds: 0, analysisWidth: W });
  assert.equal(analysis.frames.length, count);
  for (let i = 0; i < count; i++) {
    assert.ok(Math.abs(analysis.track.pos[i] - i * step) <= 1, `第 ${i} 帧位移误差过大`);
  }
  assert.equal(analysis.band.top, 0);
  assert.equal(analysis.band.bot, viewH);

  const plan = S.planMaster(analysis, 0, viewH, 200);
  assert.ok(Math.abs(plan.masterH - stripH) <= 2, `masterH=${plan.masterH} 原长=${stripH}`);
  assert.equal(plan.cropHpx, viewH);
  assert.equal(plan.pageCount, plan.cuts.length - 1);
  assert.equal(plan.cuts[0], 0);
  assert.equal(plan.cuts[plan.cuts.length - 1], plan.masterH);

  // 页间无重叠、无空隙：cuts 严格递增且覆盖整个主图
  for (let i = 1; i < plan.cuts.length; i++) {
    assert.ok(plan.cuts[i] > plan.cuts[i - 1], 'cut 必须严格递增');
  }
  let covered = 0;
  for (let i = 0; i < plan.pageCount; i++) covered += plan.cuts[i + 1] - plan.cuts[i];
  assert.equal(covered, plan.masterH);

  // ops 覆盖完整、首尾相接
  let sum = 0;
  for (let i = 0; i < plan.ops.length; i++) {
    if (i > 0) assert.equal(plan.ops[i].y0, plan.ops[i - 1].y1);
    sum += plan.ops[i].y1 - plan.ops[i].y0;
  }
  assert.equal(sum, plan.masterH);

  // 实测重叠率 ≈ 50%
  assert.ok(Math.abs(plan.stats.medianOverlapRatio - 0.5) < 0.03, `实测重叠率 ${plan.stats.medianOverlapRatio}`);
});

test('analyze+planMaster：产品要求的 10% 重叠（0.9 屏位移）稳定', async () => {
  const viewH = 160;
  const step = 144; // 正好 10% 重叠
  const count = 6;
  const stripH = (count - 1) * step + viewH;
  const strip = synthStrip(stripH);
  const frames = sliceFrames(strip, W, viewH, step, count);
  const provider = makeProvider(frames, { width: W, height: viewH });

  const analysis = await S.analyze(provider, { refineRounds: 0, analysisWidth: W });
  for (let i = 0; i < count; i++) {
    assert.ok(Math.abs(analysis.track.pos[i] - i * step) <= 1, `10% 重叠第 ${i} 帧位移 ${analysis.track.pos[i]}`);
  }
  const plan = S.planMaster(analysis, 0, viewH, 160);
  assert.ok(Math.abs(plan.masterH - stripH) <= 2, `masterH=${plan.masterH} 原长=${stripH}`);
  assert.ok(Math.abs(plan.stats.medianOverlapRatio - 0.1) < 0.03, `实测重叠率 ${plan.stats.medianOverlapRatio}`);
  // 页高在 0.75~1.15 倍目标之间
  for (let i = 0; i < plan.pageCount - 1; i++) {
    const h = plan.cuts[i + 1] - plan.cuts[i];
    assert.ok(h >= 160 * 0.75 - 1 && h <= 160 * 1.15 + 1, `页高 ${h} 超范围`);
  }
});

test('analyze+planMaster：5% 重叠（下限）也能拼上', async () => {
  const viewH = 160;
  const step = 152; // 95% 位移，仅 5% 重叠
  const count = 6;
  const stripH = (count - 1) * step + viewH;
  const strip = synthStrip(stripH);
  const frames = sliceFrames(strip, W, viewH, step, count);
  const provider = makeProvider(frames, { width: W, height: viewH });

  const analysis = await S.analyze(provider, { refineRounds: 0, analysisWidth: W });
  assert.equal(analysis.track.bad.length, 0, `5% 重叠被判坏: ${JSON.stringify(analysis.track.bad[0] || null)}`);
  for (let i = 0; i < count; i++) {
    assert.ok(Math.abs(analysis.track.pos[i] - i * step) <= 1, `5% 重叠第 ${i} 帧位移 ${analysis.track.pos[i]}`);
  }
  const plan = S.planMaster(analysis, 0, viewH, 160);
  assert.ok(Math.abs(plan.masterH - stripH) <= 2, `masterH=${plan.masterH} 原长=${stripH}`);
});

test('planMaster：原始像素缩放（1080×2400 帧 / 144 宽分析）主图高度正确', async () => {
  const aw = 144;
  const ah = 320; // 1080x2400 按 144 宽缩放后的高度
  const viewH = ah;
  const step = Math.round(ah * 0.75); // 25% 重叠
  const count = 5;
  const stripH = (count - 1) * step + viewH;
  const strip = synthStrip(stripH, aw);
  const frames = sliceFrames(strip, aw, viewH, step, count);
  const provider = makeProvider(frames, { width: 1080, height: 2400 });
  const analysis = await S.analyze(provider, { refineRounds: 0, analysisWidth: aw });
  const plan = S.planMaster(analysis, 0, 2400, 2000);
  const scale = 2400 / ah;
  assert.ok(Math.abs(plan.masterH - stripH * scale) <= 3, `masterH=${plan.masterH} 期望≈${stripH * scale}`);
  assert.equal(plan.topPx, 0);
  assert.equal(plan.cropHpx, 2400);
  assert.ok(plan.pageCount >= 1);
  for (let i = 0; i < plan.pageCount; i++) {
    assert.equal(plan.cuts[i + 1] - plan.cuts[i] > 0, true);
  }
});

test('cutPoints：切在消息之间的空行、页高被夹在 0.75~1.15 倍目标内', () => {
  const total = 1000;
  const tileH = 200;
  const act = new Float32Array(total).fill(1);
  const blankBands = [];
  for (let k = 1; k < 5; k++) {
    const start = k * 200 - 40;
    const end = start + 40;
    blankBands.push([start, end]);
    for (let y = start; y < end; y++) act[y] = 0;
  }
  const cuts = S.cutPoints(act, total, tileH, 24);
  assert.ok(cuts instanceof Int32Array);
  assert.equal(cuts[0], 0);
  assert.equal(cuts[cuts.length - 1], total);
  assert.equal(cuts.length, 6); // 5 页
  for (let i = 1; i < cuts.length; i++) assert.ok(cuts[i] > cuts[i - 1], '严格递增');
  for (let i = 0; i < cuts.length - 1; i++) {
    const h = cuts[i + 1] - cuts[i];
    assert.ok(h >= Math.round(tileH * 0.75) && h <= Math.round(tileH * 1.15) + 1, `页高 ${h} 超范围`);
  }
  // 中间切点必须落在空行带上（不劈开消息）
  for (let i = 1; i < cuts.length - 1; i++) {
    assert.equal(act[cuts[i]], 0, `切点 ${cuts[i]} 落在内容行上`);
    assert.ok(blankBands.some(([a, b]) => cuts[i] >= a && cuts[i] < b));
  }
});

test('cutPoints：纯色/全空活跃度也能均分，非法参数不崩', () => {
  const flat = new Float32Array(600);
  const cuts = S.cutPoints(flat, 600, 200, 20);
  assert.equal(cuts[0], 0);
  assert.equal(cuts[cuts.length - 1], 600);
  for (let i = 1; i < cuts.length; i++) assert.ok(cuts[i] > cuts[i - 1]);

  const one = S.cutPoints(flat, 120, 200, 20);
  assert.deepEqual(Array.from(one), [0, 120]); // 不足一页时只有一页
});

test('renderPages：逐页回调、页边界来自 cuts、进度最终到 1', async () => {
  const cuts = Int32Array.from([0, 100, 250]);
  const plan = { cuts, masterH: 250, cropHpx: 100 };
  const seen = [];
  const renderer = {
    width: 144,
    height: 100,
    async pageImage(index, p, y0, y1) {
      assert.equal(typeof y0, 'number');
      assert.equal(typeof y1, 'number');
      return { width: 144, height: y1 - y0, index, fill: 'fake' };
    },
  };
  const progress = [];
  const n = await S.renderPages(
    plan,
    renderer,
    async (page, i) => {
      seen.push({ ...page, canvas: undefined });
      assert.equal(page.index, i);
      assert.equal(page.y1 - page.y0 > 0, true);
    },
    (f, msg) => progress.push(f),
  );
  assert.equal(n, 2);
  assert.equal(seen.length, 2);
  assert.deepEqual(seen.map((p) => [p.y0, p.y1]), [[0, 100], [100, 250]]);
  assert.equal(progress[progress.length - 1], 1);
  // 自定义 renderer.height 且没有 cuts 时也能按高度分页
  const n2 = await S.renderPages({ masterH: 250, cropHpx: 100 }, { ...renderer, pageImage: async (i, p) => ({ width: 144, height: 100 }) }, null, null);
  assert.equal(n2, 3);
});

test('analyze：固定 UI 栏（状态栏/输入栏）被 band 排除，位移仍准确', async () => {
  // 帧结构：[顶部静态栏 40 行][滚动内容 248 行][底部静态栏 32 行]，共 320 行
  const viewH = 320;
  const topBar = 40;
  const botBar = 32;
  const contentH = viewH - topBar - botBar;
  const top = new Uint8Array(W * topBar).fill(210); // 顶部固定栏（纯色，静态）
  const bot = new Uint8Array(W * botBar).fill(240);
  const step = Math.round(contentH * 0.9); // 内容区 10% 重叠
  const count = 6;
  const stripH = (count - 1) * step + contentH;
  const strip = synthStrip(stripH);

  const frames = [];
  for (let k = 0; k < count; k++) {
    const gray = new Uint8Array(W * viewH);
    gray.set(top, 0);
    gray.set(strip.slice(k * step * W, (k * step + contentH) * W), topBar * W);
    gray.set(bot, (viewH - botBar) * W);
    frames.push({ gray, width: W, height: viewH });
  }
  const provider = makeProvider(frames, { width: 1080, height: 2400 });
  const analysis = await S.analyze(provider, { refineRounds: 0, analysisWidth: W });
  assert.ok(analysis.band.top >= topBar - 4 && analysis.band.top <= topBar + 4, `band.top=${analysis.band.top}`);
  assert.ok(analysis.band.bot <= viewH - botBar + 4 && analysis.band.bot >= viewH - botBar - 4, `band.bot=${analysis.band.bot}`);
  for (let i = 0; i < count; i++) {
    assert.ok(Math.abs(analysis.track.pos[i] - i * step) <= 2, `第 ${i} 帧位移 ${analysis.track.pos[i]} 期望 ${i * step}`);
  }
  // 只裁内容区：主图高度 = 内容长图（按 1080/144 放大）
  const plan = S.planMaster(analysis, (2400 * topBar) / viewH, (2400 * (viewH - botBar)) / viewH, 2000);
  const scale = 2400 / viewH;
  assert.ok(Math.abs(plan.masterH - stripH * scale) <= 3, `masterH=${plan.masterH} 期望≈${stripH * scale}`);
  assert.ok(Math.abs(plan.stats.medianOverlapRatio - 0.1) < 0.04);
});

test('analyze：重复截图（内容不动）不产生假位移', async () => {
  const viewH = 160;
  const strip = synthStrip(viewH);
  const frames = [0, 1, 2].map(() => ({ gray: strip.slice(), width: W, height: viewH }));
  const provider = makeProvider(frames, { width: W, height: viewH });
  const analysis = await S.analyze(provider, { refineRounds: 0, analysisWidth: W });
  for (let i = 1; i < 3; i++) {
    assert.equal(analysis.track.step[i], 0);
    assert.equal(analysis.track.pos[i], 0);
  }
  assert.equal(analysis.stats.medianOverlapRatio, null); // 没有有效位移
  const plan = S.planMaster(analysis, 0, viewH, viewH);
  assert.equal(plan.masterH, viewH);
  assert.equal(plan.pageCount, 1);
});

// ---------------------------------------------------------------------------
// 全局单调性兜底（validateGlobalMonotonic / planMaster 拦截错位长图）
// ---------------------------------------------------------------------------

const GLOBAL_MONO_MSG =
  '截图/录屏内容存在重复片段，无法稳定对齐。请放慢滚动速度或重新录制后重试。';

/**
 * 构造一份“track 已算错”的分析结果：frames 用真实滚动切片（签名有效），
 * pos/ncc 由测试指定，用来模拟重复内容导致的错误位移。
 */
function makeTrackAnalysis({ viewH = 160, stripH, offsets, pos, ncc, kind = 'static', frameHeight = viewH }) {
  const strip = synthStrip(stripH);
  const frames = offsets.map((off, i) => {
    const gray = strip.slice(off * W, (off + viewH) * W);
    return { timeUs: i * 100000, sig: S.rowSig(gray, W, viewH, S.BINS), sharp: 1 };
  });
  const n = pos.length;
  const step = new Float64Array(n);
  for (let i = 1; i < n; i++) step[i] = pos[i] - pos[i - 1];
  const nccArr = new Float64Array(n);
  for (let i = 0; i < n; i++) nccArr[i] = ncc && ncc[i] != null ? ncc[i] : 0.99;
  return {
    aw: W,
    ah: viewH,
    band: { top: 0, bot: viewH, h: viewH },
    frames,
    track: {
      pos: Float64Array.from(pos),
      step,
      ncc: nccArr,
      cost: new Float64Array(n),
      agree: new Float64Array(n),
      bad: [],
      maxShift: Math.round(viewH * 0.9),
      tol: 4,
      medCost: 0,
    },
    frameWidth: W,
    frameHeight,
    missingCount: 0,
    kind,
    stats: {},
  };
}

test('全局单调性：重复气泡导致某帧位移折返时 planMaster 抛 StitchError', () => {
  // 模拟 Android 事故：真位移 0/144/288/432/576，但第 4 帧在重复气泡上
  // 匹配到“上一个气泡”，pos 从 288 回退到 160（NCC 仍 0.97，骗过局部质量门）。
  const analysis = makeTrackAnalysis({
    stripH: 576 + 160,
    offsets: [0, 144, 288, 432, 576],
    pos: [0, 144, 288, 160, 304],
    ncc: [0, 0.98, 0.98, 0.97, 0.97],
  });
  assert.throws(
    () => S.planMaster(analysis, 0, 160, 200),
    (err) => err.name === 'StitchError' && err.message === GLOBAL_MONO_MSG,
    '位移折返必须抛 StitchError 阻止输出错位长图',
  );
});

test('全局单调性：相邻位移超过一屏（跳帧/漏拍）时抛 StitchError', () => {
  // 158 行 > 0.98*160=156.8：中间内容没拍到，拼接会缺内容
  const analysis = makeTrackAnalysis({
    stripH: 318 + 160,
    offsets: [0, 80, 238, 318],
    pos: [0, 80, 238, 318],
    ncc: [0, 0.95, 0.9, 0.9],
  });
  assert.throws(() => S.planMaster(analysis, 0, 160, 200), /无法稳定对齐/);
});

test('全局单调性：静止/重复帧（同位置同内容）不误报，正常出图', () => {
  // 第 2、3 张截图完全重复（滚到底/手抖），位置不变但 ncc≈1，属于安全重复
  const analysis = makeTrackAnalysis({
    stripH: 432 + 160,
    offsets: [0, 144, 144, 288, 432],
    pos: [0, 144, 144, 288, 432],
    ncc: [0, 0.99, 1, 0.99, 0.99],
  });
  const plan = S.planMaster(analysis, 0, 160, 200);
  assert.equal(plan.masterH, 160 + 432); // 一屏 + 总位移
  let covered = 0;
  for (let i = 0; i < plan.ops.length; i++) {
    if (i > 0) assert.equal(plan.ops[i].y0, plan.ops[i - 1].y1);
    covered += plan.ops[i].y1 - plan.ops[i].y0;
  }
  assert.equal(covered, plan.masterH);
});

test('全局单调性：视频稠密采样的小步长（慢速滚动）不误报', () => {
  // 10fps 采样、慢速滚动时相邻帧只滚 3 行（<0.05*bandH）：只有静态截图才要求最小步长
  const analysis = makeTrackAnalysis({
    viewH: 200,
    stripH: 12 + 200,
    offsets: [0, 3, 6, 9, 12],
    pos: [0, 3, 6, 9, 12],
    ncc: [0, 0.995, 0.995, 0.995, 0.995],
    kind: 'video',
  });
  const plan = S.planMaster(analysis, 0, 200, 200);
  assert.equal(plan.masterH, 200 + 12);
});

test('全局单调性：静态截图位移过小（重叠远超产品范围）时抛 StitchError', () => {
  const analysis = makeTrackAnalysis({
    stripH: 12 + 160,
    offsets: [0, 4, 8, 12],
    pos: [0, 4, 8, 12],
    ncc: [0, 0.999, 0.999, 0.999],
  });
  assert.throws(() => S.planMaster(analysis, 0, 160, 200), /无法稳定对齐/);
});

test('全局单调性：validateGlobalMonotonic 直接拦截 ops 帧序折返与覆盖重叠', () => {
  const analysis = {
    kind: 'static',
    ah: 160,
    band: { top: 0, bot: 160, h: 160 },
    track: { pos: Float64Array.from([0, 80, 160]), ncc: Float64Array.from([1, 0.99, 0.99]) },
  };
  // 帧序折返：帧 2 之后又出现帧 0
  assert.throws(
    () =>
      S.validateGlobalMonotonic(analysis, {
        bandH: 160,
        scale: 1,
        ops: [
          { y0: 0, y1: 100, frame: 2, src: 0 },
          { y0: 100, y1: 200, frame: 0, src: 0 },
        ],
      }),
    /无法稳定对齐/,
  );
  // 覆盖区间重叠：后一个 op 与前一个重叠超过 2px 容差
  assert.throws(
    () =>
      S.validateGlobalMonotonic(analysis, {
        bandH: 160,
        scale: 1,
        ops: [
          { y0: 0, y1: 100, frame: 0, src: 0 },
          { y0: 50, y1: 100, frame: 0, src: 0 },
        ],
      }),
    /无法稳定对齐/,
  );
});

// ---------------------------------------------------------------------------
// 真人录屏的实际形态：朝「更早消息」滚（内容向下移动）、中间回滑
// ---------------------------------------------------------------------------

test('normalizeVideoTimeline：内容向下滚（pos 递减）自动反转时间轴并变正', () => {
  // 用户录屏常见：从最新消息开始，往更早的消息滚，画面内容整体向下移动。
  const analysis = makeTrackAnalysis({
    stripH: 432 + 160,
    offsets: [432, 288, 144, 0],
    pos: [432, 288, 144, 0],
    ncc: [0, 0.99, 0.99, 0.99],
    kind: 'video',
  });
  const norm = S.normalizeVideoTimeline(analysis.frames, analysis.track);
  assert.equal(norm.reversed, true);
  assert.deepEqual(Array.from(norm.track.pos), [0, 144, 288, 432]);
  assert.ok(norm.track.step[1] > 0 && norm.track.step[3] > 0);
  // 帧序列物理倒序（渲染按 timeUs 取帧，顺序不影响画面）
  for (let i = 0; i < norm.frames.length; i++) {
    assert.equal(norm.frames[i].timeUs, analysis.frames[analysis.frames.length - 1 - i].timeUs);
  }
  const reversed = { ...analysis, frames: norm.frames, track: norm.track };
  const plan = S.planMaster(reversed, 0, 160, 200);
  assert.equal(plan.masterH, 160 + 432);
  assert.ok(plan.pageCount >= 2);
});

test('trimMonotoneTimeline：中间回滑的一段被裁掉，剩下的仍能拼接', () => {
  const pos = [];
  for (let i = 0; i <= 9; i++) pos.push(i * 100); // 0..900 正常
  pos.push(400); // 第 10 帧回滑（step -500）
  for (let i = 11; i <= 20; i++) pos.push(400 + (i - 10) * 100); // 继续正常
  const analysis = makeTrackAnalysis({
    stripH: 1700 + 160,
    offsets: pos.map((p) => Math.max(0, 900 - p)), // 随便给，签名有效即可
    pos,
    ncc: pos.map(() => 0.99),
    kind: 'video',
  });
  const trimmed = S.trimMonotoneTimeline(analysis.frames, analysis.track, 160);
  assert.ok(trimmed.dropped > 0, '应裁掉不连续片段');
  assert.equal(trimmed.frames.length + trimmed.dropped, analysis.frames.length);
  for (let i = 2; i < trimmed.track.step.length; i++) {
    assert.ok(trimmed.track.step[i] > 0, '裁剪后位移应全为正');
  }
  const plan = S.planMaster(
    { ...analysis, frames: trimmed.frames, track: trimmed.track },
    0,
    160,
    200,
  );
  assert.ok(plan.masterH > 160);
});

test('repairVideoTrack：少量失配用邻近步长补齐后仍可出图', () => {
  const pos = [0, 40, 80, 120, -20, 40, 80, 120, 160, 200];
  const analysis = makeTrackAnalysis({
    stripH: 1700 + 160,
    offsets: [0, 200, 400, 600, 800, 900, 960, 1020, 1080, 1140],
    pos,
    ncc: pos.map((_, i) => (i === 4 || i === 5 ? 0.42 : 0.99)),
    kind: 'video',
  });
  const rep = S.repairVideoTrack(analysis.frames, analysis.track, 160, { stationaryRatio: 0.06 });
  assert.ok(rep.imputed >= 1, '应修复失配步长');
  assert.ok(rep.usableRatio < 1);
  assert.equal(rep.track.bad.length, 0, '补齐后不应再有失配');
  for (let i = 1; i < rep.track.pos.length; i++) {
    assert.ok(rep.track.pos[i] > rep.track.pos[i - 1], '补齐后主图坐标应严格递增');
  }
  const plan = S.planMaster({ ...analysis, frames: rep.frames, track: rep.track }, 0, 160, 200);
  assert.ok(plan.masterH > 160);
  assert.ok(plan.pageCount >= 1);
});

test('trackAgainstMaster：逐帧以主图为参照对齐，恢复真实位移且不产生累计漂移', () => {
  const viewH = 160;
  const step = 46;
  const count = 40;
  const strip = synthStrip(viewH + step * (count - 1) + 8);
  const frames = [];
  for (let k = 0; k < count; k++) {
    const off = k * step;
    const gray = strip.slice(off * W, (off + viewH) * W);
    frames.push({ timeUs: k * 100000, sig: S.rowSig(gray, W, viewH, S.BINS), sharp: 1 });
  }
  const res = S.trackAgainstMaster(frames, viewH, S.BINS, { minOverlap: 0.25 });
  assert.ok(res, '应返回对齐结果');
  assert.equal(res.skipped, 0, '理想匀速滚动不应跳帧');
  assert.equal(res.frames.length, count);
  for (let i = 0; i < count; i++) {
    assert.equal(Math.round(res.track.pos[i]), i * step, `第 ${i} 帧位置应等于真实位移`);
  }
  for (let i = 1; i < count; i++) {
    assert.equal(Math.round(res.track.step[i]), step);
  }
});

test('analyze+planMaster：朝更早消息滚动的录屏（内容向下）也能出图', async () => {
  const viewH = 160;
  const frameCount = 30;
  const speed = 40; // 每帧向下移动 40 行 => 75% 重叠
  const startOff = (frameCount - 1) * speed;
  const strip = synthStrip(startOff + viewH);
  const durationSec = frameCount / 10; // fps=10
  const provider = {
    kind: 'video',
    frameCount,
    duration: durationSec,
    async sample(items) {
      return items.map((tSec) => {
        const off = Math.max(0, Math.min(startOff, Math.round(startOff - tSec * speed * 10)));
        return { timeUs: Math.round(tSec * 1e6), gray: strip.slice(off * W, (off + viewH) * W), width: W, height: viewH };
      });
    },
    frameWidth: () => W,
    frameHeight: () => viewH,
  };
  const analysis = await S.analyze(provider, { fps: 10, refineRounds: 0, analysisWidth: W });
  assert.equal(analysis.kind, 'video');
  assert.equal(analysis.stats.directionReversed, 1, '内容向下滚动应标记为方向反转');
  const plan = S.planMaster(analysis, 0, viewH, viewH);
  assert.ok(plan.pageCount >= 2);
  assert.ok(plan.masterH >= viewH + (frameCount - 2) * speed * 0.9);
});

// ---------------------------------------------------------------- trackBySpeed（真实帧序列 + dt 先验）
function makeSigFrame(offset, bandH) {
  const sig = new Uint8Array(bandH * 8);
  for (let y = 0; y < bandH; y++) {
    const base = (offset + y) * 97 % 256;
    for (let b = 0; b < 8; b++) sig[y * 8 + b] = (base + b * 13) % 256;
  }
  return sig;
}
function makeMovingFrames({ count, bandH, gapsSec, speed, startOffset, direction }) {
  // direction=+1：帧顶对应的主图行号递增（画面内容向上滚）；-1：行号递减（内容向下滚）
  const frames = [];
  let t = 0;
  const at = (sec) => Math.round(startOffset + direction * speed * sec);
  frames.push({ timeUs: 0, sig: makeSigFrame(at(0), bandH), sharp: 1 });
  for (let i = 1; i < count; i++) {
    const dt = gapsSec[i % gapsSec.length];
    t += dt;
    frames.push({ timeUs: Math.round(t * 1e6), sig: makeSigFrame(at(t), bandH), sharp: 1 });
  }
  return frames;
}
test('trackBySpeed：内容向下滚动（录屏滚向更早消息）时反转时间轴并恢复位移', () => {
  const bandH = 120;
  const gaps = [0.1, 0.1667, 0.1333, 0.2, 0.0667];
  const speed = 320;
  const frames = makeMovingFrames({ count: 36, bandH, gapsSec: gaps, speed, startOffset: 900, direction: -1 });
  const traced = S.trackBySpeed(frames, bandH, 8, { costLimit: 1e9 });
  assert.equal(traced.directionReversed, true);
  assert.equal(traced.frames.length, frames.length);
  for (let j = 1; j < traced.steps.length; j++) {
    const origIndex = traced.steps.length - j;
    const dt = gaps[origIndex % gaps.length];
    const expected = speed * dt;
    assert.ok(Math.abs(traced.steps[j] - expected) <= 4, 'step ' + j + ' ' + traced.steps[j] + ' vs ' + expected);
  }
  for (let i = 1; i < traced.track.pos.length; i++) {
    assert.ok(traced.track.pos[i] > traced.track.pos[i - 1]);
  }
});
test('trackBySpeed：内容向上滚动时无需反转，位移正确', () => {
  const bandH = 120;
  const gaps = [0.1333, 0.1, 0.1667];
  const speed = 260;
  const frames = makeMovingFrames({ count: 24, bandH, gapsSec: gaps, speed, startOffset: 200, direction: 1 });
  const traced = S.trackBySpeed(frames, bandH, 8, { costLimit: 1e9 });
  assert.equal(traced.directionReversed, false);
  for (let j = 1; j < traced.steps.length; j++) {
    const dt = gaps[j % gaps.length];
    const expected = speed * dt;
    assert.ok(Math.abs(traced.steps[j] - expected) <= 4, 'step ' + j + ' ' + traced.steps[j] + ' vs ' + expected);
  }
});
