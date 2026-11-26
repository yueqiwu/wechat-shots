import fs from 'node:fs';
import * as core from '../app/js/core.js';
import { makeZip } from '../app/js/zip.js';

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

const W = 108;
const H = 200;
const DOC_H = 600;
const SHIFT = 100;

function makeDoc(h, w, blankEvery) {
  const rnd = mulberry32(12345);
  const d = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) {
    const blank = blankEvery && (y % blankEvery === 0);
    for (let x = 0; x < w; x++) d[y * w + x] = blank ? 245 : Math.floor(rnd() * 200) + 20;
  }
  return d;
}

function runCase(label, positions, docH, expectTotal, blankEvery) {
  const doc = makeDoc(docH, W, blankEvery);
  const screens = positions.map((p) => doc.slice(p * W, (p + H) * W));
  const sigs = screens.map((s) => core.rowSig(s, W, H, 8));
  const shifts = [];
  for (let i = 1; i < screens.length; i++) {
    const r = core.findShift(sigs[i - 1], sigs[i], H, 8, Math.floor(H * 0.85), 0.15);
    shifts.push(r.shift);
  }
  const norm = core.normalizeDirection(screens, shifts);
  const plan = core.planStrips(H, norm.steps);
  const out = new Uint8Array(W * plan.total);
  let y = 0;
  for (const st of plan.strips) {
    const src = norm.screens[st.screen];
    out.set(src.subarray(st.srcY * W, (st.srcY + st.h) * W), y * W);
    y += st.h;
  }
  const expect = doc.slice(0, W * plan.total);
  let diff = 0;
  for (let i = 0; i < out.length; i++) if (out[i] !== expect[i]) diff++;
  ok(plan.total === expectTotal, label + ': total height ' + plan.total + ' == ' + expectTotal, plan.total);
  ok(diff === 0, label + ': reconstructed pixels identical', diff);
  return { plan, norm, shifts };
}

// capture order: newest (bottom of doc) first -> scrolling upward
const up = runCase('scroll-up', [400, 300, 200, 100, 0], DOC_H, DOC_H, 0);
ok(up.shifts.every((s) => s === SHIFT), 'scroll-up: every detected shift == ' + SHIFT, up.shifts);
ok(up.norm.reverse === true, 'scroll-up: order reversed for stitching');

const up2 = runCase('scroll-up-blank', [400, 300, 200, 100, 0], DOC_H, DOC_H, 3);
ok(up2.shifts.every((s) => s === SHIFT), 'scroll-up-blank: shifts == ' + SHIFT, up2.shifts);

// scrolling downward: capture order is already top -> bottom
const down = runCase('scroll-down', [0, 100, 200, 300, 400], DOC_H, DOC_H, 0);
ok(down.shifts.every((s) => s === -SHIFT), 'scroll-down: shifts == -' + SHIFT, down.shifts);
ok(down.norm.reverse === false, 'scroll-down: order kept');

// unequal steps
const mixed = runCase('uneven', [0, 60, 180, 240, 400], DOC_H, DOC_H, 0);
ok(mixed.plan.total === DOC_H, 'uneven: total height', mixed.plan.total);

// analyzer helpers
const d1 = new Uint8Array([0, 0, 0, 0, 30, 30, 30]);
ok(core.autoThreshold(d1) > 1, 'autoThreshold separates static from moving', core.autoThreshold(d1));
const runs = core.detectRuns(d1, 2, 2);
ok(runs.length === 1 && runs[0].start === 0 && runs[0].end === 4, 'detectRuns static block', runs);

// zip
const enc = new TextEncoder();
const blob = makeZip([
  { name: 'a.txt', data: enc.encode('hello wechat') },
  { name: 'dir/b.bin', data: new Uint8Array([1, 2, 3, 250]) },
]);
const buf = new Uint8Array(await blob.arrayBuffer());
fs.mkdirSync('_out', { recursive: true });
fs.writeFileSync('_out/test.zip', buf);
ok(buf.length > 60, 'zip produced ' + buf.length + ' bytes');

console.log(fails === 0 ? 'ALL PASS' : fails + ' FAILURES');
process.exit(fails === 0 ? 0 : 1);
