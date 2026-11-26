import { setTimeout as sleep } from 'node:timers/promises';

export async function connect(port, timeoutMs = 25000) {
  const t0 = Date.now();
  let target = null;
  for (;;) {
    try {
      const r = await fetch('http://127.0.0.1:' + port + '/json/list');
      const list = await r.json();
      target = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      if (target) break;
    } catch (e) {}
    if (Date.now() - t0 > timeoutMs) throw new Error('no cdp page target on ' + port);
    await sleep(300);
  }
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = () => res(); ws.onerror = () => rej(new Error('ws connect failed')); });
  let id = 0;
  const pending = new Map();
  const events = [];
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      if (m.error) p.rej(new Error(p.method + ': ' + m.error.message)); else p.res(m.result);
      return;
    }
    if (m.method === 'Runtime.exceptionThrown') {
      const d = m.params.exceptionDetails;
      events.push('EXC ' + (d.exception && d.exception.description ? d.exception.description.split('\n')[0] : d.text));
    } else if (m.method === 'Runtime.consoleAPICalled' && (m.params.type === 'error' || m.params.type === 'warning')) {
      events.push('CONSOLE ' + m.params.type + ' ' + m.params.args.map((a) => a.value !== undefined ? a.value : a.description).join(' '));
    } else if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
      events.push('LOG ' + m.params.entry.text);
    }
  };
  const send = (method, params = {}, timeout = 60000) => new Promise((res, rej) => {
    const i = ++id;
    pending.set(i, { res, rej, method });
    ws.send(JSON.stringify({ id: i, method, params }));
    setTimeout(() => { if (pending.has(i)) { pending.delete(i); rej(new Error('timeout ' + method)); } }, timeout);
  });
  const evaluate = async (expression, awaitPromise = true) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise, returnByValue: true });
    if (r.exceptionDetails) {
      const d = r.exceptionDetails;
      throw new Error('eval error: ' + (d.exception && d.exception.description ? d.exception.description : d.text));
    }
    return r.result.value;
  };
  return { send, evaluate, events, close: () => ws.close() };
}
