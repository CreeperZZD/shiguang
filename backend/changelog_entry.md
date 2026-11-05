
## 2026-11-03 · 菜谱视频教程接口（B站搜索 + 缓存/限流/降级）

### 内容
1. server.js 升级（备份：backups/server.js.bak-2026-11-03-videos；db.js 备份：backups/db.js.bak-2026-11-03-videos），新增接口 `GET /api/recipes/:id/videos`（无需登录）：
   - 服务端取菜谱名 → 查缓存 → miss 则调 B站搜索（wbi 签名 + buvid3 cookie）→ 校验解析 → 写缓存 → 返回
   - 响应 `{ok, items:[{title,author,duration,bvid,play_url,cover,view_count}], source:"bilibili"|"none", cached, fallback}`，items 最多 10 条，title 已去 `<em>` 高亮并做实体解码，cover 强制 https
   - 任何上游失败（风控/改版/网络/解析/8 秒超时）一律返回 `{ok:true, items:[], source:"none", cached:false, fallback}`，客户端无缝走跳转兜底，绝不报 5xx
2. 缓存：SQLite 新表 `video_cache(recipe_name TEXT PRIMARY KEY, items_json TEXT, fetched_at TEXT NOT NULL)`（db.js），TTL 24 小时，只缓存成功结果
3. 限流：内存固定 60s 窗口计数器，每分钟最多 10 次出站请求（nav/spi/search 全部计入）；超限直接返回 items:[] + fallback
4. wbi 签名已实现并实测：nav 接口取 img_key/sub_key（未登录 code=-101 但 wbi_img 正常返回）→ 固定字符置换表混合 mixin_key（截 32 位）→ 参数按 key 排序拼接后 MD5 得 w_rid
   - 实测：wbi 签名但无 cookie 会被风控（HTTP 412 / code -412）；带 buvid3 cookie 后成功。cookie 优先由 `x/frontend/finger/spi` 获取（进程内复用），失败退随机 buvid3（两者均实测可用）
   - 另保留无 wbi 的纯接口作为备选路径（当前实测也可通）；疑似风控时自动重置凭据缓存下次重新获取
5. 出站请求统一 8 秒超时（AbortSignal.timeout），node 内置 fetch，零新依赖

### 验证
- node --check 通过；restart 后 health 正常（recipes=125），全程未触碰 astrbot
- id=1 番茄炒蛋首次返回 10 条真实视频（cached:false，约 0.6s）；二次调用 cached:true（约 2ms）
- 连续调 5 个不同菜谱（id=2~6）+ 第 6 个（id=7）全部成功不报错；继续调至 id=8 为第 10 次出站成功，id=9 起触发限流：瞬时（约 2ms）返回 items:[] source:none，无任何报错
- 限流窗口 60 秒后自动恢复：id=9 鱼香肉丝再次调用成功返回 10 条真实数据（外网直连 118.178.99.65:8000 实测）
- 限流期间已缓存菜谱（id=1）不受影响，仍返回 cached:true + 10 条
- 不存在菜谱返回 404 recipe_not_found；抽查 play_url `curl -sI` 返回 200
- 内存 37.9MB（上限 300MB）；journalctl 无 error/uncaught 日志

### 回滚
```bash
systemctl stop shiguang-backend
cp /opt/recipe-app/backups/server.js.bak-2026-11-03-videos /opt/recipe-app/backend/server.js
cp /opt/recipe-app/backups/db.js.bak-2026-11-03-videos /opt/recipe-app/backend/db.js
systemctl start shiguang-backend
```
- video_cache 表留存无副作用；如需清空：`DELETE FROM video_cache;`（node:sqlite 或 sqlite3 均可）
