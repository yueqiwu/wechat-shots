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

const AW = 144, AH = 300, DOC_H = 1800, HEAD = 30, FOOT = 40;
const rnd = mulberry32(999);
const doc = new Uint8Array(AW * DOC_H);
for (let i = 0; i < doc.length; i++) doc[i] = 20 + Math.floor(rnd() * 200);
const header = new Uint8Array(AW * HEAD);
for (let y = 0; y < HEAD; y++) for (let x = 0; x < AW; x++) header[y * AW + x] = 12 + (x % 7);
const footer = new Uint8Array(AW * FOOT);
for (let y = 0; y < FOOT; y++) for (let x = 0; x < AW; x++) footer[y * AW + x] = 240 - (x % 5);

function frameAt(pos) {
  const f = new Uint8Array(AW * AH);
  for (let y = 0; y < AH; y++) {
    for (let x = 0; x < AW; x++) {
      if (y < HEAD) f[y * AW + x] = header[y * AW + x];
      else if (y >= AH - FOOT) f[y * AW + x] = footer[(y - (AH - FOOT)) * AW + x];
      else {
        const sy = y - HEAD + pos;
        f[y * AW + x] = (sy >= 0 && sy < DOC_H) ? doc[sy * AW + x] : 250;
      }
    }
  }
  return f;
}

function build(stops, step, hold, fps) {
  const seq = [];
  for (let k = 0; k < stops; k++) {
    const p = k * step;
    for (let j = 0; j < hold; j++) seq.push(p);
    if (k < stops - 1) {
      for (let d = step / 4; d < step; d += step / 4) seq.push(p + d);
    }
  }
  const frames = seq.map((p) => frameAt(Math.round(p)));
  const times = seq.map((_, i) => i / fps);
  return { frames, times, AW, AH, vw: 1080, vh: 2250, dur: seq.length / fps, fps };
}

const s = build(5, 200, 5, 3);
const analysis = pipeline.analyzeSamples(s, 1);
const picked = pipeline.pickScreens(s, analysis);

console.log('runs', analysis.runs.length, 'picked', picked.length, 'thr', analysis.thr.toFixed(3));
console.log('bounds', JSON.stringify(analysis.bounds));
console.log('shifts', JSON.stringify(picked.map((p) => p.roughShift)));

ok(analysis.runs.length === 5, '5 stable runs found', analysis.runs.length);
ok(picked.length === 5, '5 screens picked', picked.length);
ok(picked.every((p, i) => i === 0 || Math.abs(p.roughShift) === 200), 'each step is exactly 200px', picked.map((p) => p.roughShift));
ok(analysis.bounds.auto === true, 'auto bounds detected');
ok(Math.abs(analysis.bounds.top - HEAD) <= 8, 'top bound near header', analysis.bounds.top);
ok(Math.abs(analysis.bounds.bottom - (AH - FOOT)) <= 8, 'bottom bound near footer', analysis.bounds.bottom);

const flat = { frames: Array.from({ length: 12 }, () => frameAt(300)), times: Array.from({ length: 12 }, (_, i) => i / 3), AW, AH, vw: 1080, vh: 2250, dur: 4, fps: 3 };
const fa = pipeline.analyzeSamples(flat, 1);
const fp = pipeline.pickScreens(flat, fa);
ok(fp.length === 1, 'static video yields a single screen', fp.length);

console.log(fails ? 'FAILURES: ' + fails : 'ALL PASS');
process.exit(fails ? 1 : 0);
