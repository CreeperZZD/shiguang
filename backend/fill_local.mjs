/*
 * 本机补图：对 miss.json 里的菜谱用 pollinations.ai 本机直连下载到 local-covers/，
 * 之后由上传脚本推到服务器 covers/。
 */
import fs from 'node:fs';
import path from 'node:path';
import url from 'node:url';

const here = path.dirname(url.fileURLToPath(import.meta.url));
const outDir = path.join(here, 'local-covers');
if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

const list = JSON.parse(fs.readFileSync(path.join(here, 'miss.json'), 'utf8'));
console.log('待补图:', list.length);
let ok = 0, fail = 0;
for (const [i, r] of list.entries()) {
  const f = path.join(outDir, `recipe-${r.id}.jpg`);
  if (fs.existsSync(f) && fs.statSync(f).size > 5000) { ok++; continue; }
  const prompt = encodeURIComponent(
    `Chinese home cooking dish "${r.name}", plated, realistic food photography, appetizing, natural light`,
  );
  const u = `https://image.pollinations.ai/prompt/${prompt}?width=512&height=384&nologo=true&seed=${r.id}`;
  try {
    const resp = await fetch(u, { signal: AbortSignal.timeout(60000) });
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const buf = Buffer.from(await resp.arrayBuffer());
    if (buf.length < 5000) throw new Error('too small ' + buf.length);
    fs.writeFileSync(f, buf);
    ok++;
    console.log(`[${i + 1}/${list.length}] ok  ${r.name} (${buf.length}B)`);
  } catch (e) {
    fail++;
    console.log(`[${i + 1}/${list.length}] FAIL ${r.name}: ${e.message}`);
  }
  await new Promise((r2) => setTimeout(r2, 800));
}
console.log(`本机完成：成功 ${ok}，失败 ${fail}`);
