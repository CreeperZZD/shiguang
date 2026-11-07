'use strict';
/*
 * 食光 (Shiguang) 食谱 APP 后端 — M1 骨架
 * 零 npm 依赖：仅使用 Node 内置模块（http / node:sqlite / crypto / fs）。
 * - 监听 0.0.0.0:8000
 * - 数据：backend/data/shiguang.db（node:sqlite，首启自动导入种子菜谱）
 * - LLM 凭据：只从 /opt/recipe-app/secrets/llm.json 读取（chmod 600），绝不写入代码/日志
 */

const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { db } = require('./db');

const PORT = Number(process.env.PORT || 8000);
const HOST = process.env.HOST || '0.0.0.0';
const SECRETS_PATH = process.env.SHIGUANG_SECRETS || '/opt/recipe-app/secrets/llm.json';
const SECRETS_DIR = path.dirname(SECRETS_PATH);
const PAGE_SIZE = 10;
const START_TS = Date.now();

/* ---------------- 基础工具 ---------------- */

class HttpError extends Error {
  constructor(status, code, detail) { super(code); this.status = status; this.code = code; this.detail = detail; }
}
const bad = (detail) => new HttpError(400, 'bad_request', detail);
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const round2 = (x) => Math.round(x * 100) / 100;
const pad2 = (n) => String(n).padStart(2, '0');
const nullish = (v) => (v === undefined ? null : v);

function sendJSON(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function readJSON(req, limit = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(bad('body_too_large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        resolve(parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {});
      } catch { reject(bad('invalid_json_body')); }
    });
    req.on('error', () => reject(bad('body_read_error')));
  });
}

/* ---------------- 认证 ---------------- */

function getUserByToken(req) {
  const h = req.headers['authorization'] || '';
  const m = /^Bearer\s+(.+)$/i.exec(h);
  if (!m) return null;
  return db.prepare(`SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id
    WHERE s.token = ? AND (s.expires_at IS NULL OR s.expires_at > datetime('now','localtime'))`).get(m[1].trim()) || null;
}
function requireAuth(req) {
  const u = getUserByToken(req);
  if (!u) throw new HttpError(401, 'unauthorized', '缺少或无效的 Bearer token');
  return u;
}
function publicUser(u) {
  return {
    id: u.id, phone: u.phone, email: u.email || null, nickname: u.nickname, gender: u.gender, age: u.age,
    height_cm: u.height_cm, weight_kg: u.weight_kg, activity: u.activity, goal: u.goal,
    created_at: u.created_at,
  };
}

/* ---------------- 安全防护：IP 限流 + 可选配置 ---------------- */

function clientIp(req) {
  return String(req.socket.remoteAddress || 'unknown').replace(/^::ffff:/, '').replace(/^::1$/, '127.0.0.1');
}
const rateBuckets = new Map(); // key -> { count, resetAt }
function rateLimit(key, limit, windowMs) {
  const now = Date.now();
  let b = rateBuckets.get(key);
  if (!b || now > b.resetAt) { b = { count: 0, resetAt: now + windowMs }; rateBuckets.set(key, b); }
  b.count += 1;
  return b.count <= limit;
}
setInterval(() => {
  const now = Date.now();
  for (const [k, b] of rateBuckets) if (now > b.resetAt) rateBuckets.delete(k);
}, 5 * 60 * 1000).unref();

/* 可选配置：secrets/config.json（缺省全部兼容旧行为） */
let _appConfig = null;
function appConfig() {
  if (_appConfig) return _appConfig;
  try { _appConfig = JSON.parse(fs.readFileSync(path.join(SECRETS_DIR, 'config.json'), 'utf8')); }
  catch { _appConfig = {}; }
  return _appConfig;
}

/* ---------------- SMTP 邮箱验证码（零依赖手写 SMTPS，QQ 邮箱等均可） ---------------- */

let _smtpConfig = null;
function smtpConfig() {
  if (_smtpConfig !== null) return _smtpConfig;
  try {
    const cfg = JSON.parse(fs.readFileSync(path.join(SECRETS_DIR, 'smtp.json'), 'utf8'));
    _smtpConfig = cfg && cfg.user && cfg.pass && cfg.from ? cfg : null;
  } catch { _smtpConfig = null; }
  return _smtpConfig;
}

function smtpSend(cfg, to, subject, text) {
  const tls = require('node:tls');
  return new Promise((resolve, reject) => {
    const cmds = [
      `EHLO shiguang.local`,
      'AUTH LOGIN',
      Buffer.from(cfg.user).toString('base64'),
      Buffer.from(cfg.pass).toString('base64'),
      `MAIL FROM:<${cfg.from}>`,
      `RCPT TO:<${to}>`,
      'DATA',
      'From: =?UTF-8?B?' + Buffer.from('食光').toString('base64') + '?= <' + cfg.from + '>\r\n' +
      'To: <' + to + '>\r\n' +
      'Subject: =?UTF-8?B?' + Buffer.from(subject).toString('base64') + '?=\r\n' +
      'MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n' +
      text.replace(/\r?\n/g, '\r\n') + '\r\n.',
      'QUIT',
    ];
    const okCodes = [[220], [250], [334], [334], [235], [250], [250, 251], [354], [250], [221]];
    let idx = 0;
    let buf = '';
    let done = false;
    const sock = tls.connect({ host: cfg.host || 'smtp.qq.com', port: cfg.port || 465, rejectUnauthorized: false }, () => { });
    sock.setEncoding('utf8');
    const fail = (e) => { if (!done) { done = true; try { sock.destroy(); } catch { } reject(e); } };
    sock.on('error', (e) => fail(new HttpError(502, 'smtp_error', '邮件服务暂时不可用')));
    sock.on('data', (chunk) => {
      buf += chunk;
      if (!/\r\n$/.test(buf)) return; // 等一整条响应
      const code = parseInt(buf.slice(0, 3), 10);
      buf = '';
      if (isNaN(code)) return fail(new HttpError(502, 'smtp_error', '邮件服务响应异常'));
      const allowed = okCodes[idx] || [250];
      if (!allowed.includes(code)) return fail(new HttpError(502, 'smtp_error', '邮件发送失败(' + code + ')'));
      const cmd = cmds[idx]; idx += 1;
      if (!cmd) { if (!done) { done = true; sock.end(); resolve(); } return; }
      sock.write(cmd + '\r\n');
    });
    sock.on('close', () => { if (!done) { done = true; resolve(); } });
    setTimeout(() => fail(new HttpError(502, 'smtp_timeout', '邮件发送超时')), 15000).unref?.();
  });
}

/* ---------------- 身体数据计算 ---------------- */

const ACTIVITY_ENUM = {
  sedentary: 1.2, light: 1.375, moderate: 1.55, active: 1.725, very_active: 1.9,
  '久坐': 1.2, '轻度活动': 1.375, '中度活动': 1.55, '高度活动': 1.725, '极高活动': 1.9,
};
function parseActivity(v) {
  if (isNum(v)) return v >= 1.2 && v <= 1.9 ? round2(v) : null;
  if (typeof v === 'string') {
    const k = v.trim().toLowerCase();
    if (ACTIVITY_ENUM[k]) return ACTIVITY_ENUM[k];
    const n = Number(k);
    if (Number.isFinite(n) && n >= 1.2 && n <= 1.9) return round2(n);
  }
  return null;
}
/* Mifflin-St Jeor：男 10w + 6.25h - 5a + 5；女 10w + 6.25h - 5a - 161 */
function healthProfile(u) {
  const p = {};
  if (isNum(u.height_cm) && isNum(u.weight_kg)) {
    const h = u.height_cm / 100;
    p.bmi = round2(u.weight_kg / (h * h));
    if (Number.isInteger(u.age) && (u.gender === 'm' || u.gender === 'f')) {
      const bmr = 10 * u.weight_kg + 6.25 * u.height_cm - 5 * u.age + (u.gender === 'm' ? 5 : -161);
      p.bmr = round2(bmr);
      const act = isNum(u.activity) ? u.activity : 1.55;
      p.activity_factor = act;
      p.tdee = round2(bmr * act);
      const goal = u.goal || 'keep';
      p.goal = goal;
      p.target_calories = round2(goal === 'lose' ? p.tdee - 400 : goal === 'gain' ? p.tdee + 300 : p.tdee);
      p.target_rule = goal === 'lose' ? 'TDEE-400' : goal === 'gain' ? 'TDEE+300' : 'TDEE';
    }
  }
  return p;
}

/* ---------------- 确定性随机（按日期做种子，同日同参数结果稳定） ---------------- */

function xmur3(str) {
  let h = 1779033703 ^ str.length;
  for (let i = 0; i < str.length; i++) {
    h = Math.imul(h ^ str.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  return function () {
    h = Math.imul(h ^ (h >>> 16), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    return (h ^= h >>> 16) >>> 0;
  };
}
function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function rngFrom(str) { return mulberry32(xmur3(str)()); }
function localToday() { const d = new Date(); return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`; }
function addDays(ymd, n) {
  const d = new Date(ymd + 'T12:00:00');
  d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}
const WEEKDAY_CN = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

/* ---------------- 菜谱输出 ---------------- */

/* 真实菜品封面：covers/ 目录下 recipe-<id>.jpg（Wikimedia 爬虫产物），存在即返回相对 URL */
const COVER_DIR = path.join(__dirname, 'covers');
let _coverSet = null;
function coverSet() {
  if (_coverSet) return _coverSet;
  try { _coverSet = new Set(fs.readdirSync(COVER_DIR).filter((f) => f.endsWith('.jpg'))); }
  catch { _coverSet = new Set(); }
  return _coverSet;
}
function coverFor(id) {
  const f = `recipe-${id}.jpg`;
  return coverSet().has(f) ? `/covers/${f}` : null;
}

function recipeOut(row, state) {
  let ingredients = [];
  let steps = [];
  try { ingredients = JSON.parse(row.ingredients_json); } catch { }
  try { steps = JSON.parse(row.steps_json); } catch { }
  return {
    id: row.id, name: row.name, intro: row.intro, category: row.category, meat_tag: row.meat_tag,
    difficulty: row.difficulty, minutes: row.minutes, calorie_tier: row.calorie_tier,
    ingredients, steps,
    nutrition: { kcal: row.kcal, protein: row.protein, fat: row.fat, carb: row.carb },
    views: Number(row.views), like_count: Number(row.like_count), favorite_count: Number(row.favorite_count),
    cover_url: coverFor(row.id),
    source: row.source || 'official',
    liked: !!(state && state.liked),
    favorited: !!(state && state.favorited),
  };
}
function mealOut(row, servings, state) {
  return { recipe: recipeOut(row, state), servings, kcal: round2(row.kcal * servings) };
}

/* 结构化一餐输出：dishes 数组（含份量标签）；recipe/servings/kcal 取首菜保持旧客户端兼容 */
function mealOutMulti(items, state) {
  const dishes = items.map((it) => ({
    recipe: recipeOut(it.r, state && state.get(it.r.id)),
    servings: it.servings,
    kcal: round2(it.r.kcal * it.servings),
    serving_label: servingLabel(it.servings),
  }));
  const first = dishes[0];
  return {
    recipe: first.recipe,
    servings: first.servings,
    kcal: dishes.reduce((s, d) => s + d.kcal, 0),
    dishes,
  };
}

function parseMealCount(query, key) {
  const raw = query.get(key);
  if (raw === null) return 1;
  const n = Math.round(Number(raw));
  if (!Number.isFinite(n) || n < 1 || n > 4) throw bad(`${key} 需为 1-4 的整数`);
  return n;
}
function loadAllRecipes() { return db.prepare('SELECT * FROM recipes ORDER BY id').all(); }

/* 当前用户对若干菜谱的点赞/收藏状态（可选登录：无 token 返回空 map，字段为 false） */
function userStateMap(userId, recipeIds) {
  const map = new Map();
  if (!userId || !recipeIds || !recipeIds.length) return map;
  const uniq = [...new Set(recipeIds.map(Number))];
  const ph = uniq.map(() => '?').join(',');
  const liked = new Set(db.prepare(`SELECT recipe_id FROM likes WHERE user_id = ? AND recipe_id IN (${ph})`)
    .all(userId, ...uniq).map((r) => r.recipe_id));
  const favd = new Set(db.prepare(`SELECT recipe_id FROM favorites WHERE user_id = ? AND recipe_id IN (${ph})`)
    .all(userId, ...uniq).map((r) => r.recipe_id));
  for (const id of uniq) map.set(id, { liked: liked.has(id), favorited: favd.has(id) });
  return map;
}

/* ---------------- 三餐/周排餐规划（结构化组合版） ----------------
 * 午/晚餐支持「几菜一汤」（lunch_n/dinner_n 1~4 + soup 开关）：
 *   - 荤素搭配：n≥2 时至少 1 荤 1 素；同类菜上限 2 道（不会端出 3 碗同汤）；
 *   - 汤羹类（category=汤羹 或菜名以「汤」结尾）单独成汤位；
 *   - 份量系数 0.5~3 按各菜配比自动配平，使三餐总热量落在 target±15%；
 *   - serving_label：0.5=小份 / 1~1.5=中份 / ≥2=大份；
 * 富余热量 ≥120 且 drinks=1 时，从饮品库（非菜谱，固定热量）配一杯饮品。
 * 早/午/晚热量配比 25/40/35；同日三道菜不重复；周计划对已用菜谱加权惩罚；
 * 按日期做种子，同日结果确定。
 */
const SERVING_OPTIONS = [0.5, 1, 1, 1, 1.5, 1.5, 2, 2.5, 3]; // 重复元素=加权，偏中份
function servingLabel(s) {
  if (s <= 0.75) return '小份';
  if (s <= 1.75) return '中份';
  return '大份';
}
function servPenalty(s) {
  return s === 0.5 ? 25 : s === 1 ? 0 : s === 1.5 ? 15 : s === 2 ? 40 : s === 2.5 ? 60 : 90;
}
function nearestServing(want) {
  let best = SERVING_OPTIONS[0];
  let diff = 1e9;
  for (const s of SERVING_OPTIONS) {
    const d = Math.abs(s - want);
    if (d < diff) { diff = d; best = s; }
  }
  return best;
}

/* 饮品库：不在菜谱表内，仅用于补足富余热量 */
const DRINKS = [
  { name: '温牛奶', kcal: 140 }, { name: '无糖豆浆', kcal: 70 }, { name: '酸奶', kcal: 120 },
  { name: '鲜榨橙汁', kcal: 110 }, { name: '柠檬水', kcal: 10 }, { name: '普洱茶', kcal: 5 },
  { name: '酸梅汤', kcal: 90 }, { name: '绿豆汤', kcal: 80 }, { name: '冰红茶', kcal: 130 }, { name: '可乐', kcal: 150 },
];
function pickDrink(leftover, rng) {
  // 在热量能补进富余空间的饮品里，取最接近 leftover*0.4 的前三杯随机挑一杯
  const want = leftover * 0.4;
  const cands = DRINKS.filter((d) => d.kcal <= leftover * 0.7)
    .sort((a, b) => Math.abs(a.kcal - want) - Math.abs(b.kcal - want))
    .slice(0, 3);
  if (!cands.length) return null;
  return cands[Math.floor(rng() * cands.length)];
}

function pickDayPlan(recipes, target, rng, usedCount, opts = {}) {
  const lo = target * 0.85;
  const hi = target * 1.15;
  const lunchN = Math.min(4, Math.max(1, Math.round(opts.lunchN || 1)));
  const dinnerN = Math.min(4, Math.max(1, Math.round(opts.dinnerN || 1)));
  const withSoup = opts.soup !== false;
  const slotT = { b: target * 0.25, l: target * 0.40, d: target * 0.35 };
  const isSoup = (r) => r.category === '汤羹' || /汤$/.test(r.name);
  const soupPool = recipes.filter(isSoup);
  const dishPool = recipes.filter((r) => !isSoup(r));
  const meatPool = dishPool.filter((r) => r.meat_tag === '荤');
  const vegPool = dishPool.filter((r) => r.meat_tag === '素');
  let bPool = recipes.filter((r) => (r.calorie_tier === '低' || r.kcal <= 250) && !isSoup(r));
  if (bPool.length < 3) bPool = dishPool.slice().sort((a, b) => a.kcal - b.kcal).slice(0, 6);
  const reuse = (r) => usedCount.get(r.id) || 0;
  const pick = (arr) => arr[Math.floor(rng() * arr.length)];

  /* 组一餐：n 道菜 +（可选）1 汤；荤素搭配、同类去重、避开 excludeIds */
  function composeMeal(n, soup, excludeIds) {
    const items = [];
    const used = new Set(excludeIds || []);
    const cats = new Map();
    const tryPick = (pool) => {
      for (let k = 0; k < 24; k++) {
        const r = pool.length ? pick(pool) : null;
        if (!r || used.has(r.id)) continue;
        if ((cats.get(r.category) || 0) >= 2) continue; // 同一类最多 2 道
        return r;
      }
      return null;
    };
    if (soup && soupPool.length) {
      const s = tryPick(soupPool);
      if (s) { used.add(s.id); items.push({ r: s, share: 0.18 }); }
    }
    const wantMeat = n >= 2 ? Math.max(1, Math.round(n / 2)) : (rng() < 0.5 ? 1 : 0);
    for (let i = 0; i < n; i++) {
      const pool = i < wantMeat ? meatPool : vegPool;
      const r = tryPick(pool) || tryPick(dishPool);
      if (!r) break;
      used.add(r.id);
      cats.set(r.category, (cats.get(r.category) || 0) + 1);
      items.push({ r, share: 0.82 / n });
    }
    return items.length >= (soup && soupPool.length ? 2 : 1) ? items : null;
  }

  /* 按配比给固定一组菜分配份量档位，使这餐热量 ≈ slotT */
  function assignServings(items, t) {
    const denom = items.reduce((s, it) => s + it.r.kcal * it.share, 0);
    const x = denom > 0 ? t / denom : 1;
    return items.map((it) => ({ r: it.r, servings: nearestServing(x * it.share) }));
  }
  const mealKcal = (items) => items.reduce((s, it) => s + it.r.kcal * it.servings, 0);

  let best = null;
  for (let i = 0; i < 300; i++) {
    const b = pick(bPool);
    if (!b) continue;
    const lRaw = composeMeal(lunchN, withSoup, [b.id]);
    if (!lRaw) continue;
    const dRaw = composeMeal(dinnerN, withSoup, [b.id, ...lRaw.map((x) => x.r.id)]);
    if (!dRaw) continue;
    const ls = assignServings(lRaw, slotT.l);
    const ds = assignServings(dRaw, slotT.d);
    const sb = nearestServing(slotT.b / Math.max(b.kcal, 1));
    const ids = [b.id, ...ls.map((x) => x.r.id), ...ds.map((x) => x.r.id)];
    if (new Set(ids).size !== ids.length) continue;
    const total = b.kcal * sb + mealKcal(ls) + mealKcal(ds);
    if (total < lo || total > hi) continue;
    const dupPenalty = ids.reduce((s, id) => s + (usedCount.get(id) || 0), 0) * 400;
    const score = Math.abs(total - target) + dupPenalty + servPenalty(sb) +
      ls.concat(ds).reduce((s, it) => s + servPenalty(it.servings), 0);
    if (!best || score < best.score) best = { b, sb, ls, ds, total, score };
  }
  if (!best) {
    // 兜底：各池取热量最高前 8 道做有限的单菜组合（毫秒级；全量穷举在 150+ 道菜时会卡死）
    const top = (arr, n) => arr.slice().sort((a, b) => b.kcal - a.kcal).slice(0, n);
    const bTop = top(bPool, 8);
    const lTop = top(dishPool.length ? dishPool : recipes, 8);
    const dTop = top(dishPool.length ? dishPool : recipes, 8);
    for (const b of bTop) {
      for (const l of lTop) {
        for (const d of dTop) {
          if (b.id === l.id || l.id === d.id || b.id === d.id) continue;
          for (const sb of SERVING_OPTIONS) {
            for (const sl of SERVING_OPTIONS) {
              for (const sd of SERVING_OPTIONS) {
                const total = b.kcal * sb + l.kcal * sl + d.kcal * sd;
                const score = Math.abs(total - target) + (reuse(b) + reuse(l) + reuse(d)) * 400 + servPenalty(sb) + servPenalty(sl) + servPenalty(sd);
                if (!best || score < best.score) {
                  best = { b, sb, ls: [{ r: l, servings: sl }], ds: [{ r: d, servings: sd }], total, score };
                }
              }
            }
          }
        }
      }
    }
  }
  return best;
}
function parseTarget(query) {
  const raw = query.get('target');
  if (raw === null) return 2000;
  const t = Number(raw);
  if (!Number.isFinite(t) || t < 500 || t > 6000) throw bad('target 需为 500-6000 的热量值(千卡)');
  return t;
}

/* ---------------- 点赞/收藏 ---------------- */

function toggleLike(user, id, on) {
  const r = db.prepare('SELECT id FROM recipes WHERE id = ?').get(id);
  if (!r) throw new HttpError(404, 'recipe_not_found');
  if (on) {
    const ret = db.prepare('INSERT OR IGNORE INTO likes (user_id, recipe_id) VALUES (?, ?)').run(user.id, id);
    if (Number(ret.changes) > 0) db.prepare('UPDATE recipes SET like_count = like_count + 1 WHERE id = ?').run(id);
  } else {
    const ret = db.prepare('DELETE FROM likes WHERE user_id = ? AND recipe_id = ?').run(user.id, id);
    if (Number(ret.changes) > 0) db.prepare('UPDATE recipes SET like_count = MAX(like_count - 1, 0) WHERE id = ?').run(id);
  }
  const cnt = Number(db.prepare('SELECT like_count FROM recipes WHERE id = ?').get(id).like_count);
  return { ok: true, liked: on, like_count: cnt };
}
function toggleFavorite(user, id, on) {
  const r = db.prepare('SELECT id FROM recipes WHERE id = ?').get(id);
  if (!r) throw new HttpError(404, 'recipe_not_found');
  if (on) {
    const ret = db.prepare('INSERT OR IGNORE INTO favorites (user_id, recipe_id) VALUES (?, ?)').run(user.id, id);
    if (Number(ret.changes) > 0) db.prepare('UPDATE recipes SET favorite_count = favorite_count + 1 WHERE id = ?').run(id);
  } else {
    const ret = db.prepare('DELETE FROM favorites WHERE user_id = ? AND recipe_id = ?').run(user.id, id);
    if (Number(ret.changes) > 0) db.prepare('UPDATE recipes SET favorite_count = MAX(favorite_count - 1, 0) WHERE id = ?').run(id);
  }
  const cnt = Number(db.prepare('SELECT favorite_count FROM recipes WHERE id = ?').get(id).favorite_count);
  return { ok: true, favorited: on, favorite_count: cnt };
}

/* ---------------- 社区 ---------------- */

const POST_SELECT = `SELECT p.*, u.nickname,
  (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id) AS comment_count
  FROM posts p LEFT JOIN users u ON u.id = p.user_id`;
function postOut(row) {
  let images = [];
  try { images = JSON.parse(row.image_urls_json || '[]'); } catch { }
  return {
    id: row.id, user_id: row.user_id, nickname: row.nickname, text: row.text,
    image_urls: images, recipe_id: row.recipe_id, city: row.city,
    comment_count: Number(row.comment_count || 0), created_at: row.created_at,
  };
}

/* ---------------- LLM（凭据只在 secrets 文件里） ---------------- */

let _secrets = null;
function llmSecrets() {
  if (_secrets) return _secrets;
  try {
    const s = JSON.parse(fs.readFileSync(SECRETS_PATH, 'utf8'));
    if (!s.endpoint || !s.key || !s.model) throw new Error('incomplete');
    _secrets = s;
    return s;
  } catch {
    throw new HttpError(502, 'llm_not_configured', '无法读取 LLM 配置文件');
  }
}
async function llmChat(messages, maxTokens, timeoutMs = 90000) {
  const s = llmSecrets();
  let attemptMax = maxTokens;
  // 推理模型的思维链会消耗 max_tokens：若正式回答被截断为空，自动加大 token 重试（最多 2 次）
  for (let attempt = 0; attempt < 3; attempt++) {
    let r;
    try {
      r = await fetch(s.endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + s.key },
        body: JSON.stringify({ model: s.model, messages, max_tokens: attemptMax, temperature: 0.2, stream: false }),
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (e) {
      throw new HttpError(502, 'llm_request_failed', String((e && e.message) || e));
    }
    const text = await r.text();
    if (!r.ok) throw new HttpError(502, 'llm_request_failed', '上游状态码 ' + r.status);
    let data;
    try { data = JSON.parse(text); } catch { throw new HttpError(502, 'llm_bad_response', '上游返回非 JSON'); }
    const choice = data && data.choices && data.choices[0];
    const msg = choice && choice.message;
    if (!msg || typeof msg.content !== 'string') throw new HttpError(502, 'llm_bad_response', '上游缺少 message.content');
    if (msg.content.trim() === '' && choice.finish_reason === 'length' && attempt < 2) {
      attemptMax = Math.min(attemptMax * 2, 16384);
      continue;
    }
    return msg; // 注意：推理模型可能有 reasoning_content 字段，答案在 content
  }
  throw new HttpError(502, 'llm_bad_response', '模型输出为空（已尝试加大 token）');
}
/* 从模型输出中提取 JSON（容忍 markdown 代码块、前后缀文字） */
function extractJSON(text) {
  const t = String(text);
  const fc = t.indexOf('{');
  const fs2 = t.indexOf('[');
  let start, open, close;
  if (fs2 !== -1 && (fc === -1 || fs2 < fc)) { start = fs2; open = '['; close = ']'; }
  else if (fc !== -1) { start = fc; open = '{'; close = '}'; }
  else return null;
  const end = t.lastIndexOf(close);
  if (end <= start) return null;
  try { return JSON.parse(t.slice(start, end + 1)); } catch { return null; }
}

/* ---------------- B站菜谱视频教程搜索 ----------------
 * 链路：菜谱名 -> 内存/SQLite 缓存(24h) -> B站搜索(wbi 签名 + buvid3 cookie) -> 校验解析 -> 写缓存
 * 出站限流：固定 60s 窗口，最多 10 次（nav/spi/search 均计入）；超限直接走跳转兜底。
 * 任何上游失败（风控/改版/网络/解析）一律静默降级：items:[] + fallback，绝不报错。
 */
const BILI_SEARCH_URL = 'https://api.bilibili.com/x/web-interface/search/type';
const BILI_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
  'Referer': 'https://www.bilibili.com/',
  'Origin': 'https://www.bilibili.com',
  'Accept': 'application/json, text/plain, */*',
};
const BILI_TIMEOUT_MS = 8000;
const VIDEO_CACHE_TTL_MS = 24 * 3600 * 1000;   // 缓存 24 小时
const BILI_RATE_MAX = 10;                      // 每分钟最多向上游发起 10 次请求
const BILI_RATE_WINDOW_MS = 60 * 1000;
const VIDEO_ITEM_LIMIT = 10;                   // 只取前 10 条
const VIDEO_AD_RE = /加微信|加v信|代做|代写|网课|私聊|领券|优惠券|培训机构|广告/; // 过滤明显无关广告（宽松，宁漏勿误）

/* B站时长字符串规范化：'5:6' → '5:06'，'1:2:3' → '1:02:03' */
function formatDuration(d) {
  const parts = String(d || '').split(':').map((x) => parseInt(x, 10));
  if (!parts.length || parts.some((x) => !Number.isFinite(x))) return String(d || '');
  if (parts.length === 3) return `${parts[0]}:${String(parts[1]).padStart(2, '0')}:${String(parts[2]).padStart(2, '0')}`;
  if (parts.length === 2) return `${parts[0]}:${String(parts[1]).padStart(2, '0')}`;
  return String(d || '');
}

let biliRateStart = 0;
let biliRateCount = 0;
function biliAcquire() { // 需要出站请求时先取配额；false = 本窗口已超限
  const now = Date.now();
  if (now - biliRateStart >= BILI_RATE_WINDOW_MS) { biliRateStart = now; biliRateCount = 0; }
  if (biliRateCount >= BILI_RATE_MAX) return false;
  biliRateCount++;
  return true;
}

/* wbi 签名（B站 2024 后搜索接口要求 w_rid&wts） */
const MIXIN_KEY_ENC_TAB = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
  33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
  61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
  36, 20, 34, 44, 52,
];
function getMixinKey(orig) {
  let str = '';
  for (const i of MIXIN_KEY_ENC_TAB) str += orig[i] ?? '';
  return str.slice(0, 32);
}
function wbiSignQuery(params, imgKey, subKey) {
  // 返回已编码的 query 串（排序 -> 拼 mixin_key -> MD5），发送与签名用同一编码，避免二义
  const mixinKey = getMixinKey(imgKey + subKey);
  const wts = Math.floor(Date.now() / 1000);
  const p = { ...params, wts };
  for (const k of Object.keys(p)) p[k] = String(p[k]).replace(/[!'()*]/g, '');
  const enc = (s) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());
  const query = Object.keys(p).sort().map((k) => enc(k) + '=' + enc(p[k])).join('&');
  const wRid = crypto.createHash('md5').update(query + mixinKey).digest('hex');
  return query + '&w_rid=' + wRid + '&wts=' + wts;
}

/* wbi key 内存缓存（1 小时；nav 未登录 code=-101 但 wbi_img 仍返回） */
let biliWbi = { keys: null, at: 0 };
function wbiKeyFromUrl(u) { const m = /\/(\w+)\.(?:png|jpg)/.exec(String(u || '')); return m ? m[1] : ''; }
async function biliWbiKeys() {
  if (biliWbi.keys && Date.now() - biliWbi.at < 3600 * 1000) return biliWbi.keys;
  if (!biliAcquire()) return null;
  const r = await fetch('https://api.bilibili.com/x/web-interface/nav', { headers: BILI_HEADERS, signal: AbortSignal.timeout(BILI_TIMEOUT_MS) });
  const d = await r.json();
  const img = wbiKeyFromUrl(d && d.data && d.data.wbi_img && d.data.wbi_img.img_url);
  const sub = wbiKeyFromUrl(d && d.data && d.data.wbi_img && d.data.wbi_img.sub_url);
  if (!img || !sub) throw new Error('nav missing wbi_img');
  biliWbi = { keys: { img, sub }, at: Date.now() };
  return biliWbi.keys;
}

/* buvid3 cookie：优先 finger/spi 获取，失败用随机值（实测均可用）；进程内复用 */
let biliCookie = '';
async function biliCookieValue() {
  if (biliCookie) return biliCookie;
  try {
    if (biliAcquire()) {
      const r = await fetch('https://api.bilibili.com/x/frontend/finger/spi', { headers: BILI_HEADERS, signal: AbortSignal.timeout(BILI_TIMEOUT_MS) });
      const d = await r.json();
      if (d && d.code === 0 && d.data && d.data.b_3) {
        biliCookie = 'buvid3=' + d.data.b_3 + (d.data.b_4 ? '; buvid4=' + d.data.b_4 : '');
        return biliCookie;
      }
    }
  } catch { /* fallthrough */ }
  return 'buvid3=' + crypto.randomBytes(16).toString('hex').toUpperCase() + 'infoc';
}

function decodeHTMLText(s) {
  return String(s)
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&#x27;/gi, "'");
}
function biliParseItems(data) {
  const arr = data && data.data && Array.isArray(data.data.result) ? data.data.result : [];
  const items = [];
  for (const it of arr) {
    if (!it || !it.bvid || !it.title) continue;
    const title = decodeHTMLText(String(it.title).replace(/<em class="keyword">/g, '').replace(/<\/em>/g, ''))
      .replace(/\s+/g, ' ').trim();
    if (!title || VIDEO_AD_RE.test(title)) continue;
    let pic = String(it.pic || '');
    if (pic.startsWith('//')) pic = 'https:' + pic;
    else if (pic.startsWith('http://')) pic = 'https://' + pic.slice(7);
    items.push({
      title,
      author: String(it.author || '').trim(),
      duration: formatDuration(String(it.duration || '').trim()),
      bvid: String(it.bvid),
      play_url: 'https://www.bilibili.com/video/' + it.bvid,
      cover: pic,
      view_count: Number(it.play) > 0 ? Number(it.play) : 0,
    });
    if (items.length >= VIDEO_ITEM_LIMIT) break;
  }
  return items;
}

/* 单次搜索：wbi 主路径失败时回退纯接口；全部失败返回 ok:false（由上层降级） */
async function biliSearchVideos(keyword) {
  const params = { search_type: 'video', keyword };
  const tryFetch = async (url) => {
    if (!biliAcquire()) return null; // 超限：不发请求
    const r = await fetch(url, { headers: { ...BILI_HEADERS, Cookie: await biliCookieValue() }, signal: AbortSignal.timeout(BILI_TIMEOUT_MS) });
    const text = await r.text();     // 先 text 再 parse，容忍非 JSON 改版
    let data = null;
    try { data = JSON.parse(text); } catch { }
    return { httpStatus: r.status, data };
  };
  // 主路径：wbi 签名
  try {
    const keys = await biliWbiKeys();
    if (keys) {
      const r = await tryFetch(BILI_SEARCH_URL + '?' + wbiSignQuery(params, keys.img, keys.sub));
      if (r && r.data && r.data.code === 0) return { ok: true, items: biliParseItems(r.data) };
      if (r && (r.httpStatus === 412 || (r.data && r.data.code === -412))) {
        biliCookie = ''; biliWbi = { keys: null, at: 0 }; // 疑似风控：重置凭据，下次重新获取
      }
    }
  } catch { /* fallthrough */ }
  // 备选：无 wbi 纯接口（实测当前可通；B站收紧后失效则整体降级）
  try {
    const q = Object.keys(params).map((k) => encodeURIComponent(k) + '=' + encodeURIComponent(params[k])).join('&');
    const r = await tryFetch(BILI_SEARCH_URL + '?' + q);
    if (r && r.data && r.data.code === 0) return { ok: true, items: biliParseItems(r.data) };
  } catch { /* fallthrough */ }
  return { ok: false, items: [] };
}

function videoCacheGet(name) {
  try {
    const row = db.prepare('SELECT items_json, fetched_at FROM video_cache WHERE recipe_name = ?').get(name);
    if (!row) return null;
    const t = Date.parse(row.fetched_at);
    if (!Number.isFinite(t) || Date.now() - t > VIDEO_CACHE_TTL_MS) return null;
    const items = JSON.parse(row.items_json);
    return Array.isArray(items) ? items : null;
  } catch { return null; }
}
function videoCachePut(name, items) {
  try {
    db.prepare('INSERT OR REPLACE INTO video_cache (recipe_name, items_json, fetched_at) VALUES (?, ?, ?)')
      .run(name, JSON.stringify(items), new Date().toISOString());
  } catch (e) { console.error('[videos] cache write failed:', (e && e.message) || e); }
}


/* ---------------- 路由 ---------------- */

const routes = [];
function route(method, pattern, auth, handler) {
  routes.push({ method, regex: new RegExp('^' + pattern + '$'), auth, handler });
}

/* --- 健康检查 --- */
route('GET', '/api/health', false, ({ res }) => {
  let recipes = 0;
  try { recipes = Number(db.prepare('SELECT COUNT(*) AS c FROM recipes').get().c); } catch { }
  sendJSON(res, 200, {
    ok: true, service: 'shiguang-backend', version: 'm1.0.0',
    time: new Date().toISOString(), uptime_sec: Math.floor((Date.now() - START_TS) / 1000),
    storage: 'sqlite', recipes,
  });
});

/* --- 认证：手机号验证码（测试码可经 secrets/config.json 关闭）+ 邮箱验证码 --- */

function issueToken(user) {
  const token = crypto.randomBytes(32).toString('hex');
  db.prepare("INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, datetime('now','localtime','+30 days'))").run(token, user.id);
  return token;
}

route('POST', '/api/auth/request-code', false, ({ req, res, body }) => {
  const ip = clientIp(req);
  if (!rateLimit('rc:' + ip, 20, 60 * 60 * 1000)) throw new HttpError(429, 'rate_limited', '验证码请求过于频繁，请稍后再试');
  const phone = typeof body.phone === 'string' ? body.phone.trim() : '';
  if (!/^1\d{10}$/.test(phone)) throw bad('phone 必须为 11 位手机号');
  const allowTest = appConfig().allow_test_code !== false; // 未配置默认开放，上线建议改为 false
  sendJSON(res, 200, {
    ok: true, test_mode: allowTest, dev_code: allowTest ? '888888' : null, expires_in: 600,
    message: allowTest ? '测试模式：验证码固定为 888888' : '验证码暂未开放短信通道，请使用邮箱注册',
  });
});

route('POST', '/api/auth/login', false, ({ req, res, body }) => {
  const ip = clientIp(req);
  if (!rateLimit('login:' + ip, 20, 60 * 60 * 1000)) throw new HttpError(429, 'rate_limited', '尝试过于频繁，请稍后再试');
  const phone = typeof body.phone === 'string' ? body.phone.trim() : '';
  if (!/^1\d{10}$/.test(phone)) throw bad('phone 必须为 11 位手机号');
  const code = typeof body.code === 'string' ? body.code.trim() : '';
  if (!code) throw bad('code 不能为空');
  const allowTest = appConfig().allow_test_code !== false;
  if (!allowTest || code !== '888888') throw new HttpError(401, 'invalid_code', '验证码错误或已过期');
  let user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) {
    db.prepare('INSERT INTO users (phone, nickname) VALUES (?, ?)').run(phone, '食光用户' + phone.slice(-4));
    user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  }
  sendJSON(res, 200, { ok: true, token: issueToken(user), user: publicUser(user) });
});

/* --- 账号密码登录：account 可为手机号或邮箱；密码经 scrypt 加盐存储 --- */
function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return salt + ':' + hash;
}
function verifyPassword(password, stored) {
  const [salt, hash] = String(stored || '').split(':');
  if (!salt || !hash) return false;
  const calc = crypto.scryptSync(password, salt, 64).toString('hex');
  return crypto.timingSafeEqual(Buffer.from(calc, 'hex'), Buffer.from(hash, 'hex'));
}

route('POST', '/api/auth/password-login', false, ({ req, res, body }) => {
  if (!rateLimit('pl:' + clientIp(req), 10, 60 * 60 * 1000)) throw new HttpError(429, 'rate_limited', '尝试过于频繁，请稍后再试');
  const account = String(body.account || '').trim().toLowerCase();
  const password = String(body.password || '');
  if (!account || !password) throw bad('account 与 password 必填');
  const user = db.prepare('SELECT * FROM users WHERE phone = ? OR email = ?').get(account, account);
  if (!user || !user.password_hash || !verifyPassword(password, user.password_hash)) {
    throw new HttpError(401, 'invalid_credentials', '账号或密码错误');
  }
  sendJSON(res, 200, { ok: true, token: issueToken(user), user: publicUser(user) });
});

/* 设置/修改密码（需已登录） */
route('PUT', '/api/me/password', true, ({ res, user, body }) => {
  const password = String(body.password || '');
  if (password.length < 6 || password.length > 64) throw bad('密码需为 6-64 位');
  db.prepare('UPDATE users SET password_hash = ? WHERE id = ?').run(hashPassword(password), user.id);
  sendJSON(res, 200, { ok: true });
});

/* --- 邮箱验证码注册/登录（需服务器配置 secrets/smtp.json，未配置则返回 email_disabled） --- */
const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

route('POST', '/api/auth/email-code', false, async ({ req, res, body }) => {
  const ip = clientIp(req);
  if (!rateLimit('ec:' + ip, 10, 60 * 60 * 1000)) throw new HttpError(429, 'rate_limited', '验证码请求过于频繁，请 1 小时后再试');
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  if (!EMAIL_RE.test(email) || email.length > 60) throw bad('email 格式不正确');
  if (!rateLimit('ecm:' + email, 1, 60 * 1000)) throw new HttpError(429, 'rate_limited', '同一邮箱 60 秒内只能获取一次验证码');
  const cfg = smtpConfig();
  if (!cfg) throw new HttpError(503, 'email_disabled', '邮箱注册暂未开放，可先游客模式浏览');
  const code = String(Math.floor(100000 + Math.random() * 900000));
  db.prepare(`INSERT INTO email_codes (email, code, expires_at, attempts, last_ip)
    VALUES (?, ?, datetime('now','localtime','+10 minutes'), 0, ?)
    ON CONFLICT(email) DO UPDATE SET code=excluded.code, expires_at=excluded.expires_at, attempts=0, last_ip=excluded.last_ip, created_at=datetime('now','localtime')`)
    .run(email, code, ip);
  try {
    await smtpSend(cfg, email, '食光登录验证码', `你的验证码是：${code}\n\n10 分钟内有效。若非本人操作请忽略本邮件。\n\n—— 食光 App`);
  } catch (e) {
    if (e instanceof HttpError) throw e;
    throw new HttpError(502, 'smtp_error', '邮件发送失败，请稍后再试');
  }
  sendJSON(res, 200, { ok: true, expires_in: 600 });
});

route('POST', '/api/auth/email-login', false, ({ req, res, body }) => {
  const ip = clientIp(req);
  if (!rateLimit('el:' + ip, 20, 60 * 60 * 1000)) throw new HttpError(429, 'rate_limited', '尝试过于频繁，请稍后再试');
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  if (!EMAIL_RE.test(email)) throw bad('email 格式不正确');
  const code = typeof body.code === 'string' ? body.code.trim() : '';
  const row = db.prepare("SELECT * FROM email_codes WHERE email = ? AND expires_at > datetime('now','localtime')").get(email);
  if (!row) throw new HttpError(401, 'invalid_code', '验证码已过期，请重新获取');
  if (Number(row.attempts) >= 5) throw new HttpError(429, 'too_many_attempts', '错误次数过多，请重新获取验证码');
  if (row.code !== code) {
    db.prepare('UPDATE email_codes SET attempts = attempts + 1 WHERE email = ?').run(email);
    throw new HttpError(401, 'invalid_code', '验证码错误');
  }
  db.prepare('DELETE FROM email_codes WHERE email = ?').run(email);
  let user = db.prepare('SELECT * FROM users WHERE email = ?').get(email);
  if (!user) {
    const nickname = '食友' + email.slice(0, 3) + email.split('@')[0].slice(-2);
    db.prepare('INSERT INTO users (phone, email, nickname) VALUES (?, ?, ?)').run('email:' + email, email, nickname);
    user = db.prepare('SELECT * FROM users WHERE email = ?').get(email);
  }
  sendJSON(res, 200, { ok: true, token: issueToken(user), user: publicUser(user) });
});

/* --- 个人档案 --- */
route('GET', '/api/me', true, ({ res, user }) => {
  sendJSON(res, 200, { user: publicUser(user), profile: healthProfile(user) });
});

route('PUT', '/api/me', true, ({ res, user, body }) => {
  const updates = {};
  if (body.nickname !== undefined) {
    if (typeof body.nickname !== 'string' || body.nickname.trim().length > 30) throw bad('nickname 需为不超过 30 字的字符串');
    updates.nickname = body.nickname.trim();
  }
  if (body.gender !== undefined) {
    if (body.gender !== 'm' && body.gender !== 'f') throw bad("gender 只能为 'm' 或 'f'");
    updates.gender = body.gender;
  }
  if (body.age !== undefined) {
    const age = Number(body.age);
    if (!Number.isInteger(age) || age < 1 || age > 120) throw bad('age 需为 1-120 的整数');
    updates.age = age;
  }
  if (body.height_cm !== undefined) {
    const h = Number(body.height_cm);
    if (!isNum(h) || h < 50 || h > 250) throw bad('height_cm 需在 50-250 之间');
    updates.height_cm = h;
  }
  if (body.weight_kg !== undefined) {
    const w = Number(body.weight_kg);
    if (!isNum(w) || w < 20 || w > 300) throw bad('weight_kg 需在 20-300 之间');
    updates.weight_kg = w;
  }
  if (body.activity !== undefined) {
    const a = parseActivity(body.activity);
    if (a === null) throw bad('activity 需为 1.2~1.9 的系数或枚举(sedentary/light/moderate/active/very_active)');
    updates.activity = a;
  }
  if (body.goal !== undefined) {
    if (body.goal !== 'lose' && body.goal !== 'keep' && body.goal !== 'gain') throw bad("goal 只能为 'lose'/'keep'/'gain'");
    updates.goal = body.goal;
  }
  const keys = Object.keys(updates);
  if (keys.length) {
    const setSQL = keys.map((k) => `${k} = ?`).join(', ');
    db.prepare(`UPDATE users SET ${setSQL} WHERE id = ?`).run(...keys.map((k) => nullish(updates[k])), user.id);
  }
  const fresh = db.prepare('SELECT * FROM users WHERE id = ?').get(user.id);
  sendJSON(res, 200, { ok: true, user: publicUser(fresh), profile: healthProfile(fresh) });
});

/* --- 菜谱列表/详情/搜索 --- */
route('GET', '/api/recipes', false, ({ req, res, query }) => {
  const q = (query.get('q') || '').trim();
  const category = (query.get('category') || '').trim();
  let page = Number(query.get('page') || 1);
  if (!Number.isInteger(page) || page < 1) page = 1;
  const where = [];
  const args = [];
  if (q) {
    const pat = '%' + q.replace(/[\\%_]/g, (c) => '\\' + c) + '%';
    where.push("(name LIKE ? ESCAPE '\\' OR ingredients_json LIKE ? ESCAPE '\\')");
    args.push(pat, pat);
  }
  if (category) { where.push('category = ?'); args.push(category); }
  const whereSQL = where.length ? 'WHERE ' + where.join(' AND ') : '';
  const total = Number(db.prepare(`SELECT COUNT(*) AS c FROM recipes ${whereSQL}`).get(...args).c);
  const rows = db.prepare(`SELECT * FROM recipes ${whereSQL} ORDER BY id LIMIT ? OFFSET ?`)
    .all(...args, PAGE_SIZE, (page - 1) * PAGE_SIZE);
  const optUser = getUserByToken(req);
  const sm = userStateMap(optUser && optUser.id, rows.map((r) => r.id));
  sendJSON(res, 200, {
    items: rows.map((r) => recipeOut(r, sm.get(r.id))), page, page_size: PAGE_SIZE, total,
    total_pages: Math.max(1, Math.ceil(total / PAGE_SIZE)),
  });
});

/* 全家餐桌：聚合"我"与全部家庭成员的每日目标；返回 targets 数组与合计 */
function familyTargets(user) {
  if (!user) return { parts: [], total: 0 };
  const parts = [];
  const myP = healthProfile(user);
  if (isNum(myP.target_calories)) parts.push({ name: user.nickname || '我', target: Math.round(myP.target_calories) });
  const fam = db.prepare('SELECT * FROM family_members WHERE owner_user_id = ?').all(user.id);
  for (const m of fam) {
    const mp = memberProfile(m);
    if (isNum(mp.target_calories)) parts.push({ name: m.name, target: Math.round(mp.target_calories) });
  }
  const total = parts.reduce((s, p) => s + p.target, 0);
  return { parts, total };
}

/* --- 每日三餐推荐（family=1 时按全家目标合计规划） --- */
route('GET', '/api/recipes/daily', false, ({ req, res, query }) => {
  const date = (query.get('date') || localToday()).trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(new Date(date + 'T12:00:00').getTime())) {
    throw bad('date 格式需为 YYYY-MM-DD');
  }
  let target = parseTarget(query);
  let breakdown = null;
  if (query.get('family') === '1') {
    const ft = familyTargets(getUserByToken(req));
    if (ft.total >= 600) {
      target = ft.total;
      breakdown = ft.parts;
    }
  }
  const reroll = (query.get('seed') || '').trim(); // 换一换：客户端传随机 seed 即可重摇
  const lunchN = parseMealCount(query, 'lunch_n');
  const dinnerN = parseMealCount(query, 'dinner_n');
  const withSoup = query.get('soup') !== '0';
  const wantDrinks = query.get('drinks') !== '0';
  const prefs = prefsFor(getUserByToken(req));
  let recipes = loadAllRecipes().filter((r) => !hardExcluded(r, prefs));
  // 病理禁忌软排除：候选充足时优先过滤（池子太小则保留，详情页仍有警示）
  const safeRecipes = recipes.filter((r) => matchWarnings(r, prefs).length === 0);
  if (safeRecipes.length >= 30) recipes = safeRecipes;
  if (recipes.length < 3) throw new HttpError(503, 'not_enough_recipes', '菜谱库不足');
  const plan = pickDayPlan(recipes, target, rngFrom(`daily|${date}|${target}${reroll ? '|' + reroll : ''}`), new Map(), { lunchN, dinnerN, soup: withSoup });
  const drink = wantDrinks && target - plan.total >= 120 ? pickDrink(target - plan.total, rngFrom(`drink|${date}|${target}${reroll ? '|' + reroll : ''}`)) : null;
  sendJSON(res, 200, {
    date, target, reroll: reroll || null, breakdown,
    range: { lo: round2(target * 0.85), hi: round2(target * 1.15) },
    total_kcal: round2(plan.total + (drink ? drink.kcal : 0)),
    within_range: plan.total >= target * 0.85 && plan.total <= target * 1.15,
    structure: { lunch: `${lunchN} 菜${withSoup ? ' 1 汤' : ''}`, dinner: `${dinnerN} 菜${withSoup ? ' 1 汤' : ''}` },
    drinks: drink ? [drink] : [],
    meals: {
      breakfast: mealOutMulti([{ r: plan.b, servings: plan.sb }]),
      lunch: mealOutMulti(plan.ls),
      dinner: mealOutMulti(plan.ds),
    },
  });
});

/* --- 7 天排餐（family=1 时按全家目标合计规划） --- */
route('GET', '/api/recipes/weekly', false, ({ req, res, query }) => {
  let target = parseTarget(query);
  let breakdown = null;
  if (query.get('family') === '1') {
    const ft = familyTargets(getUserByToken(req));
    if (ft.total >= 600) {
      target = ft.total;
      breakdown = ft.parts;
    }
  }
  const start = localToday();
  const lunchN = parseMealCount(query, 'lunch_n');
  const dinnerN = parseMealCount(query, 'dinner_n');
  const withSoup = query.get('soup') !== '0';
  const wantDrinks = query.get('drinks') !== '0';
  const prefs = prefsFor(getUserByToken(req));
  let recipes = loadAllRecipes().filter((r) => !hardExcluded(r, prefs));
  const safeRecipes = recipes.filter((r) => matchWarnings(r, prefs).length === 0);
  if (safeRecipes.length >= 30) recipes = safeRecipes;
  if (recipes.length < 3) throw new HttpError(503, 'not_enough_recipes', '菜谱库不足');
  const usedCount = new Map();
  const days = [];
  for (let i = 0; i < 7; i++) {
    const date = addDays(start, i);
    const plan = pickDayPlan(recipes, target, rngFrom(`weekly|${start}|${target}|${i}`), usedCount, { lunchN, dinnerN, soup: withSoup });
    for (const it of [...plan.ls, ...plan.ds]) usedCount.set(it.r.id, (usedCount.get(it.r.id) || 0) + 1);
    usedCount.set(plan.b.id, (usedCount.get(plan.b.id) || 0) + 1);
    const drink = wantDrinks && target - plan.total >= 120 ? pickDrink(target - plan.total, rngFrom(`drink|${start}|${i}`)) : null;
    days.push({
      date, weekday: WEEKDAY_CN[new Date(date + 'T12:00:00').getDay()],
      total_kcal: round2(plan.total + (drink ? drink.kcal : 0)),
      within_range: plan.total >= target * 0.85 && plan.total <= target * 1.15,
      structure: { lunch: `${lunchN} 菜${withSoup ? ' 1 汤' : ''}`, dinner: `${dinnerN} 菜${withSoup ? ' 1 汤' : ''}` },
      drinks: drink ? [drink] : [],
      meals: {
        breakfast: mealOutMulti([{ r: plan.b, servings: plan.sb }]),
        lunch: mealOutMulti(plan.ls),
        dinner: mealOutMulti(plan.ds),
      },
    });
  }
  sendJSON(res, 200, { start_date: start, target, breakdown, days });
});

/* --- 菜谱详情（浏览数 +1；带点赞/收藏状态与偏好警示） --- */
route('GET', '/api/recipes/(\\d+)', false, ({ req, res, m }) => {
  const id = Number(m[1]);
  const row = db.prepare('SELECT * FROM recipes WHERE id = ?').get(id);
  if (!row) throw new HttpError(404, 'recipe_not_found');
  db.prepare('UPDATE recipes SET views = views + 1 WHERE id = ?').run(id);
  row.views = Number(row.views) + 1;
  const optUser = getUserByToken(req);
  const sm = userStateMap(optUser && optUser.id, [id]);
  const item = recipeOut(row, sm.get(id));
  if (optUser) item.warnings = matchWarnings(row, prefsFor(optUser));
  sendJSON(res, 200, { item });
});

/* --- 菜谱视频教程（B站搜索；带缓存/限流/降级，任何上游失败都走跳转兜底不报错） --- */
route('GET', '/api/recipes/(\\d+)/videos', false, async ({ res, m }) => {
  const row = db.prepare('SELECT name FROM recipes WHERE id = ?').get(Number(m[1]));
  if (!row) throw new HttpError(404, 'recipe_not_found');
  const name = String(row.name).trim();
  const fallback = 'https://search.bilibili.com/all?keyword=' + encodeURIComponent(name);
  const hit = videoCacheGet(name);
  if (hit) return sendJSON(res, 200, { ok: true, items: hit, source: 'bilibili', cached: true, fallback });
  let items = [];
  let source = 'none';
  try {
    const r = await biliSearchVideos(name);
    if (r.ok) {
      items = r.items;
      source = 'bilibili';
      videoCachePut(name, items); // 只缓存成功结果（含上游确实搜不到的空结果）
    }
  } catch (e) {
    console.error('[videos] bilibili search failed:', (e && e.message) || e);
  }
  sendJSON(res, 200, { ok: true, items, source, cached: false, fallback });
});

/* --- 食材模糊联想：聚合全部菜谱的食材名，前缀优先 + 包含匹配 --- */
let _ingIndex = null;
function ingredientIndex() {
  if (_ingIndex) return _ingIndex;
  const set = new Set();
  for (const r of loadAllRecipes()) {
    try {
      for (const i of JSON.parse(r.ingredients_json)) {
        const n = String(i.name || '').trim();
        if (n) set.add(n);
      }
    } catch { }
  }
  _ingIndex = [...set].sort();
  return _ingIndex;
}

route('GET', '/api/ingredients/suggest', true, ({ res, query }) => {
  const q = (query.get('q') || '').trim();
  if (!q) { sendJSON(res, 200, { items: [] }); return; }
  const idx = ingredientIndex();
  const starts = idx.filter((n) => n.startsWith(q));
  const contains = idx.filter((n) => !n.startsWith(q) && n.includes(q));
  sendJSON(res, 200, { items: [...starts, ...contains].slice(0, 8) });
});

/* --- 家庭成员：多人健康档案；体脂率等权威数据选填，填了用更准的算法 --- */

function memberProfile(m) {
  const p = { id: m.id, name: m.name };
  if (isNum(m.height_cm) && isNum(m.weight_kg)) {
    const h = m.height_cm / 100;
    p.bmi = round2(m.weight_kg / (h * h));
    if (Number.isInteger(m.age) && (m.gender === 'm' || m.gender === 'f')) {
      let bmr;
      let method;
      if (isNum(m.body_fat_pct) && m.body_fat_pct > 3 && m.body_fat_pct < 65) {
        // Katch-McArdle：按瘦体重算，有实测体脂率时更准
        const lbm = m.weight_kg * (1 - m.body_fat_pct / 100);
        bmr = 370 + 21.6 * lbm;
        method = 'katch-mcardle';
      } else {
        // Mifflin-St Jeor：默认公式
        bmr = 10 * m.weight_kg + 6.25 * m.height_cm - 5 * m.age + (m.gender === 'm' ? 5 : -161);
        method = 'mifflin-st-jeor';
      }
      p.bmr = round2(bmr);
      const act = isNum(m.activity) ? m.activity : 1.2;
      p.activity_factor = act;
      p.tdee = round2(bmr * act);
      const goal = m.goal || 'keep';
      p.goal = goal;
      p.target_calories = round2(goal === 'lose' ? p.tdee - 400 : goal === 'gain' ? p.tdee + 300 : p.tdee);
      p.target_rule = goal === 'lose' ? 'TDEE-400' : goal === 'gain' ? 'TDEE+300' : 'TDEE';
      p.method = method;
    }
  }
  return p;
}
function memberOut(m) {
  return { ...m, metrics: memberProfile(m) };
}
function memberBody(body, requireName) {
  const out = {};
  if (requireName || body.name !== undefined) {
    const name = String(body.name || '').trim();
    if (!name || name.length > 20) throw bad('name 必填且不超过 20 字');
    out.name = name;
  }
  if (body.gender !== undefined && body.gender !== null) {
    if (body.gender !== 'm' && body.gender !== 'f') throw bad("gender 只能为 'm'/'f'");
    out.gender = body.gender;
  }
  if (body.age !== undefined && body.age !== null) {
    const a = Number(body.age);
    if (!Number.isInteger(a) || a < 1 || a > 120) throw bad('age 需为 1-120 整数');
    out.age = a;
  }
  if (body.height_cm !== undefined && body.height_cm !== null) {
    const h = Number(body.height_cm);
    if (!isNum(h) || h < 50 || h > 250) throw bad('height_cm 需在 50-250 之间');
    out.height_cm = h;
  }
  if (body.weight_kg !== undefined && body.weight_kg !== null) {
    const w = Number(body.weight_kg);
    if (!isNum(w) || w < 5 || w > 400) throw bad('weight_kg 需在 5-400 之间');
    out.weight_kg = w;
  }
  if (body.body_fat_pct !== undefined && body.body_fat_pct !== null) {
    const b = Number(body.body_fat_pct);
    if (!isNum(b) || b < 3 || b > 65) throw bad('body_fat_pct 需在 3-65 之间');
    out.body_fat_pct = b;
  }
  if (body.activity !== undefined && body.activity !== null) {
    const a = parseActivity(body.activity);
    if (a === null) throw bad('activity 需为 1.2~1.9 系数或枚举');
    out.activity = a;
  }
  if (body.goal !== undefined && body.goal !== null) {
    if (!['lose', 'keep', 'gain'].includes(body.goal)) throw bad("goal 只能是 lose/keep/gain");
    out.goal = body.goal;
  }
  return out;
}

route('GET', '/api/family', true, ({ res, user }) => {
  const rows = db.prepare('SELECT * FROM family_members WHERE owner_user_id = ? ORDER BY id').all(user.id);
  sendJSON(res, 200, { items: rows.map(memberOut) });
});
route('POST', '/api/family', true, ({ res, user, body }) => {
  const fields = memberBody(body, true);
  const keys = Object.keys(fields);
  const cols = ['owner_user_id', ...keys];
  const vals = [user.id, ...keys.map((k) => fields[k])];
  const info = db.prepare(`INSERT INTO family_members (${cols.join(',')}) VALUES (${cols.map(() => '?').join(',')})`).run(...vals);
  const row = db.prepare('SELECT * FROM family_members WHERE id = ?').get(info.lastInsertRowid);
  sendJSON(res, 200, { ok: true, item: memberOut(row) });
});
route('PUT', '/api/family/(\\d+)', true, ({ res, m, user, body }) => {
  const row = db.prepare('SELECT * FROM family_members WHERE id = ? AND owner_user_id = ?').get(Number(m[1]), user.id);
  if (!row) throw new HttpError(404, 'member_not_found');
  const fields = memberBody(body, false);
  const keys = Object.keys(fields);
  if (keys.length) {
    db.prepare(`UPDATE family_members SET ${keys.map((k) => k + ' = ?').join(', ')} WHERE id = ?`)
      .run(...keys.map((k) => fields[k]), row.id);
  }
  const fresh = db.prepare('SELECT * FROM family_members WHERE id = ?').get(row.id);
  sendJSON(res, 200, { ok: true, item: memberOut(fresh) });
});
route('DELETE', '/api/family/(\\d+)', true, ({ res, m, user }) => {
  const info = db.prepare('DELETE FROM family_members WHERE id = ? AND owner_user_id = ?').run(Number(m[1]), user.id);
  if (!info.changes) throw new HttpError(404, 'member_not_found');
  sendJSON(res, 200, { ok: true });
});

/* --- 饮食偏好：不喜欢/忌口/病理/菜系 --- */
const CONDITION_PRESETS = {
  cold:        { label: '感冒',        avoid: ['辣椒', '花椒', '小米辣', '冰', '肥肉'] },
  diabetes:    { label: '糖尿病',      avoid: ['白糖', '冰糖', '红糖', '蜂蜜', '可乐'] },
  hypertension:{ label: '高血压',      avoid: ['咸菜', '腌', '豆瓣酱', '腐乳'] },
  gout:        { label: '痛风',        avoid: ['虾', '蟹', '贝', '鱼头', '鱼', '海鲜', '腰', '肝', '啤酒', '浓汤'] },
  gastritis:   { label: '肠胃炎',      avoid: ['辣椒', '花椒', '冰', '肥肉'] },
  hyperlipid:  { label: '高血脂',      avoid: ['肥肉', '五花肉', '猪油', '油炸'] },
  lactose:     { label: '乳糖不耐受',  avoid: ['牛奶', '奶油', '芝士', '奶酪'] },
  pregnancy:   { label: '孕期',        avoid: ['冰', '酒', '咖啡'] },
};

function prefsFor(user) {
  let p = { dislikes: [], allergens: [], conditions: [], cuisines: [] };
  try {
    const raw = user && user.prefs_json ? JSON.parse(user.prefs_json) : null;
    if (raw && typeof raw === 'object') {
      for (const k of ['dislikes', 'allergens', 'conditions', 'cuisines']) {
        if (Array.isArray(raw[k])) p[k] = raw[k].map(String).slice(0, 30);
      }
    }
  } catch { }
  return p;
}

/* 计算一道菜对当前用户偏好的警示：返回 [{tag, label, reason}]（仅按食材匹配，避免菜名误伤如"鱼香肉丝"） */
function matchWarnings(recipe, prefs) {
  const warnings = [];
  const text = String(recipe.ingredients_json || recipe.ingredients || '');
  for (const key of prefs.conditions) {
    const preset = CONDITION_PRESETS[key];
    if (!preset) continue;
    const hits = preset.avoid.filter((kw) => text.includes(kw));
    if (hits.length) {
      warnings.push({ tag: key, label: preset.label, reason: `含「${hits.join('、')}」（你设置了${preset.label}宜避免）` });
    }
  }
  return warnings;
}

/* 硬排除判断：allergens/conditions 命中（allergens 命中=排除；conditions 命中=保留但警示） */
function hardExcluded(recipe, prefs) {
  const text = typeof (recipe.ingredients_json || recipe.ingredients || '') === 'string'
    ? (recipe.ingredients_json || recipe.ingredients || '') : JSON.stringify(recipe.ingredients || '');
  return prefs.allergens.some((kw) => text.includes(kw));
}

route('GET', '/api/me/preferences', true, ({ res, user }) => {
  sendJSON(res, 200, { ok: true, preferences: prefsFor(user) });
});
route('GET', '/api/me/preferences/conditions', false, ({ res }) => {
  sendJSON(res, 200, { ok: true, items: Object.entries(CONDITION_PRESETS).map(([key, v]) => ({ key, label: v.label, avoid: v.avoid.join('、') })) });
});
route('PUT', '/api/me/preferences', true, ({ res, user, body }) => {
  const p = prefsFor(user);
  for (const k of ['dislikes', 'allergens', 'conditions', 'cuisines']) {
    if (Array.isArray(body[k])) {
      const arr = body[k].map(String).map((x) => x.trim()).filter(Boolean);
      if (arr.some((x) => x.length > 12)) throw bad(`${k} 单项不能超过 12 字`);
      if (arr.length > 30) throw bad(`${k} 数量过多（≤30）`);
      p[k] = [...new Set(arr)];
    }
  }
  db.prepare('UPDATE users SET prefs_json = ? WHERE id = ?').run(JSON.stringify(p), user.id);
  const fresh = db.prepare('SELECT * FROM users WHERE id = ?').get(user.id);
  sendJSON(res, 200, { ok: true, preferences: prefsFor(fresh) });
});

/* --- 三餐日记：只记录一日三餐吃了什么 --- */
const MEAL_KEYS = ['breakfast', 'lunch', 'dinner'];
const MEAL_LABELS = { breakfast: '早餐', lunch: '午餐', dinner: '晚餐' };

function diaryOut(row) {
  return {
    id: row.id, meal_key: row.meal_key, recipe_id: row.recipe_id || null,
    recipe_name: row.recipe_name, kcal: row.kcal == null ? null : round2(row.kcal),
    servings: row.servings == null ? null : row.servings, note: row.note || '',
    created_at: row.created_at,
  };
}

route('GET', '/api/diary', true, ({ req, res, query, user }) => {
  const date = (query.get('date') || localToday()).trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(new Date(date + 'T12:00:00').getTime())) {
    throw bad('date 格式需为 YYYY-MM-DD');
  }
  const rows = db.prepare('SELECT * FROM diary_entries WHERE user_id = ? AND entry_date = ? ORDER BY id ASC').all(user.id, date);
  const meals = { breakfast: [], lunch: [], dinner: [] };
  let total = 0;
  for (const r of rows) {
    meals[r.meal_key] && meals[r.meal_key].push(diaryOut(r));
    total += Number(r.kcal) || 0;
  }
  sendJSON(res, 200, { ok: true, date, meals, total_kcal: round2(total) });
});

route('POST', '/api/diary', true, ({ res, user, body }) => {
  const date = String(body.date || localToday()).trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(new Date(date + 'T12:00:00').getTime())) {
    throw bad('date 格式需为 YYYY-MM-DD');
  }
  const mealKey = String(body.meal_key || '').trim();
  if (!MEAL_KEYS.includes(mealKey)) throw bad('meal_key 需为 breakfast/lunch/dinner');
  let name = String(body.recipe_name || '').trim();
  let kcal = body.kcal == null || body.kcal === '' ? null : Number(body.kcal);
  const servings = body.servings == null || body.servings === '' ? null : Number(body.servings);
  if (servings != null && (!Number.isFinite(servings) || servings <= 0 || servings > 50)) throw bad('servings 需为 0-50');
  let recipeId = null;
  if (body.recipe_id != null) {
    const row = db.prepare('SELECT * FROM recipes WHERE id = ?').get(Number(body.recipe_id));
    if (!row) throw new HttpError(404, 'recipe_not_found');
    recipeId = row.id;
    if (!name) name = row.name;
    if (kcal == null || !Number.isFinite(kcal)) {
      // 记录实际摄入：单份热量 × 份量
      kcal = row.kcal * (servings != null && servings > 0 ? servings : 1);
    }
  }
  if (!name || name.length > 30) throw bad('菜名必填且不超过 30 字');
  if (kcal != null && (!Number.isFinite(kcal) || kcal < 0 || kcal > 20000)) throw bad('kcal 需为 0-20000');
  const note = String(body.note || '').trim().slice(0, 100);
  const ret = db.prepare(
    'INSERT INTO diary_entries (user_id, entry_date, meal_key, recipe_id, recipe_name, kcal, servings, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
  ).run(user.id, date, mealKey, recipeId, name, kcal, servings, note);
  const row = db.prepare('SELECT * FROM diary_entries WHERE id = ?').get(Number(ret.lastInsertRowid));
  sendJSON(res, 200, { ok: true, entry: diaryOut(row) });
});

route('DELETE', '/api/diary/(\\d+)', true, ({ res, user, m }) => {
  const ret = db.prepare('DELETE FROM diary_entries WHERE id = ? AND user_id = ?').run(Number(m[1]), user.id);
  if (Number(ret.changes) === 0) throw new HttpError(404, 'entry_not_found');
  sendJSON(res, 200, { ok: true });
});

/* ---------------- 干饭街：大学生/上班族模式（美食街单人餐 + 转盘 + 打卡记账） ---------------- */

const STREET_SELECT = 'SELECT * FROM street_foods';

function streetOut(row) {
  return {
    id: row.id, name: row.name, category: row.category, price: row.price, kcal: row.kcal,
    spicy: row.spicy, slots: String(row.slots || '').split(','), tags: String(row.tags || '').split(',').filter(Boolean),
    intro: row.intro,
  };
}

/* 通用过滤：时段 / 预算上限 / 辣度上限（spicy_max=4 表示无限制） */
function streetPool(query) {
  const slot = String(query.get('slot') || 'lunch');
  const budget = Number(query.get('budget')) || 0;
  const spicyMax = Number(query.get('spicy_max'));
  let pool = db.prepare(STREET_SELECT).all().map(streetOut);
  if (slot !== 'all') {
    const filtered = pool.filter((f) => f.slots.includes(slot));
    if (filtered.length >= 4) pool = filtered;
  }
  if (budget > 0) {
    const filtered = pool.filter((f) => f.price <= budget);
    if (filtered.length >= 4) pool = filtered;
  }
  if (Number.isFinite(spicyMax) && spicyMax < 4) {
    const filtered = pool.filter((f) => f.spicy <= spicyMax);
    if (filtered.length >= 4) pool = filtered;
  }
  return pool;
}

/* 今日推荐：按日期+时段确定性推荐 3 个（换一批用 reroll 种子） */
route('GET', '/api/street/menu', false, ({ res, query }) => {
  const pool = streetPool(query);
  if (pool.length < 3) throw new HttpError(503, 'not_enough_foods', '过滤条件太严，放宽预算或辣度试试');
  const date = (query.get('date') || localToday()).trim();
  const reroll = (query.get('reroll') || '').trim();
  const rng = rngFrom(`street|${date}|${query.get('slot')}|${query.get('budget')}|${query.get('spicy_max')}${reroll ? '|' + reroll : ''}`);
  const picks = [];
  const used = new Set();
  for (let i = 0; i < 3 && used.size < pool.length; i++) {
    let f = pool[Math.floor(rng() * pool.length)];
    for (let k = 0; k < 12 && used.has(f.id); k++) f = pool[Math.floor(rng() * pool.length)];
    if (used.has(f.id)) continue;
    used.add(f.id);
    picks.push(f);
  }
  sendJSON(res, 200, { ok: true, items: picks, pool_size: pool.length });
});

/* 转盘候选：n 个不重复（默认 6） */
route('GET', '/api/street/wheel', false, ({ res, query }) => {
  const pool = streetPool(query);
  const n = Math.min(8, Math.max(3, Number(query.get('n')) || 6));
  if (pool.length < n) throw new HttpError(503, 'not_enough_foods', '过滤条件太严，放宽预算或辣度试试');
  const seed = (query.get('seed') || String(Date.now()));
  const rng = rngFrom('wheel|' + seed);
  const items = [];
  const used = new Set();
  for (let i = 0; i < n && used.size < pool.length; i++) {
    let f = pool[Math.floor(rng() * pool.length)];
    for (let k = 0; k < 12 && used.has(f.id); k++) f = pool[Math.floor(rng() * pool.length)];
    if (used.has(f.id)) continue;
    used.add(f.id);
    items.push(f);
  }
  sendJSON(res, 200, { ok: true, items });
});

/* 打卡：吃了什么记一笔（登录用户） */
route('POST', '/api/street/checkin', true, ({ res, user, body }) => {
  const name = String(body.food_name || '').trim();
  if (!name || name.length > 30) throw bad('food_name 必填且不超过 30 字');
  const price = body.price == null || body.price === '' ? null : Number(body.price);
  if (price != null && (!Number.isFinite(price) || price < 0 || price > 9999)) throw bad('price 需为 0-9999');
  const kcal = body.kcal == null || body.kcal === '' ? null : Number(body.kcal);
  if (kcal != null && (!Number.isFinite(kcal) || kcal < 0 || kcal > 20000)) throw bad('kcal 需为 0-20000');
  const slot = ['breakfast', 'lunch', 'dinner', 'night'].includes(body.slot) ? body.slot : '';
  const date = String(body.date || localToday()).trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw bad('date 格式需为 YYYY-MM-DD');
  const ret = db.prepare('INSERT INTO street_checkins (user_id, food_name, price, kcal, slot, entry_date) VALUES (?, ?, ?, ?, ?, ?)')
    .run(user.id, name, price, Math.round(kcal || 0), slot, date);
  sendJSON(res, 200, { ok: true, id: Number(ret.lastInsertRowid) });
});

/* 外食小结：本月次数/花费/最常吃/最近记录 */
route('GET', '/api/street/summary', true, ({ req, res, user, query }) => {
  const month = (query.get('month') || localToday().slice(0, 7)).trim();
  if (!/^\d{4}-\d{2}$/.test(month)) throw bad('month 格式需为 YYYY-MM');
  const rows = db.prepare("SELECT food_name, price, entry_date FROM street_checkins WHERE user_id = ? AND entry_date LIKE ? ORDER BY id DESC").all(user.id, month + '-%');
  const count = rows.length;
  const totalPrice = rows.reduce((s, r) => s + (Number(r.price) || 0), 0);
  const freq = new Map();
  for (const r of rows) freq.set(r.food_name, (freq.get(r.food_name) || 0) + 1);
  const favs = [...freq.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3).map(([name, times]) => ({ name, times }));
  sendJSON(res, 200, {
    ok: true, month, count,
    total_price: round2(totalPrice),
    avg_price: count ? round2(totalPrice / count) : 0,
    favs,
    recent: rows.slice(0, 8).map((r) => ({ name: r.food_name, price: r.price, date: r.entry_date })),
  });
});

/* --- 用户上传菜谱制作流程图（先审后展：pending → 管理员 approve → 详情页可见） --- */
const PHOTO_DIR = path.join(__dirname, 'photos');
if (!fs.existsSync(PHOTO_DIR)) fs.mkdirSync(PHOTO_DIR, { recursive: true });
const DOWNLOAD_DIR = path.join(__dirname, 'downloads');

route('POST', '/api/recipes/(\\d+)/photos', true, ({ req, res, user, m, body }) => {
  if (!rateLimit('up:' + clientIp(req), 5, 60 * 60 * 1000)) throw new HttpError(429, 'rate_limited', '上传过于频繁，请明天再试');
  const recipeId = Number(m[1]);
  if (!db.prepare('SELECT id FROM recipes WHERE id = ?').get(recipeId)) throw new HttpError(404, 'recipe_not_found');
  let b64 = typeof body.image_base64 === 'string' ? body.image_base64.trim() : '';
  const dm = /^data:image\/(jpeg|jpg|png|webp);base64,/.exec(b64);
  if (dm) b64 = b64.slice(dm[0].length);
  if (b64.length < 100) throw bad('image_base64 必填');
  if (b64.length > 800 * 1024) throw bad('图片过大（base64 需 ≤ 800KB，请压缩后上传）');
  if (!/^[A-Za-z0-9+/=\s]+$/.test(b64)) throw bad('image_base64 格式不合法');
  const file = 'p' + Date.now() + '-' + crypto.randomBytes(4).toString('hex') + '.jpg';
  fs.writeFileSync(path.join(PHOTO_DIR, file), Buffer.from(b64.replace(/\s+/g, ''), 'base64'));
  const ret = db.prepare("INSERT INTO recipe_photos (recipe_id, user_id, file, status) VALUES (?, ?, ?, 'pending')")
    .run(recipeId, user.id, file);
  sendJSON(res, 200, { ok: true, photo_id: Number(ret.lastInsertRowid), status: 'pending', message: '已提交，审核通过后将展示在菜谱页' });
});

route('GET', '/api/recipes/(\\d+)/photos', false, ({ res, m }) => {
  const rows = db.prepare(`SELECT p.id, p.created_at, u.nickname FROM recipe_photos p
    LEFT JOIN users u ON u.id = p.user_id
    WHERE p.recipe_id = ? AND p.status = 'approved' ORDER BY p.id DESC LIMIT 20`).all(Number(m[1]));
  sendJSON(res, 200, { ok: true, items: rows.map((r) => ({ id: r.id, url: '/api/photos/' + r.id, nickname: r.nickname || '食友', created_at: r.created_at })) });
});

route('GET', '/api/photos/(\\d+)', false, ({ req, res, m }) => {
  const row = db.prepare("SELECT * FROM recipe_photos WHERE id = ? AND status = 'approved'").get(Number(m[1]));
  if (!row) throw new HttpError(404, 'photo_not_found');
  const f = path.join(PHOTO_DIR, path.basename(row.file));
  if (!fs.existsSync(f)) throw new HttpError(404, 'photo_not_found');
  const data = fs.readFileSync(f);
  res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Content-Length': data.length, 'Cache-Control': 'public, max-age=86400' });
  res.end(data);
});

/* --- 管理端：审核流程图 + 统计（X-Admin-Token 鉴权，密钥在 secrets/admin.json） --- */
let _adminCfg = null;
function adminConfig() {
  if (_adminCfg !== null) return _adminCfg;
  try { _adminCfg = JSON.parse(fs.readFileSync(path.join(SECRETS_DIR, 'admin.json'), 'utf8')) || {}; }
  catch { _adminCfg = {}; }
  return _adminCfg;
}
function adminToken() {
  return String(adminConfig().token || '');
}
function requireAdmin(req) {
  const t = String(req.headers['x-admin-token'] || '');
  if (!adminToken()) throw new HttpError(503, 'admin_disabled', '服务器未配置管理员密钥');
  if (!t || t !== adminToken()) throw new HttpError(401, 'unauthorized', '管理员令牌无效');
}

route('GET', '/api/admin/stats', false, ({ req, res }) => {
  requireAdmin(req);
  const c = (sql) => Number(db.prepare(sql).get().c);
  sendJSON(res, 200, {
    ok: true,
    users: c('SELECT COUNT(*) AS c FROM users'),
    recipes: c("SELECT COUNT(*) AS c FROM recipes WHERE source = 'official'"),
    user_recipes: c("SELECT COUNT(*) AS c FROM recipes WHERE source = 'user'"),
    posts: c('SELECT COUNT(*) AS c FROM posts'),
    comments: c('SELECT COUNT(*) AS c FROM comments'),
    diary_entries: c('SELECT COUNT(*) AS c FROM diary_entries'),
    photos_pending: c("SELECT COUNT(*) AS c FROM recipe_photos WHERE status = 'pending'"),
    photos_approved: c("SELECT COUNT(*) AS c FROM recipe_photos WHERE status = 'approved'"),
    sessions_active: c("SELECT COUNT(*) AS c FROM sessions WHERE expires_at IS NULL OR expires_at > datetime('now','localtime')"),
    uptime_sec: Math.floor((Date.now() - START_TS) / 1000),
    time: new Date().toISOString(),
  });
});

route('GET', '/api/admin/photos', false, ({ req, res, query }) => {
  requireAdmin(req);
  const status = ['pending', 'approved', 'rejected'].includes(query.get('status')) ? query.get('status') : 'pending';
  const limit = Math.min(50, Math.max(1, Number(query.get('limit')) || 30));
  const rows = db.prepare(`SELECT p.*, r.name AS recipe_name, u.nickname FROM recipe_photos p
    LEFT JOIN recipes r ON r.id = p.recipe_id LEFT JOIN users u ON u.id = p.user_id
    WHERE p.status = ? ORDER BY p.id ASC LIMIT ?`).all(status, limit);
  const items = rows.map((r) => {
    let dataUrl = null;
    try {
      const f = path.join(PHOTO_DIR, path.basename(r.file));
      if (fs.existsSync(f)) dataUrl = 'data:image/jpeg;base64,' + fs.readFileSync(f).toString('base64');
    } catch { }
    return { id: r.id, recipe_id: r.recipe_id, recipe_name: r.recipe_name, nickname: r.nickname, status: r.status, created_at: r.created_at, data_url: dataUrl };
  });
  sendJSON(res, 200, { ok: true, items });
});

route('POST', '/api/admin/photos/(\\d+)/review', false, ({ req, res, m, body }) => {
  requireAdmin(req);
  const action = String(body.action || '');
  if (!['approve', 'reject'].includes(action)) throw bad("action 需为 'approve' 或 'reject'");
  const row = db.prepare('SELECT * FROM recipe_photos WHERE id = ?').get(Number(m[1]));
  if (!row) throw new HttpError(404, 'photo_not_found');
  const status = action === 'approve' ? 'approved' : 'rejected';
  db.prepare('UPDATE recipe_photos SET status = ? WHERE id = ?').run(status, row.id);
  if (status === 'rejected') {
    try { fs.unlinkSync(path.join(PHOTO_DIR, path.basename(row.file))); } catch { }
  }
  sendJSON(res, 200, { ok: true, id: row.id, status });
});

/* --- APK 下载（白名单仅 .apk，供直接分发） --- */
route('GET', '/downloads/([A-Za-z0-9._-]+\\.apk)', false, ({ res, m }) => {
  const f = path.join(DOWNLOAD_DIR, path.basename(m[1]));
  if (!fs.existsSync(f)) throw new HttpError(404, 'not_found');
  const data = fs.readFileSync(f);
  res.writeHead(200, {
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Length': data.length,
    'Content-Disposition': 'attachment; filename="' + path.basename(m[1]) + '"',
  });
  res.end(data);
});

/* --- 用户发布菜谱（AI 量化结果确认后入库，source='user'） --- */
route('POST', '/api/recipes', true, ({ res, user, body }) => {
  const name = String(body.name || '').trim();
  if (!name || name.length > 30) throw bad('name 必填且不超过 30 字');
  if (db.prepare('SELECT id FROM recipes WHERE name = ?').get(name)) {
    throw new HttpError(409, 'recipe_exists', '菜谱库已有同名菜谱');
  }
  const intro = String(body.intro || '').trim().slice(0, 60);
  const category = String(body.category || '家常小炒').trim().slice(0, 10) || '家常小炒';
  const meatTag = body.meat_tag === '荤' ? '荤' : '素';
  const difficulty = Math.min(3, Math.max(1, Math.round(Number(body.difficulty) || 1)));
  const minutes = Math.min(600, Math.max(1, Math.round(Number(body.minutes) || 30)));
  const kcal = Math.round(Number(body.nutrition && body.nutrition.kcal) || 0);
  if (kcal < 10 || kcal > 2000) throw bad('nutrition.kcal 需在 10~2000 之间');
  const tier = kcal <= 250 ? '低' : kcal <= 500 ? '中' : '高';
  const protein = Math.max(0, Math.min(300, Number(body.nutrition && body.nutrition.protein) || 0));
  const fat = Math.max(0, Math.min(300, Number(body.nutrition && body.nutrition.fat) || 0));
  const carb = Math.max(0, Math.min(500, Number(body.nutrition && body.nutrition.carb) || 0));
  const ingredients = (Array.isArray(body.ingredients) ? body.ingredients : [])
    .map((i) => ({ name: String((i && i.name) || '').trim().slice(0, 20), amount: String((i && i.amount) || '').trim().slice(0, 30) }))
    .filter((i) => i.name).slice(0, 25);
  const steps = (Array.isArray(body.steps) ? body.steps : [])
    .map((s) => String(s || '').trim().slice(0, 200)).filter(Boolean).slice(0, 25);
  if (!ingredients.length) throw bad('ingredients 不能为空');
  if (!steps.length) throw bad('steps 不能为空');
  const info = db.prepare(`INSERT INTO recipes
    (name, intro, category, meat_tag, difficulty, minutes, calorie_tier, ingredients_json, steps_json, kcal, protein, fat, carb, source)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'user')`)
    .run(name, intro, category, meatTag, difficulty, minutes, tier, JSON.stringify(ingredients), JSON.stringify(steps), kcal, protein, fat, carb);
  const row = db.prepare('SELECT * FROM recipes WHERE id = ?').get(info.lastInsertRowid);
  _ingIndex = null; // 新菜谱入库后失效食材索引
  sendJSON(res, 200, { ok: true, item: recipeOut(row, { liked: false, favorited: false }) });
});

/* --- 点赞/收藏（服务端计数 + 用户去重） --- */
route('POST', '/api/recipes/(\\d+)/like', true, ({ res, m, user }) => sendJSON(res, 200, toggleLike(user, Number(m[1]), true)));
route('DELETE', '/api/recipes/(\\d+)/like', true, ({ res, m, user }) => sendJSON(res, 200, toggleLike(user, Number(m[1]), false)));
route('POST', '/api/recipes/(\\d+)/favorite', true, ({ res, m, user }) => sendJSON(res, 200, toggleFavorite(user, Number(m[1]), true)));
route('DELETE', '/api/recipes/(\\d+)/favorite', true, ({ res, m, user }) => sendJSON(res, 200, toggleFavorite(user, Number(m[1]), false)));

route('GET', '/api/me/favorites', true, ({ res, user }) => {
  const rows = db.prepare(`SELECT r.*, f.created_at AS favorited_at FROM favorites f
    JOIN recipes r ON r.id = f.recipe_id WHERE f.user_id = ? ORDER BY f.created_at DESC, r.id DESC`).all(user.id);
  sendJSON(res, 200, { items: rows.map((r) => ({ ...recipeOut(r), favorited_at: r.favorited_at })) });
});

/* --- 用户食材柜（支持保质期：{items:[{name,shelf_life_days?}]} 或旧格式 {names:[...]}） --- */
route('GET', '/api/me/ingredients', true, ({ res, user }) => {
  const rows = db.prepare('SELECT name, shelf_life_days, created_at FROM user_ingredients WHERE user_id = ? ORDER BY rowid').all(user.id);
  const items = rows.map((r) => {
    let days_left = null;
    if (r.shelf_life_days) {
      const added = new Date(String(r.created_at).replace(' ', 'T'));
      const expires = new Date(added.getTime() + Number(r.shelf_life_days) * 86400000);
      days_left = Math.ceil((expires.getTime() - Date.now()) / 86400000);
    }
    return { name: r.name, shelf_life_days: r.shelf_life_days, days_left };
  });
  sendJSON(res, 200, {
    items,
    names: rows.map((r) => r.name),
    alerts: items.filter((i) => i.days_left !== null && i.days_left <= 3),
  });
});

route('PUT', '/api/me/ingredients', true, ({ res, user, body }) => {
  // 两种入参：{names:[...]}（无保质期）或 {items:[{name, shelf_life_days?}]}
  let items = [];
  if (Array.isArray(body.items)) {
    items = body.items
      .map((i) => ({ name: String((i && i.name) || '').trim().slice(0, 30), shelf: Number(i && i.shelf_life_days) || null }))
      .filter((i) => i.name);
  } else if (Array.isArray(body.names)) {
    items = body.names.map((x) => ({ name: String(x).trim(), shelf: null })).filter((i) => i.name);
  } else {
    throw bad('需要 names 或 items 数组');
  }
  // 同名去重（保留最后一个的保质期设置）
  const uniq = new Map();
  for (const i of items) uniq.set(i.name, i);
  if (uniq.size > 200) throw bad('食材数量过多（≤200）');
  db.exec('BEGIN');
  try {
    db.prepare('DELETE FROM user_ingredients WHERE user_id = ?').run(user.id);
    const ins = db.prepare('INSERT OR IGNORE INTO user_ingredients (user_id, name, shelf_life_days) VALUES (?, ?, ?)');
    for (const i of uniq.values()) ins.run(user.id, i.name, i.shelf);
    db.exec('COMMIT');
  } catch (e) { db.exec('ROLLBACK'); throw e; }
  const rows = db.prepare('SELECT name FROM user_ingredients WHERE user_id = ? ORDER BY rowid').all(user.id);
  sendJSON(res, 200, { ok: true, names: rows.map((r) => r.name) });
});

/* --- 按现有食材推荐 --- */
const EXEMPT_INGREDIENTS = ['油', '盐', '糖', '生抽', '老抽', '醋', '料酒', '葱', '姜', '蒜', '鸡精', '白胡椒粉', '清水'];
route('GET', '/api/recommend/by-ingredients', true, ({ res, user, query }) => {
  const mode = query.get('mode');
  if (mode !== 'strict' && mode !== 'best') throw bad("mode 必须为 'strict' 或 'best'");
  const pantryRows = db.prepare('SELECT name FROM user_ingredients WHERE user_id = ?').all(user.id);
  const pantry = new Set(pantryRows.map((r) => r.name));
  const isExempt = (name) => EXEMPT_INGREDIENTS.some((t) => name.includes(t));
  const prefs = prefsFor(user);
  const items = [];
  for (const row of loadAllRecipes()) {
    let ings = [];
    try { ings = JSON.parse(row.ingredients_json); } catch { }
    const ingText = JSON.stringify(ings);
    // 忌口/过敏/不喜欢：命中的菜直接不推荐
    const banned = [...prefs.allergens, ...prefs.dislikes].find((kw) => ingText.includes(kw));
    if (banned) continue;
    const required = ings.map((i) => i.name).filter((n) => !isExempt(n));
    const missing = required.filter((n) => !pantry.has(n));
    const matched = required.length - missing.length;
    const warnings = matchWarnings(row, prefs);
    if (mode === 'strict') {
      if (missing.length === 0 && required.length > 0) {
        items.push({ recipe: recipeOut(row), matched, required_total: required.length, missing: [], warnings });
      }
    } else {
      if (missing.length >= 1 && missing.length <= 2) {
        items.push({ recipe: recipeOut(row), matched, required_total: required.length, missing, warnings });
      }
    }
  }
  if (mode === 'strict') {
    items.sort((a, b) => (b.matched - a.matched) || (b.required_total - a.required_total));
  } else {
    items.sort((a, b) => (a.missing.length - b.missing.length) || (b.matched - a.matched));
  }
  sendJSON(res, 200, { mode, pantry: [...pantry], exempt: EXEMPT_INGREDIENTS, items });
});

/* --- 购物清单 --- */
route('GET', '/api/shopping-list', true, ({ res, user }) => {
  const rows = db.prepare('SELECT id, name, created_at FROM shopping_list WHERE user_id = ? ORDER BY id DESC').all(user.id);
  sendJSON(res, 200, { items: rows });
});

route('POST', '/api/shopping-list', true, ({ res, user, body }) => {
  const name = typeof body.name === 'string' ? body.name.trim() : '';
  if (!name || name.length > 30) throw bad('name 必填且不超过 30 字');
  const ret = db.prepare('INSERT INTO shopping_list (user_id, name) VALUES (?, ?)').run(user.id, name);
  const row = db.prepare('SELECT id, name, created_at FROM shopping_list WHERE id = ?').get(Number(ret.lastInsertRowid));
  sendJSON(res, 200, { ok: true, item: row });
});

route('DELETE', '/api/shopping-list/(\\d+)', true, ({ res, m, user }) => {
  const id = Number(m[1]);
  const row = db.prepare('SELECT * FROM shopping_list WHERE id = ? AND user_id = ?').get(id, user.id);
  if (!row) throw new HttpError(404, 'shopping_item_not_found');
  db.prepare('DELETE FROM shopping_list WHERE id = ?').run(id);
  // 删除 = 已购买，自动加入食材柜
  const ins = db.prepare('INSERT OR IGNORE INTO user_ingredients (user_id, name) VALUES (?, ?)').run(user.id, row.name);
  sendJSON(res, 200, { ok: true, purchased: row.name, added_to_pantry: Number(ins.changes) > 0 });
});

/* --- 排行榜（like+favorite+views，前 50；带完整营养与用户收藏态） --- */
route('GET', '/api/leaderboard', false, ({ req, res, query }) => {
  const SORTS = {
    hot: '(views + like_count * 3 + favorite_count * 5) DESC, id ASC',
    new: 'id DESC',
    views: 'views DESC, id ASC',
    likes: 'like_count DESC, views DESC, id ASC',
    favs: 'favorite_count DESC, views DESC, id ASC',
    ratio: '(like_count * 1.0 / (views + 5)) DESC, views DESC, id ASC', // 好评率：浏览≥5 才有区分度
  };
  const sort = SORTS[query.get('sort')] || SORTS.hot;
  const rows = db.prepare(`SELECT id, name, intro, category, meat_tag, difficulty, minutes, calorie_tier,
    ingredients_json, steps_json, kcal, protein, fat, carb, source, views, like_count, favorite_count
    FROM recipes ORDER BY ${sort} LIMIT 50`).all();
  const optUser = getUserByToken(req);
  const sm = userStateMap(optUser && optUser.id, rows.map((r) => r.id));
  const items = rows.map((r) => recipeOut(r, sm.get(r.id)));
  const me = optUser
    ? { favorite_count: Number(db.prepare('SELECT COUNT(*) AS c FROM favorites WHERE user_id = ?').get(optUser.id).c) }
    : null;
  sendJSON(res, 200, { items, me });
});

/* --- 周报：过去 N 天每日推荐计划的热量曲线与贴合评分（daily 按日期确定性可复算） --- */
route('GET', '/api/recipes/weekly-report', true, ({ req, res, query, user }) => {
  let target = parseTarget(query);
  if (query.get('family') === '1') {
    const ft = familyTargets(user);
    if (ft.total >= 600) target = ft.total;
  }
  const daysN = Math.min(14, Math.max(3, Number(query.get('days')) || 7));
  const recipes = loadAllRecipes();
  if (recipes.length < 3) throw new HttpError(503, 'not_enough_recipes', '菜谱库不足');
  const list = [];
  for (let i = daysN - 1; i >= 0; i--) {
    const date = addDays(localToday(), -i);
    const plan = pickDayPlan(recipes, target, rngFrom(`daily|${date}|${target}`), new Map());
    list.push({
      date,
      total_kcal: round2(plan.total),
      within_range: plan.total >= target * 0.85 && plan.total <= target * 1.15,
      deviation: Math.round(plan.total - target),
    });
  }
  const onTarget = list.filter((d) => d.within_range).length;
  const avg = round2(list.reduce((s, d) => s + d.total_kcal, 0) / list.length);
  // 评分：贴合天数占比 × 70 + 平均偏差越小越好（最多 30 分）
  const devPenalty = Math.min(30, Math.abs(avg - target) / target * 100 * 1.5);
  const score = Math.max(0, Math.min(100, Math.round((onTarget / list.length) * 70 + (30 - devPenalty))));
  sendJSON(res, 200, {
    ok: true, target, days: list, avg_kcal: avg,
    on_target_days: onTarget, total_days: list.length, score,
  });
});

/* --- 随机"今天吃啥"：随机一道菜（可按分类过滤） --- */
route('GET', '/api/recipes/random', false, ({ res, query }) => {
  const category = (query.get('category') || '').trim();
  const rows = category
    ? db.prepare('SELECT * FROM recipes WHERE category = ? ORDER BY RANDOM() LIMIT 1').all(category)
    : db.prepare('SELECT * FROM recipes ORDER BY RANDOM() LIMIT 1').all();
  if (!rows.length) throw new HttpError(404, 'recipe_not_found');
  db.prepare('UPDATE recipes SET views = views + 1 WHERE id = ?').run(rows[0].id);
  rows[0].views = Number(rows[0].views) + 1;
  sendJSON(res, 200, { ok: true, item: recipeOut(rows[0], null) });
});

/* --- 菜品封面静态服务（Wikimedia 爬虫产物，仅允许 recipe-<id>.jpg 白名单格式） --- */
route('GET', '/covers/([A-Za-z0-9._-]+)', false, ({ res, m }) => {
  const file = m[1];
  if (!/^recipe-\d+\.jpg$/.test(file)) throw new HttpError(404, 'not_found');
  const p = path.join(COVER_DIR, file);
  if (!fs.existsSync(p)) throw new HttpError(404, 'not_found');
  res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'public, max-age=604800' });
  res.end(fs.readFileSync(p));
});

/* --- 同城（按城市过滤 posts，骨架实现） --- */
route('GET', '/api/nearby', false, ({ res, query }) => {
  const city = (query.get('city') || '').trim();
  if (!city) throw bad('city 必填');
  const rows = db.prepare(`${POST_SELECT} WHERE p.city = ? ORDER BY p.id DESC LIMIT 50`).all(city);
  sendJSON(res, 200, { city, items: rows.map(postOut) });
});

/* --- 社区动态（骨架实现） --- */
route('POST', '/api/posts', true, ({ res, user, body }) => {
  const text = typeof body.text === 'string' ? body.text.trim() : '';
  if (!text || text.length > 2000) throw bad('text 必填且不超过 2000 字');
  let recipeId = null;
  if (body.recipe_id !== undefined && body.recipe_id !== null) {
    recipeId = Number(body.recipe_id);
    if (!Number.isInteger(recipeId) || !db.prepare('SELECT id FROM recipes WHERE id = ?').get(recipeId)) {
      throw bad('recipe_id 不存在');
    }
  }
  let imageUrls = [];
  if (body.image_urls !== undefined) {
    if (!Array.isArray(body.image_urls) || body.image_urls.length > 9
      || !body.image_urls.every((x) => typeof x === 'string' && x.length <= 500)) {
      throw bad('image_urls 必须为不超过 9 个的字符串数组');
    }
    imageUrls = body.image_urls;
  }
  const city = typeof body.city === 'string' ? body.city.trim().slice(0, 20) : '';
  const ret = db.prepare('INSERT INTO posts (user_id, text, image_urls_json, recipe_id, city) VALUES (?, ?, ?, ?, ?)')
    .run(user.id, text, JSON.stringify(imageUrls), recipeId, city);
  const row = db.prepare(`${POST_SELECT} WHERE p.id = ?`).get(Number(ret.lastInsertRowid));
  sendJSON(res, 200, { ok: true, post: postOut(row) });
});

route('GET', '/api/posts', false, ({ res, query }) => {
  const tab = query.get('tab') || 'latest';
  if (tab !== 'latest' && tab !== 'recommend') throw bad("tab 只能为 'recommend' 或 'latest'");
  const orderSQL = tab === 'recommend' ? 'ORDER BY comment_count DESC, p.id DESC' : 'ORDER BY p.id DESC';
  const rows = db.prepare(`${POST_SELECT} ${orderSQL} LIMIT 50`).all();
  sendJSON(res, 200, { tab, items: rows.map(postOut) });
});

route('POST', '/api/posts/(\\d+)/comments', true, ({ res, m, user, body }) => {
  const postId = Number(m[1]);
  if (!db.prepare('SELECT id FROM posts WHERE id = ?').get(postId)) throw new HttpError(404, 'post_not_found');
  const text = typeof body.text === 'string' ? body.text.trim() : '';
  if (!text || text.length > 500) throw bad('text 必填且不超过 500 字');
  const ret = db.prepare('INSERT INTO comments (post_id, user_id, text) VALUES (?, ?, ?)').run(postId, user.id, text);
  const row = db.prepare('SELECT * FROM comments WHERE id = ?').get(Number(ret.lastInsertRowid));
  sendJSON(res, 200, { ok: true, comment: row });
});

route('GET', '/api/posts/(\\d+)/comments', false, ({ res, m }) => {
  const postId = Number(m[1]);
  const rows = db.prepare(`SELECT c.*, u.nickname FROM comments c LEFT JOIN users u ON u.id = c.user_id
    WHERE c.post_id = ? ORDER BY c.id ASC LIMIT 200`).all(postId);
  sendJSON(res, 200, { items: rows });
});

/* --- AI：文本菜谱结构化（真实调用 LLM，推理模型：答案在 content） --- */
/* 云端 AI 仅限管理员账号（secrets/admin.json 配 phone 才启用门禁，未配则所有登录用户可用） */
function requireCloudAi(user) {
  const phone = adminConfig().phone;
  if (!phone) return;
  if (!user || String(user.phone) !== String(phone)) {
    throw new HttpError(403, 'cloud_ai_admin_only', '云端 AI 仅限管理员账号使用；其他用户请在「设置 → AI 识别接口」接入自己的 API');
  }
}

route('POST', '/api/ai/quantify-recipe', true, async ({ req, res, user, body }) => {
  requireCloudAi(user);
  if (!rateLimit('ai:' + clientIp(req), 6, 60 * 1000)) throw new HttpError(429, 'rate_limited', 'AI 调用过于频繁（每分钟 6 次）');
  const text = typeof body.text === 'string' ? body.text.trim() : '';
  if (!text || text.length > 4000) throw bad('text 必填且不超过 4000 字');
  const sys = '你是食谱结构化助手。将用户提供的菜谱文本解析为 JSON，只输出 JSON 本身，'
    + '禁止输出任何解释、前后缀或 markdown 代码块。JSON 格式：'
    + '{"菜名":字符串,"简介":一句话,"分类":"家常小炒/汤羹/主食/海鲜/凉菜/烘焙"之一,'
    + '"食材":[{"name":字符串,"amount":字符串}],"步骤":[字符串数组],"时长分钟":数字,'
    + '"营养":{"kcal":数字,"protein":数字,"fat":数字,"carb":数字},"健康评分":1到10的整数}';
  const msg = await llmChat([
    { role: 'system', content: sys },
    { role: 'user', content: text },
  ], 2048);
  const parsed = extractJSON(msg.content);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new HttpError(502, 'llm_parse_failed', '模型原始输出：' + msg.content);
  }
  sendJSON(res, 200, { ok: true, recipe: parsed });
});

/* --- AI：拍冰箱识别食材（支持多图合并识别，最多 4 张） --- */
route('POST', '/api/ai/recognize-ingredients', true, async ({ req, res, user, body }) => {
  requireCloudAi(user);
  if (!rateLimit('ai:' + clientIp(req), 6, 60 * 1000)) throw new HttpError(429, 'rate_limited', 'AI 调用过于频繁（每分钟 6 次）');
  const images = [];
  const pushB64 = (raw) => {
    let b64 = typeof raw === 'string' ? raw.trim() : '';
    if (!b64) return;
    const dm = /^data:image\/[a-zA-Z+.-]+;base64,/.exec(b64);
    if (dm) b64 = b64.slice(dm[0].length);
    if (b64.length < 100 || b64.length > 10 * 1024 * 1024 || !/^[A-Za-z0-9+/=\s]+$/.test(b64)) return;
    images.push(b64);
  };
  if (Array.isArray(body.images)) body.images.forEach(pushB64);
  pushB64(body.image_base64); // 兼容旧单图字段
  if (!images.length) throw bad('images 必填（1~4 张 base64 图片）');
  if (images.length > 4) images.length = 4;

  const content = [
    { type: 'text', text: '这些是用户冰箱/厨房的照片（可能有多张，拍的是同一批食材）。识别所有照片中出现的可食用食材，去重后合并为一个清单；包装食品给出通用食材名（如"牛奶""豆腐"），不要输出品牌、厨具、餐具。只输出 JSON 字符串数组，例如 ["番茄","鸡蛋","牛奶"]，不要输出任何其他内容。' },
    ...images.map((b64) => ({ type: 'image_url', image_url: { url: 'data:image/jpeg;base64,' + b64 } })),
  ];
  const msg = await llmChat([{ role: 'user', content }], 4096, 150000);
  let parsed = extractJSON(msg.content);
  if (!Array.isArray(parsed) && msg.reasoning_content) {
    // 推理模型可能在思维链里输出 JSON，兜底提取
    parsed = extractJSON(String(msg.reasoning_content));
  }
  if (!Array.isArray(parsed)) throw new HttpError(502, 'llm_parse_failed', '模型原始输出：' + (msg.content || '(空)') + (msg.reasoning_content ? ' | 思维链尾部：' + String(msg.reasoning_content).slice(-300) : ''));
  const uniq = [...new Set(parsed.map((x) => String(x).trim()).filter(Boolean))];
  sendJSON(res, 200, { ok: true, ingredients: uniq, image_count: images.length });
});

/* ---------------- HTTP 服务与分发 ---------------- */

const server = http.createServer(async (req, res) => {
  try {
    // 全局 IP 限流：120 次/分钟，防爬虫与滥用
    if (!rateLimit('g:' + clientIp(req), 120, 60 * 1000)) {
      return sendJSON(res, 429, { error: 'rate_limited', detail: '请求过于频繁，请稍后再试' });
    }
    const u = new URL(req.url, 'http://localhost');
    let pathName;
    try { pathName = decodeURIComponent(u.pathname); } catch { throw bad('bad_url'); }
    let pathMatched = false;
    for (const r of routes) {
      const m = pathName.match(r.regex);
      if (!m) continue;
      pathMatched = true;
      if (r.method !== req.method) continue;
      const user = r.auth ? requireAuth(req) : null;
      const body = ['POST', 'PUT', 'DELETE', 'PATCH'].includes(req.method) ? await readJSON(req) : {};
      await r.handler({ req, res, m, body, user, query: u.searchParams });
      return;
    }
    if (pathMatched) throw new HttpError(405, 'method_not_allowed');
    throw new HttpError(404, 'not_found');
  } catch (e) {
    if (res.headersSent) { try { res.end(); } catch { } return; }
    if (e instanceof HttpError) {
      sendJSON(res, e.status, { error: e.code, detail: e.detail });
    } else {
      console.error('[error]', req.method, req.url, (e && e.stack) || e);
      sendJSON(res, 500, { error: 'internal_error' });
    }
  }
});

server.listen(PORT, HOST, () => {
  console.log(`[server] shiguang-backend listening on ${HOST}:${PORT}`);
});
server.on('error', (e) => { console.error('[server] fatal:', e); process.exit(1); });
process.on('uncaughtException', (e) => { console.error('[uncaught]', e); process.exit(1); });
process.on('unhandledRejection', (e) => { console.error('[unhandled]', e); process.exit(1); });
process.on('SIGTERM', () => { console.log('[server] SIGTERM received, exiting'); process.exit(0); });
