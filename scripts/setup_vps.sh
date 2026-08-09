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
echo "# next.config.mjs は output:\"standalone\" のため、ビルド後に静的ファイルを"
echo "# standalone 配下へコピーする必要がある（省略するとCSS/JSが404になる）。"
echo "cp -r ${APP_DIR}/app/.next/static ${APP_DIR}/app/.next/standalone/.next/"
echo "[ -d ${APP_DIR}/app/public ] && cp -r ${APP_DIR}/app/public ${APP_DIR}/app/.next/standalone/"
echo "# 起動は 'npm run start' ではなく .next/standalone/server.js を使う（下記8参照）"

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
echo "# server_name は keiri.ai-l-a-b-o.com に設定済み（別ドメインを使う場合のみ書き換える）"
echo "# この設定はHTTP版。SSL化は下記10で certbot が自動で追記する。"
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
echo "curl -s http://127.0.0.1:8101/health"
echo "curl -s -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:3100"
echo "# 待ち受けアドレスの検証（127.0.0.1 限定であること。0.0.0.0/* なら設定が効いていない）"
echo "ss -tlnp | grep -E '3100|8101'"
echo "# ブラウザで http://YOUR_DOMAIN/ にアクセスし、Basic認証(eigyo)でログイン"

echo
echo "== 10. SSL化 =="
echo "# 前提: DNSがこのVPSのIPに向いており、9まで完了してHTTPでアクセスできること。"
echo "# Basic認証のパスワードはHTTPでは平文で流れるため、必ずHTTPS化する。"
echo "sudo certbot --nginx -d keiri.ai-l-a-b-o.com --redirect"
echo "# certbot が /etc/nginx/sites-available/keiri-ai に"
echo "# listen 443 / ssl_certificate / HTTP→HTTPSリダイレクトを追記し、nginxをreloadする。"
echo "# 以降この設定の最終形はVPS側が正となる（リポジトリ側はHTTP版のまま維持）。"
echo
echo "# 自動更新の確認"
echo "sudo systemctl status certbot.timer"
echo "sudo certbot renew --dry-run --cert-name keiri.ai-l-a-b-o.com"
echo
echo "# 確認"
echo "curl -s -o /dev/null -w '%{http_code}\\n' https://keiri.ai-l-a-b-o.com   # 401ならOK"
