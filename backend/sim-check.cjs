'use strict';
/* 离线仿真：从 server.js 提取 pickDayPlan 相关纯函数，多目标验证结构化组合 */
const fs = require('node:fs');
const src = fs.readFileSync(__dirname + '/server.js', 'utf8');
const algoStart = src.indexOf('const SERVING_OPTIONS');
const pdpStart = src.indexOf('function pickDayPlan', algoStart);
const endMarker = '  return best;\n}';
const pdpEnd = src.indexOf(endMarker, pdpStart) + endMarker.length;
const algo = src.slice(algoStart, pdpEnd);
const test = `
function rngFrom(s) { let h = 1779033703 ^ s.length; for (let i = 0; i < s.length; i++) { h = Math.imul(h ^ s.charCodeAt(i), 3432918353); h = h << 13 | h >>> 19; } return () => { h = Math.imul(h ^ (h >>> 16), 2246822507); h = Math.imul(h ^ (h >>> 13), 3266489909); h ^= h >>> 16; return (h >>> 0) / 4294967296; }; }
const cats = ['家常小炒', '烧炖', '蒸菜', '汤羹', '主食', '凉菜', '早餐', '海鲜'];
const recipes = [];
for (let i = 0; i < 155; i++) {
  const cat = cats[i % cats.length];
  const kcal = cat === '汤羹' ? 80 + Math.round(Math.random() * 180) : cat === '早餐' ? 120 + Math.round(Math.random() * 180) : 150 + Math.round(Math.random() * 500);
  recipes.push({ id: i + 1, name: '菜' + i + '(' + cat + ')', category: cat, meat_tag: i % 3 === 0 ? '素' : '荤', kcal, calorie_tier: kcal < 250 ? '低' : kcal < 450 ? '中' : '高' });
}
const label = (it) => it.r.kcal + 'kcal×' + it.servings + (it.servings <= 0.75 ? '小' : it.servings <= 1.75 ? '中' : '大') + '份';
const used = new Map();
for (const [t, ln, dn] of [[1627, 1, 1], [1627, 2, 3], [2507, 2, 2], [5988, 3, 3], [2000, 4, 4], [1200, 1, 1]]) {
  const p = pickDayPlan(recipes, t, rngFrom('daily|2026-11-06|' + t), used, { lunchN: ln, dinnerN: dn, soup: true });
  console.log('target=' + t + ' 午' + ln + '菜晚' + dn + '菜(+汤) => 总' + Math.round(p.total) + '(' + (p.total / t * 100).toFixed(0) + '%) 早:' + p.b.kcal + '×' + p.sb + ' 午:' + p.ls.map(label).join('+') + ' 晚:' + p.ds.map(label).join('+'));
}
console.time('大目标x7天');
const u2 = new Map();
for (let i = 0; i < 7; i++) pickDayPlan(recipes, 5988, rngFrom('weekly|x|' + i), u2, { lunchN: 3, dinnerN: 3, soup: true });
console.timeEnd('大目标x7天');
`;
new Function(algo + test)();
