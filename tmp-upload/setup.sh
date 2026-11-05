#!/usr/bin/env bash
# 食光后端 M1 部署脚本（只操作 /opt/recipe-app，不触碰其他目录）
set -u

NODE_BIN=$(command -v node)
echo "node-bin: $NODE_BIN"
"$NODE_BIN" -v

# 1) 验证 node:sqlite 可用性（决定是否需要 --experimental-sqlite）
if "$NODE_BIN" -e 'const{DatabaseSync}=require("node:sqlite");console.log("sqlite-ok")' >/dev/null 2>&1; then
  NODE_FLAGS=""
  echo "sqlite: ok (无需 flag)"
elif "$NODE_BIN" --experimental-sqlite -e 'const{DatabaseSync}=require("node:sqlite");console.log("sqlite-ok")' >/dev/null 2>&1; then
  NODE_FLAGS="--experimental-sqlite"
  echo "sqlite: ok (需要 --experimental-sqlite)"
else
  echo "FATAL: 该 node 构建不支持 node:sqlite"
  exit 3
fi

# 2) 目录与权限
mkdir -p /opt/recipe-app/backend/data /opt/recipe-app/secrets
chmod 700 /opt/recipe-app/secrets
if [ -f /opt/recipe-app/secrets/llm.json ]; then
  chmod 600 /opt/recipe-app/secrets/llm.json
else
  echo "WARN: /opt/recipe-app/secrets/llm.json 不存在（AI 接口将返回 502）"
fi

# 3) 语法检查 + 数据库初始化冒烟
"$NODE_BIN" --check /opt/recipe-app/backend/server.js || { echo "FATAL: server.js 语法错误"; exit 5; }
"$NODE_BIN" --check /opt/recipe-app/backend/db.js || { echo "FATAL: db.js 语法错误"; exit 5; }
"$NODE_BIN" $NODE_FLAGS -e 'require("/opt/recipe-app/backend/db.js");console.log("db-init-ok")' || { echo "FATAL: 数据库初始化失败"; exit 4; }

# 4) systemd 服务
cat > /etc/systemd/system/shiguang-backend.service <<EOF
[Unit]
Description=Shiguang Recipe App Backend (M1, zero-dependency Node)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/recipe-app/backend
ExecStart=${NODE_BIN} ${NODE_FLAGS} /opt/recipe-app/backend/server.js
Restart=always
RestartSec=3
MemoryMax=300M
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now shiguang-backend
sleep 3

echo "--- systemctl status ---"
systemctl status shiguang-backend --no-pager -l | head -12
echo "--- 启动日志 ---"
journalctl -u shiguang-backend -n 5 --no-pager
echo "--- 端口检查 ---"
ss -tlnp | grep ':8000' || echo "PORT-8000-NOT-LISTENING"
echo "--- health ---"
curl -s -m 5 http://127.0.0.1:8000/api/health || echo "HEALTH-CURL-FAILED"
echo
echo "SETUP-DONE"
