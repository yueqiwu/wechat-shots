import { writeFileSync, readdirSync, statSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import { connect } from './cdp.mjs';

const c = await connect(9223);
await c.send('Page.enable');
await c.send('Runtime.enable');

const cards = await c.evaluate("document.querySelectorAll('#grid .card-shot').length");
console.log('cards:', cards);
console.log('count:', await c.evaluate("document.getElementById('count').textContent"));
console.log('status:', await c.evaluate("document.getElementById('status').textContent"));

await c.evaluate("document.getElementById('gallery').scrollIntoView({block:'start'})");
await sleep(600);
const shot = await c.send('Page.captureScreenshot', { format: 'png' });
writeFileSync('.hc/gallery.png', Buffer.from(shot.data, 'base64'));
console.log('gallery.png written');

const imgs = await c.evaluate(`(async () => {
  const cards = [...document.querySelectorAll('#grid .card-shot')];
  const out = [];
  for (const idx of [0, 2, 5]) {
    const img = cards[idx].querySelector('img');
    const res = await fetch(img.src);
    const buf = new Uint8Array(await res.arrayBuffer());
    let s = '';
    for (let i = 0; i < buf.length; i += 4096) s += String.fromCharCode.apply(null, buf.subarray(i, i + 4096));
    out.push({ idx, w: img.naturalWidth, h: img.naturalHeight, size: buf.length, b64: btoa(s) });
  }
  return out;
})()`);
for (const it of imgs) {
  writeFileSync('.hc/card' + it.idx + '.png', Buffer.from(it.b64, 'base64'));
  console.log('card' + it.idx, it.w + 'x' + it.h, it.size + 'B');
}

const dl = process.argv[2];
await c.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: dl, eventsEnabled: true });
await c.evaluate("document.getElementById('btnZip').click()");
for (let i = 0; i < 40; i++) {
  await sleep(500);
  const files = readdirSync(dl).filter((f) => f.endsWith('.zip'));
  if (files.length) {
    console.log('zip:', files[0], statSync(dl + '/' + files[0]).size + 'B');
    break;
  }
}
c.close();
