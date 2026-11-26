import { connect } from './cdp.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';
const c = await connect(9223);
mkdirSync('_frames', { recursive: true });
const grab = `(async (t) => {
  const v = document.getElementById('video');
  const seek = (t) => new Promise((res) => { const h = () => { v.removeEventListener('seeked', h); res(); }; v.addEventListener('seeked', h); v.currentTime = t; });
  await seek(t);
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  const cv = document.createElement('canvas'); cv.width = v.videoWidth; cv.height = v.videoHeight;
  cv.getContext('2d').drawImage(v, 0, 0);
  return cv.toDataURL('image/jpeg', 0.85).split(',')[1];
})`;
const ts = [];
for (let t = 1.0; t < 23.4; t += 0.7) { ts.push(+t.toFixed(2)); ts.push(+(t + 0.12).toFixed(2)); }
for (let i = 0; i < ts.length; i++) {
  const b64 = await c.evaluate(grab + '(' + ts[i] + ')');
  writeFileSync('_frames/f' + String(i).padStart(3, '0') + '_' + ts[i].toFixed(2) + '.jpg', Buffer.from(b64, 'base64'));
}
console.log('frames', ts.length, JSON.stringify(ts.slice(0, 6)));
c.close();
