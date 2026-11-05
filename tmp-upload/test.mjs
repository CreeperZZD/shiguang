/* 食光后端 M1 自测脚本：在服务器上对 127.0.0.1:8000 执行 13 项验证 */
const BASE = 'http://127.0.0.1:8000';
let passed = 0, failed = 0;

function check(name, cond, detail) {
  const ok = !!cond;
  ok ? passed++ : failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${name} | ${detail ?? ''}`);
}
const approx = (a, b, tol = 1) => typeof a === 'number' && Math.abs(a - b) <= tol;
const inRange = (x, a, b) => typeof x === 'number' && x >= a && x <= b;

async function api(method, path, token, body, timeoutMs = 20000) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const r = await fetch(BASE + path, {
    method, headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { }
  return { status: r.status, json, text };
}

async function step(name, fn) {
  try { await fn(); } catch (e) { check(name, false, 'EXCEPTION: ' + String((e && e.message) || e)); }
}

/* 1. health */
await step('1.health', async () => {
  const h = await api('GET', '/api/health');
  check('1.health', h.status === 200 && h.json?.ok === true && h.json?.service === 'shiguang-backend',
    `status=${h.status} service=${h.json?.service} recipes=${h.json?.recipes} storage=${h.json?.storage}`);
});

/* 2. request-code */
let phone = '13800138000';
let token = null;
await step('2.request-code', async () => {
  const rc = await api('POST', '/api/auth/request-code', null, { phone });
  check('2.request-code', rc.status === 200 && rc.json?.dev_code === '888888' && rc.json?.test_mode === true,
    `status=${rc.status} dev_code=${rc.json?.dev_code}`);
});

/* 3. login */
await step('3.login', async () => {
  const lg = await api('POST', '/api/auth/login', null, { phone, code: '888888' });
  token = lg.json?.token || null;
  check('3.login', lg.status === 200 && typeof token === 'string' && token.length === 64 && lg.json?.user?.id > 0,
    `status=${lg.status} token_len=${token?.length} user_id=${lg.json?.user?.id}`);
});

if (token) {
  /* 4. PUT/GET /me：男 30 岁 175cm 70kg 中度活动 减脂 */
  await step('4.me', async () => {
    const put = await api('PUT', '/api/me', token, {
      nickname: '食光测试员', gender: 'm', age: 30, height_cm: 175, weight_kg: 70,
      activity: 'moderate', goal: 'lose',
    });
    const p = put.json?.profile;
    check('4.put-me',
      put.status === 200 && p && approx(p.bmi, 22.86, 0.05) && approx(p.bmr, 1648.75, 0.5)
      && approx(p.tdee, 2555.56, 0.6) && approx(p.target_calories, 2155.56, 0.6) && p.activity_factor === 1.55,
      `status=${put.status} bmi=${p?.bmi} bmr=${p?.bmr} tdee=${p?.tdee} target=${p?.target_calories} rule=${p?.target_rule}`);
    const me = await api('GET', '/api/me', token);
    check('4.get-me',
      me.status === 200 && approx(me.json?.profile?.bmi, 22.86, 0.05) && me.json?.user?.nickname === '食光测试员'
      && me.json?.user?.goal === 'lose',
      `status=${me.status} bmi=${me.json?.profile?.bmi} nickname=${me.json?.user?.nickname}`);
  });

  /* 5. 菜谱列表/详情/搜索 */
  await step('5.recipes', async () => {
    const l1 = await api('GET', '/api/recipes?page=1');
    check('5.recipes-list', l1.status === 200 && l1.json?.total === 20 && l1.json?.items?.length === 10,
      `status=${l1.status} total=${l1.json?.total} items=${l1.json?.items?.length} pages=${l1.json?.total_pages}`);
    const l2 = await api('GET', '/api/recipes?page=2');
    const l3 = await api('GET', '/api/recipes?page=3');
    check('5.recipes-page', l2.json?.items?.length === 10 && l3.json?.items?.length === 0,
      `page2=${l2.json?.items?.length} page3=${l3.json?.items?.length}`);
    const d1 = await api('GET', '/api/recipes/1');
    const d1b = await api('GET', '/api/recipes/1');
    check('5.recipes-detail',
      d1.status === 200 && d1.json?.item?.name === '番茄炒蛋' && d1b.json?.item?.views === (d1.json?.item?.views ?? 0) + 1,
      `name=${d1.json?.item?.name} views ${d1.json?.item?.views}->${d1b.json?.item?.views}`);
    const s1 = await api('GET', '/api/recipes?q=' + encodeURIComponent('番茄'));
    check('5.recipes-search-name', s1.status === 200 && s1.json?.items?.some((x) => x.name === '番茄炒蛋'),
      `q=番茄 -> [${s1.json?.items?.map((x) => x.name).join(',')}]`);
    const s2 = await api('GET', '/api/recipes?q=' + encodeURIComponent('土豆'));
    check('5.recipes-search-ingredient',
      s2.status === 200 && s2.json?.items?.some((x) => x.name === '酸辣土豆丝') && s2.json?.items?.some((x) => x.name === '地三鲜'),
      `q=土豆 -> [${s2.json?.items?.map((x) => x.name).join(',')}]`);
    const c1 = await api('GET', '/api/recipes?category=' + encodeURIComponent('汤羹'));
    check('5.recipes-category', c1.status === 200 && c1.json?.total === 3, `category=汤羹 total=${c1.json?.total}`);
  });

  /* 6. daily / weekly（target=2000，±15% 与不重复） */
  await step('6.daily', async () => {
    const d = await api('GET', '/api/recipes/daily?target=2000');
    const m = d.json?.meals;
    const ids = m ? [m.breakfast?.recipe?.id, m.lunch?.recipe?.id, m.dinner?.recipe?.id] : [];
    const total = m ? m.breakfast.kcal + m.lunch.kcal + m.dinner.kcal : 0;
    check('6.daily',
      d.status === 200 && m && new Set(ids).size === 3 && inRange(total, 1700, 2300) && d.json?.within_range === true,
      `date=${d.json?.date} 早=${m?.breakfast?.recipe?.name}(${m?.breakfast?.kcal}) 午=${m?.lunch?.recipe?.name}(${m?.lunch?.kcal}) 晚=${m?.dinner?.recipe?.name}(${m?.dinner?.kcal}) 总=${total}`);
    const a = await api('GET', '/api/recipes/daily?date=2026-11-02&target=2000');
    const b = await api('GET', '/api/recipes/daily?date=2026-11-02&target=2000');
    const same = a.json?.total_kcal === b.json?.total_kcal
      && a.json?.meals?.lunch?.recipe?.id === b.json?.meals?.lunch?.recipe?.id;
    check('6.daily-deterministic', same && inRange(a.json?.total_kcal, 1700, 2300),
      `同日两次调用 total ${a.json?.total_kcal} vs ${b.json?.total_kcal}`);
  });
  await step('6.weekly', async () => {
    const w = await api('GET', '/api/recipes/weekly?target=2000');
    const days = w.json?.days || [];
    let ok = w.status === 200 && days.length === 7;
    const mains = [];
    const totals = [];
    for (const d of days) {
      const m = d.meals;
      if (!m) { ok = false; break; }
      const ids = [m.breakfast.recipe.id, m.lunch.recipe.id, m.dinner.recipe.id];
      if (new Set(ids).size !== 3) ok = false;
      if (!inRange(d.total_kcal, 1700, 2300) || d.within_range !== true) ok = false;
      mains.push(m.lunch.recipe.id, m.dinner.recipe.id);
      totals.push(d.total_kcal);
    }
    const distinctMains = new Set(mains).size;
    check('6.weekly', ok && distinctMains >= 11,
      `days=${days.length} 主菜不重复 ${distinctMains}/14 各日总热量=[${totals.join(',')}]`);
  });

  /* 7. 食材柜 + strict/best 推荐（构造"只缺一样"用例） */
  await step('7.ingredients-recommend', async () => {
    const pi = await api('PUT', '/api/me/ingredients', token, { names: ['紫菜', '鸡蛋', '青菜', '土豆'] });
    check('7.put-ingredients', pi.status === 200 && JSON.stringify(pi.json?.names) === JSON.stringify(['紫菜', '鸡蛋', '青菜', '土豆']),
      `status=${pi.status} names=${JSON.stringify(pi.json?.names)}`);
    const st = await api('GET', '/api/recommend/by-ingredients?mode=strict', token);
    const sNames = st.json?.items?.map((x) => x.recipe.name) || [];
    check('7.strict',
      st.status === 200 && sNames.includes('紫菜蛋花汤') && sNames.includes('清炒时蔬')
      && !sNames.includes('酸辣土豆丝') && st.json?.items?.every((x) => x.missing.length === 0 && x.matched > 0),
      `可做=[${sNames.join(',')}]`);
    const be = await api('GET', '/api/recommend/by-ingredients?mode=best', token);
    const bItems = be.json?.items || [];
    const suanLa = bItems.find((x) => x.recipe.name === '酸辣土豆丝');
    const sorted = bItems.every((x, i, arr) => i === 0 || arr[i - 1].missing.length <= x.missing.length);
    check('7.best',
      be.status === 200 && !!suanLa && JSON.stringify(suanLa.missing) === JSON.stringify(['干辣椒'])
      && bItems.length >= 3 && bItems.every((x) => x.missing.length >= 1 && x.missing.length <= 2) && sorted,
      `只缺1~2样 n=${bItems.length} 酸辣土豆丝missing=${JSON.stringify(suanLa?.missing)} 缺失数升序=${sorted}`);
  });

  /* 8. 购物清单增删 → 自动入食材柜 */
  await step('8.shopping-list', async () => {
    const add = await api('POST', '/api/shopping-list', token, { name: '莲藕' });
    const id = add.json?.item?.id;
    const list = await api('GET', '/api/shopping-list', token);
    check('8.shopping-add', add.status === 200 && Number.isInteger(id) && list.json?.items?.some((x) => x.id === id && x.name === '莲藕'),
      `status=${add.status} id=${id} 清单条数=${list.json?.items?.length}`);
    const del = await api('DELETE', '/api/shopping-list/' + id, token);
    const ing = await api('GET', '/api/me/ingredients', token);
    const list2 = await api('GET', '/api/shopping-list', token);
    check('8.shopping-delete-to-pantry',
      del.status === 200 && ing.json?.names?.includes('莲藕') && !list2.json?.items?.some((x) => x.id === id),
      `purchased=${del.json?.purchased} added_to_pantry=${del.json?.added_to_pantry} 食材柜含莲藕=${ing.json?.names?.includes('莲藕')}`);
  });

  /* 9. 点赞/收藏计数与去重 */
  await step('9.like-favorite', async () => {
    const l1 = await api('POST', '/api/recipes/3/like', token);
    const l2 = await api('POST', '/api/recipes/3/like', token);
    check('9.like-dedupe', l1.status === 200 && l2.status === 200 && l1.json?.like_count === l2.json?.like_count && l1.json?.like_count === 1,
      `like_count ${l1.json?.like_count} -> ${l2.json?.like_count}（重复点赞不增加）`);
    const u1 = await api('DELETE', '/api/recipes/3/like', token);
    const u2 = await api('DELETE', '/api/recipes/3/like', token);
    check('9.unlike', u1.status === 200 && u2.status === 200 && u1.json?.like_count === 0 && u2.json?.like_count === 0,
      `取消点赞 ${u1.json?.like_count} -> ${u2.json?.like_count}`);
    const f1 = await api('POST', '/api/recipes/3/favorite', token);
    const f2 = await api('POST', '/api/recipes/3/favorite', token);
    const favs = await api('GET', '/api/me/favorites', token);
    check('9.favorite-dedupe',
      f1.json?.favorite_count === 1 && f2.json?.favorite_count === 1 && favs.json?.items?.length === 1 && favs.json?.items?.[0]?.id === 3,
      `favorite_count=${f1.json?.favorite_count}/${f2.json?.favorite_count} 收藏列表=${favs.json?.items?.length}条`);
    const uf = await api('DELETE', '/api/recipes/3/favorite', token);
    const favs2 = await api('GET', '/api/me/favorites', token);
    check('9.unfavorite', uf.json?.favorite_count === 0 && favs2.json?.items?.length === 0,
      `favorite_count=${uf.json?.favorite_count} 收藏列表=${favs2.json?.items?.length}条`);
  });

  /* 10. 排行榜 */
  await step('10.leaderboard', async () => {
    const lb = await api('GET', '/api/leaderboard');
    const items = lb.json?.items || [];
    let sortedOk = true, scoreOk = true;
    for (let i = 0; i < items.length; i++) {
      if (i > 0 && items[i - 1].score < items[i].score) sortedOk = false;
      if (items[i].score !== items[i].views + items[i].like_count + items[i].favorite_count) scoreOk = false;
    }
    check('10.leaderboard', lb.status === 200 && items.length > 0 && items.length <= 50 && sortedOk && scoreOk,
      `n=${items.length} 榜首=${items[0]?.name} score=${items[0]?.score}(views${items[0]?.views}+like${items[0]?.like_count}+fav${items[0]?.favorite_count}) 降序=${sortedOk} 分值正确=${scoreOk}`);
  });

  /* 11. 社区：发帖/列表/评论/nearby */
  await step('11.posts', async () => {
    const pp = await api('POST', '/api/posts', token, {
      text: '今天做了番茄炒蛋，酸甜开胃！', city: '长沙', recipe_id: 1,
      image_urls: ['https://example.com/tomato.jpg'],
    });
    const postId = pp.json?.post?.id;
    const cm = await api('POST', `/api/posts/${postId}/comments`, token, { text: '看起来不错，明天试试' });
    const latest = await api('GET', '/api/posts?tab=latest');
    const found = latest.json?.items?.find((x) => x.id === postId);
    check('11.posts',
      pp.status === 200 && Number.isInteger(postId) && cm.status === 200 && !!found
      && found.comment_count === 1 && found.nickname === '食光测试员',
      `post_id=${postId} comment_id=${cm.json?.comment?.id} comment_count=${found?.comment_count} nickname=${found?.nickname}`);
    const rec = await api('GET', '/api/posts?tab=recommend');
    check('11.posts-recommend', rec.status === 200 && Array.isArray(rec.json?.items) && rec.json.items.length >= 1,
      `tab=recommend n=${rec.json?.items?.length}`);
    const near = await api('GET', '/api/nearby?city=' + encodeURIComponent('长沙'));
    const near2 = await api('GET', '/api/nearby?city=' + encodeURIComponent('深圳'));
    check('11.nearby', near.status === 200 && near.json?.items?.some((x) => x.id === postId)
      && near2.status === 200 && (near2.json?.items?.length || 0) === 0,
      `长沙=${near.json?.items?.length}条 深圳=${near2.json?.items?.length}条`);
  });

  /* 12. AI 菜谱结构化（真实调用 1 次 LLM） */
  await step('12.ai-quantify', async () => {
    const ai = await api('POST', '/api/ai/quantify-recipe', token, {
      text: '番茄炒蛋：番茄2个、鸡蛋3个、小葱1根、盐和糖少许。鸡蛋加盐打散，热油炒至凝固盛出；番茄切块下锅炒出汁，倒回鸡蛋加糖翻炒均匀，撒葱花出锅。全程约10分钟。',
    }, 120000);
    const r = ai.json?.recipe;
    check('12.ai-quantify',
      ai.status === 200 && r && typeof r['菜名'] === 'string' && Array.isArray(r['食材']) && r['食材'].length > 0
      && Array.isArray(r['步骤']) && r['步骤'].length > 0 && r['营养'] && Number(r['营养']?.kcal) > 0,
      `status=${ai.status} 菜名=${r?.['菜名']} 食材数=${r?.['食材']?.length} 步骤数=${r?.['步骤']?.length} kcal=${r?.['营养']?.kcal} 健康评分=${r?.['健康评分']}`);
  });

  /* 13. 鉴权与参数校验 */
  await step('13.auth-validation', async () => {
    const t1 = await api('GET', '/api/me', 'deadbeef');
    const t2 = await api('GET', '/api/me');
    const wc = await api('POST', '/api/auth/login', null, { phone, code: '000000' });
    check('13.unauthorized', t1.status === 401 && t2.status === 401 && wc.status === 401,
      `无效token=${t1.status} 无token=${t2.status} 错误验证码=${wc.status}`);
    const e1 = await api('POST', '/api/auth/login', null, {});
    const e2 = await api('POST', '/api/shopping-list', token, {});
    const e3 = await api('GET', '/api/recommend/by-ingredients', token);
    const e4 = await api('POST', '/api/ai/quantify-recipe', token, {});
    const e5 = await api('GET', '/api/nearby');
    const e6 = await api('PUT', '/api/me', token, { gender: 'x' });
    const e7 = await api('POST', '/api/ai/recognize-ingredients', token, {});
    check('13.bad-request',
      e1.status === 400 && e2.status === 400 && e3.status === 400 && e4.status === 400
      && e5.status === 400 && e6.status === 400 && e7.status === 400,
      `login空参=${e1.status} 购物清单空参=${e2.status} 无mode=${e3.status} AI空参=${e4.status} nearby无city=${e5.status} 非法gender=${e6.status} 识别空图=${e7.status}`);
  });
} else {
  console.log('FAIL | 3.login 失败，后续依赖 token 的用例全部跳过');
  failed += 10;
}

console.log(`TOTAL: ${passed} passed, ${failed} failed`);
process.exit(failed > 0 ? 1 : 0);
