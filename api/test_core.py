"""機能テスト（AI APIを呼ばずに検証できる範囲）。

実行: cd api && python3 test_core.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import io

from PIL import Image

import accounts
import freee_csv
import image_prep
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

print("\n[5] 撮影画像の前処理")


def make_jpeg(width: int, height: int, orientation: int | None = None) -> bytes:
    """テスト用のJPEGを作る。orientationを指定するとEXIFに回転情報を埋める。"""
    img = Image.new("RGB", (width, height), (200, 200, 200))
    # 上下左右が分かるよう、左上だけ色を変える（回転の検証に使う）
    img.paste((20, 20, 20), (0, 0, max(1, width // 4), max(1, height // 4)))
    buf = io.BytesIO()
    if orientation is None:
        img.save(buf, format="JPEG")
    else:
        exif = img.getexif()
        exif[image_prep.EXIF_ORIENTATION_TAG] = orientation
        img.save(buf, format="JPEG", exif=exif)
    return buf.getvalue()


def size_of(data: bytes) -> tuple[int, int]:
    return Image.open(io.BytesIO(data)).size


# --- EXIF回転 ---
# orientation=6 は「反時計回りに90度回すと正立する」。
# 横長(900x600)で撮られた縦向き写真を想定し、補正後は縦長(600x900)になるはず。
rotated = image_prep.prepare(make_jpeg(900, 600, orientation=6), filename="p.jpg")
check("EXIF回転が正立する（縦横が入れ替わる）",
      size_of(rotated.data) == (600, 900), str(size_of(rotated.data)))
check("回転したことがフラグで分かる", rotated.was_rotated)

upright = image_prep.prepare(make_jpeg(800, 600, orientation=1), filename="p.jpg")
check("回転不要な画像は変形しない",
      size_of(upright.data) == (800, 600), str(size_of(upright.data)))
check("回転なしのフラグが立つ", not upright.was_rotated)

no_exif = image_prep.prepare(make_jpeg(800, 600), filename="p.jpg")
check("EXIFが無くても処理できる", size_of(no_exif.data) == (800, 600))

# --- リサイズ ---
big = image_prep.prepare(make_jpeg(4032, 3024), filename="p.jpg")
check("リサイズ後の長辺が2000px以下",
      max(size_of(big.data)) <= image_prep.MAX_LONG_EDGE, str(size_of(big.data)))
check("縦横比が保たれる",
      abs(size_of(big.data)[0] / size_of(big.data)[1] - 4032 / 3024) < 0.01)
check("縮小したことがフラグで分かる", big.was_resized)
check("縮小でデータ量が小さくなる",
      len(big.data) < len(make_jpeg(4032, 3024)))

small = image_prep.prepare(make_jpeg(1200, 900), filename="p.jpg")
check("2000px以下の画像は拡大されない", size_of(small.data) == (1200, 900))
check("縮小なしのフラグが立つ", not small.was_resized)

# --- 回転とリサイズの併用 ---
both = image_prep.prepare(make_jpeg(4032, 3024, orientation=6), filename="p.jpg")
check("回転とリサイズが同時に効く",
      max(size_of(both.data)) <= image_prep.MAX_LONG_EDGE
      and size_of(both.data)[1] > size_of(both.data)[0], str(size_of(both.data)))

# --- 出力形式 ---
check("出力はJPEGで返る", both.mime_type == "image/jpeg")
png = Image.new("RGBA", (100, 100), (255, 0, 0, 128))
pbuf = io.BytesIO()
png.save(pbuf, format="PNG")
check("PNG(透過あり)もJPEGに変換できる",
      image_prep.prepare(pbuf.getvalue(), filename="p.png").mime_type == "image/jpeg")

# --- HEIC ---
heic_bytes = b"\x00\x00\x00\x18ftypheic" + b"\x00" * 32
check("HEICをバイト列で判定できる", image_prep.is_heic(heic_bytes))
check("拡張子でもHEICと判定できる", image_prep.is_heic(b"\x00" * 32, "IMG_0001.HEIC"))
check("JPEGはHEICと誤判定しない", not image_prep.is_heic(make_jpeg(10, 10), "p.jpg"))

try:
    image_prep.prepare(heic_bytes, filename="IMG_0001.heic")
    check("HEICはエラーになる", False, "例外が送出されなかった")
except image_prep.UnsupportedImageError as exc:
    check("HEICはエラーになる", True)
    check("HEICのエラーに「互換性優先」の案内が含まれる", "互換性優先" in str(exc))
    check("HEICのエラーにPDF変換の案内が含まれる", "PDF" in str(exc))

try:
    image_prep.prepare(b"this is not an image", filename="p.jpg")
    check("画像でないデータはエラーになる", False, "例外が送出されなかった")
except image_prep.UnsupportedImageError:
    check("画像でないデータはエラーになる", True)

print(f"\n結果: {passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
