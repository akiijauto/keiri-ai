"""プロンプト定義。

モデル非依存の形（プレーンなテキスト）で管理し、
出力は必ずJSONで受け取ってスキーマ検証する。
プロバイダを差し替えてもこのファイルは変更不要。
"""

from __future__ import annotations

import accounts

SYSTEM = """あなたは日本の会計実務に精通した経理担当者です。
請求書の内容を読み取り、会計ソフト（freee）に登録するための仕訳案を作成します。

重要な原則:
- あなたは「案」を出すだけです。確定するのは人間です。
- 推測が入る箇所は必ず confidence を下げ、reason に理由を書いてください。
- 読み取れない項目は空文字ではなく null にしてください。推測で埋めないでください。
- 出力はJSONのみ。前置き・説明文・コードフェンスは一切付けないでください。
"""

_EXTRACTION_FIELDS = """
{
  "issuer": "請求元（発行者）の会社名",
  "issuer_invoice_reg_no": "インボイス登録番号（T+13桁）。無ければ null",
  "bill_to": "請求先の会社名",
  "invoice_no": "請求書番号",
  "issue_date": "請求日 (YYYY-MM-DD)",
  "due_date": "支払期日 (YYYY-MM-DD)。無ければ null",
  "total_incl_tax": 税込合計金額（整数）,
  "total_excl_tax": 税抜合計金額（整数）。読み取れなければ null,
  "tax_amount": 消費税額合計（整数）。読み取れなければ null,
  "line_items": [
    {
      "description": "摘要",
      "amount": 金額（整数・税抜）,
      "tax_rate": "10" | "8" | "非課税" | "不課税"
    }
  ]
}
"""

_JOURNAL_FIELDS = """
{
  "is_qualified_invoice": true/false,
  "qualified_invoice_note": "適格請求書か否かの判定理由（1〜2文）",
  "entries": [
    {
      "debit_account": "借方勘定科目（下の一覧から選ぶ）",
      "debit_amount": 借方金額（整数・税込）,
      "credit_account": "貸方勘定科目（通常は 未払金 または 買掛金）",
      "credit_amount": 貸方金額（整数・税込）,
      "tax_code": "税区分（下の一覧のキーから選ぶ）",
      "description": "摘要（取引先名＋内容を簡潔に）",
      "reason": "なぜこの科目・この税区分にしたかの説明（1〜2文、監査で使える具体性で）",
      "confidence": 0.0〜1.0の数値,
      "needs_review": true/false
    }
  ],
  "warnings": ["人間が必ず確認すべき点があれば列挙。無ければ空配列"]
}
"""


def extraction_prompt() -> str:
    """請求書の読取（項目抽出）用プロンプト。"""
    return f"""この請求書から、以下のJSON形式で情報を抽出してください。

{_EXTRACTION_FIELDS}

注意:
- 金額はカンマや円記号を除いた整数で返してください。
- 日付は和暦・「2026年7月31日」形式でも YYYY-MM-DD に変換してください。
- インボイス登録番号は「T」で始まる13桁の数字です。見当たらなければ必ず null にしてください。
  （書かれていないのに推測で埋めることは絶対にしないでください）

JSONのみを出力してください。
"""


def journal_prompt(extracted_json: str) -> str:
    """仕訳案生成用プロンプト。"""
    return f"""以下は請求書から抽出した内容です。

{extracted_json}

これをもとに、freeeに登録する仕訳案を作成してください。

## 使用できる勘定科目

{accounts.as_prompt_table()}

## 使用できる税区分

{accounts.tax_code_list()}

## 判断ルール

1. 税率が明細ごとに異なる場合は、税率ごとに仕訳行を分けてください。
2. インボイス登録番号（T+13桁）が無い場合:
   - is_qualified_invoice を false にする
   - 税区分は「課対仕入10%(区分記載)」を使う
   - warnings に仕入税額控除の経過措置に関する確認事項を入れる
3. 賃料・保険料など非課税取引は「非課仕入」を使ってください。
   ただし事業用建物の賃貸は原則課税のため、契約内容の確認を warnings に入れてください。
4. 持ち帰りの飲食物は軽減税率8%、店内飲食・ケータリングサービスは10%です。
5. 判断に迷う場合は confidence を 0.7 未満にし、needs_review を true にしてください。
6. 借方合計と貸方合計は必ず一致させてください。

## 出力形式

{_JOURNAL_FIELDS}

JSONのみを出力してください。
"""
