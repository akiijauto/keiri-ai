# SLO Handoff Protocol v1.0

**Secure Local OCR Handoff Protocol** — 確認済み個人情報を「取込元」から「登録先Webフォーム」へ
受け渡すための共通契約。

本プロトコルは *取込方法に依存しない*。オンデバイスOCR（Android / iOS）、入居フォーム等の
Web入力、職員による手入力のいずれも、**同一のデータモデル・同一の正規化規則・同一の検証規則**で
登録先へ受け渡す。

> 原則: 本プロトコルの経路にクラウドOCR・生成AI・外部ストレージは一切登場しない。
> 受け渡しは端末内（WebViewブリッジ / クリップボード / 手動転記）またはループバックで完結する。

---

## 1. 用語

| 用語 | 定義 |
|------|------|
| Source（取込元） | 確認済みデータを生成する主体。OCRアプリ、入居Webフォーム、手入力画面。 |
| Target（登録先） | 確認済みデータを受け取る業務Webサイトのフォーム。 |
| Envelope | 受け渡し単位のJSONオブジェクト。§3。 |
| Profile | 項目集合と正規化・検証規則の版番号付き定義。§5。 |
| Transport | Envelope の物理的な受け渡し手段。§7。 |

---

## 2. 設計上の不変条件（Invariants）

| ID | 不変条件 |
|----|----------|
| INV-1 | `confirmed: true` の Envelope のみが Target に渡る。人間の確認を経ていないデータは Envelope 化しない。 |
| INV-2 | Envelope に原画像・OCR生テキスト全文を含めない。含めてよいのは抽出・確認済みの項目値のみ。 |
| INV-3 | Envelope は `expires_at` を過ぎたら Target 側で無条件に破棄される。 |
| INV-4 | Target は Envelope を受け取っても**自動送信しない**。入力欄を埋めるところまでが上限。 |
| INV-5 | 監査ログに項目値を書かない。書けるのは項目名・件数・イベント種別・結果のみ。 |
| INV-6 | 正規化・検証は Source 側と Target 側の双方で実行し、結果が一致しない場合は取り込みを拒否する。 |

---

## 3. Envelope

```json
{
  "protocol": "slo-handoff/1.0",
  "handoff_id": "6f1d2c9a-6b1e-4f52-9d33-2a1b0c4e77aa",
  "issued_at": "2026-08-22T09:15:00Z",
  "expires_at": "2026-08-22T09:20:00Z",
  "document_type": "residency_application",
  "profile": "jp.personal.basic/1",
  "source": {
    "kind": "ondevice-ocr",
    "app": "SecureLocalOCR-Android",
    "version": "0.1.0",
    "engine": "mlkit-ja-on-device",
    "offline_capture": true
  },
  "confirmed": true,
  "fields": {
    "name":        { "value": "山田　太郎",   "origin": "ocr",    "confidence": 0.92, "edited": false, "confirmed": true },
    "birthday":    { "value": "1980-01-01",  "origin": "ocr",    "confidence": 0.71, "edited": true,  "confirmed": true },
    "postal_code": { "value": "150-0001",    "origin": "manual", "confidence": null, "edited": true,  "confirmed": true }
  },
  "integrity": {
    "alg": "HMAC-SHA256",
    "key_id": "session:9d0f...",
    "value": "3b1f...base64url"
  }
}
```

### 3.1 トップレベル項目

| キー | 型 | 必須 | 説明 |
|------|----|------|------|
| `protocol` | string | ✓ | 固定 `"slo-handoff/1.0"`。 |
| `handoff_id` | string(UUIDv4) | ✓ | 受け渡し1回の識別子。Target は同一IDの二重取込を拒否する。 |
| `issued_at` | string(RFC3339, UTC) | ✓ | Envelope 生成時刻。 |
| `expires_at` | string(RFC3339, UTC) | ✓ | 失効時刻。既定は `issued_at + 300s`、上限 900s。 |
| `document_type` | string | ✓ | 帳票種別（`residency_application` 等）。 |
| `profile` | string | ✓ | 項目定義の版。`jp.personal.basic/1` 等。 |
| `source` | object | ✓ | §3.2。 |
| `confirmed` | boolean | ✓ | 常に `true`（INV-1）。`false` の Envelope は不正。 |
| `fields` | object | ✓ | 項目キー → §3.3。 |
| `integrity` | object |  | Transport が改ざん検知を要求する場合に必須。§6。 |

### 3.2 `source`

| キー | 型 | 必須 | 値 |
|------|----|------|----|
| `kind` | string | ✓ | `ondevice-ocr` / `web-form` / `manual` |
| `app` | string | ✓ | 実装名 |
| `version` | string | ✓ | 実装バージョン |
| `engine` | string |  | `mlkit-ja-on-device` / `apple-vision` / `none` |
| `offline_capture` | boolean |  | 取込フェーズが機内モード等で完結したか |

### 3.3 `fields[key]`

| キー | 型 | 必須 | 説明 |
|------|----|------|------|
| `value` | string | ✓ | **正規化後**の値。Profile の規則に適合していること。 |
| `origin` | string | ✓ | `ocr` / `manual` / `web-form` |
| `confidence` | number\|null | ✓ | 0.0–1.0。OCR以外は `null`。 |
| `edited` | boolean | ✓ | 人間が値を修正したか。 |
| `confirmed` | boolean | ✓ | 常に `true`（INV-1）。 |

---

## 4. 正規化（Normalization）

Source と Target の両方が、同一の入力に対し同一の出力を返さねばならない（INV-6）。
実装は 3 言語（Kotlin / TypeScript / Swift）に存在し、
`protocol/testdata/normalization-vectors.json` の共通ベクタで一致を検証する。

共通前処理（全項目）:

1. Unicode NFKC 正規化を **行わない**（住所の丸数字・ローマ数字を壊さないため）。個別規則で対応する。
2. 前後の空白（半角/全角/タブ）を除去。
3. 制御文字を除去。
4. OCR頻出誤字を項目別の文脈でのみ補正する（§4.1）。

項目別規則は §5 の Profile 定義に従う。

### 4.1 OCR頻出誤字補正（文脈限定）

数字が期待される位置でのみ適用する。氏名・住所には適用しない。

| 誤 | 正 | 適用文脈 |
|----|----|----------|
| `O` `o` `〇` | `0` | 数字列内 |
| `I` `l` `｜` | `1` | 数字列内 |
| `S` | `5` | 数字列内 |
| `B` | `8` | 数字列内 |
| `—` `ー` `―` `‐` | `-` | 電話番号・郵便番号の区切り |

---

## 5. Profile: `jp.personal.basic/1`

| キー | 日本語ラベル | 正規化 | 検証 |
|------|--------------|--------|------|
| `name` | 氏名 | 全角化、姓名間を全角スペース1個に統一 | 1–64文字、数字を含まない |
| `name_kana` | フリガナ | ひらがな→カタカナ、全角化、姓名間を全角スペース1個 | 全角カタカナと全角スペースのみ |
| `birthday` | 生年月日 | 和暦・スラッシュ・漢字年月日を `YYYY-MM-DD` へ | 実在日付、1900-01-01〜本日 |
| `postal_code` | 郵便番号 | 数字7桁を抽出し `NNN-NNNN` | 正規表現 `^\d{3}-\d{4}$` |
| `address` | 住所 | 全角化（数字・ハイフンは半角維持）、連続空白を1個へ | 1–128文字、都道府県で始まる（警告レベル） |
| `phone` | 電話番号 | 数字のみ抽出、先頭 `+81` は `0` へ | 10桁または11桁、先頭 `0` |
| `email` | メールアドレス | 全角英数を半角化、小文字化 | `^[^@\s]+@[^@\s]+\.[^@\s]+$`、254文字以下 |
| `customer_no` | 顧客番号 | 全角英数を半角化、大文字化 | 1–32文字、英数とハイフン |
| `move_in_date` | 入居予定日 | `birthday` と同じ日付正規化 | 実在日付 |

`document_type` ごとの必須項目:

| document_type | 必須 |
|---------------|------|
| `residency_application`（入居申込） | `name`, `name_kana`, `birthday`, `postal_code`, `address`, `phone` |
| `contact_registration`（連絡先登録） | `name`, `phone` |
| `generic` | なし |

---

## 6. 完全性（Integrity）

`webview-bridge` および `loopback` Transport では `integrity` を必須とする。

1. Envelope から `integrity` を除いたオブジェクトを **正準化JSON**（§6.1）へ直列化。
2. `HMAC-SHA256(session_key, canonical_bytes)` を base64url（パディング無し）で `integrity.value` に格納。
3. `session_key` は Transport のハンドシェイクで確立した 32 バイトのセッション鍵。**端末外へ出ない**。
4. Target は同じ手順で再計算し、一致しなければ取込を拒否する。

> 目的は「秘匿」ではなく「同一端末内での取り違え・改ざん・リプレイの検知」。
> 秘匿は Transport が端末内で完結していること自体で担保する。

### 6.1 正準化JSON

RFC 8785 (JCS) のサブセット:

- オブジェクトのキーは UTF-16 コードユニット昇順にソート
- 余分な空白なし（`{"a":1,"b":2}`）
- 文字列は `"` `\` と U+0000–U+001F のみエスケープ（`\n` `\t` 等の短縮形を優先）、非ASCIIはそのまま
- 数値は整数はそのまま、小数は最短往復表現
- `null` はそのまま出力する

---

## 7. Transport

| ID | 名称 | 用途 | Level | integrity |
|----|------|------|-------|-----------|
| T1 | `manual` | 画面表示のみ。人間が転記 | 1 | 不要 |
| T2 | `clipboard` | 項目ごとにコピー | 2 | 不要 |
| T3 | `webview-bridge` | アプリ内WebView ↔ 対象サイト | 3–4 | **必須** |
| T4 | `loopback` | 127.0.0.1 経由でPC側ブラウザへ | 3–4 | **必須** |

### 7.1 T3: WebView ブリッジ ハンドシェイク

```
[Target Page]                              [Native App]
      |  1. slo-bridge.js 読込                    |
      |  2. SLO.ready({profile, document_type,    |
      |        fields:[...], nonce})              |
      | ----------- postMessage -------->         |
      |                                           | 3. オリジン許可リスト検証
      |                                           | 4. セッション鍵生成（端末内）
      |  5. SLO._session({key_id, key})           |
      | <-------- evaluateJavascript ----         |
      |                                           | 6. 確認画面を人間へ提示
      |  7. SLO._deliver(envelope)                | 　（人間が「入力する」を押す）
      | <-------- evaluateJavascript ----         |
      |  8. 検証（protocol/nonce/expiry/HMAC/     |
      |      正規化再計算）→ 入力欄へ充填         |
      |  9. slo:filled { field_count }            |
      | ----------- postMessage -------->         | 10. 監査ログ記録（値なし）
```

- Native は許可リストに無いオリジンからの `ready` を**無視**する（エラー内容も返さない）。
- `nonce` は Target が生成する 16 バイト乱数。Envelope の HMAC 対象に含まれる `handoff_id` と
  1:1 に対応させ、Native 側で消費済み nonce を再利用しない。
- 送信ボタンの自動クリックは実装しない（INV-4）。

### 7.2 T4: ループバック

`POST http://127.0.0.1:<port>/slo/v1/handoff`。
待受はブラウザ拡張／ローカル補助アプリ側。ヘッダに `X-SLO-Session: <key_id>`。
外部NICへはバインドしない（`127.0.0.1` のみ）。

---

## 8. フィールドマッピング

Target サイトごとに `slo-mapping.json` を用意する。

```json
{
  "site": "example-residency",
  "origins": ["https://form.example.co.jp"],
  "document_type": "residency_application",
  "map": {
    "name":        { "selector": "#applicant_name" },
    "name_kana":   { "selector": "#applicant_kana" },
    "birthday":    { "selector": "#birth", "format": "YYYY/MM/DD" },
    "postal_code": { "selector": ["#zip1", "#zip2"], "split": "-" },
    "phone":       { "selector": ["#tel1","#tel2","#tel3"], "split": "jp-phone" }
  }
}
```

マッピングが無い項目は自動推定（`autocomplete` 属性 → `name` 属性 → `label` テキスト）に
フォールバックするが、推定で埋めた欄は UI 上で「推定」バッジを付け、人間の確認対象とする。

`format` に指定できる値: `ISO`（既定）, `YYYY/MM/DD`, `YYYYMMDD`, `wareki`。
`split` に指定できる値: 任意の区切り文字, `jp-phone`（市外/市内/加入者番号の3分割）。

---

## 9. Web入力元（入居フォーム）との連携

入居フォーム等、Web上で直接入力された内容も**同じ Envelope** に載せる。

```
入居フォーム（Web入力）           OCRアプリ（撮影）
        |                              |
   同一の正規化                   同一の正規化
        |                              |
        +------- Envelope v1 ----------+
                      |
              登録先Webフォーム
```

`source.kind = "web-form"`、`fields[].origin = "web-form"`、`confidence = null`。
検証・マッピング・監査ログの経路は OCR 由来と完全に共通。
これにより登録先は取込元を意識せず、1つの受け口だけを実装すればよい。

---

## 10. 監査ログ（値を含まない）

```
2026-08-22T09:15:00Z  HANDOFF_REQUESTED  profile=jp.personal.basic/1 fields=6
2026-08-22T09:15:03Z  HANDOFF_DELIVERED  handoff_id=6f1d…7aa fields=6
2026-08-22T09:15:03Z  HANDOFF_VERIFIED   result=ok
2026-08-22T09:15:04Z  FORM_FILLED        filled=6 skipped=0 guessed=1
2026-08-22T09:15:31Z  SUBMIT_BY_HUMAN    -
```

拒否理由コード: `E_ORIGIN` / `E_PROTOCOL` / `E_EXPIRED` / `E_NONCE` / `E_INTEGRITY` /
`E_UNCONFIRMED` / `E_VALIDATION` / `E_REPLAY`。

---

## 11. バージョニング

`slo-handoff/1.0` の MINOR 追加は後方互換（未知キーは無視）。
互換性を壊す変更は `slo-handoff/2.0` とし、Target は両対応期間を設ける。
Profile は独立に版管理する（`jp.personal.basic/2` 等）。
