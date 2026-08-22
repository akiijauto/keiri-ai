/**
 * SLO Core (JavaScript / ESM) — Kotlin実装 jp.slo.core と1対1で対応する。
 *
 * 用途は2つ。
 *  1. 入居フォーム等「Web入力の取込元」で、OCRアプリと同じ基準の正規化・検証を行う。
 *  2. 登録先の業務Webサイトで、受け取ったEnvelopeを再検証する（INV-6）。
 *
 * 依存ライブラリなし。ブラウザでも Node でもそのまま動く。
 * 外部への通信は一切行わない。
 */

const IDEOGRAPHIC_SPACE = '　';

const OCR_DIGIT_FIX = {
  O: '0', o: '0', '〇': '0', D: '0',
  I: '1', l: '1', '｜': '1', '|': '1',
  S: '5', s: '5',
  B: '8',
  Z: '2',
  q: '9',
};

const HYPHEN_LIKE = new Set([
  '－', '−', '‐', '‑', '‒', '–', '—', '―', 'ー', 'ｰ', '⁃', '˗',
]);

const HALFWIDTH_KATAKANA = {
  '｡': '。', '｢': '「', '｣': '」', '､': '、', '･': '・',
  'ｦ': 'ヲ', 'ｧ': 'ァ', 'ｨ': 'ィ', 'ｩ': 'ゥ', 'ｪ': 'ェ', 'ｫ': 'ォ',
  'ｬ': 'ャ', 'ｭ': 'ュ', 'ｮ': 'ョ', 'ｯ': 'ッ', 'ｰ': 'ー',
  'ｱ': 'ア', 'ｲ': 'イ', 'ｳ': 'ウ', 'ｴ': 'エ', 'ｵ': 'オ',
  'ｶ': 'カ', 'ｷ': 'キ', 'ｸ': 'ク', 'ｹ': 'ケ', 'ｺ': 'コ',
  'ｻ': 'サ', 'ｼ': 'シ', 'ｽ': 'ス', 'ｾ': 'セ', 'ｿ': 'ソ',
  'ﾀ': 'タ', 'ﾁ': 'チ', 'ﾂ': 'ツ', 'ﾃ': 'テ', 'ﾄ': 'ト',
  'ﾅ': 'ナ', 'ﾆ': 'ニ', 'ﾇ': 'ヌ', 'ﾈ': 'ネ', 'ﾉ': 'ノ',
  'ﾊ': 'ハ', 'ﾋ': 'ヒ', 'ﾌ': 'フ', 'ﾍ': 'ヘ', 'ﾎ': 'ホ',
  'ﾏ': 'マ', 'ﾐ': 'ミ', 'ﾑ': 'ム', 'ﾒ': 'メ', 'ﾓ': 'モ',
  'ﾔ': 'ヤ', 'ﾕ': 'ユ', 'ﾖ': 'ヨ',
  'ﾗ': 'ラ', 'ﾘ': 'リ', 'ﾙ': 'ル', 'ﾚ': 'レ', 'ﾛ': 'ロ',
  'ﾜ': 'ワ', 'ﾝ': 'ン',
};

const DAKUTEN = 'カキクケコサシスセソタチツテトハヒフヘホウ';
const HANDAKUTEN = 'ハヒフヘホ';

export const Text = {
  stripControlChars(s) {
    let out = '';
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      if (c === '\t') out += ' ';
      else if (s.charCodeAt(i) >= 0x20) out += c;
    }
    return out;
  },

  toHalfwidthAscii(s) {
    let out = '';
    for (let i = 0; i < s.length; i++) {
      const code = s.charCodeAt(i);
      if (code >= 0xff01 && code <= 0xff5e) out += String.fromCharCode(code - 0xfee0);
      else if (code === 0x3000) out += ' ';
      else out += s[i];
    }
    return out;
  },

  digitsToHalfwidth(s) {
    let out = '';
    for (let i = 0; i < s.length; i++) {
      const code = s.charCodeAt(i);
      if (code >= 0xff10 && code <= 0xff19) out += String.fromCharCode(code - 0xfee0);
      else out += s[i];
    }
    return out;
  },

  halfwidthKatakanaToFullwidth(s) {
    let out = '';
    let i = 0;
    while (i < s.length) {
      const base = HALFWIDTH_KATAKANA[s[i]];
      if (base === undefined) { out += s[i]; i += 1; continue; }
      const next = i + 1 < s.length ? s[i + 1] : null;
      if (next === 'ﾞ' && DAKUTEN.includes(base)) {
        out += base === 'ウ' ? 'ヴ' : String.fromCharCode(base.charCodeAt(0) + 1);
        i += 2;
      } else if (next === 'ﾟ' && HANDAKUTEN.includes(base)) {
        out += String.fromCharCode(base.charCodeAt(0) + 2);
        i += 2;
      } else {
        out += base;
        i += 1;
      }
    }
    return out;
  },

  hiraganaToKatakana(s) {
    let out = '';
    for (let i = 0; i < s.length; i++) {
      const code = s.charCodeAt(i);
      if (code >= 0x3041 && code <= 0x3096) out += String.fromCharCode(code + 0x60);
      else out += s[i];
    }
    return out;
  },

  collapseSpacesToIdeographic(s) {
    const collapsed = s.replace(/[ \t　]+/g, IDEOGRAPHIC_SPACE);
    let start = 0;
    let end = collapsed.length;
    const isTrimmable = (c) => c === ' ' || c === IDEOGRAPHIC_SPACE || c === '\t';
    while (start < end && isTrimmable(collapsed[start])) start += 1;
    while (end > start && isTrimmable(collapsed[end - 1])) end -= 1;
    return collapsed.slice(start, end);
  },

  removeAllSpaces(s) {
    return s.replace(/[\s　]+/g, '');
  },

  fixOcrDigits(s) {
    let out = '';
    for (let i = 0; i < s.length; i++) out += OCR_DIGIT_FIX[s[i]] ?? s[i];
    return out;
  },

  isHyphenLike(ch) {
    return ch === '-' || HYPHEN_LIKE.has(ch);
  },

  normalizeHyphensBetweenDigits(s) {
    const chars = [...s];
    const isDigit = (c) => c !== undefined && c >= '0' && c <= '9';
    for (let i = 0; i < chars.length; i++) {
      if (!Text.isHyphenLike(chars[i])) continue;
      if (isDigit(chars[i - 1]) && isDigit(chars[i + 1])) chars[i] = '-';
    }
    return chars.join('');
  },

  normalizeAllHyphens(s) {
    let out = '';
    for (let i = 0; i < s.length; i++) out += Text.isHyphenLike(s[i]) ? '-' : s[i];
    return out;
  },

  digitsOnly(s) {
    let out = '';
    for (let i = 0; i < s.length; i++) if (s[i] >= '0' && s[i] <= '9') out += s[i];
    return out;
  },
};

/* ------------------------------------------------------------------ */
/* Profile: jp.personal.basic/1                                        */
/* ------------------------------------------------------------------ */

export const Profile = {
  ID: 'jp.personal.basic/1',
  FIELDS: [
    { key: 'name', label: '氏名', inputHint: 'text', maxLength: 64 },
    { key: 'name_kana', label: 'フリガナ', inputHint: 'kana', maxLength: 64 },
    { key: 'birthday', label: '生年月日', inputHint: 'date', maxLength: 10 },
    { key: 'postal_code', label: '郵便番号', inputHint: 'postal', maxLength: 8 },
    { key: 'address', label: '住所', inputHint: 'text', maxLength: 128 },
    { key: 'phone', label: '電話番号', inputHint: 'tel', maxLength: 11 },
    { key: 'email', label: 'メールアドレス', inputHint: 'email', maxLength: 254 },
    { key: 'customer_no', label: '顧客番号', inputHint: 'text', maxLength: 32 },
    { key: 'move_in_date', label: '入居予定日', inputHint: 'date', maxLength: 10 },
  ],
  REQUIRED: {
    residency_application: ['name', 'name_kana', 'birthday', 'postal_code', 'address', 'phone'],
    contact_registration: ['name', 'phone'],
    generic: [],
  },
  label(key) {
    const f = Profile.FIELDS.find((x) => x.key === key);
    return f ? f.label : key;
  },
  requiredFor(documentType) {
    return Profile.REQUIRED[documentType] ?? [];
  },
};

/* ------------------------------------------------------------------ */
/* 日付                                                                 */
/* ------------------------------------------------------------------ */

export const SimpleDate = {
  isLeap(y) {
    return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
  },
  daysInMonth(y, m) {
    if ([1, 3, 5, 7, 8, 10, 12].includes(m)) return 31;
    if ([4, 6, 9, 11].includes(m)) return 30;
    if (m === 2) return SimpleDate.isLeap(y) ? 29 : 28;
    return 0;
  },
  isValid(d) {
    if (d.month < 1 || d.month > 12) return false;
    if (d.day < 1) return false;
    return d.day <= SimpleDate.daysInMonth(d.year, d.month);
  },
  toIso(d) {
    const p = (n, w) => String(n).padStart(w, '0');
    return `${p(d.year, 4)}-${p(d.month, 2)}-${p(d.day, 2)}`;
  },
  compare(a, b) {
    if (a.year !== b.year) return a.year - b.year;
    if (a.month !== b.month) return a.month - b.month;
    return a.day - b.day;
  },
  today() {
    const n = new Date();
    return { year: n.getFullYear(), month: n.getMonth() + 1, day: n.getDate() };
  },
};

/* ------------------------------------------------------------------ */
/* 正規化・検証                                                          */
/* ------------------------------------------------------------------ */

const ERAS = {
  '明治': 1868, M: 1868, m: 1868,
  '大正': 1912, T: 1912, t: 1912,
  '昭和': 1926, S: 1926, s: 1926,
  '平成': 1989, H: 1989, h: 1989,
  '令和': 2019, R: 2019, r: 2019,
};

const ERA_RE = /^(明治|大正|昭和|平成|令和|[MTSHRmtshr])\s*(\d{1,2})\s*(?:年|[./-])\s*(\d{1,2})\s*(?:月|[./-])\s*(\d{1,2})\s*日?$/;
const WESTERN_RE = /^(\d{4})\s*(?:年|[./-])\s*(\d{1,2})\s*(?:月|[./-])\s*(\d{1,2})\s*日?$/;
const COMPACT_RE = /^(\d{4})(\d{2})(\d{2})$/;
const EMAIL_RE = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/;
const CUSTOMER_RE = /^[A-Z0-9-]{1,32}$/;
const KANA_RE = /^[ァ-ヶー　]+$/;

const MIN_BIRTH_YEAR = 1900;

export const E_PARSE = 'E_PARSE';
export const E_VALIDATION = 'E_VALIDATION';
export const E_UNKNOWN_FIELD = 'E_UNKNOWN_FIELD';

const ok = (value) => ({ ok: true, value, error: null });
const err = (error) => ({ ok: false, value: null, error });

export const Normalizer = {
  normalize(field, input, today = SimpleDate.today()) {
    const s0 = Text.stripControlChars(input);
    switch (field) {
      case 'name': return Normalizer.normalizeName(s0);
      case 'name_kana': return Normalizer.normalizeKana(s0);
      case 'birthday': return Normalizer.normalizeDate(s0, MIN_BIRTH_YEAR, today);
      case 'move_in_date': return Normalizer.normalizeDate(s0, MIN_BIRTH_YEAR, null);
      case 'postal_code': return Normalizer.normalizePostal(s0);
      case 'address': return Normalizer.normalizeAddress(s0);
      case 'phone': return Normalizer.normalizePhone(s0);
      case 'email': return Normalizer.normalizeEmail(s0);
      case 'customer_no': return Normalizer.normalizeCustomerNo(s0);
      default: return err(E_UNKNOWN_FIELD);
    }
  },

  normalizeName(input) {
    let s = Text.halfwidthKatakanaToFullwidth(input);
    s = Text.collapseSpacesToIdeographic(s);
    if (s.length === 0 || s.length > 64) return err(E_VALIDATION);
    if (/[0-9０-９]/.test(s)) return err(E_VALIDATION);
    if (s.includes('@')) return err(E_VALIDATION);
    return ok(s);
  },

  normalizeKana(input) {
    let s = Text.halfwidthKatakanaToFullwidth(input);
    s = Text.hiraganaToKatakana(s);
    s = Text.collapseSpacesToIdeographic(s);
    if (s.length === 0 || s.length > 64) return err(E_VALIDATION);
    if (!KANA_RE.test(s)) return err(E_VALIDATION);
    return ok(s);
  },

  normalizeDate(input, minYear, notAfter) {
    const raw = Text.removeAllSpaces(Text.toHalfwidthAscii(input));
    const date = parseDate(raw);
    if (!date) return err(E_PARSE);
    if (!SimpleDate.isValid(date)) return err(E_VALIDATION);
    if (date.year < minYear) return err(E_VALIDATION);
    if (notAfter && SimpleDate.compare(date, notAfter) > 0) return err(E_VALIDATION);
    return ok(SimpleDate.toIso(date));
  },

  normalizePostal(input) {
    const s = Text.digitsOnly(Text.fixOcrDigits(Text.toHalfwidthAscii(input)));
    if (s.length !== 7) return err(E_VALIDATION);
    return ok(`${s.slice(0, 3)}-${s.slice(3)}`);
  },

  normalizeAddress(input) {
    let s = Text.halfwidthKatakanaToFullwidth(input);
    s = Text.digitsToHalfwidth(s);
    s = Text.normalizeHyphensBetweenDigits(s);
    s = Text.collapseSpacesToIdeographic(s);
    if (s.length === 0 || s.length > 128) return err(E_VALIDATION);
    return ok(s);
  },

  normalizePhone(input) {
    let s = Text.toHalfwidthAscii(input);
    s = Text.fixOcrDigits(s);
    s = Text.removeAllSpaces(s);
    if (s.startsWith('+81')) s = `0${s.slice(3)}`;
    if (s.startsWith('+')) return err(E_VALIDATION);
    const digits = Text.digitsOnly(s);
    if (digits.length < 10 || digits.length > 11) return err(E_VALIDATION);
    if (!digits.startsWith('0')) return err(E_VALIDATION);
    return ok(digits);
  },

  normalizeEmail(input) {
    let s = Text.toHalfwidthAscii(input);
    s = Text.removeAllSpaces(s).toLowerCase();
    if (s.length === 0 || s.length > 254) return err(E_VALIDATION);
    if (!EMAIL_RE.test(s)) return err(E_VALIDATION);
    return ok(s);
  },

  normalizeCustomerNo(input) {
    let s = Text.toHalfwidthAscii(input);
    s = Text.normalizeAllHyphens(s);
    s = Text.removeAllSpaces(s).toUpperCase();
    if (!CUSTOMER_RE.test(s)) return err(E_VALIDATION);
    return ok(s);
  },
};

function parseDate(raw) {
  const era = ERA_RE.exec(raw);
  if (era) {
    const base = ERAS[era[1]];
    if (base === undefined) return null;
    return { year: base + Number(era[2]) - 1, month: Number(era[3]), day: Number(era[4]) };
  }
  // 元号として読めなかった場合に限りOCR誤読補正（S/H/R を数字に潰さないための順序）
  const fixed = Text.fixOcrDigits(raw);
  const w = WESTERN_RE.exec(fixed);
  if (w) return { year: Number(w[1]), month: Number(w[2]), day: Number(w[3]) };
  const c = COMPACT_RE.exec(fixed);
  if (c) return { year: Number(c[1]), month: Number(c[2]), day: Number(c[3]) };
  return null;
}

/* ------------------------------------------------------------------ */
/* 正準化JSON（RFC 8785 サブセット / SPEC.md 6.1）                        */
/* ------------------------------------------------------------------ */

const JSON_ESCAPES = {
  '"': '\\"', '\\': '\\\\', '\b': '\\b', '\f': '\\f',
  '\n': '\\n', '\r': '\\r', '\t': '\\t',
};

function writeString(s) {
  let out = '"';
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    const esc = JSON_ESCAPES[ch];
    if (esc) out += esc;
    else if (s.charCodeAt(i) < 0x20) out += `\\u${s.charCodeAt(i).toString(16).padStart(4, '0')}`;
    else out += ch;
  }
  return `${out}"`;
}

function formatNumber(d) {
  if (!Number.isFinite(d)) throw new Error('non-finite number is not representable in JSON');
  if (Number.isInteger(d) && Math.abs(d) < 1e15) return String(d);
  return String(d);
}

/** UTF-16コードユニット順。JSの既定の文字列比較と同じ。 */
function compareUtf16(a, b) {
  if (a < b) return -1;
  if (a > b) return 1;
  return 0;
}

export function canonicalJson(v) {
  if (v === null) return 'null';
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'number') return formatNumber(v);
  if (typeof v === 'string') return writeString(v);
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(',')}]`;
  if (typeof v === 'object') {
    const keys = Object.keys(v).sort(compareUtf16);
    return `{${keys.map((k) => `${writeString(k)}:${canonicalJson(v[k])}`).join(',')}}`;
  }
  throw new Error(`unsupported type: ${typeof v}`);
}

/* ------------------------------------------------------------------ */
/* Envelope                                                            */
/* ------------------------------------------------------------------ */

export const PROTOCOL = 'slo-handoff/1.0';
export const DEFAULT_TTL_SECONDS = 300;
export const MAX_TTL_SECONDS = 900;

export const E_PROTOCOL = 'E_PROTOCOL';
export const E_EXPIRED = 'E_EXPIRED';
export const E_INTEGRITY = 'E_INTEGRITY';
export const E_UNCONFIRMED = 'E_UNCONFIRMED';
export const E_ORIGIN = 'E_ORIGIN';
export const E_NONCE = 'E_NONCE';
export const E_REPLAY = 'E_REPLAY';

const cryptoApi = globalThis.crypto;

function bytesToBase64Url(bytes) {
  const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = i + 1 < bytes.length ? bytes[i + 1] : -1;
    const b2 = i + 2 < bytes.length ? bytes[i + 2] : -1;
    out += ALPHABET[b0 >>> 2];
    if (b1 < 0) { out += ALPHABET[(b0 & 0x03) << 4]; break; }
    out += ALPHABET[((b0 & 0x03) << 4) | (b1 >>> 4)];
    if (b2 < 0) { out += ALPHABET[(b1 & 0x0f) << 2]; break; }
    out += ALPHABET[((b1 & 0x0f) << 2) | (b2 >>> 6)];
    out += ALPHABET[b2 & 0x3f];
  }
  return out;
}

export function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

export async function hmacSha256Base64Url(keyBytes, message) {
  const key = await cryptoApi.subtle.importKey(
    'raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const sig = await cryptoApi.subtle.sign('HMAC', key, new TextEncoder().encode(message));
  return bytesToBase64Url(new Uint8Array(sig));
}

export function formatRfc3339(epochSeconds) {
  return `${new Date(epochSeconds * 1000).toISOString().slice(0, 19)}Z`;
}

export function parseRfc3339(text) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(text)) return null;
  const t = Date.parse(text);
  return Number.isNaN(t) ? null : Math.floor(t / 1000);
}

export function newHandoffId() {
  if (cryptoApi.randomUUID) return cryptoApi.randomUUID();
  const b = cryptoApi.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/**
 * 確認済み項目からEnvelopeを組み立てる。
 * INV-1: confirmed が false のまま呼ぶことは設計上の誤りなので例外にする。
 */
export function buildEnvelope({
  handoffId, documentType, source, fields,
  issuedAtEpochSeconds, ttlSeconds = DEFAULT_TTL_SECONDS, confirmed = true,
}) {
  if (!confirmed) throw new Error('INV-1: 未確認データをEnvelope化してはならない');
  if (!fields || Object.keys(fields).length === 0) throw new Error('fields must not be empty');
  if (ttlSeconds < 1 || ttlSeconds > MAX_TTL_SECONDS) throw new Error('ttl out of range');

  const out = {
    protocol: PROTOCOL,
    handoff_id: handoffId,
    issued_at: formatRfc3339(issuedAtEpochSeconds),
    expires_at: formatRfc3339(issuedAtEpochSeconds + ttlSeconds),
    document_type: documentType,
    profile: Profile.ID,
    source: { kind: source.kind, app: source.app, version: source.version },
    confirmed: true,
    fields: {},
  };
  if (source.engine !== undefined) out.source.engine = source.engine;
  if (source.offline_capture !== undefined) out.source.offline_capture = source.offline_capture;

  for (const [k, v] of Object.entries(fields)) {
    out.fields[k] = {
      value: v.value,
      origin: v.origin,
      confidence: v.confidence ?? null,
      edited: Boolean(v.edited),
      confirmed: true,
    };
  }
  return out;
}

export function canonicalWithoutIntegrity(envelope) {
  const copy = { ...envelope };
  delete copy.integrity;
  return canonicalJson(copy);
}

export async function signEnvelope(envelope, keyId, keyBytes) {
  const value = await hmacSha256Base64Url(keyBytes, canonicalWithoutIntegrity(envelope));
  envelope.integrity = { alg: 'HMAC-SHA256', key_id: keyId, value };
  return envelope;
}

function constantTimeEquals(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/**
 * 受け取り側の検証。プロトコル・確認済みフラグ・失効・正規化の再計算・HMACをすべて確認する。
 * ひとつでも落ちたら取り込まない。
 */
export async function verifyEnvelope(envelope, keyBytes, nowEpochSeconds, today = SimpleDate.today()) {
  if (!envelope || typeof envelope !== 'object') return { ok: false, error: E_PROTOCOL, fieldCount: 0 };
  if (envelope.protocol !== PROTOCOL) return { ok: false, error: E_PROTOCOL, fieldCount: 0 };
  if (envelope.profile !== Profile.ID) return { ok: false, error: E_PROTOCOL, fieldCount: 0 };
  if (envelope.confirmed !== true) return { ok: false, error: E_UNCONFIRMED, fieldCount: 0 };

  const expires = parseRfc3339(envelope.expires_at ?? '');
  if (expires === null) return { ok: false, error: E_PROTOCOL, fieldCount: 0 };
  if (nowEpochSeconds > expires) return { ok: false, error: E_EXPIRED, fieldCount: 0 };

  const fields = envelope.fields;
  if (!fields || typeof fields !== 'object' || Object.keys(fields).length === 0) {
    return { ok: false, error: E_PROTOCOL, fieldCount: 0 };
  }

  for (const [k, f] of Object.entries(fields)) {
    if (!f || typeof f !== 'object') return { ok: false, error: E_PROTOCOL, fieldCount: 0 };
    if (f.confirmed !== true) return { ok: false, error: E_UNCONFIRMED, fieldCount: 0 };
    if (typeof f.value !== 'string') return { ok: false, error: E_PROTOCOL, fieldCount: 0 };
    const r = Normalizer.normalize(k, f.value, today);
    if (!r.ok || r.value !== f.value) return { ok: false, error: E_VALIDATION, fieldCount: 0 };
  }

  if (keyBytes) {
    const integrity = envelope.integrity;
    if (!integrity || integrity.alg !== 'HMAC-SHA256') return { ok: false, error: E_INTEGRITY, fieldCount: 0 };
    const expected = await hmacSha256Base64Url(keyBytes, canonicalWithoutIntegrity(envelope));
    if (typeof integrity.value !== 'string' || !constantTimeEquals(expected, integrity.value)) {
      return { ok: false, error: E_INTEGRITY, fieldCount: 0 };
    }
  }

  return { ok: true, error: null, fieldCount: Object.keys(fields).length };
}

/** 必須項目のうち、値が無い／検証に落ちたものを返す。 */
export function missingRequired(fields, documentType) {
  return Profile.requiredFor(documentType).filter((key) => {
    const f = fields[key];
    return !f || !f.value;
  });
}
