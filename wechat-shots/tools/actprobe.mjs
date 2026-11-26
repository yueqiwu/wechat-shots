import { connect } from './cdp.mjs';
import { setTimeout as sleep } from 'node:timers/promises';
const c = await connect(9223);
await c.send('Page.enable');
await c.send('Runtime.enable');
await c.send('Page.navigate', { url: 'http://127.0.0.1:8765/' });
for (let i = 0; i < 40; i++) { if (await c.evaluate('document.readyState === "complete"').catch(() => false)) break; await sleep(250); }
await sleep(500);
await c.evaluate('window.__DBG_PLAN = 1');
await c.evaluate("document.getElementById('btnSample').click()");
let st = null;
for (let i = 0; i < 200; i++) {
  st = await c.evaluate("(() => { const v = document.getElementById('video'); return { w: v.videoWidth, rs: v.readyState }; })()");
  if (st.w > 0 && st.rs >= 2) break;
  if (st.w === 0 && i % 12 === 0) await c.evaluate("document.getElementById('btnSample').click()");
  await sleep(250);
}
console.log('video', JSON.stringify(st));
await c.evaluate("document.getElementById('fps').value = '10'");
await c.evaluate("document.getElementById('btnRun').click()");
for (let i = 0; i < 400; i++) {
  const done = await c.evaluate("document.querySelectorAll('#grid .card-shot').length");
  if (done > 0) break;
  await sleep(500);
}
const info = await c.evaluate(`(() => {
  const a = window.__actPx, cuts = window.__lastCuts || [];
  if (!a) return 'no act';
  const n = a.length;
  const s = Array.from(a).sort((x, y) => x - y);
  const pick = (q) => s[Math.min(n - 1, (n * q) | 0)];
  const at = cuts.slice(1, -1).map((y) => a[y]);
  const rank = (v) => { let lo = 0, hi = n; while (lo < hi) { const m = (lo + hi) >> 1; if (s[m] < v) lo = m + 1; else hi = m; } return lo / n; };
  const ranks = at.map(rank).sort((x, y) => x - y);
  return JSON.stringify({ n, p10: pick(0.1), p50: pick(0.5), p90: pick(0.9), nc: at.length,
    cutActMedian: ranks[(ranks.length / 2) | 0], cutActP90: ranks[Math.min(ranks.length - 1, (ranks.length * 0.9) | 0)],
    minRank: ranks[0], first5: cuts.slice(1, 6) });
})()`);
console.log(info);
c.close();
