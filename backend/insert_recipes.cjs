/* 食光 · 菜谱入库脚本：合并手写菜到 seed-recipes-extra.json，去重后插入 SQLite */
const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');

const BASE = '/opt/recipe-app/backend';
const extra = JSON.parse(fs.readFileSync(BASE + '/seed-recipes-extra.json', 'utf8'));
const hand = JSON.parse(fs.readFileSync(BASE + '/hand-recipes.json', 'utf8'));

const merged = [...extra];
const names = new Set(merged.map((r) => r.name));
for (const r of hand) {
  if (!names.has(r.name)) { merged.push(r); names.add(r.name); }
}
fs.writeFileSync(BASE + '/seed-recipes-extra.json', JSON.stringify(merged, null, 1));

const db = new DatabaseSync(BASE + '/data/shiguang.db');
const existing = new Set(db.prepare('SELECT name FROM recipes').all().map((r) => r.name));
const ins = db.prepare(`INSERT INTO recipes
  (name,intro,category,meat_tag,difficulty,minutes,calorie_tier,ingredients_json,steps_json,kcal,protein,fat,carb)
  VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`);
let n = 0;
for (const r of merged) {
  if (existing.has(r.name)) continue;
  ins.run(
    r.name, r.intro, r.category, r.meat_tag, r.difficulty, r.minutes, r.calorie_tier,
    JSON.stringify(r.ingredients), JSON.stringify(r.steps),
    r.nutrition.kcal, r.nutrition.protein, r.nutrition.fat, r.nutrition.carb,
  );
  n++;
}
console.log('inserted:', n);
console.log('db total:', db.prepare('SELECT COUNT(*) AS c FROM recipes').get().c);
