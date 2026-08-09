"""サンプル請求書PDFを生成する（すべて架空企業）。

想定パターン:
  01 通常課税仕入（インボイス登録番号あり）
  02 軽減税率8%混在
  03 非課税取引（賃料・保険料）
  04 インボイス登録番号なし
  05 画像PDF（スキャン想定）
"""

from __future__ import annotations

import subprocess
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle,
)
from reportlab.lib.styles import ParagraphStyle

# 埋め込み可能なTTFを使う（CIDフォントだとラスタライズ時にフォント解決に失敗するため）
pdfmetrics.registerFont(TTFont("JPGothic", "/usr/share/fonts/truetype/fonts-japanese-gothic.ttf"))
FONT = "JPGothic"

OUT = Path(__file__).resolve().parent

title_style = ParagraphStyle("t", fontName=FONT, fontSize=20, leading=26)
normal = ParagraphStyle("n", fontName=FONT, fontSize=9.5, leading=14)
small = ParagraphStyle("s", fontName=FONT, fontSize=8, leading=11)
right = ParagraphStyle("r", fontName=FONT, fontSize=9.5, leading=14, alignment=2)


def yen(n: int) -> str:
    return f"\u00a5{n:,}"


def build(filename: str, meta: dict, rows: list[dict], footer_notes: list[str]) -> Path:
    path = OUT / filename
    doc = SimpleDocTemplate(
        str(path), pagesize=A4,
        leftMargin=20 * mm, rightMargin=20 * mm,
        topMargin=18 * mm, bottomMargin=18 * mm,
    )
    story = [Paragraph("請 求 書", title_style), Spacer(1, 6 * mm)]

    head = Table(
        [[Paragraph(f"{meta['bill_to']} 御中", normal),
          Paragraph(f"請求書番号: {meta['invoice_no']}<br/>"
                    f"請求日: {meta['issue_date']}<br/>"
                    f"支払期日: {meta['due_date']}", right)]],
        colWidths=[95 * mm, 75 * mm],
    )
    head.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP")]))
    story += [head, Spacer(1, 4 * mm)]

    issuer = (f"<b>{meta['issuer']}</b><br/>{meta['issuer_addr']}<br/>"
              f"TEL: {meta['issuer_tel']}")
    if meta.get("invoice_reg_no"):
        issuer += f"<br/>登録番号: {meta['invoice_reg_no']}"
    story += [Paragraph(issuer, right), Spacer(1, 6 * mm)]

    total = sum(r["amount"] for r in rows)
    story += [
        Paragraph(f"下記のとおりご請求申し上げます。<br/>"
                  f"<b>ご請求金額（税込）: {yen(meta['total_incl'])}</b>", normal),
        Spacer(1, 5 * mm),
    ]

    data = [["摘要", "数量", "単価", "金額", "税区分"]]
    for r in rows:
        data.append([
            Paragraph(r["desc"], small), str(r["qty"]),
            yen(r["unit"]), yen(r["amount"]), r["tax"],
        ])
    tbl = Table(data, colWidths=[75 * mm, 15 * mm, 25 * mm, 30 * mm, 25 * mm])
    tbl.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), FONT),
        ("FONTSIZE", (0, 0), (-1, -1), 8.5),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#EFEFEF")),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#888888")),
        ("ALIGN", (1, 1), (-1, -1), "RIGHT"),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    story += [tbl, Spacer(1, 4 * mm)]

    summary = [[k, yen(v)] for k, v in meta["summary"]]
    stbl = Table(summary, colWidths=[45 * mm, 35 * mm], hAlign="RIGHT")
    stbl.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), FONT),
        ("FONTSIZE", (0, 0), (-1, -1), 9),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#888888")),
        ("ALIGN", (1, 0), (1, -1), "RIGHT"),
    ]))
    story += [stbl, Spacer(1, 6 * mm)]

    for note in footer_notes:
        story.append(Paragraph(note, small))

    doc.build(story)
    return path


SAMPLES = []

# --- 01 通常課税仕入 ---
SAMPLES.append(dict(
    filename="01_通常課税_インボイスあり.pdf",
    meta=dict(
        bill_to="株式会社サンプル商事", invoice_no="INV-2026-0451",
        issue_date="2026年7月31日", due_date="2026年8月31日",
        issuer="オフィスサプライ東西株式会社",
        issuer_addr="東京都千代田区架空町1-2-3", issuer_tel="03-0000-0001",
        invoice_reg_no="T1234567890123",
        total_incl=59_400,
        summary=[("小計（10%対象）", 54_000), ("消費税（10%）", 5_400),
                 ("合計", 59_400)],
    ),
    rows=[
        dict(desc="コピー用紙 A4 500枚×10箱", qty=10, unit=2_800, amount=28_000, tax="10%"),
        dict(desc="トナーカートリッジ 黒", qty=2, unit=9_500, amount=19_000, tax="10%"),
        dict(desc="事務用ファイル 一式", qty=1, unit=7_000, amount=7_000, tax="10%"),
    ],
    footer_notes=["お振込先: 架空銀行 架空支店 普通 1234567 オフィスサプライトウザイ(カ",
                  "振込手数料は貴社にてご負担願います。"],
))

# --- 02 軽減税率混在 ---
SAMPLES.append(dict(
    filename="02_軽減税率混在.pdf",
    meta=dict(
        bill_to="株式会社サンプル商事", invoice_no="INV-2026-0777",
        issue_date="2026年7月28日", due_date="2026年8月31日",
        issuer="ケータリング架空亭",
        issuer_addr="東京都港区架空1-1-1", issuer_tel="03-0000-0002",
        invoice_reg_no="T9876543210987",
        total_incl=48_100,
        summary=[("小計（8%対象）", 25_000), ("消費税（8%）", 2_000),
                 ("小計（10%対象）", 19_000), ("消費税（10%）", 1_900),
                 ("合計", 48_100)],
    ),
    rows=[
        dict(desc="会議用弁当 50食（持ち帰り）", qty=50, unit=500, amount=25_000, tax="8%（軽減）"),
        dict(desc="会場内飲食サービス 懇親会", qty=1, unit=19_000, amount=19_000, tax="10%"),
    ],
    footer_notes=["※8%は軽減税率対象品目です。",
                  "お振込先: 架空銀行 架空支店 普通 7654321 ケータリングカクウテイ"],
))

# --- 03 非課税取引 ---
SAMPLES.append(dict(
    filename="03_非課税_賃料保険料.pdf",
    meta=dict(
        bill_to="株式会社サンプル商事", invoice_no="RENT-2026-08",
        issue_date="2026年7月25日", due_date="2026年8月05日",
        issuer="架空不動産管理株式会社",
        issuer_addr="東京都新宿区架空2-2-2", issuer_tel="03-0000-0003",
        invoice_reg_no="T1112223334445",
        total_incl=341_000,
        summary=[("非課税小計", 300_000), ("小計（10%対象）", 20_000),
                 ("消費税（10%）", 2_000), ("火災保険料（非課税）", 19_000),
                 ("合計", 341_000)],
    ),
    rows=[
        dict(desc="事務所賃料 2026年8月分", qty=1, unit=300_000, amount=300_000, tax="非課税"),
        dict(desc="共益費 2026年8月分", qty=1, unit=20_000, amount=20_000, tax="10%"),
        dict(desc="火災保険料 2026年8月分", qty=1, unit=19_000, amount=19_000, tax="非課税"),
    ],
    footer_notes=["※住宅以外の賃貸借に係る消費税の取扱いにご留意ください。"],
))

# --- 04 インボイス登録番号なし ---
SAMPLES.append(dict(
    filename="04_インボイス登録番号なし.pdf",
    meta=dict(
        bill_to="株式会社サンプル商事", invoice_no="2026-0032",
        issue_date="2026年7月20日", due_date="2026年8月20日",
        issuer="フリーランス架空デザイン事務所",
        issuer_addr="神奈川県横浜市架空区3-3-3", issuer_tel="045-000-0004",
        invoice_reg_no=None,
        total_incl=165_000,
        summary=[("小計（10%対象）", 150_000), ("消費税（10%）", 15_000),
                 ("合計", 165_000)],
    ),
    rows=[
        dict(desc="Webサイトデザイン制作費 一式", qty=1, unit=120_000, amount=120_000, tax="10%"),
        dict(desc="バナー制作 3点", qty=3, unit=10_000, amount=30_000, tax="10%"),
    ],
    footer_notes=["お振込先: 架空信用金庫 架空支店 普通 1112223"],
))

# --- 05 画像PDF用（内容は 01 と別取引） ---
SAMPLES.append(dict(
    filename="05_スキャン想定_原本.pdf",
    meta=dict(
        bill_to="株式会社サンプル商事", invoice_no="A-20260715",
        issue_date="2026年7月15日", due_date="2026年8月15日",
        issuer="架空運送株式会社",
        issuer_addr="埼玉県さいたま市架空区4-4-4", issuer_tel="048-000-0005",
        invoice_reg_no="T5556667778889",
        total_incl=93_500,
        summary=[("小計（10%対象）", 85_000), ("消費税（10%）", 8_500),
                 ("合計", 93_500)],
    ),
    rows=[
        dict(desc="配送料 2026年7月分（120件）", qty=120, unit=600, amount=72_000, tax="10%"),
        dict(desc="時間指定手数料", qty=26, unit=500, amount=13_000, tax="10%"),
    ],
    footer_notes=["お振込先: 架空銀行 架空支店 当座 0009988"],
))


def main() -> None:
    made = []
    for s in SAMPLES:
        made.append(build(s["filename"], s["meta"], s["rows"], s["footer_notes"]))

    # 05 を画像PDF（スキャン想定）に変換
    src = OUT / "05_スキャン想定_原本.pdf"
    subprocess.run(
        ["pdftoppm", "-r", "150", "-jpeg", str(src), str(OUT / "05_page")],
        check=True,
    )
    img = OUT / "05_page-1.jpg"
    dst = OUT / "05_スキャン想定_画像PDF.pdf"
    subprocess.run(["img2pdf", str(img), "-o", str(dst)], check=False)
    if not dst.exists():
        from reportlab.pdfgen import canvas
        from reportlab.lib.utils import ImageReader
        c = canvas.Canvas(str(dst), pagesize=A4)
        c.drawImage(ImageReader(str(img)), 0, 0, width=A4[0], height=A4[1])
        c.save()
    src.unlink(missing_ok=True)
    img.unlink(missing_ok=True)
    made.append(dst)

    for p in sorted(OUT.glob("*.pdf")):
        print(f"  {p.name}  ({p.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
