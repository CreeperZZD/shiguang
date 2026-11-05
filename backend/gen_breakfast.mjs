/* 食光 · 早餐菜谱扩容（跑在服务器上）：node gen_breakfast.mjs
 * 输出 /opt/recipe-app/backend/seed-recipes-breakfast.json，与既有库查重 */
import fs from 'node:fs';

const SECRETS_PATH = '/opt/recipe-app/secrets/llm.json';
const OUT_PATH = '/opt/recipe-app/backend/seed-recipes-breakfast.json';
const EXISTING_PATHS = [
  '/opt/recipe-app/backend/seed-recipes.json',
  '/opt/recipe-app/backend/seed-recipes-extra.json',
];
const BATCH = 5;
const MAX_TOKENS = 6000;
const TIMEOUT_MS = 150000;

const DISHES = [
  ['粥类', '小米南瓜粥'], ['粥类', '八宝粥'], ['粥类', '红豆粥'], ['粥类', '紫薯粥'], ['粥类', '绿豆粥'], ['粥类', '牛奶燕麦粥'], ['粥类', '玉米碴子粥'], ['粥类', '山药小米粥'],
  ['蛋饼类', '厚蛋烧'], ['蛋饼类', '番茄鸡蛋饼'], ['蛋饼类', '土豆丝鸡蛋饼'], ['蛋饼类', '西葫芦鸡蛋饼'], ['蛋饼类', '胡萝卜鸡蛋饼'], ['蛋饼类', '葱花软饼'],
  ['面点', '菜肉包子'], ['面点', '香菇菜包'], ['面点', '烧麦'], ['面点', '锅贴'], ['面点', '生煎包'], ['面点', '葱油花卷'], ['面点', '奶香馒头'], ['面点', '白菜素馅饺子'],
  ['简餐', '火腿三明治'], ['简餐', '鸡蛋芝士吐司'], ['简餐', '花生酱吐司'], ['简餐', '酸奶水果杯'], ['简餐', '玉米烙'], ['简餐', '蒸红薯配酸奶'], ['简餐', '茶香卤蛋'], ['简餐', '黄瓜鸡蛋汤面'],
];

const secrets = JSON.parse(fs.readFileSync(SECRETS_PATH, 'utf8'));
const existing = new Set();
for (const p of EXISTING_PATHS) {
  try { for (const r of JSON.parse(fs.readFileSync(p, 'utf8'))) existing.add(r.name); } catch { }
}
let done = [];
try { done = JSON.parse(fs.readFileSync(OUT_PATH, 'utf8')); } catch { }
const doneNames = new Set(done.map((r) => r.name));

const todo = DISHES.filter(([, name]) => !existing.has(name) && !doneNames.has(name));
console.log(`[bf] 待生成 ${todo.length} 道`);

function buildPrompt(batch) {
  const list = batch.map(([cat, name]) => `${name}（分类：${cat}）`).join('\n');
  return `你是专业厨师与营养师。为以下早餐/简餐生成标准化结构化菜谱。

菜谱清单：
${list}

要求：
1. 只输出一个 JSON 数组，无任何其他文字或代码块标记。
2. 字段严格齐全：name(与给定一致)、intro(≤25字)、category(用给定分类)、meat_tag("荤"或"素")、difficulty(1~3)、minutes(5~60)、calorie_tier(按每份: ≤250"低" / 251~500"中" / >500"高")、ingredients(2~12个 {name,amount带单位})、steps(3~8个字符串，每步15~50字)、nutrition({kcal,protein,fat,carb}，每份，早餐类 kcal 控制在 80~450)
3. 家常易做，步骤口语化。`;
}

function validate(r) {
  const errs = [];
  if (!r || typeof r !== 'object') return ['not object'];
  if (typeof r.name !== 'string' || !r.name.trim()) errs.push('name');
  if (typeof r.intro !== 'string' || r.intro.length > 40) errs.push('intro');
  if (typeof r.category !== 'string' || !r.category) errs.push('category');
  if (r.meat_tag !== '荤' && r.meat_tag !== '素') errs.push('meat_tag');
  if (!Number.isInteger(r.difficulty) || r.difficulty < 1 || r.difficulty > 3) errs.push('difficulty');
  if (!Number.isInteger(r.minutes) || r.minutes < 3 || r.minutes > 90) errs.push('minutes');
  if (!['低', '中', '高'].includes(r.calorie_tier)) errs.push('calorie_tier');
  if (!Array.isArray(r.ingredients) || r.ingredients.length < 2 || r.ingredients.length > 15) errs.push('ingredients');
  if (!Array.isArray(r.steps) || r.steps.length < 3 || r.steps.length > 10) errs.push('steps');
  if (!r.nutrition || !Number.isFinite(r.nutrition.kcal) || r.nutrition.kcal < 30 || r.nutrition.kcal > 800) errs.push('kcal');
  return errs;
}

function extractJSON(text) {
  const t = String(text);
  const start = t.indexOf('[');
  const end = t.lastIndexOf(']');
  if (start === -1 || end <= start) return null;
  try { return JSON.parse(t.slice(start, end + 1)); } catch { return null; }
}

async function llmCall(batch) {
  const r = await fetch(secrets.endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + secrets.key },
    body: JSON.stringify({
      model: secrets.model,
      messages: [{ role: 'user', content: buildPrompt(batch) }],
      max_tokens: MAX_TOKENS, temperature: 0.3, stream: false,
    }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  const text = await r.text();
  if (!r.ok) throw new Error('HTTP ' + r.status);
  const data = JSON.parse(text);
  const msg = data && data.choices && data.choices[0] && data.choices[0].message;
  if (!msg || typeof msg.content !== 'string') throw new Error('no content');
  return msg.content;
}

async function main() {
  const batches = [];
  for (let i = 0; i < todo.length; i += BATCH) batches.push(todo.slice(i, i + BATCH));
  let okCount = 0, failCount = 0;
  for (let bi = 0; bi < batches.length; bi++) {
    const batch = batches[bi];
    let got = null;
    for (let attempt = 1; attempt <= 2 && !got; attempt++) {
      try {
        const content = await llmCall(batch);
        const arr = extractJSON(content);
        if (!Array.isArray(arr)) throw new Error('非 JSON 数组');
        got = [];
        for (const item of arr) {
          const errs = validate(item);
          if (errs.length) { console.log(`[bf] 校验失败 ${item && item.name}: ${errs.join(',')}`); failCount++; continue; }
          if (existing.has(item.name) || doneNames.has(item.name) || got.some((g) => g.name === item.name)) { failCount++; continue; }
          got.push({
            name: item.name.trim(), intro: item.intro.trim(), category: item.category,
            meat_tag: item.meat_tag, difficulty: item.difficulty, minutes: item.minutes,
            calorie_tier: item.calorie_tier,
            ingredients: item.ingredients.map((i) => ({ name: i.name.trim(), amount: i.amount.trim() })),
            steps: item.steps.map((s) => s.trim()),
            nutrition: {
              kcal: Math.round(item.nutrition.kcal), protein: Math.round(item.nutrition.protein),
              fat: Math.round(item.nutrition.fat), carb: Math.round(item.nutrition.carb),
            },
          });
          doneNames.add(item.name);
          okCount++;
        }
      } catch (e) {
        console.log(`[bf] 批次${bi + 1} 第${attempt}次失败: ${String(e.message || e).slice(0, 120)}`);
      }
    }
    if (got && got.length) {
      done.push(...got);
      fs.writeFileSync(OUT_PATH, JSON.stringify(done, null, 1));
    }
    console.log(`[bf] 进度 ${bi + 1}/${batches.length}（累计成功 ${okCount}）`);
  }
  console.log(`[bf] 结束：成功 ${okCount}，输出 ${done.length} 道 → ${OUT_PATH}`);
}

main().catch((e) => { console.error('[bf] fatal', e); process.exit(1); });
