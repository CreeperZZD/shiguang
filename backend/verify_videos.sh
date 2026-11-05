#!/bin/sh
# 菜谱视频接口验证脚本（在服务器上执行）
B=http://127.0.0.1:8000
sum() {
  node -e "
    const j=JSON.parse(require('fs').readFileSync('$1','utf8'));
    console.log('ok='+j.ok, 'source='+j.source, 'cached='+j.cached, 'items='+j.items.length, 'fallback='+decodeURIComponent(j.fallback.split('keyword=')[1]||''));
  "
}
echo '=== 1) 首次调用 id=1 (番茄炒蛋) ==='
curl -s "$B/api/recipes/1/videos" -o /tmp/v1.json -w 'http=%{http_code} time=%{time_total}s\n'
head -c 1200 /tmp/v1.json; echo
echo '--- items[0] 完整样例 ---'
node -e "const j=JSON.parse(require('fs').readFileSync('/tmp/v1.json','utf8'));console.log(JSON.stringify(j.items[0],null,1))"
echo '=== 2) 第二次调用 id=1（应 cached:true）==='
curl -s "$B/api/recipes/1/videos" -o /tmp/v2.json -w 'http=%{http_code} time=%{time_total}s\n'
sum /tmp/v2.json
echo '=== 3) 连续 5 个不同菜谱 id=2,3,4,5,6，再调第 6 个不同 id=7 ==='
for id in 2 3 4 5 6 7; do
  curl -s "$B/api/recipes/$id/videos" -o /tmp/v_$id.json -w "id=$id time=%{time_total}s  "
  sum /tmp/v_$id.json
done
echo '=== 4) 继续不同菜谱 id=8,9,10 以触发限流（10次/分钟出站）==='
for id in 8 9 10; do
  curl -s "$B/api/recipes/$id/videos" -o /tmp/v_$id.json -w "id=$id time=%{time_total}s  "
  sum /tmp/v_$id.json
done
echo '=== 5) 已缓存菜谱不受限流影响（id=1 再调，应 cached:true 有结果）==='
curl -s "$B/api/recipes/1/videos" -o /tmp/v1c.json -w 'time=%{time_total}s  '
sum /tmp/v1c.json
echo '=== 6) 不存在的菜谱 id=99999（应 404）==='
curl -s "$B/api/recipes/99999/videos" -w '  http=%{http_code}\n'
echo '=== 7) 抽查 items[0] play_url 可访问性 ==='
BV=$(node -e "const j=JSON.parse(require('fs').readFileSync('/tmp/v1.json','utf8'));console.log(j.items[0].play_url)")
echo "play_url = $BV"
curl -sI "$BV" -o /dev/null -w 'play_url http=%{http_code}\n'
echo '=== 8) video_cache 表内容概览 ==='
node -e "
const {DatabaseSync}=require('node:sqlite');
const db=new DatabaseSync('/opt/recipe-app/backend/data/shiguang.db',{readOnly:true});
const rows=db.prepare('SELECT recipe_name, length(items_json) AS len, fetched_at FROM video_cache ORDER BY recipe_name').all();
for (const r of rows) console.log(r.recipe_name, 'len='+r.len, r.fetched_at);
"
echo 'DONE'
