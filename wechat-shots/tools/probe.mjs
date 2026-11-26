import { connect } from './cdp.mjs';
const c = await connect(9223);
console.log('status:', await c.evaluate("document.getElementById('status').textContent"));
console.log('btnZip disabled:', await c.evaluate("document.getElementById('btnZip').disabled"));
c.close();
