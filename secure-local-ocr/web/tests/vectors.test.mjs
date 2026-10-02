/**
 * JavaScript実装が Kotlin実装と同じ共通ベクタを通ることを検証する。
 * 実行: node --test web/tests/
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import {
  Normalizer, canonicalJson, hmacSha256Base64Url, hexToBytes,
  buildEnvelope, signEnvelope, verifyEnvelope, formatRfc3339, parseRfc3339,
  Profile, missingRequired,
  E_EXPIRED, E_INTEGRITY, E_VALIDATION, E_UNCONFIRMED, E_PROTOCOL,
} from '../public/slo/slo-core.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const TESTDATA = join(HERE, '..', '..', 'protocol', 'testdata');

const load = (name) => JSON.parse(readFileSync(join(TESTDATA, name), 'utf8'));

// ベクタの判定が実行日に左右されないよう固定日を使う（Kotlin側のテストと同じ日）
const FIXED_TODAY = { year: 2026, month: 8, day: 22 };

test('正規化ベクタ（Kotlin実装と同一の結果になること）', () => {
  const { cases } = load('normalization-vectors.json');
  assert.ok(cases.length >= 50, `ベクタ件数が少なすぎます: ${cases.length}`);

  const failures = [];
  for (const c of cases) {
    const r = Normalizer.normalize(c.field, c.input, FIXED_TODAY);
    if (c.expected !== undefined) {
      if (!r.ok) failures.push(`${c.id}: エラー ${r.error} (期待値 '${c.expected}')`);
      else if (r.value !== c.expected) failures.push(`${c.id}: '${r.value}' != 期待値 '${c.expected}'`);
    } else {
      if (r.ok) failures.push(`${c.id}: 成功 '${r.value}' したが ${c.error} を期待`);
      else if (r.error !== c.error) failures.push(`${c.id}: ${r.error} != 期待 ${c.error}`);
    }
  }
  assert.deepEqual(failures, [], `\n${failures.join('\n')}`);
});

test('正準化JSONとHMACベクタ', async () => {
  const { cases } = load('canonical-vectors.json');
  for (const c of cases) {
    const canon = canonicalJson(JSON.parse(c.input));
    assert.equal(canon, c.canonical, `${c.id}: canonical不一致`);
    const mac = await hmacSha256Base64Url(hexToBytes(c.hmac_key_hex), canon);
    assert.equal(mac, c.hmac_b64url, `${c.id}: HMAC不一致`);
  }
});

test('Envelopeの組み立て・署名・検証', async () => {
  const key = hexToBytes('00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff');
  const issued = parseRfc3339('2026-08-22T09:15:00Z');

  const env = buildEnvelope({
    handoffId: '6f1d2c9a-6b1e-4f52-9d33-2a1b0c4e77aa',
    documentType: 'residency_application',
    source: { kind: 'web-form', app: 'SLO-Intake-Web', version: '0.1.0' },
    fields: {
      name: { value: '山田　太郎', origin: 'web-form', confidence: null, edited: true },
      phone: { value: '09012345678', origin: 'web-form', confidence: null, edited: true },
    },
    issuedAtEpochSeconds: issued,
  });
  await signEnvelope(env, 'session:test', key);

  const okResult = await verifyEnvelope(env, key, issued + 10, FIXED_TODAY);
  assert.equal(okResult.ok, true, `検証失敗: ${okResult.error}`);
  assert.equal(okResult.fieldCount, 2);

  // 失効後（INV-3）
  const expired = await verifyEnvelope(env, key, issued + 301, FIXED_TODAY);
  assert.equal(expired.error, E_EXPIRED);

  // 鍵違い
  const wrong = await verifyEnvelope(env, hexToBytes('ff'.repeat(32)), issued + 10, FIXED_TODAY);
  assert.equal(wrong.error, E_INTEGRITY);

  // 値の改ざんは正規化の再計算で落ちる
  const tampered = JSON.parse(JSON.stringify(env));
  tampered.fields.phone.value = '0901234567X';
  assert.equal((await verifyEnvelope(tampered, key, issued + 10, FIXED_TODAY)).error, E_VALIDATION);

  // confirmed を落としたものは受け付けない（INV-1）
  const unconfirmed = JSON.parse(JSON.stringify(env));
  unconfirmed.fields.name.confirmed = false;
  assert.equal((await verifyEnvelope(unconfirmed, key, issued + 10, FIXED_TODAY)).error, E_UNCONFIRMED);

  // プロトコル違いは拒否
  const otherProto = JSON.parse(JSON.stringify(env));
  otherProto.protocol = 'slo-handoff/2.0';
  assert.equal((await verifyEnvelope(otherProto, key, issued + 10, FIXED_TODAY)).error, E_PROTOCOL);
});

test('EnvelopeはOCR由来でもWebフォーム由来でも同一構造になる（SPEC.md 9）', async () => {
  const issued = parseRfc3339('2026-08-22T09:15:00Z');
  const fieldsOcr = { name: { value: '山田　太郎', origin: 'ocr', confidence: 0.9, edited: false } };
  const fieldsWeb = { name: { value: '山田　太郎', origin: 'web-form', confidence: null, edited: true } };

  const a = buildEnvelope({
    handoffId: '6f1d2c9a-6b1e-4f52-9d33-2a1b0c4e77aa',
    documentType: 'contact_registration',
    source: { kind: 'ondevice-ocr', app: 'SecureLocalOCR-Android', version: '0.1.0' },
    fields: fieldsOcr,
    issuedAtEpochSeconds: issued,
  });
  const b = buildEnvelope({
    handoffId: '6f1d2c9a-6b1e-4f52-9d33-2a1b0c4e77aa',
    documentType: 'contact_registration',
    source: { kind: 'web-form', app: 'SLO-Intake-Web', version: '0.1.0' },
    fields: fieldsWeb,
    issuedAtEpochSeconds: issued,
  });

  // 登録先が見るキー構造は完全に一致する（取込元を意識しなくてよい）
  assert.deepEqual(Object.keys(a).sort(), Object.keys(b).sort());
  assert.deepEqual(Object.keys(a.fields.name).sort(), Object.keys(b.fields.name).sort());
  assert.equal(a.profile, b.profile);

  const key = hexToBytes('00'.repeat(32));
  for (const env of [a, b]) {
    await signEnvelope(env, 'session:test', key);
    assert.equal((await verifyEnvelope(env, key, issued + 1, { year: 2026, month: 8, day: 22 })).ok, true);
  }
});

test('RFC3339の往復', () => {
  for (const s of ['1970-01-01T00:00:00Z', '2000-02-29T12:34:56Z', '2026-08-22T09:15:00Z']) {
    assert.equal(formatRfc3339(parseRfc3339(s)), s);
  }
  assert.equal(parseRfc3339('2026-08-22 09:15:00'), null);
});

test('必須項目の不足検出', () => {
  const fields = { name: { value: '山田　太郎' }, phone: { value: '09012345678' } };
  assert.deepEqual(missingRequired(fields, 'residency_application'),
    ['name_kana', 'birthday', 'postal_code', 'address']);
  assert.deepEqual(missingRequired(fields, 'contact_registration'), []);
  assert.deepEqual(Profile.requiredFor('generic'), []);
});
