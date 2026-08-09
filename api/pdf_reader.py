"""PDF読取モジュール。

テキストPDFはそのままテキスト抽出し、テキストが乏しい（＝スキャン画像）場合は
ページを画像化してAIのマルチモーダル入力に回す。
"""

from __future__ import annotations

import io
from dataclasses import dataclass
from pathlib import Path

import pdfplumber
from pdf2image import convert_from_path

# この文字数を下回ったら画像PDFとみなす
TEXT_THRESHOLD = 120


@dataclass
class ExtractedPdf:
    """PDFから取り出した素材。"""

    text: str
    images: list[bytes]
    is_scanned: bool
    page_count: int


def extract_text(pdf_path: str | Path) -> tuple[str, int]:
    """テキストレイヤーを抽出する。"""
    chunks: list[str] = []
    with pdfplumber.open(str(pdf_path)) as pdf:
        page_count = len(pdf.pages)
        for page in pdf.pages:
            chunks.append(page.extract_text() or "")
    return "\n".join(chunks).strip(), page_count


def render_images(pdf_path: str | Path, dpi: int = 200, max_pages: int = 3) -> list[bytes]:
    """ページを画像（PNGバイト列）に変換する。

    dpi=200 は読取精度とAPI送信サイズのバランスを取った既定値。
    精度が出ない場合は 300 まで上げて振り返り.md に結果を記録すること。
    """
    pages = convert_from_path(str(pdf_path), dpi=dpi)[:max_pages]
    out: list[bytes] = []
    for img in pages:
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        out.append(buf.getvalue())
    return out


def extract(pdf_path: str | Path, dpi: int = 200) -> ExtractedPdf:
    """テキスト or 画像を判定して素材を返す。"""
    text, page_count = extract_text(pdf_path)
    is_scanned = len(text) < TEXT_THRESHOLD
    images = render_images(pdf_path, dpi=dpi) if is_scanned else []
    return ExtractedPdf(
        text=text, images=images, is_scanned=is_scanned, page_count=page_count
    )
