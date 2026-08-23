#!/usr/bin/env python3
"""正準化JSON / HMAC の共通テストベクタ生成器（SPEC.md 6.1）。

Kotlin・TypeScript・Swift の3実装とは独立した第4の実装としてベクタを作る。
「実装同士が同じバグを共有していないこと」をここで担保する。
生成物: protocol/testdata/canonical-vectors.json
"""
import hashlib
import hmac
import json
import math
import base64
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
TESTDATA = HERE.parent / "protocol" / "testdata"

HMAC_KEY_HEX = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

ESCAPES = {
    '"': '\\"',
    "\\": "\\\\",
    "\b": "\\b",
    "\f": "\\f",
    "\n": "\\n",
    "\r": "\\r",
    "\t": "\\t",
}


def fmt_number(v):
    if isinstance(v, bool):
        raise TypeError("bool is not a number here")
    if isinstance(v, int):
        return str(v)
    if math.isnan(v) or math.isinf(v):
        raise ValueError("non-finite")
    if v == math.floor(v) and abs(v) < 1e15:
        return str(int(v))
    return repr(v)


def write_string(s):
    out = ['"']
    for ch in s:
        if ch in ESCAPES:
            out.append(ESCAPES[ch])
        elif ord(ch) < 0x20:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def canonical(v):
    if v is None:
        return "null"
    if v is True:
        return "true"
    if v is False:
        return "false"
    if isinstance(v, str):
        return write_string(v)
    if isinstance(v, (int, float)):
        return fmt_number(v)
    if isinstance(v, list):
        return "[" + ",".join(canonical(x) for x in v) + "]"
    if isinstance(v, dict):
        # UTF-16 コードユニット順。テストベクタのキーはすべてBMP内なのでコードポイント順と一致する。
        keys = sorted(v.keys())
        return "{" + ",".join(write_string(k) + ":" + canonical(v[k]) for k in keys) + "}"
    raise TypeError(type(v))


def b64url(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


CASES = [
    ("canon-01-key-order", '{"b":1,"a":2,"A":3,"_":4}'),
    ("canon-02-types", '{"n":null,"t":true,"f":false,"i":42,"d":0.9,"neg":-0.5}'),
    (
        "canon-03-japanese-and-escapes",
        json.dumps(
            {"name": "山田　太郎", "note": 'line1\nline2\t"quoted"\\end', "kana": "ヤマダ　タロウ"},
            ensure_ascii=False,
        ),
    ),
    ("canon-04-integral-floats", '{"a":1.0,"b":1e3,"c":0.72,"d":100}'),
    ("canon-05-nested", '{"z":{"b":[1,{"y":2,"x":3}],"a":"v"},"m":[]}'),
    (
        "canon-06-envelope",
        json.dumps(
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
                    "offline_capture": True,
                },
                "confirmed": True,
                "fields": {
                    "name": {
                        "value": "山田　太郎",
                        "origin": "ocr",
                        "confidence": 0.9,
                        "edited": False,
                        "confirmed": True,
                    },
                    "phone": {
                        "value": "09012345678",
                        "origin": "ocr",
                        "confidence": 0.72,
                        "edited": True,
                        "confirmed": True,
                    },
                    "email": {
                        "value": "taro.yamada@example.co.jp",
                        "origin": "manual",
                        "confidence": None,
                        "edited": True,
                        "confirmed": True,
                    },
                },
            },
            ensure_ascii=False,
        ),
    ),
]


def main():
    key = bytes.fromhex(HMAC_KEY_HEX)
    cases = []
    for cid, raw in CASES:
        parsed = json.loads(raw)
        canon = canonical(parsed)
        mac = hmac.new(key, canon.encode("utf-8"), hashlib.sha256).digest()
        cases.append(
            {
                "id": cid,
                "input": raw,
                "canonical": canon,
                "hmac_key_hex": HMAC_KEY_HEX,
                "hmac_b64url": b64url(mac),
            }
        )
    doc = {
        "comment": "正準化JSON(RFC 8785サブセット)とHMAC-SHA256の共通テストベクタ。"
        "tools/gen_canonical_vectors.py が生成する。個人情報はすべて架空データ。",
        "cases": cases,
    }
    out = TESTDATA / "canonical-vectors.json"
    out.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out} ({len(cases)} cases)")


if __name__ == "__main__":
    main()
