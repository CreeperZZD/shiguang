# 食光 ShiGuang 🍚

> **"告诉我你有什么食材、你的身体数据，帮你决定今天吃什么、一周怎么吃"**
> 智能食谱 APP：健康饮食规划 + 食材智能推荐 + 家庭餐桌 + 美食社区

Android 原生（Kotlin + Jetpack Compose）· 零依赖 Node 后端 · AI 能力（菜谱量化/拍冰箱识食材）

---

## ✨ 功能总览

| 模块 | 功能 |
|---|---|
| 🏠 今日 | 每日三餐推荐（贴合个人热量目标 ±15%）、换一换、**摇一摇出菜**（摇动手机或「我的」页入口）、🍽 全家餐桌模式、**几菜一汤结构化组合**（午/晚 1~4 道菜 + 汤位 + 荤素搭配 + 小/中/大份）、**富余热量自动配饮品**、**三餐日记**（餐卡一键记入 / 按天浏览 / 手动添加） |
| 🏙️ 干饭街 | **学生/上班族模式**（我的页开关）：60 道美食街单人餐（黄焖鸡/鸡公煲/螺蛳粉/抄手/轻食/干锅烤鱼…）、按时段+预算+辣度过滤的每日推荐、**Canvas 转盘决定吃什么**、外食打卡与**本月花费小结** |
| 🥕 食材 | 我的食材柜、**有啥做啥**（严格匹配）、**最佳推荐**（缺料提醒→购物清单）、📷 **拍冰箱识食材**（AI 视觉多图，支持接入自己的 API）、食材模糊联想、保质期与临期提醒 |
| 📖 食谱 | 150+ 道结构化菜谱（真实照片/AI 配图）、搜索（回车即搜）、**视频教程**（B站站内列表 + 抖音/B站直跳）、烹饪计时器、点赞收藏、**排行榜六种排序**（综合/最新/浏览/点赞/收藏/好评率，前三名奖牌）、**用户上传作品图**（审核后展示） |
| 👨‍👩‍👧 家庭 | 多成员健康档案（体脂率等权威数据选填，Katch-McArdle / Mifflin-St Jeor 双算法）、**按成员目标重算全餐**、全家目标聚合 |
| 🥗 偏好 | **不喜欢吃 / 忌口过敏 / 8 种常见病饮食禁忌 / 菜系偏好**：推荐与三餐自动避开，相关菜品详情页红色警示 |
| 🤖 AI | 口语化做法 → 标准菜谱量化（食材/步骤/营养/评分）→ 一键入库；拍冰箱识别支持**用户自带 API**（OpenAI 兼容视觉接口，Key 只存本机） |
| 💬 社区 | 广场动态、评论、附近在吃什么（城市级） |
| ⚙️ 其他 | **手机号 / 邮箱验证码登录**、**游客模式**（不登录可浏览，点赞收藏存本机）、**权限管理**（状态查看 + 一键跳系统设置）、深浅模式、省流模式、一周热量报告、购物清单分享 |

## 📥 直接下载安装包（免构建）

服务器直发的 APK（未上架应用商店，安装时允许「未知来源」即可）：

- 食光主 APP v3.1.0：`http://118.178.99.65:8000/downloads/shiguang-v3.1.0.apk`
- 审核台 APP v1.0.0（开发者用）：`http://118.178.99.65:8000/downloads/shiguang-admin-v1.0.0.apk`

## 🏗 项目结构

```
├── ShiGuang/            # Android APP（Kotlin + Jetpack Compose + Material3）
│   └── app/src/main/java/com/shiguang/app/
│       ├── MainActivity.kt      # 路由状态机 + 全局摇一摇传感器
│       ├── data/                # Api（OkHttp）/ Session / AppSettings
│       └── ui/                  # 全部界面（Compose）
├── ShiGuangAdmin/       # 开发者审核台 APP（独立安装：后台统计 + 作品图审核）
├── backend/             # 云端后端（零 npm 依赖：node:http + node:sqlite）
│   ├── server.js               # 全部业务逻辑与路由
│   ├── db.js                   # 建表 + 增量迁移 + 种子导入
│   ├── seed-recipes.json       # 内置菜谱库
│   ├── fetch_covers.mjs        # Wikimedia 真实菜品图爬虫（两阶段）
│   ├── wanx_fill.mjs           # 万相文生图补封面脚本
│   └── gen_recipes.mjs         # LLM 批量扩容菜谱脚本
└── docs/                # 文档与验收报告
```

## 🚀 快速开始

### 一、构建 APP（Android Studio）

1. 安装 Android Studio（含 JDK/SDK）
2. `File → Open` 打开 `ShiGuang/` 目录
3. 等 Gradle 同步完成（依赖走阿里云镜像，国内快）
4. `Run ▶` 到模拟器或真机（真机需开 USB 调试）
5. 打包发布：`Build → Generate Signed APK`

> 修改服务器地址：`app/src/main/java/com/shiguang/app/data/Api.kt` 的 `HOST`

### 二、部署后端（任意有 Node ≥22 的服务器）

```bash
# 1. 上传 backend/ 目录到服务器（示例 /opt/recipe-app/backend）
scp -r backend/ user@your-server:/opt/recipe-app/backend/

# 2. 配置 AI 能力（可选，用于 AI 量化/拍冰箱识别）
#    写入你的 OpenAI 兼容接口凭据（支持视觉的模型优先）
sudo mkdir -p /opt/recipe-app/secrets
echo '{"endpoint":"https://xxx/v1/chat/completions","key":"sk-xxx","model":"模型名"}' \
  | sudo tee /opt/recipe-app/secrets/llm.json
sudo chmod 600 /opt/recipe-app/secrets/llm.json

# 3. 试运行
node /opt/recipe-app/backend/server.js   # 默认监听 0.0.0.0:8000

# 4. systemd 常驻（推荐）
sudo tee /etc/systemd/system/shiguang-backend.service <<'EOF'
[Unit]
Description=ShiGuang recipe backend
After=network.target
[Service]
ExecStart=/usr/bin/node /opt/recipe-app/backend/server.js
WorkingDirectory=/opt/recipe-app/backend
Restart=always
RestartSec=3
MemoryMax=300M
[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now shiguang-backend
```

5. 云服务器记得在**安全组放行 8000/TCP**
6. 手机 APP 里登录（任意手机号 + 验证码 `888888` 测试模式）

### 三、可选：真实菜品封面

```bash
cd backend
node fetch_covers.mjs   # 从 Wikimedia Commons 爬取（CC 协议，credit 自动记录）
# 国内服务器直连 Commons 可能被阻断：可先在本机跑 --search-seed 生成种子，
# 再传到服务器用 --seed 下载（详见脚本头注释）
```

### 四、可选：LLM 批量扩菜谱

```bash
node gen_recipes.mjs    # 按内置菜名清单调 LLM 生成结构化菜谱（校验+去重+断点续跑）
```

### 五、邮箱验证码登录（免费，QQ 邮箱即可）

后端内置**零依赖手写 SMTP 客户端**，开启邮箱验证码只需三步：

```bash
# 1. 在 QQ 邮箱网页版：设置 → 账号 → 开启 SMTP 服务，拿到「授权码」（不是登录密码，免费）
# 2. 服务器上写配置（chmod 600，不进代码/日志）
cat > /opt/recipe-app/secrets/smtp.json << 'EOF'
{ "host": "smtp.qq.com", "port": 465, "user": "你的QQ号@qq.com", "pass": "授权码", "from": "你的QQ号@qq.com" }
EOF
chmod 600 /opt/recipe-app/secrets/smtp.json
# 3. systemctl restart shiguang-backend
```

- 未配置 smtp.json 时邮箱登录接口返回 `email_disabled`，APP 引导用户用手机号或游客模式
- 个人 QQ 邮箱每日可发几百封，初期够用；量大建议换企业 SMTP（只改 smtp.json）
- 防护：同邮箱 60 秒冷却、验证码 10 分钟过期、错 5 次锁定、每 IP 每小时 10 次

### 六、游客模式与权限

- 登录页「先逛逛」→ 无账号直接浏览；点赞/收藏只记本机；日记、发帖等云功能提示先登录
- 「设置 → 权限管理」查看相机/通知授权状态，一键跳系统设置；被拒绝的功能会提示无权限
- 拍冰箱识别默认走官方服务器（需登录）；**自带 API 的用户**在「设置 → AI 识别接口」填 BASE URL / Key / 模型名，手机直连自己的接口（Key 只存本机 SharedPreferences）

### 七、作品图审核（开发者审核台 APP）

用户在菜谱详情页上传制作流程图 → 进待审队列 → **审核台 APP**（或直接调 API）通过后才公开展示。

```bash
# 1. 服务器生成管理员密钥（server.js 已自动生成；也可手动）
node -e 'require("fs").writeFileSync("/opt/recipe-app/secrets/admin.json", JSON.stringify({token: require("crypto").randomBytes(24).toString("hex")}));' && chmod 600 /opt/recipe-app/secrets/admin.json
# 2. 查看令牌
cat /opt/recipe-app/secrets/admin.json
# 3. 构建/下载审核台 APP，输入服务器地址 + 令牌即可登录
#    首页=后台统计（用户/菜谱/帖子/日记/待审数），第二页=待审照片（通过/拒绝）
```

审核台只读**非隐私聚合数据**（计数与状态），令牌泄露面可控；密钥文件 600 权限只在服务器。

## 🧪 测试

- 后端：`curl http://127.0.0.1:8000/api/health`
- APP：所有界面均可真机/模拟器走通；算法（BMI/BMR/推荐匹配）有 JUnit 用例
- 组合算法离线仿真：`node backend/sim-check.cjs`

## 🔐 安全说明

- 验证码：手机号测试码 `888888` **默认开放（阶段产物）**——接入真实短信或邮箱注册跑通后，在 `/opt/recipe-app/secrets/config.json` 写 `{"allow_test_code": false}` 关闭
- **全局限流**：每 IP 120 次/分钟；登录/验证码 20 次/小时；AI 接口 6 次/分钟；上传 5 次/小时
- 密钥（LLM/SMTP/管理员令牌）只存服务器 `secrets/`（600 权限），绝不打进 APP 或日志
- 用户数据单表隔离，owner 校验防止越权；上传图片白名单校验 + 大小限制；下载目录仅 `.apk`
- 审核台管理员令牌独立于用户体系，只发给开发者本人

## 📄 开源协议

[MIT](LICENSE) · 菜品照片遵循 Wikimedia Commons 各自 CC 协议（署名见 `covers-manifest.json`）

## 🙏 致谢

- [Jetpack Compose](https://developer.android.com/compose) · [OkHttp](https://square.github.io/okhttp/) · [Coil](https://coil-kt.github.io/coil/)
- [Wikimedia Commons](https://commons.wikimedia.org/) · [pollinations.ai](https://pollinations.ai/)
- LLM：deepseek-v4.1（阿里云百炼兼容模式）

---
Made with 🍚 by 食光 · 2026
