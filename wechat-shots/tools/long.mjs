import { writeFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import { connect } from './cdp.mjs';

const c = await connect(9223);
await c.send('Runtime.enable');
console.log('before:', await c.evaluate("document.getElementById('status').textContent"));
await c.evaluate("document.getElementById('btnLong').click()");
let s = '';
for (let i = 0; i < 120; i++) {
  s = await c.evaluate("(() => { const w = document.getElementById('longWrap'); return { status: document.getElementById('status').textContent, html: w ? w.className + ':' + w.querySelectorAll('img').length : 'none', toast: document.getElementById('toast').hidden ? '' : document.getElementById('toast').textContent }; })()");
  if (s.html !== 'none' && s.html.split(':')[1] > 0) break;
  if (s.toast) break;
  await sleep(500);
}
console.log('after:', JSON.stringify(s));

const info = await c.evaluate(`(async () => {
  const imgs = [...document.querySelectorAll('#longWrap img')];
  if (!imgs.length) return null;
  const out = [];
  const img = imgs[0];
  const res = await fetch(img.src);
  const buf = new Uint8Array(await res.arrayBuffer());
  let s = '';
  for (let i = 0; i < buf.length; i += 4096) s += String.fromCharCode.apply(null, buf.subarray(i, i + 4096));
  return { n: imgs.length, w: img.naturalWidth, h: img.naturalHeight, b64: btoa(s), label: (document.getElementById('longWrap').textContent || '').slice(0, 120) };
})()`);
if (!info) console.log('no long image'); else {
  writeFileSync('.hc/long.png', Buffer.from(info.b64, 'base64'));
  console.log('long segs:', info.n, info.w + 'x' + info.h, info.label);
}
c.close();
