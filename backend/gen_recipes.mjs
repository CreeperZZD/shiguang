/* 食光 · 菜谱库扩容生成脚本（跑在服务器上）
 * 用法: node gen_recipes.mjs
 * 读取 /opt/recipe-app/secrets/llm.json，按批次调用 LLM 为指定菜名生成结构化菜谱，
 * 校验后写入 /opt/recipe-app/backend/seed-recipes-extra.json（每批落盘，可断点续跑）。
 */
import fs from 'node:fs';

const SECRETS_PATH = '/opt/recipe-app/secrets/llm.json';
const EXISTING_PATH = '/opt/recipe-app/backend/seed-recipes.json';
const OUT_PATH = '/opt/recipe-app/backend/seed-recipes-extra.json';
const BATCH = 5;
const MAX_TOKENS = 6000;
const TIMEOUT_MS = 150000;

// [分类, 菜名, 荤素]  —— 荤素仅作提示，最终以内容为准
const DISHES = [
  // 家常小炒 25
  ['家常小炒', '木须肉'], ['家常小炒', '京酱肉丝'], ['家常小炒', '蒜苔炒肉'], ['家常小炒', '芹菜炒香干'], ['家常小炒', '洋葱炒鸡蛋'],
  ['家常小炒', '韭菜炒鸡蛋'], ['家常小炒', '苦瓜炒蛋'], ['家常小炒', '荷兰豆炒腊肠'], ['家常小炒', '回锅肉'], ['家常小炒', '农家小炒肉'],
  ['家常小炒', '蚂蚁上树'], ['家常小炒', '干煸四季豆'], ['家常小炒', '虎皮青椒'], ['家常小炒', '醋溜白菜'], ['家常小炒', '手撕包菜'],
  ['家常小炒', '蚝油生菜'], ['家常小炒', '蒜蓉空心菜'], ['家常小炒', '葱爆羊肉'], ['家常小炒', '孜然牛肉'], ['家常小炒', '杭椒牛柳'],
  ['家常小炒', '糖醋里脊'], ['家常小炒', '红烧茄子'], ['家常小炒', '肉末豆腐'], ['家常小炒', '家常豆腐'], ['家常小炒', '鱼香茄子'],
  // 烧炖硬菜 20
  ['烧炖', '土豆炖牛肉'], ['烧炖', '红烧牛腩'], ['烧炖', '萝卜炖羊肉'], ['烧炖', '红烧狮子头'], ['烧炖', '红烧带鱼'],
  ['烧炖', '红烧鸡翅'], ['烧炖', '黄焖鸡'], ['烧炖', '啤酒鸭'], ['烧炖', '板栗烧鸡'], ['烧炖', '排骨炖豆角'],
  ['烧炖', '白菜炖豆腐'], ['烧炖', '咖喱鸡肉'], ['烧炖', '土豆烧排骨'], ['烧炖', '糖醋排骨'], ['烧炖', '糖醋鱼块'],
  ['烧炖', '水煮肉片'], ['烧炖', '酸菜鱼'], ['烧炖', '香菇焖鸡'], ['烧炖', '红烧猪蹄'], ['烧炖', '冬瓜烧鸭'],
  // 蒸菜 7
  ['蒸菜', '肉饼蒸蛋'], ['蒸菜', '粉蒸肉'], ['蒸菜', '剁椒鱼头'], ['蒸菜', '蒜蓉粉丝蒸扇贝'], ['蒸菜', '豆豉蒸排骨'], ['蒸菜', '香菇蒸滑鸡'], ['蒸菜', '清蒸鳕鱼'],
  // 汤羹 13
  ['汤羹', '玉米排骨汤'], ['汤羹', '莲藕排骨汤'], ['汤羹', '山药鸡汤'], ['汤羹', '酸辣汤'], ['汤羹', '海带豆腐汤'],
  ['汤羹', '番茄牛腩汤'], ['汤羹', '鲫鱼豆腐汤'], ['汤羹', '冬瓜丸子汤'], ['汤羹', '丝瓜蛋汤'], ['汤羹', '南瓜浓汤'],
  ['汤羹', '蘑菇鸡汤'], ['汤羹', '罗宋汤'], ['汤羹', '老鸭汤'],
  // 主食 17
  ['主食', '葱油拌面'], ['主食', '炸酱面'], ['主食', '西红柿鸡蛋面'], ['主食', '红烧牛肉面'], ['主食', '重庆小面'],
  ['主食', '鲜虾馄饨'], ['主食', '白菜猪肉饺子'], ['主食', '韭菜盒子'], ['主食', '葱花鸡蛋饼'], ['主食', '手抓饼'],
  ['主食', '疙瘩汤'], ['主食', '扬州炒饭'], ['主食', '皮蛋瘦肉粥'], ['主食', '腊肠煲仔饭'], ['主食', '南瓜粥'], ['主食', '牛肉炒河粉'], ['主食', '荠菜馄饨'],
  // 凉菜 8
  ['凉菜', '拍黄瓜'], ['凉菜', '凉拌木耳'], ['凉菜', '大拌菜'], ['凉菜', '口水鸡'], ['凉菜', '凉拌海带丝'],
  ['凉菜', '蒜泥白肉'], ['凉菜', '皮蛋豆腐'], ['凉菜', '凉拌毛豆'],
  // 早餐 7
  ['早餐', '鸡蛋灌饼'], ['早餐', '香煎馒头片'], ['早餐', '牛奶燕麦粥'], ['早餐', '小米红枣粥'], ['早餐', '蔬菜鸡蛋饼'], ['早餐', '香蕉松饼'], ['早餐', '吐司披萨'],
  // 海鲜 8
  ['海鲜', '辣炒花蛤'], ['海鲜', '葱油蛏子'], ['海鲜', '红烧黄鱼'], ['海鲜', '酱爆鱿鱼'], ['海鲜', '姜葱炒蟹'], ['海鲜', '椒盐大虾'], ['海鲜', '蒜蓉蒸生蚝'], ['海鲜', '清炒虾仁'],
];

const secrets = JSON.parse(fs.readFileSync(SECRETS_PATH, 'utf8'));
const existing = new Set(
  JSON.parse(fs.readFileSync(EXISTING_PATH, 'utf8')).map((r) => r.name)
);
let done = [];
try { done = JSON.parse(fs.readFileSync(OUT_PATH, 'utf8')); } catch { }
const doneNames = new Set(done.map((r) => r.name));

const todo = DISHES.filter(([, name]) => !existing.has(name) && !doneNames.has(name));
console.log(`[gen] 待生成 ${todo.length} 道（已有库 ${existing.size} 道，已完成 ${doneNames.size} 道）`);

function buildPrompt(batch) {
  const list = batch.map(([cat, name]) => `${name}（分类：${cat}）`).join('\n');
  return `你是专业中餐厨师与营养师。请为以下每道家常菜生成标准化的结构化菜谱数据。

菜谱清单：
${list}

要求：
1. 只输出一个 JSON 数组，不要输出任何其他文字、注释或 markdown 代码块标记。
2. 数组元素字段（必须严格齐全）：
   - name：字符串，必须与给定菜名完全一致
   - intro：一句话简介，不超过 25 字
   - category：使用给定的分类
   - meat_tag：只能填 "荤" 或 "素"
   - difficulty：1~3 的整数（1=新手 2=一般 3=有点讲究）
   - minutes：10~120 的整数（总耗时分钟）
   - calorie_tier：按每份热量填 "低"(≤250kcal) / "中"(251~500) / "高"(>500)
   - ingredients：3~12 个元素，每个为 {"name":"食材名","amount":"用量带单位，如 2个/300g/10ml"}
   - steps：4~10 个元素的字符串数组，每步 15~50 字，口语化、可执行、有火候细节
   - nutrition：{"kcal":每份热量整数, "protein":蛋白质克, "fat":脂肪克, "carb":碳水克}
3. 营养按“一份”计；粥和早餐类 kcal 控制在 80~350。
4. 用料家常易买；调味料（油盐糖生抽老抽醋料酒葱姜蒜）可列入 ingredients。`;
}

function validate(r) {
  const errs = [];
  if (!r || typeof r !== 'object') return ['not object'];
  if (typeof r.name !== 'string' || !r.name.trim()) errs.push('name');
  if (typeof r.intro !== 'string' || r.intro.length > 40) errs.push('intro');
  if (typeof r.category !== 'string' || !r.category) errs.push('category');
  if (r.meat_tag !== '荤' && r.meat_tag !== '素') errs.push('meat_tag');
  if (!Number.isInteger(r.difficulty) || r.difficulty < 1 || r.difficulty > 3) errs.push('difficulty');
  if (!Number.isInteger(r.minutes) || r.minutes < 5 || r.minutes > 180) errs.push('minutes');
  if (!['低', '中', '高'].includes(r.calorie_tier)) errs.push('calorie_tier');
  if (!Array.isArray(r.ingredients) || r.ingredients.length < 2 || r.ingredients.length > 15) errs.push('ingredients');
  else if (r.ingredients.some((i) => !i || typeof i.name !== 'string' || typeof i.amount !== 'string')) errs.push('ingredient_fields');
  if (!Array.isArray(r.steps) || r.steps.length < 3 || r.steps.length > 12) errs.push('steps');
  else if (r.steps.some((s) => typeof s !== 'string' || s.length < 5)) errs.push('step_text');
  if (!r.nutrition || !Number.isFinite(r.nutrition.kcal) || r.nutrition.kcal < 30 || r.nutrition.kcal > 1500) errs.push('kcal');
  else if ([r.nutrition.protein, r.nutrition.fat, r.nutrition.carb].some((v) => !Number.isFinite(v) || v < 0 || v > 200)) errs.push('macros');
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
      max_tokens: MAX_TOKENS,
      temperature: 0.3,
      stream: false,
    }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  const text = await r.text();
  if (!r.ok) throw new Error('HTTP ' + r.status + ': ' + text.slice(0, 200));
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
    const names = batch.map(([, n]) => n).join('/');
    let got = null;
    for (let attempt = 1; attempt <= 2 && !got; attempt++) {
      try {
        const content = await llmCall(batch);
        const arr = extractJSON(content);
        if (!Array.isArray(arr)) throw new Error('非 JSON 数组');
        got = [];
        for (const item of arr) {
          const errs = validate(item);
          if (errs.length) { console.log(`[gen] 校验失败 ${item && item.name}: ${errs.join(',')}`); failCount++; continue; }
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
        console.log(`[gen] 批次${bi + 1} 第${attempt}次失败: ${String(e.message || e).slice(0, 160)}`);
      }
    }
    if (got && got.length) {
      done.push(...got);
      fs.writeFileSync(OUT_PATH, JSON.stringify(done, null, 1));
    }
    console.log(`[gen] 进度 ${bi + 1}/${batches.length} 完成（本批 ${got ? got.length : 0} 道，累计成功 ${okCount}）`);
  }
  console.log(`[gen] 全部结束：成功 ${okCount}，失败/跳过 ${failCount}，输出 ${done.length} 道 → ${OUT_PATH}`);
}

main().catch((e) => { console.error('[gen] fatal', e); process.exit(1); });
