// 定位 planMaster 对不上的原因
import * as core from '../app/js/core.js';
import * as pipeline from '../app/js/pipeline.js';
import { readFileSync } from 'node:fs';
const t = readFileSync(new URL('./test-v2.mjs', import.meta.url), 'utf8');
const head = t.slice(t.indexOf('let fails'), t.indexOf('// —— 用 planMaster'));
eval(head.replace(/^import .*$/gm, '').replace(/\bconst core\b/g,'core'));
