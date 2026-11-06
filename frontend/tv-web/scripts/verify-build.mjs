import { readdir, readFile, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../dist/', import.meta.url));
async function walk(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) files.push(...await walk(path));
    else if (entry.isFile()) files.push(path);
    else throw new Error(`Unexpected build entry: ${entry.name}`);
  }
  return files;
}
const files = await walk(root);
const receipts = [];
let totalBytes = 0, javascriptBytes = 0;
for (const path of files) {
  const name = relative(root, path);
  const buffer = await readFile(path);
  const size = (await stat(path)).size;
  totalBytes += size;
  if (/\.js$/.test(name)) javascriptBytes += size;
  if (/\.map$/.test(name)) throw new Error(`Source map must not ship: ${name}`);
  if (/\.(js|html|css)$/.test(name)) {
    const text = buffer.toString('utf8');
    if (/FIXTURE_ONLY|fixture_0|合成开发资料/.test(text)) throw new Error(`Development fixture found in ${name}`);
    if (name.endsWith('.html')) {
      for (const match of text.matchAll(/<(?:script|link)\b[^>]*(?:src|href)\s*=\s*["']([^"']+)/gi)) {
        if (!match[1].startsWith('./assets/')) throw new Error(`Non-local entry asset: ${match[1]}`);
      }
    }
  }
  receipts.push({ file: name, bytes: size, sha256: createHash('sha256').update(buffer).digest('hex') });
}
if (!receipts.some(file => file.file === 'index.html')) throw new Error('Missing index.html');
if (!receipts.some(file => file.file === 'THIRD_PARTY_LICENSES.txt')) throw new Error('Missing bundled dependency licenses');
if (javascriptBytes > 1024 * 1024) throw new Error(`JavaScript exceeds 1 MiB: ${javascriptBytes}`);
if (totalBytes > 2 * 1024 * 1024) throw new Error(`Web bundle exceeds 2 MiB: ${totalBytes}`);
console.log(JSON.stringify({ schema: 1, javascriptBytes, totalBytes, files: receipts }, null, 2));
