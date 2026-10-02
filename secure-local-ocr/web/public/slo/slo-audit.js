/**
 * 監査ログ（ブラウザ側）。Kotlin の jp.slo.core.AuditLog と同じ規則。
 *
 * INV-5: 個人情報そのものを絶対に記録しない。
 * 記録できるのはイベント種別・項目キー・件数・結果コードだけ。
 * 値らしき文字列を渡した場合は実行時に例外にして、事故を握りつぶさない。
 */

const ALLOWED_ATTR_KEYS = new Set([
  'profile', 'document_type', 'fields', 'field', 'filled', 'skipped', 'guessed',
  'result', 'reason', 'handoff_id', 'origin', 'engine', 'offline', 'count', 'elapsed_ms',
]);

const FORBIDDEN_VALUE_PATTERNS = [
  /[０-９0-9]{7,}/,                        // 電話番号・郵便番号などの連番
  /[^\s@]+@[^\s@]+\.[^\s@]+/,             // メールアドレス
  /[一-龠]{2,}[\s　][一-龠]{1,}/,          // 氏名らしき漢字の並び
  /[ぁ-んァ-ヶ]{4,}/,                      // かな氏名・住所の一部
];

export class PiiInLogError extends Error {}

export const EVENTS = [
  'PAGE_READY', 'HANDOFF_REQUESTED', 'HANDOFF_DELIVERED', 'HANDOFF_VERIFIED',
  'HANDOFF_REJECTED', 'FORM_FILLED', 'FIELD_EDITED', 'USER_CONFIRMED',
  'SUBMIT_BY_HUMAN', 'ORIGIN_DENIED', 'SESSION_ENDED',
];

export class AuditLog {
  constructor(limit = 500) {
    this.entries = [];
    this.limit = limit;
    this.listeners = [];
  }

  /** @param {string} event @param {Record<string,string|number>} attributes */
  add(event, attributes = {}) {
    for (const [k, v] of Object.entries(attributes)) {
      if (!ALLOWED_ATTR_KEYS.has(k)) {
        throw new PiiInLogError(`監査ログに許可されていない属性キー: ${k}`);
      }
      if (k === 'handoff_id') continue; // UUIDは数字連続チェックの対象外
      const s = String(v);
      for (const p of FORBIDDEN_VALUE_PATTERNS) {
        if (p.test(s)) throw new PiiInLogError(`監査ログに個人情報らしき値: key=${k}`);
      }
    }
    const entry = {
      timestamp: new Date().toISOString().slice(0, 19) + 'Z',
      event,
      attributes,
    };
    this.entries.push(entry);
    if (this.entries.length > this.limit) this.entries.shift();
    for (const l of this.listeners) l(entry);
    return entry;
  }

  onEntry(fn) {
    this.listeners.push(fn);
  }

  format() {
    return this.entries
      .map((e) => {
        const attrs = Object.entries(e.attributes).map(([k, v]) => `${k}=${v}`).join(' ');
        return attrs ? `${e.timestamp}\t${e.event}\t${attrs}` : `${e.timestamp}\t${e.event}`;
      })
      .join('\n');
  }
}

export const auditLog = new AuditLog();
