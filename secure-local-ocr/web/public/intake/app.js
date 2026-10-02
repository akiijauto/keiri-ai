import {
  Profile, Normalizer, buildEnvelope, signEnvelope, newHandoffId, missingRequired,
} from '../slo/slo-core.js';
import { auditLog } from '../slo/slo-audit.js';

const DOCUMENT_TYPE = 'residency_application';
const APP = { kind: 'web-form', app: 'SLO-Intake-Web', version: '0.1.0' };

const fieldsEl = document.getElementById('fields');
const previewEl = document.getElementById('preview');
const msgEl = document.getElementById('msg');
const logEl = document.getElementById('log');

const renderLog = () => { logEl.textContent = auditLog.format(); };
auditLog.onEntry(renderLog);

const required = new Set(Profile.requiredFor(DOCUMENT_TYPE));
const inputs = new Map();

for (const f of Profile.FIELDS) {
  const wrap = document.createElement('div');
  wrap.className = 'field';
  const label = document.createElement('label');
  label.htmlFor = `in_${f.key}`;
  label.textContent = required.has(f.key) ? `${f.label} ※必須` : f.label;
  const input = document.createElement('input');
  input.type = 'text';
  input.id = `in_${f.key}`;
  input.dataset.key = f.key;
  wrap.append(label, input);
  fieldsEl.append(wrap);
  inputs.set(f.key, input);
  input.addEventListener('input', () => { render(); });
}

/** 入力値を正規化して現在の状態を返す。判定規則はOCRアプリと同一。 */
function currentFields() {
  const out = {};
  for (const [key, input] of inputs) {
    const raw = input.value;
    if (raw.trim() === '') continue;
    const r = Normalizer.normalize(key, raw);
    out[key] = {
      raw,
      value: r.ok ? r.value : '',
      error: r.error,
      origin: 'web-form',
      confidence: null,
      edited: true,
    };
  }
  return out;
}

function render() {
  const fields = currentFields();
  previewEl.textContent = '';
  for (const f of Profile.FIELDS) {
    const v = fields[f.key];
    if (!v) continue;
    const tr = document.createElement('tr');
    const th = document.createElement('th');
    th.textContent = f.label;
    const td = document.createElement('td');
    if (v.error) {
      td.innerHTML = `<span class="pill err">${v.error}</span> 入力を確認してください`;
    } else {
      td.textContent = v.value;
    }
    tr.append(th, td);
    previewEl.append(tr);
  }

  const invalid = Object.entries(fields).filter(([, v]) => v.error).map(([k]) => k);
  const missing = missingRequired(fields, DOCUMENT_TYPE);
  const btn = document.getElementById('btn-handoff');

  if (Object.keys(fields).length === 0) {
    msgEl.className = 'banner';
    msgEl.textContent = '未入力です。';
    btn.disabled = true;
  } else if (invalid.length > 0) {
    msgEl.className = 'banner err';
    msgEl.textContent = `形式が不正な項目があります: ${invalid.map(Profile.label).join('、')}`;
    btn.disabled = true;
  } else if (missing.length > 0) {
    msgEl.className = 'banner warn';
    msgEl.textContent = `必須項目が未入力です: ${missing.map(Profile.label).join('、')}`;
    btn.disabled = true;
  } else {
    msgEl.className = 'banner ok';
    msgEl.textContent = `${Object.keys(fields).length}項目を引き渡せます。`;
    btn.disabled = false;
  }
  return fields;
}

/* --- 登録先とのハンドシェイク（SPEC.md 7.1 と同じ手順をブラウザ間で行う） --- */

let pending = null;

document.getElementById('btn-handoff').addEventListener('click', () => {
  const fields = currentFields();
  const sessionKey = crypto.getRandomValues(new Uint8Array(32));
  pending = {
    fields,
    sessionKey,
    keyHex: [...sessionKey].map((b) => b.toString(16).padStart(2, '0')).join(''),
    keyId: `session:${newHandoffId().slice(0, 8)}`,
    window: window.open('../registration/index.html', 'slo-registration'),
  };
  auditLog.add('USER_CONFIRMED', {
    document_type: DOCUMENT_TYPE,
    fields: String(Object.keys(fields).length),
  });
  msgEl.className = 'banner warn';
  msgEl.textContent = '登録先を開きました。登録先の画面で内容を確認してください。';
});

window.addEventListener('message', async (ev) => {
  if (ev.origin !== window.location.origin) return;      // オリジン許可リスト
  const data = ev.data;
  if (!data || data.type !== 'slo.ready' || !pending) return;
  if (data.profile !== Profile.ID) return;

  // 1) セッション鍵を渡す（同一端末内で完結。外部へは出さない）
  ev.source.postMessage({
    type: 'slo.session',
    key_id: pending.keyId,
    key_hex: pending.keyHex,
    nonce: data.nonce,
  }, ev.origin);

  // 2) 確認済みEnvelopeを作って渡す
  const fields = {};
  for (const [k, v] of Object.entries(pending.fields)) {
    if (!v.value) continue;
    fields[k] = { value: v.value, origin: 'web-form', confidence: null, edited: true };
  }
  const envelope = buildEnvelope({
    handoffId: newHandoffId(),
    documentType: DOCUMENT_TYPE,
    source: APP,
    fields,
    issuedAtEpochSeconds: Math.floor(Date.now() / 1000),
  });
  await signEnvelope(envelope, pending.keyId, pending.sessionKey);

  ev.source.postMessage({ type: 'slo.deliver', envelope }, ev.origin);
  auditLog.add('HANDOFF_DELIVERED', {
    handoff_id: envelope.handoff_id.slice(0, 8) + '…',
    fields: String(Object.keys(fields).length),
  });
});

document.getElementById('btn-clear').addEventListener('click', () => {
  for (const input of inputs.values()) input.value = '';
  pending = null;
  auditLog.add('SESSION_ENDED', { reason: 'cleared_by_user' });
  render();
});

auditLog.add('PAGE_READY', { document_type: DOCUMENT_TYPE });
render();
renderLog();
