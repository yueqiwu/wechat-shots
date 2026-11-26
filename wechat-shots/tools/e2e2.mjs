import { setTimeout as sleep } from 'node:timers/promises';
import { connect } from './cdp.mjs';
import { mkdirSync, readdirSync, statSync } from 'node:fs';

const OUT = new URL('../_e2e2/', import.meta.url).pathname.replace(/^\//, '');
mkdirSync(OUT, { recursive: true });

const c = await connect(9223);
await c.send('Page.enable');
await c.send('Runtime.enable');
await c.send('Log.enable');
await c.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: OUT, eventsEnabled: true });

await c.send('Page.navigate', { url: 'http://127.0.0.1:8765/' });
for (let i = 0; i < 40; i++) {
  if (await c.evaluate("!!document.getElementById('btnSample')").catch(() => false)) break;
  await sleep(250);
}
for (let i = 0; i < 40; i++) {
  if (await c.evaluate('document.readyState === "complete"').catch(() => false)) break;
  await sleep(250);
}
await sleep(600);
console.log('title:', await c.evaluate('document.title'));
let st = null;
for (let i = 0; i < 200; i++) {
  st = await c.evaluate("(() => { const v = document.getElementById('video'); return { w: v.videoWidth, h: v.videoHeight, d: Math.round((v.duration||0)*10)/10, rs: v.readyState, err: v.error && v.error.code, src: v.src.slice(-24) }; })()");
  if (st.w > 0 && st.rs >= 2) break;
  if (st.w === 0 && i % 12 === 0) await c.evaluate("document.getElementById('btnSample').click()");
  await sleep(250);
}
console.log('video:', JSON.stringify(st));
if (!st.w) { console.log('ABORT: video never loaded'); process.exit(2); }
await c.evaluate("document.getElementById('fps').value = '10'");
const t0 = Date.now();
await c.evaluate("document.getElementById('btnRun').click()");
let last = '';
for (let i = 0; i < 900; i++) {
  const s = await c.evaluate("(() => { const st = document.getElementById('status'); const b = document.getElementById('bar'); return { status: st ? st.textContent : '', bar: b ? Math.round(parseFloat(b.style.width) || 0) : -1, cards: document.querySelectorAll('#grid .card-shot').length, count: (document.getElementById('count')||{}).textContent || '', toast: (document.getElementById('toast')||{}).hidden === false ? (document.getElementById('toast')||{}).textContent : '' }; })()");
  const line = JSON.stringify(s);
  if (line !== last) { console.log('t+' + ((Date.now() - t0) / 1000).toFixed(1) + 's ' + line); last = line; }
  if (/完成|失败|取消|没有|错误/.test(s.status) && s.cards > 0) break;
  if (s.toast && /太快|没抓到/.test(s.toast) && s.cards > 0) { await sleep(1500); break; }
  await sleep(300);
}
console.log('elapsed', ((Date.now() - t0) / 1000).toFixed(1) + 's');
console.log('cards', JSON.stringify(await c.evaluate("Array.from(document.querySelectorAll('#grid .card-shot .cap')).map(e => e.textContent)")));
console.log('final status', await c.evaluate("document.getElementById('status').textContent"));
// 下载 ZIP
await c.evaluate("document.getElementById('btnZip').click()");
let zip = null;
for (let i = 0; i < 80; i++) {
  const files = readdirSync(OUT).filter((f) => f.endsWith('.zip') && !f.endsWith('.crdownload'));
  if (files.length) { zip = files.map((f) => [f, statSync(OUT + '/' + f).size]); break; }
  await sleep(500);
}
console.log('zip', JSON.stringify(zip));
console.log('console errors', JSON.stringify(c.events.filter((e) => /error|exception/i.test(e)).slice(0, 5)));
c.close();
