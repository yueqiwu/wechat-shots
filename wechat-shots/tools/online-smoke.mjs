
import { connect } from './cdp.mjs';
const c = await connect(9223);
await c.send('Runtime.enable');
await c.send('Page.enable');
await c.send('Page.navigate', { url: 'https://qipaclub.top/wechat-shots/' });
await new Promise(r => setTimeout(r, 6000));
const info = await c.evaluate(`JSON.stringify({
  ready: document.readyState,
  fps: [...document.querySelectorAll('#fps option')].map(o => o.value),
  hasSample: !!document.querySelector('#btnSample'),
  status: document.querySelector('#status') ? document.querySelector('#status').textContent.trim() : null,
  tip: document.querySelector('#enginePill') ? document.querySelector('#enginePill').textContent.trim() : null,
  cards: document.querySelectorAll('.card').length
})`);
console.log('ONLINE', info);
console.log('EVENTS', JSON.stringify(c.events));
await c.close();
