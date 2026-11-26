import { writeFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import { connect } from './cdp.mjs';

const URL = 'https://qipaclub.top/wechat-shots/';
const VIDEO = String.raw`C:\Users\Administrator\Downloads\默认工作区\wechat-shots\input.mp4`;

const c = await connect(9224);
await c.send('Page.enable');
await c.send('Runtime.enable');
await c.send('Log.enable');
await c.send('DOM.enable');

const resp = await c.send('Page.navigate', { url: URL });
console.log('navigate:', JSON.stringify(resp));

for (let i = 0; i < 60; i++) {
  const rs = await c.evaluate('document.readyState');
  if (rs === 'complete') break;
  await sleep(250);
}
console.log('title:', await c.evaluate('document.title'));
console.log('sample link:', await c.evaluate("!!document.getElementById('btnSample')"));

const doc = await c.send('DOM.getDocument', { depth: -1 });
const node = await c.send('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#file' });
console.log('file input node:', node.nodeId);
await c.send('DOM.setFileInputFiles', { nodeId: node.nodeId, files: [VIDEO] });

let st = '';
for (let i = 0; i < 120; i++) {
  st = await c.evaluate("(() => ({ work: !document.getElementById('work').hidden, status: document.getElementById('status').textContent, meta: document.getElementById('meta').textContent, toast: document.getElementById('toast').hidden ? '' : document.getElementById('toast').textContent }))()");
  if (/自动裁剪|已载入|裁剪/.test(st.status) || st.toast) break;
  await sleep(300);
}
console.log('loaded:', JSON.stringify(st));

await c.evaluate("document.getElementById('btnRun').click()");
let run = '';
for (let i = 0; i < 200; i++) {
  run = await c.evaluate("(() => ({ status: document.getElementById('status').textContent, n: document.querySelectorAll('.card-shot').length, toast: document.getElementById('toast').hidden ? '' : document.getElementById('toast').textContent }))()");
  if (/完成/.test(run.status) || run.toast) break;
  await sleep(500);
}
console.log('run:', JSON.stringify(run));

const shot = await c.evaluate(`(async () => {
  const cards = [...document.querySelectorAll('.card-shot')];
  if (!cards.length) return null;
  const img = cards[0].querySelector('img');
  await new Promise((r) => setTimeout(r, 300));
  return { n: cards.length, w: img.naturalWidth, h: img.naturalHeight, label: cards[0].textContent.trim().slice(0, 24) };
})()`);
console.log('first card:', JSON.stringify(shot));

await c.evaluate("window.scrollTo(0, document.querySelector('.gallery').offsetTop - 20)");
await sleep(600);
const png = await c.send('Page.captureScreenshot', { format: 'png' });
writeFileSync('.hc3/shot_remote.png', Buffer.from(png.data, 'base64'));
console.log('events:', c.events.length ? c.events.slice(0, 8).join(' | ') : 'none');
c.close();
