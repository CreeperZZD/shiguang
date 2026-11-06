'use strict';
/*
 * 万相补图：对 covers/ 缺图的菜谱，用 DashScope wanx2.1-t2i-turbo 生成菜品照片并落盘。
 * 密钥复用 secrets/llm.json 的 key（阿里云百炼）。断点续跑：已存在文件跳过。
 * 用法：node wanx_fill.cjs [limit]
 */
const fs = require('node:fs');
const path = require('node:path');
const { db } = require('./db.js');

const SECRETS_PATH = process.env.SHIGUANG_SECRETS || '/opt/recipe-app/secrets/llm.json';
const KEY = JSON.parse(fs.readFileSync(SECRETS_PATH, 'utf8')).key;
const COVER_DIR = path.join(__dirname, 'covers');
if (!fs.existsSync(COVER_DIR)) fs.mkdirSync(COVER_DIR, { recursive: true });

const limit = Number(process.argv[2] || 0);
const missing = [];
for (const r of db.prepare('SELECT id, name FROM recipes ORDER BY id').all()) {
  if (!fs.existsSync(path.join(COVER_DIR, `recipe-${r.id}.jpg`))) missing.push(r);
}
const list = limit > 0 ? missing.slice(0, limit) : missing;
console.log(`[wanx] 待补图 ${list.length} 道`);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function genImage(name, id) {
  const prompt = `中餐家常菜「${name}」，家常盛装在白瓷盘/瓷碗中，摆在木餐桌上，配米饭和筷子，真实食物摄影，自然光，食欲感，高清`;
  const create = await fetch('https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + KEY, 'Content-Type': 'application/json', 'X-DashScope-Async': 'enable' },
    body: JSON.stringify({ model: 'wanx2.1-t2i-turbo', input: { prompt }, parameters: { size: '1024*768', n: 1 } }),
  });
  if (!create.ok) throw new Error('create HTTP ' + create.status + ' ' + (await create.text()).slice(0, 200));
  const cj = await create.json();
  const taskId = cj.output && cj.output.task_id;
  if (!taskId) throw new Error('no task_id');
  for (let t = 0; t < 40; t++) {
    await sleep(2500);
    const poll = await fetch('https://dashscope.aliyuncs.com/api/v1/tasks/' + taskId, {
      headers: { Authorization: 'Bearer ' + KEY },
    });
    const pj = await poll.json();
    const st = pj.output && pj.output.task_status;
    if (st === 'SUCCEEDED') {
      const urls = pj.output.results && pj.output.results.map((x) => x.url).filter(Boolean);
      if (!urls || !urls.length) throw new Error('no result url');
      const img = await fetch(urls[0]);
      const buf = Buffer.from(await img.arrayBuffer());
      if (buf.length < 10000) throw new Error('image too small');
      return buf;
    }
    if (st === 'FAILED' || st === 'CANCELED' || st === 'UNKNOWN') {
      throw new Error('task ' + st + ' ' + JSON.stringify(pj.output).slice(0, 200));
    }
  }
  throw new Error('task timeout');
}

(async () => {
  let ok = 0, fail = 0;
  for (const [i, r] of list.entries()) {
    try {
      const buf = await genImage(r.name, r.id);
      fs.writeFileSync(path.join(COVER_DIR, `recipe-${r.id}.jpg`), buf);
      ok++;
      console.log(`[${i + 1}/${list.length}] ok  ${r.name} (${buf.length}B)`);
    } catch (e) {
      fail++;
      console.log(`[${i + 1}/${list.length}] FAIL ${r.name}: ${e.message}`);
    }
    await sleep(1200);
  }
  console.log(`[wanx] 完成：成功 ${ok}，失败 ${fail}`);
})();
