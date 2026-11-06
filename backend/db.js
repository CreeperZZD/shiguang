'use strict';
/*
 * 食光后端 - 数据库层（node:sqlite，零 npm 依赖）
 * 库文件：backend/data/shiguang.db（WAL 模式）
 * 首次启动且 recipes 表为空时自动导入 seed-recipes.json
 */
const { DatabaseSync } = require('node:sqlite');
const fs = require('node:fs');
const path = require('node:path');

const DATA_DIR = path.join(__dirname, 'data');
if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
const DB_PATH = path.join(DATA_DIR, 'shiguang.db');

const db = new DatabaseSync(DB_PATH);
db.exec('PRAGMA journal_mode = WAL;');
db.exec('PRAGMA busy_timeout = 5000;');

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  phone TEXT NOT NULL UNIQUE,
  nickname TEXT NOT NULL DEFAULT '',
  gender TEXT,
  age INTEGER,
  height_cm REAL,
  weight_kg REAL,
  activity REAL,
  goal TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
  expires_at TEXT
);
CREATE TABLE IF NOT EXISTS recipes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  intro TEXT NOT NULL DEFAULT '',
  category TEXT NOT NULL DEFAULT '家常小炒',
  meat_tag TEXT NOT NULL DEFAULT '素',
  difficulty INTEGER NOT NULL DEFAULT 1,
  minutes INTEGER NOT NULL DEFAULT 30,
  calorie_tier TEXT NOT NULL DEFAULT '中',
  ingredients_json TEXT NOT NULL DEFAULT '[]',
  steps_json TEXT NOT NULL DEFAULT '[]',
  kcal REAL NOT NULL DEFAULT 0,
  protein REAL NOT NULL DEFAULT 0,
  fat REAL NOT NULL DEFAULT 0,
  carb REAL NOT NULL DEFAULT 0,
  views INTEGER NOT NULL DEFAULT 0,
  like_count INTEGER NOT NULL DEFAULT 0,
  favorite_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS likes (
  user_id INTEGER NOT NULL,
  recipe_id INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
  PRIMARY KEY (user_id, recipe_id)
);
CREATE TABLE IF NOT EXISTS favorites (
  user_id INTEGER NOT NULL,
  recipe_id INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
  PRIMARY KEY (user_id, recipe_id)
);
CREATE TABLE IF NOT EXISTS user_ingredients (
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
  PRIMARY KEY (user_id, name)
);
CREATE TABLE IF NOT EXISTS shopping_list (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS posts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  text TEXT NOT NULL,
  image_urls_json TEXT NOT NULL DEFAULT '[]',
  recipe_id INTEGER,
  city TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS comments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  post_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  text TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS video_cache (
  recipe_name TEXT PRIMARY KEY,
  items_json  TEXT,
  fetched_at  TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS family_members (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  gender TEXT,
  age INTEGER,
  height_cm REAL,
  weight_kg REAL,
  body_fat_pct REAL,
  activity REAL,
  goal TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS diary_entries (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  entry_date TEXT NOT NULL,
  meal_key TEXT NOT NULL,
  recipe_id INTEGER,
  recipe_name TEXT NOT NULL,
  kcal REAL,
  servings REAL,
  note TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_recipes_category ON recipes(category);
CREATE INDEX IF NOT EXISTS idx_posts_city ON posts(city);
CREATE INDEX IF NOT EXISTS idx_comments_post ON comments(post_id);
CREATE INDEX IF NOT EXISTS idx_diary_user_date ON diary_entries(user_id, entry_date);
`);

/* 增量迁移：recipes 表加 source 列（official=官方库 / user=用户分享），幂等 */
(function addSourceColumn() {
  try { db.exec(`ALTER TABLE recipes ADD COLUMN source TEXT NOT NULL DEFAULT 'official'`); }
  catch (e) { if (!/duplicate column/i.test(String(e && e.message))) throw e; }
})();

/* 增量迁移：user_ingredients 表加保质期列（选填，天数），幂等 */
(function addShelfLifeColumn() {
  try { db.exec(`ALTER TABLE user_ingredients ADD COLUMN shelf_life_days INTEGER`); }
  catch (e) { if (!/duplicate column/i.test(String(e && e.message))) throw e; }
})();

/* 增量迁移：users 表加 prefs_json 列（忌口/偏好/病理等饮食偏好），幂等 */
(function addPrefsColumn() {
  try { db.exec(`ALTER TABLE users ADD COLUMN prefs_json TEXT`); }
  catch (e) { if (!/duplicate column/i.test(String(e && e.message))) throw e; }
})();

/* 增量迁移：users 表加 email 列（邮箱注册，选填唯一），幂等 */
(function addEmailColumn() {
  try { db.exec(`ALTER TABLE users ADD COLUMN email TEXT`); }
  catch (e) { if (!/duplicate column/i.test(String(e && e.message))) throw e; }
})();

/* 增量迁移：邮箱验证码表（有效期/尝试次数/IP 审计），幂等 */
(function addEmailCodesTable() {
  db.exec(`
  CREATE TABLE IF NOT EXISTS email_codes (
    email TEXT PRIMARY KEY,
    code TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_ip TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
  );`);
})();

/* 增量迁移：用户上传菜谱制作流程图（pending → admin 审核 → approved/rejected），幂等 */
(function addRecipePhotosTable() {
  db.exec(`
  CREATE TABLE IF NOT EXISTS recipe_photos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recipe_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    file TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
  );
  CREATE INDEX IF NOT EXISTS idx_recipe_photos_recipe ON recipe_photos(recipe_id, status);
  `);
})();

/* 增量迁移：干饭街（大学生/上班族模式）——美食街单人餐清单 + 外食打卡记账，幂等 */
(function addStreetTables() {
  db.exec(`
  CREATE TABLE IF NOT EXISTS street_foods (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    category TEXT NOT NULL,
    price REAL NOT NULL,
    kcal INTEGER NOT NULL,
    spicy INTEGER NOT NULL DEFAULT 0,
    slots TEXT NOT NULL DEFAULT 'lunch,dinner',
    tags TEXT NOT NULL DEFAULT '',
    intro TEXT NOT NULL DEFAULT ''
  );
  CREATE TABLE IF NOT EXISTS street_checkins (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    food_name TEXT NOT NULL,
    price REAL,
    kcal INTEGER,
    slot TEXT NOT NULL DEFAULT '',
    entry_date TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
  );
  CREATE INDEX IF NOT EXISTS idx_street_checkins_user ON street_checkins(user_id, entry_date);
  `);
  const row = db.prepare('SELECT COUNT(*) AS c FROM street_foods').get();
  if (Number(row.c) === 0) {
    const seed = JSON.parse(fs.readFileSync(path.join(__dirname, 'seed-street.json'), 'utf8'));
    const ins = db.prepare('INSERT INTO street_foods (name, category, price, kcal, spicy, slots, tags, intro) VALUES (?, ?, ?, ?, ?, ?, ?, ?)');
    db.exec('BEGIN');
    try {
      for (const s of seed) ins.run(s.name, s.category, s.price, s.kcal, s.spicy || 0, s.slots || 'lunch,dinner', s.tags || '', s.intro || '');
      db.exec('COMMIT');
      console.log('[db] 已导入干饭街菜单 ' + seed.length + ' 条');
    } catch (e) {
      db.exec('ROLLBACK');
      throw e;
    }
  }
})();

/* 首启自动导入种子菜谱（仅当表为空） */
(function seedIfEmpty() {
  const row = db.prepare('SELECT COUNT(*) AS c FROM recipes').get();
  if (Number(row.c) > 0) return;
  const seed = JSON.parse(fs.readFileSync(path.join(__dirname, 'seed-recipes.json'), 'utf8'));
  const ins = db.prepare(`INSERT INTO recipes
    (name, intro, category, meat_tag, difficulty, minutes, calorie_tier, ingredients_json, steps_json, kcal, protein, fat, carb)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
  db.exec('BEGIN');
  try {
    for (const r of seed) {
      ins.run(
        r.name, r.intro || '', r.category || '家常小炒', r.meat_tag || '素',
        r.difficulty || 1, r.minutes || 30, r.calorie_tier || '中',
        JSON.stringify(r.ingredients || []), JSON.stringify(r.steps || []),
        (r.nutrition && r.nutrition.kcal) || 0,
        (r.nutrition && r.nutrition.protein) || 0,
        (r.nutrition && r.nutrition.fat) || 0,
        (r.nutrition && r.nutrition.carb) || 0
      );
    }
    db.exec('COMMIT');
    console.log('[db] 已导入种子菜谱 ' + seed.length + ' 道 -> ' + DB_PATH);
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
})();

module.exports = { db, DB_PATH };
