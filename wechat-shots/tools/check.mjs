import fs from 'node:fs';
import vm from 'node:vm';
const files = ['app/js/core.js', 'app/js/zip.js', 'app/js/pipeline.js', 'app/js/app.js'];
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  try { new vm.SourceTextModule(src, { identifier: f }); console.log('PARSE OK  ', f); }
  catch (e) { console.log('PARSE FAIL', f, e.message); }
}
const html = fs.readFileSync('app/index.html', 'utf8');
const ids = new Set([...html.matchAll(/id="([^"]+)"/g)].map((m) => m[1]));
const appjs = fs.readFileSync('app/js/app.js', 'utf8');
const used = new Set([...appjs.matchAll(/\$\('([^']+)'\)/g)].map((m) => m[1]));
const missing = [...used].filter((u) => !ids.has(u));
console.log('html ids', ids.size, 'used', used.size, 'missing', JSON.stringify(missing));
const unused = [...ids].filter((i) => !used.has(i) && i !== 'file');
console.log('html ids not referenced by $():', JSON.stringify(unused));
