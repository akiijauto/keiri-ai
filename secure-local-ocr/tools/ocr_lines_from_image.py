#!/usr/bin/env python3
"""
画像から「行の並び＋外接矩形」を作る（抽出規則の検証用）。

なぜ必要か
----------
抽出規則の不具合には、実機でしか出ないものがある。
実際に出たのが「ラベルと値が位置ではなく並び順で対応づけられていた」問題で、
手で書いた座標のテストでは気づけなかった（振り返り 課題11）。
実際のOCRが返す座標——折り返し行が前の行と数ピクセル重なる、など——を
使って検証するために、このスクリプトを用意した。

使い方
------
    python3 tools/ocr_lines_from_image.py samples/test-form-01.png

出力は protocol/testdata/extraction-vectors.json の lines と同じ形式なので、
そのままベクタへ貼り付けて回帰テストにできる。

重要な限界
----------
ここで使う Tesseract は、製品が使う ML Kit / Vision とは別のエンジンである。
読み取り精度も、行の区切り方も、ブロックの返し方も違う。
このスクリプトで確かめられるのは「実際の座標に対して抽出規則が正しく働くか」だけで、
**OCR精度の検証にも、通信していないことの検証にもならない**。
実機での確認（docs/test-plan.html）を置き換えるものではない。

前提: tesseract-ocr と tesseract-ocr-jpn
"""

import argparse
import csv
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def lines_from_tsv(tsv_path):
    """Tesseract の TSV を、単語からブロック/段落/行ごとにまとめ直す。"""
    grouped = {}
    order = []
    with open(tsv_path, encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            if row["level"] != "5":  # 5 = 単語
                continue
            text = (row["text"] or "").strip()
            if not text:
                continue
            key = (row["block_num"], row["par_num"], row["line_num"])
            left, top = int(row["left"]), int(row["top"])
            right, bottom = left + int(row["width"]), top + int(row["height"])
            if key not in grouped:
                grouped[key] = {"words": [], "box": [left, top, right, bottom], "conf": []}
                order.append(key)
            e = grouped[key]
            e["words"].append(text)
            b = e["box"]
            e["box"] = [min(b[0], left), min(b[1], top), max(b[2], right), max(b[3], bottom)]
            e["conf"].append(float(row["conf"]))

    out = []
    for key in order:
        e = grouped[key]
        avg = sum(e["conf"]) / len(e["conf"]) / 100.0
        out.append({
            # 日本語は単語間に空白を入れない
            "text": "".join(e["words"]),
            "box": e["box"],
            "confidence": round(min(max(avg, 0.0), 1.0), 2),
        })
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image", help="読み取る画像。架空データのみを使うこと")
    ap.add_argument("--lang", default="jpn")
    args = ap.parse_args()

    if shutil.which("tesseract") is None:
        print("tesseract が見つかりません:", file=sys.stderr)
        print("  sudo apt-get install -y tesseract-ocr tesseract-ocr-jpn", file=sys.stderr)
        return 2

    image = Path(args.image)
    if not image.is_file():
        print(f"画像がありません: {image}", file=sys.stderr)
        return 2

    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp) / "out"
        proc = subprocess.run(
            ["tesseract", str(image), str(base), "-l", args.lang, "tsv"],
            capture_output=True, text=True,
        )
        if proc.returncode != 0:
            print(f"tesseract が失敗しました (終了コード {proc.returncode})", file=sys.stderr)
            print(proc.stderr.strip()[:500], file=sys.stderr)
            return 1
        lines = lines_from_tsv(base.with_suffix(".tsv"))

    print(json.dumps({"lines": lines}, ensure_ascii=False, indent=2))
    print(f"\n{len(lines)}行", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
