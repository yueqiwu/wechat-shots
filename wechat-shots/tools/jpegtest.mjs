import { setTimeout as sleep } from 'node:timers/promises';
import { connect } from './cdp.mjs';

const c = await connect(9223);
await c.send('Runtime.enable');
await c.evaluate("(() => { const f = document.getElementById('fmt'); f.value = 'jpeg'; f.dispatchEvent(new Event('change', { bubbles: true })); return f.value; })()");
console.log('fmt:', await c.evaluate("document.getElementById('fmt').value"));
await c.evaluate("document.getElementById('btnRun').click()");
let st = '';
for (let i = 0; i < 150; i++) {
  st = await c.evaluate("(() => ({ s: document.getElementById('status').textContent, n: document.querySelectorAll('#grid .card-shot').length, t: document.getElementById('toast').hidden ? '' : document.getElementById('toast').textContent }))()");
  if (/完成/.test(st.s)) break;
  if (st.t) { console.log('TOAST', st.t); break; }
  await sleep(500);
}
console.log('run:', JSON.stringify(st));
const info = await c.evaluate(`(async () => {
  const cards = [...document.querySelectorAll('#grid .card-shot')];
  if (!cards.length) return null;
  const img = cards[0].querySelector('img');
  const res = await fetch(img.src);
  const b = await res.blob();
  return { type: b.type, size: b.size, w: img.naturalWidth, h: img.naturalHeight, count: cards.length, label: document.querySelector('#grid .card-shot').textContent.trim().slice(0, 40) };
})()`);
console.log('card0:', JSON.stringify(info));
c.close();
