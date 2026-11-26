// test-v2.mjs - 连续滚动重建的端到端数值验证（不下浏览器）。
import * as core from '../app/js/core.js';
import * as pipeline from '../app/js/pipeline.js';

let fails = 0;
function ok(cond, msg, extra) {
  if (cond) console.log('PASS ' + msg);
  else { fails++; console.log('FAIL ' + msg + (extra === undefined ? '' : ' :: ' + JSON.stringify(extra))); }
}
function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const AW = 120, AH = 300, HEAD = 26, FOOT = 34;
const BAND_TOP = HEAD, BAND_BOT = AH - FOOT, BAND_H = BAND_BOT - BAND_TOP;
const DOC_H = 9000;

const rnd = mulberry32(4242);
const doc = new Uint8Array(AW * DOC_H);
{
  // 一半像文字行（高对比笔画），一半像背景——贴近真实聊天记录。
  // 要点 1：必须是逐行随机，按 y 的模数生成会造出周期性假匹配（踩过）。
  // 要点 2：笔画要有 2~3 行的竖直相关性，否则画面平移不足一行时相关性会直接归零，
  //         真实截图不存在这种情况（同一条笔画/头像会跨好几行）。
  let texty = false, run = 0;
  for (let y = 0; y < DOC_H; y++) {
    if (run > 0) {
      run--;
      doc.copyWithin(y * AW, (y - 1) * AW, y * AW);
      continue;
    }
    texty = rnd() < 0.55;
    run = texty ? Math.floor(rnd() * 3) : 0;
    for (let x = 0; x < AW; x++) {
      const v = texty ? (rnd() > 0.55 ? 30 : 235) : 200 + Math.floor(rnd() * 40);
      doc[y * AW + x] = v;
    }
  }
}
// 竖直方向轻微模糊（3 抽头）。真实截图的文字/图片都是抗锯齿的，不会出现
// 「相隔 2 行的像素完全无关」的白噪声；不做这一步，画面只平移 1~2 像素时
// 相关性就会掉到 0.6 以下，测试会误判成对齐失败。
{
  const tmp = doc.slice();
  for (let y = 0; y < DOC_H; y++) {
    const a = Math.max(0, y - 1), b = Math.min(DOC_H - 1, y + 1);
    for (let x = 0; x < AW; x++) {
      doc[y * AW + x] = Math.round((tmp[a * AW + x] + 2 * tmp[y * AW + x] + tmp[b * AW + x]) / 4);
    }
  }
}
const header = new Uint8Array(AW * HEAD);
for (let y = 0; y < HEAD; y++) for (let x = 0; x < AW; x++) header[y * AW + x] = 40 + ((x * 7 + y * 3) % 60);
const footer = new Uint8Array(AW * FOOT);
for (let y = 0; y < FOOT; y++) for (let x = 0; x < AW; x++) footer[y * AW + x] = 225 + ((x + y) % 20);

// 变速轨迹：停 → 缓慢加速 → 猛甩 → 停 → 中速 → 回滑 → 停
function speedAt(t) {
  if (t < 0.5) return 0;
  if (t < 2) return 400 * (t - 0.5) / 1.5;
  if (t < 3.2) return 2600;
  if (t < 3.9) return 0;
  if (t < 5.6) return 900;
  if (t < 6.6) return -700;
  return 0;
}
const DUR = 7.6;
const POS = new Float64Array(Math.ceil(DUR * 1000) + 2);
{
  let p = 0;
  for (let i = 0; i <= DUR * 1000; i++) {
    POS[i] = p;
    p += speedAt((i + 0.5) / 1000) / 1000;
  }
}
function posAtFast(t) {
  const x = Math.max(0, Math.min(DUR * 1000, t * 1000));
  const i = Math.floor(x);
  const f = x - i;
  return POS[i] * (1 - f) + POS[Math.min(POS.length - 1, i + 1)] * f;
}

function frameAt(t) {
  const pos = posAtFast(t);
  const blur = Math.min(4, Math.floor(Math.abs(speedAt(t)) / 700));
  const f = new Uint8Array(AW * AH);
  for (let y = 0; y < AH; y++) {
    if (y < HEAD) { f.set(header.subarray(y * AW, (y + 1) * AW), y * AW); continue; }
    if (y >= BAND_BOT) { f.set(footer.subarray((y - BAND_BOT) * AW, (y - BAND_BOT + 1) * AW), y * AW); continue; }
    const d0 = Math.round(y - BAND_TOP + pos);
    for (let x = 0; x < AW; x++) {
      let s = 0, n = 0;
      for (let k = -blur; k <= blur; k++) {
        const d = d0 + k;
        if (d >= 0 && d < DOC_H) { s += doc[d * AW + x]; n++; }
      }
      f[y * AW + x] = n ? Math.round(s / n) : 250;
    }
  }
  return f;
}

function sample(fps, t0, t1) {
  const out = [];
  const n = Math.max(2, Math.round((t1 - t0) * fps));
  for (let i = 0; i < n; i++) {
    const t = t0 + (i + 0.5) * (t1 - t0) / n;
    out.push({ t, gray: frameAt(t) });
  }
  return out;
}

// —— 复刻 pipeline.analyze 的流程（无 DOM）——
let frames = sample(8, 0, DUR);
const band = pipeline.detectBand(frames, AW, AH);
pipeline.prepFrames(frames, AW, AH, band);
let track = core.trackOffsets(frames.map((f) => f.sig), band.h, { bins: 8 });
ok(Math.abs(band.top - HEAD) <= 4 && Math.abs(band.bot - BAND_BOT) <= 4, '内容条带避开静态头尾', band);
ok(track.bad.length > 0, '8 帧/秒 在猛甩段确实会跟丢（触发补帧）', track.bad.length);

const rounds = [];
for (let r = 0; r < 3 && track.bad.length; r++) {
  const planR = pipeline.refillPlan(frames, track, 8, r, 400 - frames.length);
  if (!planR.length) break;
  const add = [];
  for (const it of planR) add.push(...sample(it.n / (it.t1 - it.t0), it.t0, it.t1));
  pipeline.prepFrames(add, AW, AH, band);
  frames = frames.concat(add).sort((a, b) => a.t - b.t);
  track = core.trackOffsets(frames.map((f) => f.sig), band.h, { bins: 8 });
  rounds.push({ r, frames: frames.length, bad: track.bad.length });
}
console.log('补帧过程:', JSON.stringify(rounds));
if (process.env.DEBUG) {
  for (const b of track.bad) console.log('BAD', b.reason, 'i', b.i, 't', frames[b.i].t.toFixed(3), 'dt', (frames[b.i].t-frames[b.i-1].t).toFixed(3), 'step', Math.round(track.step[b.i]), 'ncc', b.ncc.toFixed(2), 'agree', b.agree);
  for (const b of track.bad.slice(0, 2)) {
    const i = b.i;
    const row = [];
    for (let s = -8; s <= 8; s++) {
      const a = core.alignAt(frames[i - 1].sig, frames[i].sig, band.h, 8, s, Math.max(6, Math.round(band.h * 0.1)), 1);
      row.push(s + ':' + (a ? a.ncc.toFixed(2) : 'x'));
    }
    console.log('  ncc@', row.join(' '), ' trueStep', Math.round(posAtFast(frames[i].t) - posAtFast(frames[i - 1].t)));
    // 直接比像素，看看是图不同还是签名不同
    const A = frameAt(frames[i - 1].t), B = frameAt(frames[i].t);
    const raw = [];
    for (let s = -6; s <= 6; s++) {
      let n = 0, sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
      for (let y = band.top; y < band.bot; y++) {
        const yy = y + s;
        if (yy < band.top || yy >= band.bot) continue;
        for (let x = 0; x < AW; x++) {
          const a = A[y * AW + x], b = B[yy * AW + x];
          sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b; n++;
        }
      }
      const va = saa - sa * sa / n, vb = sbb - sb * sb / n;
      raw.push(s + ':' + ((sab - sa * sb / n) / Math.sqrt(Math.max(1e-9, va * vb))).toFixed(2));
    }
    console.log('  raw@', raw.join(' '), 'band', JSON.stringify(band));
  }
}
ok(track.bad.length === 0, '补帧后不再有饱和/失配', track.bad.slice(0, 8).map((b) => b.reason + '@' + b.i + '/ncc=' + b.ncc.toFixed(2)).join(','));

// 逐帧绝对位置 vs 真值
const t0 = frames[0].t;
let errBad = 0, worst = 0;
for (let i = 0; i < frames.length; i++) {
  const e = Math.abs(track.pos[i] - (posAtFast(frames[i].t) - posAtFast(t0)));
  if (e > 2) errBad++;
  if (e > worst) worst = e;
}
if (process.env.DEBUG) console.log('worst pos err', worst.toFixed(2), 'sample steps', track.step.slice(10, 18).map((v) => v.toFixed(2)).join(' '));
ok(errBad === 0, '逐帧累计位置与真值一致（|误差| ≤ 2px）', { bad: errBad, total: frames.length, worst: +worst.toFixed(2) });

// —— 用 planMaster 生成主图，再按真实内容核对 ——
const sample4plan = { frames, AW, AH, vw: AW, vh: AH, dur: DUR };
// 裁剪区必须取「内容条带」：静态状态栏/输入栏如果被算进主图，它们不随滚动移动，
// 会被逐帧重复贴到主图的不同位置，整张主图就废了（踩过）。
const plan = pipeline.planMaster(sample4plan, track, { topPx: band.top, botPx: band.bot });
const MP = plan.MP;
const recon = new Uint8Array(AW * MP);
for (const op of plan.ops) {
  if (op.frame < 0) continue;
  const src = frameAt(frames[op.frame].t);
  for (let y = op.y0; y < op.y1; y++) {
    // op.src 是「裁剪区内的行号」，还原到整帧还要加 cTop
    const row = plan.cTop + op.src + (y - op.y0);
    recon.set(src.subarray(row * AW, (row + 1) * AW), y * AW);
  }
}

// 核对方式用「逐行相关系数」而不是像素绝对差：抽样帧带运动模糊，像素值必然和
// 清晰的原图不同，但模糊不会改变相关性。
// 期望像素 = 原图在该位置、按该帧运动模糊半径做的竖直平均（和 frameAt 生成画面
// 的方式一致），这样比较不受模糊影响，只检验「放的位置对不对」。
function rowNcc(y, d, k) {
  if (d - k < 0 || d + k >= DOC_H) return -2;
  const exp = new Float64Array(AW);
  for (let dy = -k; dy <= k; dy++) {
    const base = (d + dy) * AW;
    for (let x = 0; x < AW; x++) exp[x] += doc[base + x];
  }
  let sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
  const inv = 1 / (2 * k + 1);
  for (let x = 0; x < AW; x++) {
    const a = recon[y * AW + x], b = exp[x] * inv;
    sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b;
  }
  const va = saa - sa * sa / AW, vb = sbb - sb * sb / AW;
  if (vb <= 1e-6) return -2;
  if (va <= 1e-6) return 0;
  return (sab - sa * sb / AW) / Math.sqrt(va * vb);
}
// 主图某一行是由哪一帧提供的 → 该帧的运动模糊半径
const rowBlur = new Int8Array(MP);
for (const op of plan.ops) {
  const k = op.frame < 0 ? 0 : Math.min(4, Math.floor(Math.abs(speedAt(frames[op.frame].t)) / 700));
  for (let y = op.y0; y < op.y1; y++) rowBlur[y] = k;
}

let bestOff = 0, bestSum = -Infinity;
for (let off = -40; off <= 40; off++) {
  let s = 0, n = 0;
  for (let y = 100; y < MP - 100; y += 7) {
    const c = rowNcc(y, y + off, rowBlur[y]);
    if (c > -1.5) { s += c; n++; }
  }
  s = n ? s / n : -Infinity;
  if (s > bestSum) { bestSum = s; bestOff = off; }
}
console.log('主图高度', MP, '最佳对齐偏移', bestOff, '平均行相关', bestSum.toFixed(3));
if (process.env.DEBUG) {
  for (const op of plan.ops.slice(0, 6)) {
    const f = op.frame;
    console.log('OP', op.y0, op.y1, 'frame', f, 'src', op.src, 'pos', f >= 0 ? track.pos[f] : '-', 't', f >= 0 ? frames[f].t.toFixed(3) : '-', 'expectDoc', op.y0 + bestOff);
  }
  for (const y of [60, 61, 62, 70, 100, 500]) {
    const ex = Math.round(y + bestOff);
    let d = 0; for (let x = 0; x < AW; x++) d += Math.abs(recon[y * AW + x] - doc[ex * AW + x]);
    console.log('ROW', y, 'expect doc', ex, 'meanAbs', (d / AW).toFixed(1), 'ncc', rowNcc(y, ex, rowBlur[y]).toFixed(3), 'k', rowBlur[y]);
  }
}
ok(bestSum > 0.8, '重建出的主图和真实内容对齐', { bestOff, bestSum: +bestSum.toFixed(3) });

// 逐行核对：每一行在 ±4 行内找到的最佳对应行应当就是期望位置（允许 1~2 行抖动，
// 抽帧位置本身有整数化误差，肉眼不可见）。
// 静止/慢速帧（无运动模糊）要求严：错位 ≤1px；带运动模糊的帧本身就有 ±几行的
// 不确定性，允许 ≤4px（在 1584px 高的截图上肉眼看不出来）。
let sharpBad = 0, sharpN = 0, blurBad = 0, blurN = 0;
const hist = new Map(), badRows = [];
for (let y = 60; y < MP - 60; y++) {
  const ex = y + bestOff;
  let bestD = ex, bestC = -2;
  for (let d = ex - 4; d <= ex + 4; d++) {
    const c = rowNcc(y, d, rowBlur[y]);
    if (c > bestC) { bestC = c; bestD = d; }
  }
  if (bestC <= -1.5) continue; // 纯色行，无从判断
  const e = bestD - ex;
  hist.set(e, (hist.get(e) || 0) + 1);
  if (rowBlur[y] === 0) {
    sharpN++;
    if (Math.abs(e) > 1) { sharpBad++; if (badRows.length < 8) badRows.push(y + '→' + e); }
  } else {
    blurN++;
    if (Math.abs(e) > 4) blurBad++;
  }
}
console.log('逐行核对：清晰行错位', sharpBad, '/', sharpN, '· 模糊行错位 >4px', blurBad, '/', blurN,
  '· 误差分布', JSON.stringify([...hist.entries()].sort((a, b) => a[0] - b[0])));
if (process.env.DEBUG) {
  for (const y of [327, 330, 334, 340, 345, 400]) {
    const op = plan.ops.find((o) => y >= o.y0 && y < o.y1);
    const f = op.frame;
    console.log('ROW', y, 'op', op.y0 + '-' + op.y1, 'frame', f, 't', f >= 0 ? frames[f].t.toFixed(3) : '-',
      'speed', f >= 0 ? Math.round(speedAt(frames[f].t)) : '-', 'pos', f >= 0 ? track.pos[f].toFixed(2) : '-',
      'src', op.src, 'docRow', f >= 0 ? (track.pos[f] + op.src + (y - op.y0)).toFixed(2) : '-', 'expect', y + bestOff);
  }
}
ok(sharpBad <= Math.max(1, Math.round(sharpN * 0.002)), '清晰画面没有错位/重影的行', { bad: sharpBad, of: sharpN, sample: badRows });
ok(blurBad === 0, '运动模糊的画面没有明显错位', { bad: blurBad, of: blurN });

// 切片：沿主图顺序切、边界落在消息之间的空行上，天然不漏不重
const cropH = BAND_H;
const cuts = plan.cuts;
const tiles = cuts.length - 1;
console.log('内容总高', MP, '→', tiles, '张（目标每张', cropH, 'px）');
ok(cuts[0] === 0 && cuts[cuts.length - 1] === MP, '切片首尾与主图对齐', [cuts[0], cuts[cuts.length - 1], MP]);
let covered = 0, mono = true, minH = Infinity, maxH = 0;
for (let k = 0; k < tiles; k++) {
  const h = cuts[k + 1] - cuts[k];
  if (h <= 0) mono = false;
  covered += h; minH = Math.min(minH, h); maxH = Math.max(maxH, h);
}
ok(mono, '切片边界严格递增');
ok(covered === MP, '切片总覆盖 = 主图高度（无重叠、无遗漏）', [covered, MP]);
console.log('切片高度', minH, '~', maxH, 'px（目标', cropH, '，允许', Math.round(cropH * 0.62), '~', Math.round(cropH * 1.45), '）');
ok(minH >= Math.round(cropH * 0.62) - 1 && maxH <= Math.round(cropH * 1.45) + 1, '切片高度在允许范围内', [minH, maxH]);

// 缺口统计
const gapOps = plan.ops.filter((o) => o.frame < 0);
const gaps = gapOps.reduce((s, o) => s + (o.y1 - o.y0), 0);
console.log('空洞区间', gapOps.map((o) => o.y0 + '-' + o.y1).join(', ') || '无');
if (process.env.DEBUG && gapOps.length) {
  const big = gapOps.reduce((a, b) => (b.y1 - b.y0 > a.y1 - a.y0 ? b : a));
  const k0 = plan.ops.indexOf(big);
  for (let j = Math.max(0, k0 - 2); j < Math.min(plan.ops.length, k0 + 3); j++) {
    const o = plan.ops[j];
    console.log('around', j, 'y', o.y0, o.y1, 'frame', o.frame, 'src', o.src, 'pos', o.frame >= 0 ? track.pos[o.frame] : '-', 't', o.frame >= 0 ? frames[o.frame].t.toFixed(3) : '-');
  }
  // 真实位置的相邻帧
  const ps = [...track.pos.keys()];
  let idx = 0; for (let i = 0; i < frames.length; i++) if (track.pos[i] <= big.y0) idx = i;
  for (let i = Math.max(0, idx - 1); i < Math.min(frames.length, idx + 3); i++) console.log('frame', i, 't', frames[i].t.toFixed(3), 'pos', Math.round(track.pos[i]), 'truePos', Math.round(posAtFast(frames[i].t) - posAtFast(t0)));
}
ok(gaps === 0, '主图没有未覆盖的空洞', gaps);

console.log(fails ? 'FAILED ' + fails : 'ALL PASS');
process.exit(fails ? 1 : 0);
