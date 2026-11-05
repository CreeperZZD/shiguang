'use strict';
/*
 * B站搜索接口实测脚本（仅用于验证，不进入生产代码）
 * 依次测试：nav 取 key -> 纯接口 -> wbi 无 cookie -> wbi+随机buvid3 -> wbi+spi buvid3
 */
import crypto from 'node:crypto';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';
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
function encWbi(params, imgKey, subKey) {
  const mixinKey = getMixinKey(imgKey + subKey);
  const wts = Math.floor(Date.now() / 1000);
  const p = { ...params, wts };
  for (const k of Object.keys(p)) p[k] = String(p[k]).replace(/[!'()*]/g, '');
  const query = Object.keys(p).sort()
    .map((k) => `${encodeURIComponent(k).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())}=${encodeURIComponent(p[k]).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())}`)
    .join('&');
  const wRid = crypto.createHash('md5').update(query + mixinKey).digest('hex');
  return { ...p, w_rid: wRid, wts };
}
function keyFromUrl(u) {
  const m = /\/(\w+)\.(png|jpg)/.exec(String(u || ''));
  return m ? m[1] : '';
}
function showItems(tag, data) {
  const arr = data && data.data && Array.isArray(data.data.result) ? data.data.result : null;
  if (!arr) { console.log(`[${tag}] 无 result 数组，data keys =`, data && data.data ? Object.keys(data.data).slice(0, 20).join(',') : String(data && data.data)); return; }
  console.log(`[${tag}] result 条数 =`, arr.length);
  const it = arr[0];
  console.log(`[${tag}] 首条字段 =`, Object.keys(it).join(','));
  console.log(`[${tag}] 首条样例 =`, JSON.stringify({
    title: it.title, author: it.author, duration: it.duration, bvid: it.bvid,
    pic: it.pic, play: it.play, description: String(it.description || '').slice(0, 50),
  }));
}

async function main() {
  const keyword = '番茄炒蛋';
  const baseHeaders = {
    'User-Agent': UA,
    'Referer': 'https://www.bilibili.com/',
    'Origin': 'https://www.bilibili.com',
    'Accept': 'application/json, text/plain, */*',
  };

  // 0) nav 接口取 wbi key（未登录 code 为 -101，但 wbi_img 仍返回）
  let imgKey = '', subKey = '';
  try {
    const navRes = await fetch('https://api.bilibili.com/x/web-interface/nav', { headers: baseHeaders, signal: AbortSignal.timeout(8000) });
    const navData = await navRes.json();
    imgKey = keyFromUrl(navData.data && navData.data.wbi_img && navData.data.wbi_img.img_url);
    subKey = keyFromUrl(navData.data && navData.data.wbi_img && navData.data.wbi_img.sub_url);
    console.log('[nav] http =', navRes.status, 'code =', navData.code, 'msg =', navData.msg,
      'imgKey.len =', imgKey.length, 'subKey.len =', subKey.length);
    console.log('[nav] mixinKey.len =', getMixinKey(imgKey + subKey).length);
  } catch (e) { console.log('[nav] error =', e.message); }

  // 1) 纯接口（无 wbi、无 cookie）
  try {
    const url1 = 'https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=' + encodeURIComponent(keyword);
    const r1 = await fetch(url1, { headers: baseHeaders, signal: AbortSignal.timeout(8000) });
    const t1 = await r1.text();
    let j1 = null; try { j1 = JSON.parse(t1); } catch { }
    console.log('[plain] http =', r1.status, 'code =', j1 && j1.code, 'msg =', j1 && j1.msg, 'head =', t1.slice(0, 120).replace(/\s+/g, ' '));
  } catch (e) { console.log('[plain] error =', e.message); }

  // 2) wbi 签名、无 cookie
  if (imgKey && subKey) {
    try {
      const q2 = encWbi({ search_type: 'video', keyword }, imgKey, subKey);
      const url2 = 'https://api.bilibili.com/x/web-interface/search/type?' + new URLSearchParams(q2).toString();
      const r2 = await fetch(url2, { headers: baseHeaders, signal: AbortSignal.timeout(8000) });
      const j2 = await r2.json().catch(() => null);
      console.log('[wbi-nocookie] http =', r2.status, 'code =', j2 && j2.code, 'msg =', j2 && j2.msg);
      if (j2 && j2.code === 0) showItems('wbi-nocookie', j2);
    } catch (e) { console.log('[wbi-nocookie] error =', e.message); }

    // 3) wbi + 随机 buvid3
    try {
      const buvid = crypto.randomBytes(16).toString('hex').toUpperCase();
      const cookie = `buvid3=${buvid}infoc; b_nut=${Math.floor(Date.now() / 1000)}`;
      const q3 = encWbi({ search_type: 'video', keyword }, imgKey, subKey);
      const url3 = 'https://api.bilibili.com/x/web-interface/search/type?' + new URLSearchParams(q3).toString();
      const r3 = await fetch(url3, { headers: { ...baseHeaders, Cookie: cookie }, signal: AbortSignal.timeout(8000) });
      const j3 = await r3.json().catch(() => null);
      console.log('[wbi-buvid3] http =', r3.status, 'code =', j3 && j3.code, 'msg =', j3 && j3.msg);
      if (j3 && j3.code === 0) showItems('wbi-buvid3', j3);
    } catch (e) { console.log('[wbi-buvid3] error =', e.message); }

    // 4) wbi + spi 接口获取的 buvid3
    try {
      const spiRes = await fetch('https://api.bilibili.com/x/frontend/finger/spi', { headers: baseHeaders, signal: AbortSignal.timeout(8000) });
      const spi = await spiRes.json().catch(() => null);
      const b3 = spi && spi.data && spi.data.b_3, b4 = spi && spi.data && spi.data.b_4;
      console.log('[spi] http =', spiRes.status, 'code =', spi && spi.code, 'has b_3 =', !!b3, 'has b_4 =', !!b4);
      if (b3) {
        const cookie = `buvid3=${b3}; buvid4=${b4 || ''}`;
        const q4 = encWbi({ search_type: 'video', keyword }, imgKey, subKey);
        const url4 = 'https://api.bilibili.com/x/web-interface/search/type?' + new URLSearchParams(q4).toString();
        const r4 = await fetch(url4, { headers: { ...baseHeaders, Cookie: cookie }, signal: AbortSignal.timeout(8000) });
        const j4 = await r4.json().catch(() => null);
        console.log('[wbi-spi] http =', r4.status, 'code =', j4 && j4.code, 'msg =', j4 && j4.msg);
        if (j4 && j4.code === 0) showItems('wbi-spi', j4);
      }
    } catch (e) { console.log('[wbi-spi] error =', e.message); }
  }
  console.log('DONE');
}
main().catch((e) => { console.error('FATAL', e); process.exit(1); });
