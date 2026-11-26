import { connect } from './cdp.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';
const c = await connect(9223);
const out = '../_e2e2/master.zip';
mkdirSync('../_e2e2', { recursive: true });
// 钩住 createObjectURL，捕获 zip blob
await c.evaluate(`(() => {
  if (!window.__origC) window.__origC = URL.createObjectURL.bind(URL);
  window.__zip = null;
  URL.createObjectURL = (b) => { if (b && b.type === 'application/zip') window.__zip = b; return window.__origC(b); };
  return 1;
})()`);
await c.evaluate("document.getElementById('btnZip').click()");
let size = 0;
for (let i = 0; i < 120; i++) {
  size = await c.evaluate("window.__zip ? window.__zip.size : 0");
  if (size) break;
  await new Promise(r => setTimeout(r, 500));
}
console.log('zip size', size);
const CH = 3 * 1024 * 1024;
let buf = [];
for (let off = 0; off < size; off += CH) {
  const b64 = await c.evaluate(`(() => {
    const b = window.__zip;
    const part = b.slice(${off}, ${Math.min(off + CH, size)});
    return new Promise((res) => { const fr = new FileReader(); fr.onload = () => res(fr.result.slice(fr.result.indexOf(',') + 1)); fr.readAsDataURL(part); });
  })()`);
  buf.push(Buffer.from(b64, 'base64'));
  process.stdout.write('.');
}
writeFileSync(out, Buffer.concat(buf));
console.log('\nwrote', out, Buffer.concat(buf).length);
c.close();
