import { setTimeout as sleep } from 'node:timers/promises';
import { connect } from './cdp.mjs';

const c = await connect(9223);
await c.send('Page.enable');
await c.send('Runtime.enable');
await c.send('Log.enable');

await c.send('Page.navigate', { url: 'http://127.0.0.1:8765/' });
for (let i = 0; i < 40; i++) {
  const ok = await c.evaluate("!!document.getElementById('btnSample')").catch(() => false);
  if (ok) break;
  await sleep(250);
}
console.log('title:', await c.evaluate('document.title'));
console.log('vendor:', JSON.stringify(await c.evaluate("(() => { const v = document.createElement('video'); return { canH264: v.canPlayType('video/mp4; codecs=\"avc1.42E01E\"'), canMp4: v.canPlayType('video/mp4'), mse: typeof MediaSource !== 'undefined' }; })()")));

await c.evaluate("document.getElementById('btnSample').click()");
let st = null;
for (let i = 0; i < 120; i++) {
  st = await c.evaluate("(() => { const v = document.getElementById('video'); return { w: v.videoWidth, h: v.videoHeight, d: Math.round((v.duration||0)*10)/10, rs: v.readyState, err: v.error && v.error.code, src: (v.currentSrc||'').slice(-24) }; })()");
  if (st.w > 0 && st.rs >= 2) break;
  await sleep(250);
}
console.log('video:', JSON.stringify(st));
console.log('meta:', await c.evaluate("(document.getElementById('meta')||{}).textContent"));

await c.evaluate("document.getElementById('btnRun').click()");
let last = '';
for (let i = 0; i < 400; i++) {
  const s = await c.evaluate("(() => { const st = document.getElementById('status'); const b = document.getElementById('bar'); return { status: st ? st.textContent : '', bar: b ? Math.round(b.value || 0) : -1, cards: document.querySelectorAll('#grid .card-shot').length, count: (document.getElementById('count')||{}).textContent || '', toast: (document.getElementById('toast')||{}).textContent || '', toastOn: document.getElementById('toast') ? !document.getElementById('toast').hidden : false }; })()");
  const line = JSON.stringify(s);
  if (line !== last) { console.log('t+' + i + ' ' + line); last = line; }
  if (s.cards > 0 && /完成|好了|张截图/.test(s.count + s.status) && s.bar >= 100) break;
  if (s.toastOn && s.toast) break;
  await sleep(400);
}
console.log('events:', JSON.stringify(c.events.slice(0, 8)));
c.close();
