import { installBridge } from '../slo/slo-bridge.js';
import { auditLog } from '../slo/slo-audit.js';

const banner = document.getElementById('banner');
const stateEl = document.getElementById('state');
const logEl = document.getElementById('log');

const setBanner = (text, cls) => {
  banner.textContent = text;
  banner.className = `banner ${cls ?? ''}`.trim();
};

const renderLog = () => { logEl.textContent = auditLog.format(); };
auditLog.onEntry(renderLog);

const mapping = await fetch('../slo/mapping.example.json').then((r) => r.json());

const bridge = installBridge({
  documentType: mapping.document_type,
  mapping,
  allowedHostOrigins: [window.location.origin],
  onStateChange: (s) => { stateEl.textContent = s; },
  onFilled: ({ filled, skipped, guessed }) => {
    setBanner(
      `${filled.length}件を充填しました（推定 ${guessed.length}件／対応欄なし ${skipped.length}件）。`
      + '内容を確認し、問題なければご自身で登録ボタンを押してください。',
      'ok',
    );
  },
  onRejected: (code) => {
    setBanner(`取込を拒否しました（理由コード: ${code}）。値は一切入力していません。`, 'err');
  },
});

document.getElementById('btn-ready').addEventListener('click', () => {
  bridge.ready();
  if (bridge.state === 'no-host') {
    setBanner('取込元が見つかりません。OCRアプリのWebView内で開くか、入居フォームから開いてください。', 'warn');
  } else {
    setBanner('取込元へ引き渡しを要求しました。取込元の確認画面で「入力する」を押してください。', 'warn');
  }
});

// 入居フォームから開かれた場合は自動でハンドシェイクを始める
if (window.opener) bridge.ready();

document.getElementById('reg-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  // INV-4: ここに到達するのは人間がボタンを押したときだけ。自動送信の経路は存在しない。
  auditLog.add('SUBMIT_BY_HUMAN', { document_type: mapping.document_type });
  const form = new FormData(ev.target);
  const payload = { fields: {} };
  for (const [k, v] of form.entries()) payload.fields[k] = v;
  const res = await fetch('/api/register', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const body = await res.json();
  setBanner(`登録を受け付けました（受付番号 ${body.receipt_id} ／ ${body.field_count}項目）。`, 'ok');
});

auditLog.add('PAGE_READY', { document_type: mapping.document_type });
renderLog();
