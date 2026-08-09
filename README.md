# 請求書仕訳AI（仮称）

請求書PDFをアップロードすると、AIが内容を読み取り**仕訳案**を生成します。
人間が確認・修正して承認すると、freee形式の仕訳CSVとして出力できます。

> **設計思想**: AIは案を出すだけ。確定は必ず人間が行います（内部統制への配慮）。

---

## 特徴

- 推定根拠を必ず表示（なぜこの勘定科目か／なぜこの税区分か）
- インボイス登録番号の有無を自動チェック
- 電子帳簿保存法の検索要件（日付・金額・取引先）を意識したファイル管理
- 確信度が低い項目は要確認フラグを表示
- LLMを差し替え可能な設計（既定: Gemini）

## 動作環境

| 項目 | 要件 |
|------|------|
| OS | Ubuntu 24.04 LTS |
| Node.js | 20.x 以上 |
| Python | 3.12 以上 |
| その他 | poppler-utils（pdf2image用）、Nginx |

## セットアップ手順

### 1. リポジトリ取得

```bash
git clone <リポジトリURL>
cd keiri-ai
```

### 2. システムパッケージ

```bash
sudo apt update
sudo apt install -y poppler-utils nginx apache2-utils
```

> `apache2-utils` はBasic認証のパスワードファイル作成（htpasswd）に使用します。

### 3. バックエンド（FastAPI）

```bash
cd api
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt --break-system-packages
```

> Ubuntu 24.04 では `--break-system-packages` が必要な場合があります。

### 4. フロントエンド（Next.js）

```bash
cd ../app
npm install
npm run build

# output:"standalone" のため、静的ファイルを standalone 配下へコピーする
# （省略するとCSS/JSが404になる。ビルドし直すたびに必要）
cp -r .next/static .next/standalone/.next/
[ -d public ] && cp -r public .next/standalone/
```

### 5. 環境変数の設定

`.env.example` をコピーして `.env` を作成し、値を設定します。

```bash
cp .env.example .env
```

| 変数名 | 説明 |
|--------|------|
| `LLM_PROVIDER` | 使用するLLM（既定: `gemini`） |
| `GEMINI_API_KEY` | Gemini APIキー |
| `ANTHROPIC_API_KEY` | Claude利用時のみ（任意） |
| `SUPABASE_URL` | SupabaseプロジェクトURL |
| `SUPABASE_KEY` | Supabase APIキー |

> **重要**: `.env` は絶対にコミットしないでください（`.gitignore` に登録済み）。

### 6. Basic認証の設定（Nginx）

```bash
sudo htpasswd -c /etc/nginx/.htpasswd-keiri-ai eigyo
# パスワードの入力を求められます
```

Nginx設定例（`/etc/nginx/sites-available/keiri-ai`）:

```nginx
server {
    listen 80;
    server_name <ドメイン名>;

    auth_basic "Restricted";
    auth_basic_user_file /etc/nginx/.htpasswd-keiri-ai;

    location / {
        proxy_pass http://127.0.0.1:3100;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8101/;
        proxy_set_header Host $host;
    }

    client_max_body_size 20M;
}
```

有効化:

```bash
sudo ln -s /etc/nginx/sites-available/keiri-ai /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

> `scripts/nginx-keiri-ai.conf` は **certbot 実行前のHTTP版（ブートストラップ用）** です。
> 証明書ファイルは certbot 実行後にしか存在しないため、SSL入りの設定を先に書くと
> 初回デプロイ時に `nginx -t` が失敗します。SSL化は次項で certbot に任せます。

### 6.5 HTTPS化（Let's Encrypt）

Basic認証のパスワードはHTTPでは平文で流れるため、**公開前に必ずHTTPS化**します。

前提: DNSがVPSのIPに向いており、HTTPでアクセスできること。

```bash
sudo apt install -y certbot python3-certbot-nginx   # 未インストールの場合のみ
sudo certbot --nginx -d keiri.ai-l-a-b-o.com --redirect
```

certbot が `/etc/nginx/sites-available/keiri-ai` に `listen 443`・`ssl_certificate`・
HTTP→HTTPSリダイレクトを追記し、nginxをreloadします。
`--redirect` を付けるとHTTPアクセスは自動でHTTPSへ転送されます。

自動更新の確認:

```bash
sudo systemctl status certbot.timer                          # enabled / active であること
sudo certbot renew --dry-run --cert-name keiri.ai-l-a-b-o.com
```

動作確認（Basic認証が効いていれば401が返る）:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://keiri.ai-l-a-b-o.com
```

> **設定の正はどちらか**: certbot 実行後、この設定の最終形は
> **VPS上の `/etc/nginx/sites-enabled/keiri-ai` が正** になります。
> リポジトリ側はHTTP版のまま維持します（理由は `振り返り.md` を参照）。

### 7. 起動

```bash
# APIサーバー
cd api && source .venv/bin/activate
uvicorn main:app --host 127.0.0.1 --port 8101

# フロントエンド（standalone構成のため `npm run start` は使えない）
cd app
PORT=3100 HOSTNAME=127.0.0.1 node .next/standalone/server.js
```

起動後、**待ち受けアドレスが `127.0.0.1` に限定されているか必ず確認**します。

```bash
ss -tlnp | grep -E '3100|8101'
# 期待: 127.0.0.1:3100 / 127.0.0.1:8101
# 0.0.0.0 や * になっていたら nginx の Basic認証を迂回できる状態です
```

常駐化はsystemdで行います（`scripts/keiri-ai-*.service` を参照）。

## 使い方

1. ブラウザでアクセスし、Basic認証でログイン
2. 請求書PDFをアップロード
3. AIが読み取った内容と仕訳案、推定根拠を確認
4. 必要に応じて修正し、承認
5. 「CSV出力」でfreee形式の仕訳CSVをダウンロード

## ディレクトリ構成

```
keiri-ai/
├── app/          # Next.js フロントエンド
├── api/          # FastAPI バックエンド
│   └── llm_client.py   # LLM抽象化レイヤー
├── docs/         # 設計資料
├── samples/      # サンプル請求書
├── scripts/      # デプロイ・運用スクリプト
├── 要件定義.md
├── README.md
└── 振り返り.md
```

## 注意事項

- 本サービスはポートフォリオ用のデモです。実際の会計処理には使用しないでください
- 出力される仕訳はAIによる推定であり、必ず人間による確認が必要です
- サンプル請求書は架空企業のものです
