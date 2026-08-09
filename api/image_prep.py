"""カメラ撮影画像の前処理。

スマホで撮った請求書写真は、そのままAIに渡すと次の問題が起きる。

1. EXIFの回転情報で横向きのまま解釈され、読取精度が落ちる
2. 最近のスマホは12MP超あり、送信量とAPIコストが無駄に大きい
3. iPhone標準のHEICは受け側で開けないことがある

このモジュールで正立・リサイズし、HEICは具体的な対処を伝えて弾く。
"""

from __future__ import annotations

import io
from dataclasses import dataclass

from PIL import Image, ImageOps, UnidentifiedImageError

# 長辺の上限。請求書の文字が読める範囲で送信量を抑える妥協点。
# 2000pxあればA4を200dpi相当で撮った文字は判読できる。
MAX_LONG_EDGE = 2000

# 受け付けるMIMEタイプ（フロントの accept と揃えること）
SUPPORTED_MIME = {"image/jpeg", "image/png", "image/webp"}

# 出力は常にJPEGに統一する。送信量が小さく、AI側の対応も確実なため。
OUTPUT_MIME = "image/jpeg"
JPEG_QUALITY = 85

HEIC_BRANDS = {b"heic", b"heix", b"hevc", b"hevx", b"mif1", b"msf1"}

HEIC_MESSAGE = (
    "HEIC形式の画像は読み取れません。次のいずれかで対処してください。"
    "(1) iPhoneの「設定 > カメラ > フォーマット」を「互換性優先」に変更すると"
    "JPEGで撮影されます。"
    "(2) すでに撮影済みの写真は、共有メニューから「ファイルに保存」でPDFに変換するか、"
    "写真アプリでスクリーンショットを撮ってJPEGにしてください。"
)


class UnsupportedImageError(ValueError):
    """開けない・対応していない画像形式。"""


@dataclass
class PreparedImage:
    """AIへ渡せる状態に整えた画像。"""

    data: bytes
    mime_type: str
    width: int
    height: int
    was_rotated: bool
    was_resized: bool


def is_heic(data: bytes, filename: str = "") -> bool:
    """HEIC/HEIFかどうかを判定する。

    Pillowは標準ではHEICを開けず、例外メッセージも利用者には意味が分からないため、
    バイト列の先頭（ftypボックスのブランド）で先に判別して専用の案内を出す。
    """
    if filename.lower().endswith((".heic", ".heif")):
        return True
    # ISO BMFF: [4bytes size]['ftyp'][4bytes brand]
    return len(data) >= 12 and data[4:8] == b"ftyp" and data[8:12] in HEIC_BRANDS


EXIF_ORIENTATION_TAG = 274


def _orientation(img: Image.Image) -> int:
    """EXIFのOrientation値を返す（無ければ0）。"""
    try:
        exif = img.getexif()
    except Exception:
        return 0
    return int(exif.get(EXIF_ORIENTATION_TAG, 0) or 0)


def _to_rgb(img: Image.Image) -> Image.Image:
    """JPEG保存できるようRGBへ変換する（透過は白で埋める）。"""
    if img.mode == "RGB":
        return img
    if img.mode in ("RGBA", "LA", "P"):
        rgba = img.convert("RGBA")
        canvas = Image.new("RGB", rgba.size, (255, 255, 255))
        canvas.paste(rgba, mask=rgba.split()[-1])
        return canvas
    return img.convert("RGB")


def prepare(data: bytes, *, filename: str = "") -> PreparedImage:
    """撮影画像を正立・リサイズしてJPEGバイト列で返す。

    Raises:
        UnsupportedImageError: HEICまたは画像として開けない場合。
    """
    if is_heic(data, filename):
        raise UnsupportedImageError(HEIC_MESSAGE)

    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except UnidentifiedImageError as exc:
        raise UnsupportedImageError(
            "画像として読み取れませんでした。JPEG・PNG・WebPのいずれかで"
            "撮影・保存してからお試しください。"
        ) from exc

    # EXIFのOrientationを見て実際に回転させる。
    # 回転情報を残したまま渡すと、AI側が横向きの絵として解釈してしまう。
    # 判定はサイズ比較ではなくOrientation値で行う（180度回転はサイズが変わらないため）。
    was_rotated = _orientation(img) not in (0, 1)
    img = ImageOps.exif_transpose(img)

    was_resized = False
    long_edge = max(img.size)
    if long_edge > MAX_LONG_EDGE:
        scale = MAX_LONG_EDGE / long_edge
        new_size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
        img = img.resize(new_size, Image.LANCZOS)
        was_resized = True

    buf = io.BytesIO()
    _to_rgb(img).save(buf, format="JPEG", quality=JPEG_QUALITY, optimize=True)

    return PreparedImage(
        data=buf.getvalue(),
        mime_type=OUTPUT_MIME,
        width=img.width,
        height=img.height,
        was_rotated=was_rotated,
        was_resized=was_resized,
    )
