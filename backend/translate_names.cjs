'use strict';
/* 用服务器 LLM 把无封面菜谱名批量翻译成英文（Wikimedia 英文搜索命中率更高），输出 /tmp/en-names.json */
const fs = require('node:fs');
const path = require('node:path');
const { db } = require('./db.js');

const SECRETS_PATH = process.env.SHIGUANG_SECRETS || '/opt/recipe-app/secrets/llm.json';
const secrets = JSON.parse(fs.readFileSync(SECRETS_PATH, 'utf8'));
const BASE = secrets.endpoint;
const KEY = secrets.key;
const MODEL = secrets.model;

const COVER_DIR = path.join(__dirname, 'covers');
const missing = [];
for (const r of db.prepare('SELECT id, name FROM recipes ORDER BY id').all()) {
  if (!fs.existsSync(path.join(COVER_DIR, `recipe-${r.id}.jpg`))) missing.push({ id: r.id, name: r.name });
}
console.log('待翻译:', missing.length, '道');

(async () => {
  const names = missing.map((r) => r.name);
  const prompt = '把下面这些中式菜谱名翻译成简短英文（用于在免费图库搜索照片，用常见英文说法，如 黄焖鸡米饭=braised chicken rice, 酸辣土豆丝=hot and sour shredded potato）。只输出 JSON 数组，与输入一一对应，不要任何其他内容：\n' + JSON.stringify(names);
  const resp = await fetch(BASE, {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: MODEL, messages: [{ role: 'user', content: prompt }], max_tokens: 16384 }),
  });
  if (!resp.ok) throw new Error('LLM HTTP ' + resp.status + ' ' + (await resp.text()).slice(0, 200));
  const j = await resp.json();
  const msg0 = j.choices[0].message;
  let content = String(msg0.content || '').trim();
  if (!content.includes('[') && msg0.reasoning_content) content = String(msg0.reasoning_content);
  const s = content.indexOf('['), e = content.lastIndexOf(']');
  const arr = JSON.parse(content.slice(s, e + 1));
  if (!Array.isArray(arr) || arr.length !== missing.length) throw new Error('翻译数量不匹配 ' + arr.length);
  const out = missing.map((r, i) => ({ id: r.id, name: arr[i] }));
  fs.writeFileSync('/tmp/en-names.json', JSON.stringify(out, null, 1));
  console.log('已写入 /tmp/en-names.json 示例:', out.slice(0, 5).map((x) => x.id + '=' + x.name).join(', '));
})().catch((e) => { console.error(e.message); process.exit(1); });
