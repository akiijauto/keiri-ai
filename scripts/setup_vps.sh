#!/usr/bin/env bash
# Xserver VPS セットアップ手順（Ubuntu 24.04）
# ※ソフトのインストールを含むため、内容を確認してから実行してください。
#   各ステップは手動で1行ずつ実行することを推奨します。
set -euo pipefail

APP_DIR=/opt/keiri-ai

echo "== 1. 必要パッケージ =="
echo "sudo apt update"
echo "sudo apt install -y poppler-utils nginx apache2-utils python3-venv"

echo
echo "== 2. 配置 =="
echo "sudo mkdir -p ${APP_DIR} && sudo chown \$USER:\$USER ${APP_DIR}"
echo "# リポジトリを ${APP_DIR} に clone / 配置"

echo
echo "== 3. Python環境 =="
echo "cd ${APP_DIR}/api && python3 -m venv .venv"
echo "source .venv/bin/activate && pip install -r requirements.txt"

echo
echo "== 4. Node環境 =="
echo "cd ${APP_DIR}/app && npm install && npm run build"

echo
echo "== 5. 環境変数 =="
echo "cp ${APP_DIR}/.env.example ${APP_DIR}/.env"
echo "# .env を編集して GEMINI_API_KEY 等を設定（このファイルはgit管理外）"
echo "chmod 600 ${APP_DIR}/.env"

echo
echo "== 6. Basic認証 =="
echo "sudo htpasswd -c /etc/nginx/.htpasswd-keiri-ai eigyo"

echo
echo "== 7. Nginx =="
echo "sudo cp ${APP_DIR}/scripts/nginx-keiri-ai.conf /etc/nginx/sites-available/keiri-ai"
echo "# server_name を自分のドメインに書き換える"
echo "sudo ln -s /etc/nginx/sites-available/keiri-ai /etc/nginx/sites-enabled/"
echo "sudo nginx -t && sudo systemctl reload nginx"

echo
echo "== 8. サービス常駐化 =="
echo "sudo cp ${APP_DIR}/scripts/keiri-ai-*.service /etc/systemd/system/"
echo "# YOUR_USER を自分のユーザー名に書き換える"
echo "sudo systemctl daemon-reload"
echo "sudo systemctl enable --now keiri-ai-api keiri-ai-web"
echo "sudo systemctl status keiri-ai-api keiri-ai-web"

echo
echo "== 9. 動作確認 =="
echo "curl -s http://127.0.0.1:8100/health"
echo "# ブラウザで http://YOUR_DOMAIN/ にアクセスし、Basic認証(eigyo)でログイン"
