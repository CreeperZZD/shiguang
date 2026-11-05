# 食光 (Shiguang) 后端 CHANGELOG

## 2026-11-02  M1 后端骨架 首次部署

### 部署内容
- 「食光」食谱 APP 后端 M1 骨架，零 npm 依赖（仅 Node 内置模块 http / node:sqlite / crypto / fs）。
- 代码目录：`/opt/recipe-app/backend/`（server.js、db.js、seed-recipes.json）
- 数据库：`/opt/recipe-app/backend/data/shiguang.db`（node:sqlite，WAL 模式；首启自动导入 20 道种子家常菜）
- LLM 凭据：`/opt/recipe-app/secrets/llm.json`（权限 600，仅 AI 接口运行时读取，未写入任何代码/日志）

### 服务与端口
- systemd 服务：`shiguang-backend`（/etc/systemd/system/shiguang-backend.service，enabled，Restart=always，MemoryMax=300M）
- 启动命令：`/usr/bin/node /opt/recipe-app/backend/server.js`（node v24.22.1，node:sqlite 无需 flag）
- 监听：`0.0.0.0:8000`（阿里云安全组当前未放行 8000，外网不可达；内网 curl 127.0.0.1:8000 正常）

### 已实现接口（M1 全部真实实现）
- 健康检查：GET /api/health
- 认证（测试模式验证码固定 888888）：POST /api/auth/request-code、POST /api/auth/login（Bearer token，30 天有效）
- 个人档案：GET/PUT /api/me（服务端计算 BMI、BMR(Mifflin-St Jeor)、TDEE、目标热量）
- 菜谱：GET /api/recipes（搜索/分类/分页）、GET /api/recipes/:id（浏览数+1）、GET /api/recipes/daily、GET /api/recipes/weekly
- 点赞收藏：POST/DELETE /api/recipes/:id/like、POST/DELETE /api/recipes/:id/favorite、GET /api/me/favorites
- 食材与推荐：GET/PUT /api/me/ingredients、GET /api/recommend/by-ingredients?mode=strict|best
- 购物清单：GET/POST /api/shopping-list、DELETE /api/shopping-list/:id（删除=已购，自动入食材柜）
- 骨架接口：GET /api/leaderboard、GET /api/nearby?city=、POST/GET /api/posts、POST /api/posts/:id/comments、GET /api/posts/:id/comments
- AI：POST /api/ai/quantify-recipe（真实调用 LLM）、POST /api/ai/recognize-ingredients（代码就绪，M1 未实测）

### 验证结论
- 13 项自测全部通过（30 条断言，0 失败）：认证、档案计算（BMI 22.86 / BMR 1648.75 / TDEE 2555.56 / 目标 2155.56）、
  菜谱搜索、每日三餐（target=2000 总热量 1995，±15% 内且同日不重复）、7 天排餐（主菜 12/14 不重复，各日均在 ±15%）、
  strict/best 推荐（missing 构造用例通过）、购物清单自动入柜、点赞收藏去重、排行榜、社区、AI 结构化、401/400 校验。
- 部署后进程 RSS 约 91MB（≤300MB 限制）。

### 隔离声明
- 未对 /opt/astrbot-data/、/opt/AstrBot、/opt/QQ、/opt/snowluma 及其进程/配置做任何改动，未重启 astrbot。
- 本次仅新建并使用了 /opt/recipe-app/ 与 /etc/systemd/system/shiguang-backend.service。

### 如何回滚
```bash
systemctl stop shiguang-backend
systemctl disable shiguang-backend
rm -rf /opt/recipe-app/backend
```
如需彻底清除（含 CHANGELOG、secrets 与服务单元）：
```bash
systemctl stop shiguang-backend 2>/dev/null; systemctl disable shiguang-backend 2>/dev/null
rm -rf /opt/recipe-app /etc/systemd/system/shiguang-backend.service
systemctl daemon-reload
```
