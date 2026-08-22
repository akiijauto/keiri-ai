/**
 * 実ブラウザによるE2Eテスト（企画書 19「Web実行テスト」）。
 *
 * 確認すること:
 *  1. 入居フォーム → 登録先 の一連の流れで、正しく充填され、自動送信されないこと
 *  2. 失効・改ざん・別オリジンからの引き渡しが拒否されること
 *  3. 画面が外部ホストへ一切通信しないこと
 *  4. 監査ログに個人情報が出ないこと
 *
 * 実行: npm test --prefix web  （NODE_PATH にグローバルの playwright を指定）
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { chromium } from 'playwright';

import {
  buildEnvelope, signEnvelope, hexToBytes, formatRfc3339,
} from '../public/slo/slo-core.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const SERVER = join(HERE, '..', 'server.mjs');
const PORT = 8791;
const ATTACKER_PORT = 8792;
const BASE = `http://127.0.0.1:${PORT}`;

// テスト用の架空データ
const SAMPLE = {
  name: '山田 太郎',
  name_kana: 'やまだ たろう',
  birthday: '昭和55年1月1日',
  postal_code: '〒150-0001',
  address: '東京都渋谷区神宮前１－２－３',
  phone: '090-1234-5678',
  email: 'taro.yamada@example.co.jp',
  move_in_date: '令和8年4月1日',
};

let serverProc;
let attackerServer;
let browser;

const waitForServer = async (url, timeoutMs = 15000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* まだ起動していない */ }
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error(`server did not start: ${url}`);
};

test.before(async () => {
  serverProc = spawn(process.execPath, [SERVER, String(PORT)], { stdio: 'ignore' });
  await waitForServer(`${BASE}/`);

  // 別オリジンからの引き渡しが弾かれることを確認するための攻撃者役サーバ
  attackerServer = createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(`<!DOCTYPE html><meta charset="utf-8"><title>other-origin</title>
<script>
  window.target = null;
  window.openTarget = () => { window.target = window.open('${BASE}/registration/', 'victim'); };
  window.addEventListener('message', (ev) => {
    if (ev.data && ev.data.type === 'slo.ready') {
      window.__gotReady = true;
      ev.source.postMessage({ type: 'slo.session', key_id: 'session:evil', key_hex: '00'.repeat(32), nonce: ev.data.nonce }, '*');
      ev.source.postMessage({ type: 'slo.deliver', envelope: window.__evilEnvelope }, '*');
    }
  });
</script>`);
  });
  await new Promise((r) => attackerServer.listen(ATTACKER_PORT, '127.0.0.1', r));

  browser = await chromium.launch();
});

test.after(async () => {
  await browser?.close();
  serverProc?.kill();
  attackerServer?.close();
});

/** ページが外部ホストへ通信していないことを監視する（企画書 20「実測で確認する」のWeb版）。 */
function watchRequests(context) {
  const external = [];
  context.on('request', (req) => {
    const url = new URL(req.url());
    if (url.hostname !== '127.0.0.1' && url.hostname !== 'localhost' && url.protocol !== 'data:') {
      external.push(req.url());
    }
  });
  return external;
}

test('入居フォームから登録先へ引き渡し、人間が押すまで送信されない', async () => {
  const context = await browser.newContext();
  const external = watchRequests(context);
  const intake = await context.newPage();
  await intake.goto(`${BASE}/intake/`);

  for (const [key, value] of Object.entries(SAMPLE)) {
    await intake.fill(`#in_${key}`, value);
  }

  // 正規化結果がその場で表示される（OCRアプリと同じ規則）
  await assert.doesNotReject(intake.waitForSelector('#btn-handoff:not([disabled])'));

  const [registration] = await Promise.all([
    context.waitForEvent('page'),
    intake.click('#btn-handoff'),
  ]);
  await registration.waitForLoadState();
  await registration.waitForSelector('input[data-slo-filled="name"]');

  // 充填結果（登録先サイトの表記に変換されていること）
  assert.equal(await registration.inputValue('#applicant_name'), '山田　太郎');
  assert.equal(await registration.inputValue('#applicant_kana'), 'ヤマダ　タロウ');
  assert.equal(await registration.inputValue('#birth'), '1980/01/01');
  assert.equal(await registration.inputValue('#zip1'), '150');
  assert.equal(await registration.inputValue('#zip2'), '0001');
  assert.equal(await registration.inputValue('#addr'), '東京都渋谷区神宮前1-2-3');
  assert.equal(await registration.inputValue('#tel1'), '090');
  assert.equal(await registration.inputValue('#tel2'), '1234');
  assert.equal(await registration.inputValue('#tel3'), '5678');
  assert.equal(await registration.inputValue('#mail'), 'taro.yamada@example.co.jp');
  assert.equal(await registration.inputValue('#move_in'), '2026/04/01');

  // INV-4: この時点でまだ送信されていない
  const bannerBefore = await registration.textContent('#banner');
  assert.ok(!bannerBefore.includes('受付番号'), '人間が押す前に送信されている');

  // 人間が押して初めて送信される
  await registration.click('#btn-submit');
  await registration.waitForFunction(() => document.getElementById('banner').textContent.includes('受付番号'));

  // 監査ログに個人情報が出ていないこと（INV-5）
  const log = await registration.textContent('#log');
  for (const secret of ['山田', 'ヤマダ', '09012345678', '090-1234-5678', 'taro.yamada', '150-0001', '渋谷']) {
    assert.ok(!log.includes(secret), `監査ログに個人情報が含まれています: ${secret}`);
  }
  assert.ok(log.includes('HANDOFF_VERIFIED'));
  assert.ok(log.includes('FORM_FILLED'));
  assert.ok(log.includes('SUBMIT_BY_HUMAN'));

  // 外部ホストへの通信が発生していないこと
  assert.deepEqual(external, [], `外部通信が発生しました: ${external.join(', ')}`);

  await context.close();
});

test('失効したEnvelopeは拒否され、入力欄は空のまま', async () => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`${BASE}/registration/`);

  const key = hexToBytes('11'.repeat(32));
  const issued = Math.floor(Date.now() / 1000) - 3600;
  const env = buildEnvelope({
    handoffId: '6f1d2c9a-6b1e-4f52-9d33-2a1b0c4e77aa',
    documentType: 'residency_application',
    source: { kind: 'ondevice-ocr', app: 'SecureLocalOCR-Android', version: '0.1.0' },
    fields: { name: { value: '山田　太郎', origin: 'ocr', confidence: 0.9, edited: false } },
    issuedAtEpochSeconds: issued,
  });
  await signEnvelope(env, 'session:test', key);

  await page.evaluate(async (envelope) => {
    await window.SLO._deliver(envelope);
  }, env);

  await page.waitForFunction(() => document.getElementById('banner').textContent.includes('E_EXPIRED'));
  assert.equal(await page.inputValue('#applicant_name'), '');
  await context.close();
});

test('値を改ざんしたEnvelopeは正規化の再計算で拒否される', async () => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`${BASE}/registration/`);

  const env = buildEnvelope({
    handoffId: '7a1d2c9a-6b1e-4f52-9d33-2a1b0c4e77ab',
    documentType: 'residency_application',
    source: { kind: 'ondevice-ocr', app: 'SecureLocalOCR-Android', version: '0.1.0' },
    fields: { phone: { value: '09012345678', origin: 'ocr', confidence: 0.9, edited: false } },
    issuedAtEpochSeconds: Math.floor(Date.now() / 1000),
  });
  env.fields.phone.value = '0901234567X'; // 正規形ではない値に差し替え

  await page.evaluate(async (envelope) => { await window.SLO._deliver(envelope); }, env);
  await page.waitForFunction(() => document.getElementById('banner').textContent.includes('E_VALIDATION'));
  assert.equal(await page.inputValue('#tel1'), '');
  await context.close();
});

test('許可リストにないオリジンからの引き渡しは無視される', async () => {
  const context = await browser.newContext();
  const attacker = await context.newPage();
  await attacker.goto(`http://127.0.0.1:${ATTACKER_PORT}/`);

  const env = buildEnvelope({
    handoffId: '8b1d2c9a-6b1e-4f52-9d33-2a1b0c4e77ac',
    documentType: 'residency_application',
    source: { kind: 'ondevice-ocr', app: 'evil', version: '0.0.0' },
    fields: { name: { value: '偽名　太郎', origin: 'ocr', confidence: 0.9, edited: false } },
    issuedAtEpochSeconds: Math.floor(Date.now() / 1000),
  });
  await attacker.evaluate((e) => { window.__evilEnvelope = e; }, env);

  const [victim] = await Promise.all([
    context.waitForEvent('page'),
    attacker.evaluate(() => window.openTarget()),
  ]);
  await victim.waitForLoadState();

  // 別オリジンからの slo.* メッセージは ORIGIN_DENIED として捨てられる
  await victim.waitForFunction(() => document.getElementById('log').textContent.includes('ORIGIN_DENIED'));
  assert.equal(await victim.inputValue('#applicant_name'), '');
  await context.close();
});

test('ループバック受け口(T4)はEnvelopeを検証してから受け付ける', async () => {
  const good = buildEnvelope({
    handoffId: '9c1d2c9a-6b1e-4f52-9d33-2a1b0c4e77ad',
    documentType: 'contact_registration',
    source: { kind: 'ondevice-ocr', app: 'SecureLocalOCR-Android', version: '0.1.0' },
    fields: {
      name: { value: '山田　太郎', origin: 'ocr', confidence: 0.9, edited: false },
      phone: { value: '09012345678', origin: 'ocr', confidence: 0.8, edited: true },
    },
    issuedAtEpochSeconds: Math.floor(Date.now() / 1000),
  });
  const okRes = await fetch(`${BASE}/slo/v1/handoff`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(good),
  });
  assert.equal(okRes.status, 200);
  assert.deepEqual(await okRes.json(), { ok: true, field_count: 2 });

  const expired = { ...good, expires_at: formatRfc3339(Math.floor(Date.now() / 1000) - 10) };
  const ngRes = await fetch(`${BASE}/slo/v1/handoff`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(expired),
  });
  assert.equal(ngRes.status, 400);
  assert.equal((await ngRes.json()).error, 'E_EXPIRED');
});

test('登録先サイトは外部への通信を禁止するCSPを返す', async () => {
  const res = await fetch(`${BASE}/registration/`);
  const csp = res.headers.get('content-security-policy');
  assert.ok(csp.includes("default-src 'self'"), 'default-src が self ではありません');
  assert.ok(csp.includes("connect-src 'self'"), 'connect-src が self ではありません');
  assert.ok(csp.includes("frame-ancestors 'none'"));
  assert.equal(res.headers.get('referrer-policy'), 'no-referrer');
});
