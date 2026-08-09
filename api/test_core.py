"""機能テスト（AI APIを呼ばずに検証できる範囲）。

実行: cd api && python3 test_core.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import accounts
import freee_csv
import pdf_reader
import prompts

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
passed, failed = 0, 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


print("\n[1] 勘定科目マスタ")
check("科目が18件以上ある", len(accounts.ACCOUNTS) >= 18, str(len(accounts.ACCOUNTS)))
check("消耗品費が存在する", accounts.is_known_account("消耗品費"))
check("未登録科目を弾く", not accounts.is_known_account("架空費"))
check("税区分5種が定義済み", len(accounts.TAX_CODES) == 5)
check("軽減税率の税区分がある", accounts.is_known_tax_code("課対仕入8%(軽)"))
check("プロンプト用テーブルが生成できる", "勘定科目" in accounts.as_prompt_table())

print("\n[2] プロンプト生成")
check("抽出プロンプトに登録番号の指示がある", "T" in prompts.extraction_prompt())
check("推測禁止の指示がある", "推測" in prompts.extraction_prompt())
jp = prompts.journal_prompt('{"issuer":"テスト"}')
check("仕訳プロンプトに科目一覧が埋まる", "消耗品費" in jp)
check("仕訳プロンプトに税区分が埋まる", "課対仕入10%" in jp)
check("貸借一致ルールが含まれる", "一致" in jp)
check("インボイス経過措置ルールが含まれる", "区分記載" in jp)

print("\n[3] freee CSV出力")
sample_journal = {
    "entries": [
        {"debit_account": "消耗品費", "debit_amount": 59400,
         "credit_account": "未払金", "credit_amount": 59400,
         "tax_code": "課対仕入10%", "description": "オフィス用品", "confidence": 0.9},
    ]
}
rows = freee_csv.from_journal_result(
    sample_journal, partner="オフィスサプライ東西株式会社", date="2026-07-31")
check("仕訳行に変換できる", len(rows) == 1)
check("日付がYYYY/MM/DDになる", rows[0].to_row()[0] == "2026/07/31", rows[0].to_row()[0])
ok, msg = freee_csv.validate_balance(rows)
check("貸借一致を検出できる", ok, msg)

unbalanced = freee_csv.from_journal_result({"entries": [
    {"debit_account": "消耗品費", "debit_amount": 100,
     "credit_account": "未払金", "credit_amount": 90,
     "tax_code": "課対仕入10%", "description": "x"}]})
ok2, msg2 = freee_csv.validate_balance(unbalanced)
check("貸借不一致を検出できる", not ok2, msg2)

csv_bytes = freee_csv.build_csv(rows)
check("CSVがcp932で出力される", isinstance(csv_bytes, bytes) and len(csv_bytes) > 0)
decoded = csv_bytes.decode("cp932")
check("ヘッダー行がある", decoded.startswith("日付,借方勘定科目"))
check("改行がCRLF", "\r\n" in decoded)

print("\n[4] PDF読取")
if SAMPLES.exists():
    text_pdf = SAMPLES / "01_通常課税_インボイスあり.pdf"
    if text_pdf.exists():
        r = pdf_reader.extract(text_pdf)
        check("テキストPDFと判定される", not r.is_scanned)
        check("請求書番号が抽出できる", "INV-2026-0451" in r.text, r.text[:80])
        check("インボイス登録番号が抽出できる", "T1234567890123" in r.text)
    scan_pdf = SAMPLES / "05_スキャン想定_画像PDF.pdf"
    if scan_pdf.exists():
        r2 = pdf_reader.extract(scan_pdf)
        check("画像PDFと判定される", r2.is_scanned)
        check("画像が生成される", len(r2.images) >= 1)
else:
    print("  SKIP  samples ディレクトリがありません")

print(f"\n結果: {passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
