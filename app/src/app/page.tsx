"use client";

import { useState } from "react";

type Entry = {
  debit_account: string;
  debit_amount: number;
  credit_account: string;
  credit_amount: number;
  tax_code: string;
  description: string;
  reason: string;
  confidence: number;
  needs_review: boolean;
};

type Result = {
  source: { filename: string; is_scanned: boolean; page_count: number };
  extracted: Record<string, any>;
  journal: {
    is_qualified_invoice: boolean;
    qualified_invoice_note: string;
    entries: Entry[];
    warnings: string[];
  };
  validation: { balanced: boolean; message: string };
  warnings: string[];
  suggested_filename: string;
};

export default function Home() {
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [entries, setEntries] = useState<Entry[]>([]);

  async function analyze() {
    if (!file) return;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const fd = new FormData();
      fd.append("file", file);
      const res = await fetch("/api/analyze", { method: "POST", body: fd });
      if (!res.ok) throw new Error(`解析に失敗しました (${res.status})`);
      const data: Result = await res.json();
      setResult(data);
      setEntries(data.journal.entries ?? []);
    } catch (e: any) {
      setError(e.message ?? "エラーが発生しました");
    } finally {
      setLoading(false);
    }
  }

  function updateEntry(i: number, key: keyof Entry, value: string) {
    setEntries((prev) =>
      prev.map((e, idx) =>
        idx === i
          ? {
              ...e,
              [key]:
                key === "debit_amount" || key === "credit_amount"
                  ? Number(value) || 0
                  : value,
            }
          : e
      )
    );
  }

  async function exportCsv() {
    if (!result) return;
    const res = await fetch("/api/export/csv", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        entries,
        partner: result.extracted.issuer ?? "",
        date: result.extracted.issue_date ?? "",
      }),
    });
    if (!res.ok) {
      setError("CSV出力に失敗しました");
      return;
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "journal.csv";
    a.click();
    URL.revokeObjectURL(url);
  }

  const debitTotal = entries.reduce((s, e) => s + (e.debit_amount || 0), 0);
  const creditTotal = entries.reduce((s, e) => s + (e.credit_amount || 0), 0);
  const balanced = debitTotal === creditTotal;

  return (
    <main className="wrap">
      <header>
        <h1>請求書仕訳AI</h1>
        <p className="lead">
          請求書PDFから仕訳案を生成します。
          <strong>AIは案を出すだけです。確定は必ずご自身で行ってください。</strong>
        </p>
      </header>

      <section className="card">
        <h2>1. 請求書をアップロード</h2>
        <input
          type="file"
          accept="application/pdf"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
        <button onClick={analyze} disabled={!file || loading}>
          {loading ? "解析中…" : "仕訳案を生成"}
        </button>
        {error && <p className="error">{error}</p>}
      </section>

      {result && (
        <>
          <section className="card">
            <h2>2. 読み取った内容</h2>
            <dl>
              <div><dt>請求元</dt><dd>{result.extracted.issuer ?? "-"}</dd></div>
              <div><dt>請求書番号</dt><dd>{result.extracted.invoice_no ?? "-"}</dd></div>
              <div><dt>請求日</dt><dd>{result.extracted.issue_date ?? "-"}</dd></div>
              <div><dt>支払期日</dt><dd>{result.extracted.due_date ?? "-"}</dd></div>
              <div>
                <dt>税込合計</dt>
                <dd>
                  {result.extracted.total_incl_tax
                    ? `¥${Number(result.extracted.total_incl_tax).toLocaleString()}`
                    : "-"}
                </dd>
              </div>
              <div>
                <dt>インボイス登録番号</dt>
                <dd>
                  {result.extracted.issuer_invoice_reg_no ?? (
                    <span className="warn">なし</span>
                  )}
                </dd>
              </div>
              <div>
                <dt>読取方式</dt>
                <dd>{result.source.is_scanned ? "画像（スキャン）" : "テキスト"}</dd>
              </div>
              <div>
                <dt>保存ファイル名案</dt>
                <dd><code>{result.suggested_filename}</code></dd>
              </div>
            </dl>
            <p className="note">{result.journal.qualified_invoice_note}</p>
          </section>

          {result.warnings?.length > 0 && (
            <section className="card warnbox">
              <h2>要確認</h2>
              <ul>
                {result.warnings.map((w, i) => (
                  <li key={i}>{w}</li>
                ))}
              </ul>
            </section>
          )}

          <section className="card">
            <h2>3. 仕訳案を確認・修正</h2>
            {entries.map((e, i) => (
              <div className="entry" key={i}>
                <div className="row">
                  <label>
                    借方科目
                    <input
                      value={e.debit_account}
                      onChange={(ev) => updateEntry(i, "debit_account", ev.target.value)}
                    />
                  </label>
                  <label>
                    借方金額
                    <input
                      type="number"
                      value={e.debit_amount}
                      onChange={(ev) => updateEntry(i, "debit_amount", ev.target.value)}
                    />
                  </label>
                  <label>
                    税区分
                    <input
                      value={e.tax_code}
                      onChange={(ev) => updateEntry(i, "tax_code", ev.target.value)}
                    />
                  </label>
                </div>
                <div className="row">
                  <label>
                    貸方科目
                    <input
                      value={e.credit_account}
                      onChange={(ev) => updateEntry(i, "credit_account", ev.target.value)}
                    />
                  </label>
                  <label>
                    貸方金額
                    <input
                      type="number"
                      value={e.credit_amount}
                      onChange={(ev) => updateEntry(i, "credit_amount", ev.target.value)}
                    />
                  </label>
                  <label>
                    摘要
                    <input
                      value={e.description}
                      onChange={(ev) => updateEntry(i, "description", ev.target.value)}
                    />
                  </label>
                </div>
                <p className="reason">
                  <span
                    className={
                      e.confidence >= 0.7 ? "badge ok" : "badge low"
                    }
                  >
                    確信度 {Math.round((e.confidence ?? 0) * 100)}%
                  </span>
                  {e.needs_review && <span className="badge low">要確認</span>}
                  {e.reason}
                </p>
              </div>
            ))}

            <p className={balanced ? "balance ok" : "balance ng"}>
              借方合計 ¥{debitTotal.toLocaleString()} / 貸方合計 ¥
              {creditTotal.toLocaleString()}　{balanced ? "貸借一致" : "貸借不一致"}
            </p>
          </section>

          <section className="card">
            <h2>4. 承認してCSV出力</h2>
            <p className="note">
              内容を確認のうえ出力してください。出力形式はfreee準拠です。
            </p>
            <button onClick={exportCsv} disabled={!balanced}>
              freee形式CSVをダウンロード
            </button>
            {!balanced && <p className="error">貸借が一致するまで出力できません</p>}
          </section>
        </>
      )}

      <footer>
        <p>
          本サービスはポートフォリオ用のデモです。実際の会計処理には使用しないでください。
          サンプル請求書はすべて架空企業のものです。
        </p>
      </footer>
    </main>
  );
}
