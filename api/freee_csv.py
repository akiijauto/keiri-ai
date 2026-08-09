"""freee形式の仕訳CSV出力。

【重要】freeeのCSVインポート仕様（列名・順序・文字コード・日付形式）は
バージョンにより変わるため、実装確定前に必ず公式ヘルプで最新仕様を確認すること。
本モジュールは列定義を COLUMNS 一箇所にまとめているため、
仕様差分はそこだけ直せば吸収できる設計にしている。

既定: 振替伝票形式 / Shift_JIS(cp932) / YYYY/MM/DD
"""

from __future__ import annotations

import csv
import io
from dataclasses import dataclass

# freee 振替伝票インポートを想定した列定義
COLUMNS = [
    "日付",
    "借方勘定科目",
    "借方金額",
    "借方税区分",
    "貸方勘定科目",
    "貸方金額",
    "貸方税区分",
    "備考",
    "取引先",
]

ENCODING = "cp932"   # freeeはShift_JIS想定。UTF-8指定の場合は "utf-8-sig"
DATE_FORMAT = "%Y/%m/%d"


@dataclass
class JournalRow:
    """CSV1行分の仕訳。"""

    date: str            # YYYY-MM-DD
    debit_account: str
    debit_amount: int
    debit_tax_code: str
    credit_account: str
    credit_amount: int
    credit_tax_code: str
    description: str
    partner: str = ""

    def to_row(self) -> list[str]:
        return [
            _fmt_date(self.date),
            self.debit_account,
            str(self.debit_amount),
            self.debit_tax_code,
            self.credit_account,
            str(self.credit_amount),
            self.credit_tax_code,
            self.description,
            self.partner,
        ]


def _fmt_date(iso_date: str) -> str:
    """YYYY-MM-DD を freee 想定の YYYY/MM/DD に変換する。"""
    from datetime import datetime

    try:
        return datetime.strptime(iso_date, "%Y-%m-%d").strftime(DATE_FORMAT)
    except (ValueError, TypeError):
        return iso_date or ""


def build_csv(rows: list[JournalRow], encoding: str = ENCODING) -> bytes:
    """仕訳行からCSVバイト列を生成する。"""
    buf = io.StringIO(newline="")
    writer = csv.writer(buf, lineterminator="\r\n")
    writer.writerow(COLUMNS)
    for r in rows:
        writer.writerow(r.to_row())
    return buf.getvalue().encode(encoding, errors="replace")


def from_journal_result(result: dict, *, partner: str = "", date: str = "") -> list[JournalRow]:
    """AIの仕訳案JSONを CSV行に変換する。

    貸方の税区分は「対象外」固定（買掛金・未払金のため）。
    """
    rows: list[JournalRow] = []
    for e in result.get("entries", []):
        rows.append(JournalRow(
            date=date,
            debit_account=e.get("debit_account", ""),
            debit_amount=int(e.get("debit_amount") or 0),
            debit_tax_code=e.get("tax_code", ""),
            credit_account=e.get("credit_account", "未払金"),
            credit_amount=int(e.get("credit_amount") or 0),
            credit_tax_code="対象外",
            description=e.get("description", ""),
            partner=partner,
        ))
    return rows


def validate_balance(rows: list[JournalRow]) -> tuple[bool, str]:
    """借方合計と貸方合計の一致を検証する。"""
    debit = sum(r.debit_amount for r in rows)
    credit = sum(r.credit_amount for r in rows)
    if debit != credit:
        return False, f"貸借不一致: 借方 {debit:,} / 貸方 {credit:,}"
    return True, "貸借一致"
