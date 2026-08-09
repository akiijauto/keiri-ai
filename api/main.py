"""請求書仕訳AI — APIサーバー。

エンドポイント:
  POST /analyze     請求書PDFを解析して仕訳案を返す
  POST /export/csv  承認済みの仕訳をfreee形式CSVで返す
  GET  /accounts    勘定科目・税区分マスタを返す
  GET  /health      ヘルスチェック
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, HTTPException, UploadFile, File
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel

import accounts
import freee_csv
import pdf_reader
import prompts
from llm_client import get_client

MAX_UPLOAD_MB = int(os.environ.get("MAX_UPLOAD_MB", "20"))

app = FastAPI(title="請求書仕訳AI", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://127.0.0.1:3000"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "provider": os.environ.get("LLM_PROVIDER", "gemini")}


@app.get("/accounts")
def list_accounts() -> dict:
    return {
        "accounts": [
            {"name": a.name, "category": a.category,
             "default_tax": a.default_tax, "note": a.note}
            for a in accounts.ACCOUNTS
        ],
        "tax_codes": accounts.TAX_CODES,
    }


@app.post("/analyze")
async def analyze(file: UploadFile = File(...)) -> dict:
    """請求書PDFを解析し、抽出結果と仕訳案を返す。"""
    if not (file.filename or "").lower().endswith(".pdf"):
        raise HTTPException(400, "PDFファイルをアップロードしてください")

    body = await file.read()
    if len(body) > MAX_UPLOAD_MB * 1024 * 1024:
        raise HTTPException(413, f"ファイルサイズは{MAX_UPLOAD_MB}MBまでです")

    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
        tmp.write(body)
        tmp_path = Path(tmp.name)

    try:
        material = pdf_reader.extract(tmp_path)
        client = get_client()

        # --- 1. 項目抽出 ---
        if material.is_scanned:
            if not material.images:
                raise HTTPException(422, "PDFからテキストも画像も取得できませんでした")
            extracted = client.generate_from_image(
                prompts.extraction_prompt(), material.images[0],
                system=prompts.SYSTEM,
            ).as_json()
        else:
            extracted = client.generate(
                f"{prompts.extraction_prompt()}\n\n--- 請求書テキスト ---\n{material.text}",
                system=prompts.SYSTEM,
            ).as_json()

        # --- 2. 仕訳案生成 ---
        journal = client.generate(
            prompts.journal_prompt(json.dumps(extracted, ensure_ascii=False, indent=2)),
            system=prompts.SYSTEM,
        ).as_json()

        # --- 3. 検証 ---
        warnings = list(journal.get("warnings", []))
        rows = freee_csv.from_journal_result(
            journal,
            partner=extracted.get("issuer") or "",
            date=extracted.get("issue_date") or "",
        )
        balanced, balance_msg = freee_csv.validate_balance(rows)
        if not balanced:
            warnings.append(balance_msg)

        for e in journal.get("entries", []):
            if not accounts.is_known_account(e.get("debit_account", "")):
                warnings.append(f"マスタに無い勘定科目です: {e.get('debit_account')}")
            if not accounts.is_known_tax_code(e.get("tax_code", "")):
                warnings.append(f"マスタに無い税区分です: {e.get('tax_code')}")

        return {
            "source": {
                "filename": file.filename,
                "is_scanned": material.is_scanned,
                "page_count": material.page_count,
            },
            "extracted": extracted,
            "journal": journal,
            "validation": {"balanced": balanced, "message": balance_msg},
            "warnings": warnings,
            # 電帳法の検索要件を意識したファイル名案
            "suggested_filename": _suggest_filename(extracted),
        }
    except json.JSONDecodeError as exc:
        raise HTTPException(502, f"AIの応答をJSONとして解析できませんでした: {exc}")
    finally:
        tmp_path.unlink(missing_ok=True)


class ExportRequest(BaseModel):
    """承認済みの仕訳（フロントで修正された最終形）。"""

    entries: list[dict]
    partner: str = ""
    date: str = ""
    encoding: str = freee_csv.ENCODING


@app.post("/export/csv")
def export_csv(req: ExportRequest) -> Response:
    """承認済み仕訳をfreee形式CSVで返す。"""
    rows = freee_csv.from_journal_result(
        {"entries": req.entries}, partner=req.partner, date=req.date
    )
    if not rows:
        raise HTTPException(400, "出力する仕訳がありません")

    data = freee_csv.build_csv(rows, encoding=req.encoding)
    return Response(
        content=data,
        media_type="text/csv",
        headers={"Content-Disposition": 'attachment; filename="journal.csv"'},
    )


def _suggest_filename(extracted: dict) -> str:
    """電子帳簿保存法の検索要件（日付・金額・取引先）を含むファイル名案。"""
    date = (extracted.get("issue_date") or "").replace("-", "")
    amount = extracted.get("total_incl_tax") or 0
    partner = (extracted.get("issuer") or "取引先不明").replace("/", "_")
    return f"{date}_{amount}_{partner}.pdf"
