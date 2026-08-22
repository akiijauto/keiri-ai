/**
 * ローカル開発用サーバ（登録先Webサイトの参照実装）。
 *
 * 方針:
 *  - 127.0.0.1 のみにバインドする。外部NICでは待ち受けない。
 *  - 送信された個人情報を保存しない。受け付けたのは何項目か、だけを記録する。
 *  - CSPで外部への通信をすべて禁止する（許可リスト方式の実装例／企画書 7）。
 *
 * 実行: node web/server.mjs [port]
 */
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID, webcrypto } from 'node:crypto';

import { Normalizer, Profile, verifyEnvelope } from './public/slo/slo-core.js';

if (!globalThis.crypto) globalThis.crypto = webcrypto;

const HERE = resolve(fileURLToPath(new URL('.', import.meta.url)));
const PUBLIC_DIR = join(HERE, 'public');
const PORT = Number(process.argv[2] ?? process.env.PORT ?? 8787);
const HOST = '127.0.0.1';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

/**
 * 外部への通信を一切許可しないCSP。
 * OCRフェーズだけでなく登録フェーズでも、想定外の通信先が増えていないことを
 * ブラウザ側で機械的に止める。
 */
const SECURITY_HEADERS = {
  'content-security-policy': [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    "connect-src 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "object-src 'none'",
  ].join('; '),
  'referrer-policy': 'no-referrer',
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY',
  'cache-control': 'no-store',
};

/** 監査ログ（値は書かない）。 */
function audit(event, attributes = {}) {
  const attrs = Object.entries(attributes).map(([k, v]) => `${k}=${v}`).join(' ');
  process.stdout.write(`${new Date().toISOString().slice(0, 19)}Z\t${event}\t${attrs}\n`);
}

async function readBody(req, limitBytes = 64 * 1024) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limitBytes) throw new Error('payload too large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

function sendJson(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { ...SECURITY_HEADERS, 'content-type': 'application/json; charset=utf-8' });
  res.end(text);
}

/**
 * 登録受付。フォームから送られた項目を検証するが、値は保存も記録もしない。
 * 実運用では、ここで初めて業務システムへ書き込む。
 */
async function handleRegister(req, res) {
  const raw = await readBody(req);
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    audit('REGISTER_REJECTED', { reason: 'E_PROTOCOL' });
    return sendJson(res, 400, { error: 'E_PROTOCOL' });
  }

  const fields = payload?.fields ?? {};
  const present = Object.entries(fields).filter(([, v]) => String(v ?? '').trim() !== '');
  if (present.length === 0) {
    audit('REGISTER_REJECTED', { reason: 'E_VALIDATION', count: 0 });
    return sendJson(res, 400, { error: 'E_VALIDATION' });
  }

  const receiptId = randomUUID().slice(0, 8).toUpperCase();
  audit('REGISTER_ACCEPTED', { count: present.length, handoff_id: receiptId });
  // 値はここで破棄する。メモリ上のスコープを抜けた時点で参照は残らない。
  return sendJson(res, 200, { receipt_id: receiptId, field_count: present.length });
}

/**
 * Transport T4（ループバック）の受け口。
 * 外部NICへはバインドしないため、この経路は同一端末内で完結する。
 */
async function handleHandoff(req, res) {
  const raw = await readBody(req);
  let envelope;
  try {
    envelope = JSON.parse(raw);
  } catch {
    audit('HANDOFF_REJECTED', { reason: 'E_PROTOCOL' });
    return sendJson(res, 400, { ok: false, error: 'E_PROTOCOL' });
  }

  const now = Math.floor(Date.now() / 1000);
  // 鍵無しでの検証（プロトコル・失効・正規化の再計算）。
  // 実運用ではハンドシェイクで確立したセッション鍵を渡してHMACも検証する。
  const result = await verifyEnvelope(envelope, null, now);
  if (!result.ok) {
    audit('HANDOFF_REJECTED', { reason: result.error });
    return sendJson(res, 400, { ok: false, error: result.error });
  }
  audit('HANDOFF_VERIFIED', { result: 'ok', fields: result.fieldCount });
  return sendJson(res, 200, { ok: true, field_count: result.fieldCount });
}

/** 単項目の正規化を返す補助API（入居フォームのサーバ側再検証デモ）。値は保存しない。 */
async function handleNormalize(req, res) {
  const raw = await readBody(req);
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    return sendJson(res, 400, { error: 'E_PROTOCOL' });
  }
  const { field, value } = payload ?? {};
  if (typeof field !== 'string' || typeof value !== 'string') {
    return sendJson(res, 400, { error: 'E_PROTOCOL' });
  }
  const r = Normalizer.normalize(field, value);
  audit('NORMALIZE', { field, result: r.ok ? 'ok' : r.error });
  return sendJson(res, 200, { ok: r.ok, value: r.value, error: r.error });
}

async function serveStatic(req, res, pathname) {
  const rel = normalize(decodeURIComponent(pathname)).replace(/^(\.\.[/\\])+/, '');
  let filePath = join(PUBLIC_DIR, rel);
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403, SECURITY_HEADERS);
    return res.end('forbidden');
  }
  try {
    const s = await stat(filePath);
    if (s.isDirectory()) filePath = join(filePath, 'index.html');
  } catch {
    res.writeHead(404, SECURITY_HEADERS);
    return res.end('not found');
  }
  try {
    const body = await readFile(filePath);
    res.writeHead(200, {
      ...SECURITY_HEADERS,
      'content-type': MIME[extname(filePath)] ?? 'application/octet-stream',
    });
    return res.end(body);
  } catch {
    res.writeHead(404, SECURITY_HEADERS);
    return res.end('not found');
  }
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://${HOST}:${PORT}`);
  try {
    if (req.method === 'POST' && url.pathname === '/api/register') return await handleRegister(req, res);
    if (req.method === 'POST' && url.pathname === '/slo/v1/handoff') return await handleHandoff(req, res);
    if (req.method === 'POST' && url.pathname === '/api/normalize') return await handleNormalize(req, res);
    if (req.method === 'GET' || req.method === 'HEAD') return await serveStatic(req, res, url.pathname);
    res.writeHead(405, SECURITY_HEADERS);
    return res.end('method not allowed');
  } catch (e) {
    // 例外メッセージに個人情報が混ざらないよう、種別だけを返す。
    audit('SERVER_ERROR', { reason: e instanceof Error ? e.constructor.name : 'Unknown' });
    res.writeHead(500, SECURITY_HEADERS);
    return res.end('internal error');
  }
});

server.listen(PORT, HOST, () => {
  audit('SERVER_START', { count: Profile.FIELDS.length });
  process.stdout.write(`SLO reference site: http://${HOST}:${PORT}/\n`);
});

export { server };
