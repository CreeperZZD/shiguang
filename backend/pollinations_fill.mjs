/*
 * 补图脚本：为没有封面的菜谱用 pollinations.ai 生成真实感菜品照片并落盘 covers/recipe-<id>.jpg
 * 用法：node pollinations_fill.mjs [limit]
 */
import fs from 'node:fs';
import path from 'node:path';
import url from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { db } = require('./db.js');

const COVER_DIR = path.join(path.dirname(url.fileURLToPath(import.meta.url)), 'covers');
if (!fs.existsSync(COVER_DIR)) fs.mkdirSync(COVER_DIR, { recursive: true });

const limit = Number(process.argv[2] || 0);
const missing = [];
for (const r of db.prepare('SELECT id, name FROM recipes ORDER BY id').all()) {
  if (!fs.existsSync(path.join(COVER_DIR, `recipe-${r.id}.jpg`))) missing.push(r);
}
const list = limit > 0 ? missing.slice(0, limit) : missing;
console.log('待补图:', list.length, '道');

let ok = 0, fail = 0;
for (const [i, r] of list.entries()) {
  const prompt = encodeURIComponent(
    `Chinese home cooking dish "${r.name}", plated on a table, realistic food photography, appetizing, natural light`,
  );
  const u = `https://image.pollinations.ai/prompt/${prompt}?width=512&height=384&nologo=true&seed=${r.id}`;
  try {
    const resp = await fetch(u, { signal: AbortSignal.timeout(60000) });
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const buf = Buffer.from(await resp.arrayBuffer());
    if (buf.length < 5000) throw new Error('image too small: ' + buf.length);
    fs.writeFileSync(path.join(COVER_DIR, `recipe-${r.id}.jpg`), buf);
    ok++;
    console.log(`[${i + 1}/${list.length}] ok  ${r.name} (${buf.length}B)`);
  } catch (e) {
    fail++;
    console.log(`[${i + 1}/${list.length}] FAIL ${r.name}: ${e.message}`);
  }
  await new Promise((r2) => setTimeout(r2, 400));
}
console.log(`完成：成功 ${ok}，失败 ${fail}`);
