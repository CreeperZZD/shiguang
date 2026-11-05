# 食光 ShiGuang 🍚

> **"告诉我你有什么食材、你的身体数据，帮你决定今天吃什么、一周怎么吃"**
> 智能食谱 APP：健康饮食规划 + 食材智能推荐 + 家庭餐桌 + 美食社区

Android 原生（Kotlin + Jetpack Compose）· 零依赖 Node 后端 · AI 能力（菜谱量化/拍冰箱识食材）

---

## ✨ 功能总览

| 模块 | 功能 |
|---|---|
| 🏠 今日 | 每日三餐推荐（贴合个人热量目标 ±15%）、换一换、**摇一摇**、🍽 全家餐桌模式 |
| 🥕 食材 | 我的食材柜、**有啥做啥**（严格匹配）、**最佳推荐**（缺料提醒→购物清单）、📷 **拍冰箱识食材**（AI 视觉，多图）、食材模糊联想、保质期与临期提醒 |
| 📖 食谱 | 155 道结构化菜谱（真实照片/AI 配图）、搜索（回车即搜）、**视频教程**（B站站内列表 + 抖音/B站直跳）、烹饪计时器、点赞收藏云端化、排行榜 |
| 👨‍👩‍👧 家庭 | 多成员健康档案（体脂率等权威数据选填，Katch-McArdle / Mifflin-St Jeor 双算法）、**按成员目标重算全餐**、全家目标聚合 |
| 🤖 AI | 口语化做法 → 标准菜谱量化（食材/步骤/营养/评分）→ 一键入库 |
| 💬 社区 | 广场动态、评论、附近在吃什么（城市级） |
| ⚙️ 其他 | 手机号登录、云同步、深浅模式手动切换、省流模式、一周热量报告（柱状图+评分）、购物清单分享 |

## 📱 App 预览

（此处可放置截图）

## 🏗 项目结构

```
├── ShiGuang/            # Android APP（Kotlin + Jetpack Compose + Material3）
│   └── app/src/main/java/com/shiguang/app/
│       ├── MainActivity.kt      # 路由状态机
│       ├── data/                # Api（OkHttp）/ Session / AppSettings
│       └── ui/                  # 全部界面（Compose）
├── backend/             # 云端后端（零 npm 依赖：node:http + node:sqlite）
│   ├── server.js               # 全部业务逻辑与路由
│   ├── db.js                   # 建表 + 增量迁移 + 种子导入
│   ├── seed-recipes.json       # 内置菜谱库
│   ├── fetch_covers.mjs        # Wikimedia 真实菜品图爬虫（两阶段）
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

## 🧪 测试

- 后端：`curl http://127.0.0.1:8000/api/health`
- APP：所有界面均可真机/模拟器走通；算法（BMI/BMR/推荐匹配）有 JUnit 用例

## 🔐 安全说明

- 测试模式验证码固定 `888888`，**任何人可登录任意手机号——上线前务必接入真实短信**
- LLM Key 只存服务器 `secrets/`（600 权限），绝不打进 APP
- 用户数据单表隔离，owner 校验防止越权

## 📄 开源协议

[MIT](LICENSE) · 菜品照片遵循 Wikimedia Commons 各自 CC 协议（署名见 `covers-manifest.json`）

## 🙏 致谢

- [Jetpack Compose](https://developer.android.com/compose) · [OkHttp](https://square.github.io/okhttp/) · [Coil](https://coil-kt.github.io/coil/)
- [Wikimedia Commons](https://commons.wikimedia.org/) · [pollinations.ai](https://pollinations.ai/)
- LLM：deepseek-v4.1（阿里云百炼兼容模式）

---
Made with 🍚 by 食光 · 2026
