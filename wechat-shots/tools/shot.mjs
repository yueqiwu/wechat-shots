
import { connect } from './cdp.mjs';
import fs from 'fs';
const c = await connect(9223);
const targets = await c.send('Target.getTargets');
const page = targets.targetInfos.find(t => t.type === 'page');
const t = page.targetId;
await c.send('Emulation.setDeviceMetricsOverride', { width: 1400, height: 1000, deviceScaleFactor: 1, mobile: false }, t);
await c.evaluate("document.querySelector('#gallery, .cards, #cards')?.scrollIntoView({block:'start'}); window.scrollTo(0, document.querySelector('#result, #results')?.offsetTop - 80 || 0); 'ok'", t);
await new Promise(r => setTimeout(r, 600));
const s = await c.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false }, t);
fs.writeFileSync('../_e2e2/ui_final.png', Buffer.from(s.data, 'base64'));
console.log('shot', Buffer.from(s.data, 'base64').length);
await c.close();
