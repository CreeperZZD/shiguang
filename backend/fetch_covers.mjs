#!/usr/bin/env node
// ============================================================
// fetch_covers.mjs — "食光"食谱 APP 菜谱封面抓取
// 图片来源：Wikimedia Commons（免费可商用，manifest 记录作者与许可 credit）
//
// 三种模式：
//   1) node fetch_covers.mjs --dump-recipes
//      服务器上跑：从 SQLite 只读导出 [{id,name}]，JSON 打到 stdout
//   2) node fetch_covers.mjs --search-seed <recipes.json> <seed.json>
//      本机跑：经代理（SEARCH_PROXY，默认 socks5h://127.0.0.1:7897）逐菜
//      调用 Commons 搜索 API（curl 子进程），生成/续写种子清单 seed.json
//   3) node fetch_covers.mjs [--seed <seed.json>]
//      服务器上跑：下载缩略图到 covers/recipe-<id>.jpg，生成 covers-manifest.json
//      - 有 seed（默认 /tmp/covers-seed.json，读取后即删除）：按 seed 的 URL 下载
//      - 无 seed：直连 Commons API 逐菜搜索后下载（原任务逻辑）
//      - 断点续跑：文件已存在且 manifest 已记录的菜自动跳过
//
// 网络现实说明：
//   服务器（阿里云国内）无法直连 Wikimedia（TLS 握手被阻断）。
//   图片下载经 wsrv.nl 图片代理中转（图片内容仍来自 Commons 原始 URL，
//   wsrv 仅作网络中转与缩放；署名/许可不变，credit 原样记录）。
//   wsrv 上游偶发 429 限流：对 HTTP 429/5xx 做退避重试，并在缩略图失败后
//   降级为"原图 URL + wsrv 缩放到 640"。
// ============================================================

import { mkdirSync, existsSync, statSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileP = promisify(execFile);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.error(new Date().toISOString().slice(11, 19), ...a);

const HERE = dirname(fileURLToPath(import.meta.url));
const COVER_DIR = join(HERE, 'covers');
const MANIFEST_PATH = join(HERE, 'covers-manifest.json');
const DB_PATH = join(HERE, 'data', 'shiguang.db');

const UA = 'ShiGuangRecipeApp/0.3 (personal recipe app; contact: user)';
const API = 'https://commons.wikimedia.org/w/api.php';
const API_SLEEP_MS = 1200;      // API 请求间隔
const DL_SLEEP_MS = 800;        // 下载间隔
const HTTP_TIMEOUT_MS = 20000;  // 单请求超时 20s
const WSRV = 'https://wsrv.nl/?url=';
const SEARCH_PROXY = process.env.SEARCH_PROXY || 'socks5h://127.0.0.1:7897';
const DEFAULT_SEED = '/tmp/covers-seed.json';

// 跳过明显不是菜品照片的结果：地图/标志/图标/旗帜/图表等
const BAD_TITLE = /\b(map|logo|icon|flag|coat[ _-]?of[ _-]?arms|seal|chart|diagram|schema)\b/i;

function stripHtml(s) {
  return String(s ?? '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&[a-z0-9#]+;/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function buildApiUrl(name) {
  return `${API}?action=query&generator=search&gsrsearch=${encodeURIComponent(name)}`
    + `&gsrnamespace=6&gsrlimit=5&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=640&format=json&origin=*`;
}

// 从 Commons API 响应中按相关度挑第一个可用的 jpg/jpeg/png 结果
function pickResult(data) {
  const pages = data?.query?.pages ? Object.values(data.query.pages) : [];
  pages.sort((a, b) => (a.index ?? 1e9) - (b.index ?? 1e9));
  for (const p of pages) {
    const title = String(p.title ?? '');
    const info = Array.isArray(p.imageinfo) ? p.imageinfo[0] : null;
    if (!info) continue;
    if (!/\.(jpe?g|png)$/i.test(title)) continue;            // 只要 jpg/jpeg/png（跳过 svg/gif 等）
    if (BAD_TITLE.test(title)) continue;                      // 跳过地图/标志/图表
    const clean = (u) => String(u ?? '').split('?')[0];
    const thumb = clean(info.thumburl);
    if (!thumb || !/\.(jpe?g|png)$/i.test(thumb)) continue;
    const meta = info.extmetadata ?? {};
    const credit = [stripHtml(meta.Artist?.value), stripHtml(meta.LicenseShortName?.value)]
      .filter(Boolean).join(' · ');
    return { thumburl: thumb, origurl: clean(info.url), credit, title };
  }
  return null;
}

/* ---------------- 数据库（只读） ---------------- */
async function readRecipes() {
  const sql = 'SELECT id, name FROM recipes ORDER BY id';
  try {
    const { DatabaseSync } = await import('node:sqlite');
    let rows;
    try {
      const db = new DatabaseSync(DB_PATH, { readOnly: true });
      try { rows = db.prepare(sql).all(); } finally { db.close(); }
    } catch {
      // 个别 Node 版本选项名不同，退化为默认只读用法（脚本仅 SELECT，从不写库）
      const db = new DatabaseSync(DB_PATH);
      try { rows = db.prepare(sql).all(); } finally { db.close(); }
    }
    return rows.map((r) => ({ id: Number(r.id), name: String(r.name ?? '') }));
  } catch (e) {
    log(`[db] node:sqlite 不可用（${e.message}），回退 better-sqlite3`);
    const { createRequire } = await import('node:module');
    const req = createRequire(import.meta.url);
    const Database = req('better-sqlite3');
    const db = new Database(DB_PATH, { readonly: true });
    try { return db.prepare(sql).all().map((r) => ({ id: Number(r.id), name: String(r.name ?? '') })); }
    finally { db.close(); }
  }
}

/* ---------------- HTTP 通道 ---------------- */
// 服务器直连（默认模式使用；网络被墙时仅作兜底）
async function fetchJsonDirect(url) {
  const res = await fetch(url, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(HTTP_TIMEOUT_MS) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

// 本机搜索模式：curl 子进程 + socks5 代理（--retry 处理代理/API 瞬时抖动）
async function fetchJsonCurl(url) {
  const { stdout } = await execFileP('curl',
    ['-s', '--fail', '-m', '25', '--retry', '1', '--retry-delay', '3', '--retry-all-errors',
     '-x', SEARCH_PROXY, '-A', UA, url],
    { maxBuffer: 64 * 1024 * 1024, windowsHide: true });
  return JSON.parse(stdout);
}

function makeSearcher(fetchJson) {
  return async function searchWithRetry(name) {
    try {
      return pickResult(await fetchJson(buildApiUrl(name)));
    } catch (e) {
      log(`  搜索失败（${e.message}），重试 1 次...`);
      await sleep(800);
      return pickResult(await fetchJson(buildApiUrl(name))); // 再抛错则由外层记为失败
    }
  };
}

/* ---------------- 模式 1：导出菜谱 ---------------- */
async function modeDump() {
  const rows = await readRecipes();
  log(`从 SQLite 导出 ${rows.length} 条菜谱到 stdout`);
  process.stdout.write(JSON.stringify(rows));
}

/* ---------------- 模式 2：本机搜索生成 seed ---------------- */
async function modeSearchSeed(recipesPath, seedPath) {
  const recipes = JSON.parse(readFileSync(recipesPath, 'utf8'));
  if (!Array.isArray(recipes) || recipes.length === 0) throw new Error('recipes 文件为空或格式不对');
  const search = makeSearcher(fetchJsonCurl);

  const byId = new Map();
  if (existsSync(seedPath)) {
    try {
      const old = JSON.parse(readFileSync(seedPath, 'utf8'));
      if (Array.isArray(old)) for (const s of old) if (s && s.id != null) byId.set(Number(s.id), s);
      if (byId.size) log(`seed 断点续跑：已有 ${byId.size} 条`);
    } catch { log('旧 seed 解析失败，重建'); }
  }

  let ok = 0, noMatch = 0, fail = 0, skip = 0;
  for (let i = 0; i < recipes.length; i++) {
    const { id, name } = recipes[i];
    // 只跳过已命中条目；search-failed 的条目在后续重试轮中补搜
    if (byId.get(id)?.thumburl) { skip++; continue; }
    try {
      const hit = await search(name);
      if (hit) {
        ok++;
        byId.set(id, { id, name, thumburl: hit.thumburl, origurl: hit.origurl, credit: hit.credit, file: hit.title, source: 'wikimedia' });
        log(`[${i + 1}/${recipes.length}] 命中 ${name} <- ${hit.title}`);
      } else {
        noMatch++;
        byId.set(id, { id, name, cover: null, reason: 'no-match' });
        log(`[${i + 1}/${recipes.length}] 无匹配 ${name}`);
      }
    } catch (e) {
      fail++;
      byId.set(id, { id, name, cover: null, reason: 'search-failed', error: String(e.message ?? e) });
      log(`[${i + 1}/${recipes.length}] 失败 ${name}（${e.message ?? e}）`);
    }
    writeFileSync(seedPath, JSON.stringify([...byId.values()], null, 2)); // 增量落盘，可断点续跑
    await sleep(API_SLEEP_MS);
  }

  // 失败重试轮（最多 2 轮）：只补搜 search-failed 条目（瞬时网络抖动通常一轮即可恢复）
  for (let round = 1; round <= 2; round++) {
    const failed = recipes.filter((r) => byId.get(r.id)?.reason === 'search-failed');
    if (failed.length === 0) break;
    log(`--- 第 ${round} 轮失败重试：${failed.length} 条 ---`);
    await sleep(5000);
    for (let i = 0; i < failed.length; i++) {
      const { id, name } = failed[i];
      try {
        const hit = await search(name);
        if (hit) {
          ok++; fail--;
          byId.set(id, { id, name, thumburl: hit.thumburl, origurl: hit.origurl, credit: hit.credit, file: hit.title, source: 'wikimedia' });
          log(`  重试命中 ${name} <- ${hit.title}`);
        } else {
          fail--; noMatch++;
          byId.set(id, { id, name, cover: null, reason: 'no-match' });
          log(`  重试无匹配 ${name}`);
        }
      } catch (e) {
        log(`  重试仍失败 ${name}（${e.message ?? e}）`);
      }
      writeFileSync(seedPath, JSON.stringify([...byId.values()], null, 2));
      await sleep(API_SLEEP_MS);
    }
  }

  log(`===== seed 统计 ===== 命中 ${ok} / 无匹配 ${noMatch} / 失败 ${fail} / 跳过 ${skip}（共 ${recipes.length}）`);
  log(`seed 已写入：${seedPath}`);
}

/* ---------------- 模式 3：服务器下载 + manifest ---------------- */
// thumb URL → 原图 URL（去掉 /thumb/ 段与尾部 /960px-xxx），用于 wsrv 缩放兜底
function thumbToOriginal(thumburl) {
  try {
    const parts = new URL(thumburl).pathname.split('/').filter(Boolean);
    if (parts.length >= 6 && parts[2] === 'thumb') {
      const base = parts.filter((_, idx) => idx !== 2 && idx !== parts.length - 1).join('/');
      return `https://upload.wikimedia.org/${base}`;
    }
  } catch { /* 忽略，返回 null */ }
  return null;
}

// 经 wsrv.nl 代理下载一张图并写盘（返回字节数）
async function downloadOne(url, dest) {
  const proxied = WSRV + encodeURIComponent(url) + '&w=640&output=jpg&q=82';
  const res = await fetch(proxied, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(HTTP_TIMEOUT_MS) });
  if (res.status === 429 || res.status >= 500) throw Object.assign(new Error(`HTTP ${res.status}（wsrv 上游限流/错误）`), { backoff: true });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const ct = (res.headers.get('content-type') ?? '').toLowerCase();
  if (!ct.startsWith('image/')) throw new Error(`Content-Type 非 image/*（${ct || '空'}）`);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length < 2048) throw new Error(`文件过小（${buf.length}B），疑似错误响应`);
  writeFileSync(dest, buf);
  return buf.length;
}

// 候选源依次尝试，每个源失败重试 1 次；429/5xx 退避 5s
async function downloadWithRetry(candidates, dest) {
  let lastErr = null;
  for (const u of candidates) {
    if (!u) continue;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        return await downloadOne(u, dest);
      } catch (e) {
        lastErr = e;
        if (attempt === 0) {
          log(`  下载失败（${e.message}），重试 1 次...`);
          if (e.backoff) await sleep(5000);
          else await sleep(DL_SLEEP_MS);
        }
      }
    }
  }
  throw lastErr ?? new Error('无可用下载地址');
}

async function modeFetch(seedPath) {
  const t0 = Date.now();
  mkdirSync(COVER_DIR, { recursive: true });

  const recipes = await readRecipes();
  if (recipes.length === 0) throw new Error('recipes 表为空，退出');
  log(`[db] 共 ${recipes.length} 道菜`);

  // 载入 seed（存在则优先；读取后立即删除该临时文件）
  let seedMap = null;
  if (seedPath && existsSync(seedPath)) {
    const seed = JSON.parse(readFileSync(seedPath, 'utf8'));
    seedMap = new Map((Array.isArray(seed) ? seed : []).filter((s) => s && s.id != null).map((s) => [Number(s.id), s]));
    log(`[seed] 载入 ${seedMap.size} 条（${seedPath}）`);
    try { unlinkSync(seedPath); log('[seed] 临时种子文件已删除'); } catch { /* 忽略 */ }
  } else {
    log('[seed] 未提供种子文件，尝试直连 Commons API（网络被墙时此路径会失败）');
  }

  const search = makeSearcher(fetchJsonDirect);

  // 断点续跑：加载旧 manifest
  const byId = new Map();
  if (existsSync(MANIFEST_PATH)) {
    try {
      const old = JSON.parse(readFileSync(MANIFEST_PATH, 'utf8'));
      if (Array.isArray(old)) for (const m of old) if (m && Number.isInteger(m.id)) byId.set(m.id, m);
      if (byId.size) log(`[resume] 旧 manifest 载入 ${byId.size} 条`);
    } catch { log('[resume] 旧 manifest 损坏，重建'); }
  }

  let ok = 0, skip = 0, noMatch = 0, fail = 0;

  const writeManifest = () =>
    writeFileSync(MANIFEST_PATH, JSON.stringify(recipes.map((r) => byId.get(r.id)).filter(Boolean), null, 2));

  for (let i = 0; i < recipes.length; i++) {
    const { id, name } = recipes[i];
    const dest = join(COVER_DIR, `recipe-${id}.jpg`);
    const tag = `[${i + 1}/${recipes.length}]`;
    const old = byId.get(id);

    if (old?.cover && existsSync(dest) && statSync(dest).size > 0) {
      skip++; ok++;
      log(`${tag} 跳过（已完成） ${name}`);
      continue;
    }

    let entry;
    try {
      // 确定图片来源
      let hit = null;
      if (seedMap) {
        const s = seedMap.get(id);
        if (s?.thumburl) {
          hit = { thumburl: s.thumburl, origurl: s.origurl ?? thumbToOriginal(s.thumburl), credit: s.credit ?? '' };
        } else if (s) {
          noMatch++; entry = { id, name, cover: null };
          log(`${tag} 无匹配（seed） ${name}`);
        } else {
          hit = await search(name); // seed 缺项：直连兜底
        }
      } else {
        hit = await search(name);
      }

      if (hit) {
        if (existsSync(dest) && statSync(dest).size > 0) {
          ok++;
          entry = { id, name, cover: `/covers/recipe-${id}.jpg`, credit: hit.credit, source: 'wikimedia' };
          log(`${tag} 复用已存在文件 ${name}`);
        } else {
          await sleep(DL_SLEEP_MS);
          const size = await downloadWithRetry([hit.thumburl, hit.origurl], dest);
          ok++;
          entry = { id, name, cover: `/covers/recipe-${id}.jpg`, credit: hit.credit, source: 'wikimedia' };
          log(`${tag} 下载成功 ${name}（${(size / 1024).toFixed(0)}KB | ${hit.credit || 'credit 无'}）`);
        }
      } else if (!entry) {
        noMatch++; entry = { id, name, cover: null };
        log(`${tag} 无匹配 ${name}`);
      }
    } catch (e) {
      fail++;
      entry = { id, name, cover: null };
      log(`${tag} 失败 ${name}（${e.message ?? e}）`);
    }

    byId.set(id, entry);
    writeManifest(); // 每菜增量写盘，随时可断点续跑
    if (!seedMap) await sleep(API_SLEEP_MS);
  }

  // 终检：读回 manifest 校验结构
  const final = JSON.parse(readFileSync(MANIFEST_PATH, 'utf8'));
  const valid = Array.isArray(final) && final.length === recipes.length && final.every((m) =>
    m && Number.isInteger(m.id) && typeof m.name === 'string' && m.name.length > 0 &&
    (m.cover === null || (typeof m.cover === 'string' && m.cover.startsWith('/covers/recipe-') && m.source === 'wikimedia')));
  log(valid ? '[check] MANIFEST-VALID：JSON 解析与结构校验通过' : '[check] MANIFEST-INVALID：结构异常！');

  const min = ((Date.now() - t0) / 60000).toFixed(1);
  log('===== 统计 =====');
  log(`成功: ${ok}（其中断点跳过 ${skip}）`);
  log(`无匹配: ${noMatch}`);
  log(`失败: ${fail}`);
  log(`合计: ${recipes.length}，耗时 ${min} 分钟`);
  log(`manifest: ${MANIFEST_PATH}`);
  if (!valid) process.exit(2);
}

/* ---------------- 入口 ---------------- */
const argv = process.argv.slice(2);
try {
  if (argv[0] === '--dump-recipes') {
    await modeDump();
  } else if (argv[0] === '--search-seed') {
    if (!argv[1] || !argv[2]) throw new Error('用法: node fetch_covers.mjs --search-seed <recipes.json> <seed.json>');
    await modeSearchSeed(argv[1], argv[2]);
  } else {
    const seedPath = argv[0] === '--seed' ? (argv[1] || DEFAULT_SEED) : DEFAULT_SEED;
    await modeFetch(seedPath);
  }
} catch (e) {
  console.error('FATAL', e?.stack ?? e);
  process.exit(1);
}
