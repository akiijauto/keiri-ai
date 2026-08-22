/**
 * SLO Bridge — 登録先の業務Webサイトに1行で組み込むアダプタ（SPEC.md 7.1, 8）。
 *
 * 役割:
 *   1. 取込元（OCRアプリ／入居フォーム）とハンドシェイクする
 *   2. 受け取ったEnvelopeを検証する（プロトコル・失効・正規化再計算・HMAC・リプレイ）
 *   3. 検証を通った値だけをフォームの入力欄へ充填する
 *   4. 絶対に自動送信しない（INV-4）。送信は必ず人間が押す。
 *
 * 取込元が Android/iOS アプリでも、同一端末の別タブ（入居フォーム）でも、
 * 受け口はこの1つだけで済む。
 */
import {
  Profile, Normalizer, SimpleDate, verifyEnvelope, hexToBytes,
  E_ORIGIN, E_NONCE, E_REPLAY,
} from './slo-core.js';
import { auditLog } from './slo-audit.js';

const BRIDGE_VERSION = '0.1.0';

/** 2桁市外局番（MVPの範囲。実運用では総務省の市外局番表を同梱して置き換える）。 */
const TWO_DIGIT_AREA_CODES = ['03', '04', '06'];

const WAREKI_ERAS = [
  { name: '令和', start: 2019 },
  { name: '平成', start: 1989 },
  { name: '昭和', start: 1926 },
  { name: '大正', start: 1912 },
  { name: '明治', start: 1868 },
];

/** OCR項目 → フォーム入力欄 の自動推定に使うヒント。 */
const AUTOFILL_HINTS = {
  name: { autocomplete: ['name'], names: ['name', 'fullname', 'applicant_name'] },
  name_kana: { autocomplete: [], names: ['kana', 'furigana', 'name_kana'] },
  birthday: { autocomplete: ['bday'], names: ['birth', 'birthday', 'bday'] },
  postal_code: { autocomplete: ['postal-code'], names: ['zip', 'postal', 'postcode'] },
  address: { autocomplete: ['street-address'], names: ['address', 'addr'] },
  phone: { autocomplete: ['tel'], names: ['tel', 'phone'] },
  email: { autocomplete: ['email'], names: ['email', 'mail'] },
  customer_no: { autocomplete: [], names: ['customer', 'member_no', 'customer_no'] },
  move_in_date: { autocomplete: [], names: ['movein', 'move_in', 'move_in_date'] },
};

export class SloBridge {
  constructor(options = {}) {
    this.documentType = options.documentType ?? 'generic';
    this.mapping = options.mapping ?? { map: {} };
    this.root = options.root ?? document;
    this.sessionKey = null;
    this.sessionKeyId = null;
    this.nonce = null;
    this.consumedHandoffIds = new Set();
    this.state = 'idle';
    this.onFilled = options.onFilled ?? (() => {});
    this.onRejected = options.onRejected ?? (() => {});
    this.onStateChange = options.onStateChange ?? (() => {});
    this.hostWindow = null;
    this.hostOrigin = null;
    this.allowedHostOrigins = options.allowedHostOrigins ?? [window.location.origin];
  }

  setState(state) {
    this.state = state;
    this.onStateChange(state);
  }

  /** 取込元へハンドシェイクを開始する。 */
  ready() {
    this.nonce = randomHex(16);
    const request = {
      type: 'slo.ready',
      protocol: 'slo-handoff/1.0',
      profile: Profile.ID,
      document_type: this.documentType,
      bridge_version: BRIDGE_VERSION,
      fields: this.availableFieldKeys(),
      nonce: this.nonce,
    };
    auditLog.add('HANDOFF_REQUESTED', {
      document_type: this.documentType,
      fields: String(request.fields.length),
    });
    this.setState('waiting');

    // 1) ネイティブアプリ内のWebViewの場合
    if (window.SLOHost && typeof window.SLOHost.postMessage === 'function') {
      window.SLOHost.postMessage(JSON.stringify(request));
      return request;
    }
    if (window.webkit?.messageHandlers?.slo) {
      window.webkit.messageHandlers.slo.postMessage(request);
      return request;
    }
    // 2) 同一端末の別ウィンドウ（入居フォーム）の場合
    if (window.opener) {
      window.opener.postMessage(request, '*');
      return request;
    }
    this.setState('no-host');
    return request;
  }

  /** 取込元が確立したセッション鍵を受け取る。鍵は端末内に閉じており外部へ出ない。 */
  session({ key_id: keyId, key_hex: keyHex, nonce }) {
    if (nonce !== this.nonce) {
      this.reject(E_NONCE);
      return false;
    }
    this.sessionKeyId = keyId;
    this.sessionKey = hexToBytes(keyHex);
    this.setState('paired');
    return true;
  }

  /** Envelopeを受け取り、検証して充填する。 */
  async deliver(envelope) {
    auditLog.add('HANDOFF_DELIVERED', {
      handoff_id: shortId(envelope?.handoff_id),
      fields: String(Object.keys(envelope?.fields ?? {}).length),
    });

    if (envelope?.handoff_id && this.consumedHandoffIds.has(envelope.handoff_id)) {
      return this.reject(E_REPLAY);
    }

    const now = Math.floor(Date.now() / 1000);
    const result = await verifyEnvelope(envelope, this.sessionKey, now, SimpleDate.today());
    if (!result.ok) return this.reject(result.error);

    this.consumedHandoffIds.add(envelope.handoff_id);
    auditLog.add('HANDOFF_VERIFIED', { result: 'ok', fields: String(result.fieldCount) });

    const filled = this.fill(envelope.fields);
    auditLog.add('FORM_FILLED', {
      filled: String(filled.filled.length),
      skipped: String(filled.skipped.length),
      guessed: String(filled.guessed.length),
    });
    this.setState('filled');
    this.onFilled(filled);
    this.postToHost({ type: 'slo.filled', field_count: filled.filled.length });
    return filled;
  }

  reject(code) {
    auditLog.add('HANDOFF_REJECTED', { reason: code });
    this.setState('rejected');
    this.onRejected(code);
    this.postToHost({ type: 'slo.rejected', reason: code });
    return { ok: false, error: code, filled: [], skipped: [], guessed: [] };
  }

  postToHost(message) {
    if (window.SLOHost && typeof window.SLOHost.postMessage === 'function') {
      window.SLOHost.postMessage(JSON.stringify(message));
    } else if (window.webkit?.messageHandlers?.slo) {
      window.webkit.messageHandlers.slo.postMessage(message);
    } else if (this.hostWindow) {
      this.hostWindow.postMessage(message, this.hostOrigin ?? '*');
    }
  }

  /** マッピング定義と自動推定から、このページで受け取れる項目キーを列挙する。 */
  availableFieldKeys() {
    const keys = new Set(Object.keys(this.mapping.map ?? {}));
    for (const f of Profile.FIELDS) {
      if (this.findElements(f.key).length > 0) keys.add(f.key);
    }
    return [...keys];
  }

  /**
   * 検証済みの値をフォームへ充填する。
   * 自動送信はしない。埋めた欄には印を付け、人間が最終確認できるようにする。
   */
  fill(fields) {
    const filled = [];
    const skipped = [];
    const guessed = [];

    for (const [key, field] of Object.entries(fields)) {
      const spec = this.mapping.map?.[key];
      const elements = this.findElements(key);
      if (elements.length === 0) {
        skipped.push(key);
        continue;
      }
      if (!spec) guessed.push(key);

      const parts = this.formatForTarget(key, field.value, spec, elements.length);
      elements.forEach((el, i) => {
        const v = parts[i] ?? '';
        el.value = v;
        el.setAttribute('data-slo-filled', key);
        if (!spec) el.setAttribute('data-slo-guessed', 'true');
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      });
      filled.push(key);
    }

    this.root.dispatchEvent(new CustomEvent('slo:filled', {
      bubbles: true,
      detail: { filled, skipped, guessed, field_count: filled.length },
    }));
    return { ok: true, error: null, filled, skipped, guessed };
  }

  /** マッピングが無い項目は autocomplete → name属性 → label文言 の順に推定する。 */
  findElements(key) {
    const spec = this.mapping.map?.[key];
    if (spec) {
      const selectors = Array.isArray(spec.selector) ? spec.selector : [spec.selector];
      const els = selectors.map((s) => this.root.querySelector(s)).filter(Boolean);
      return els;
    }
    const hint = AUTOFILL_HINTS[key];
    if (!hint) return [];
    for (const ac of hint.autocomplete) {
      const el = this.root.querySelector(`input[autocomplete="${ac}"]`);
      if (el) return [el];
    }
    for (const n of hint.names) {
      const el = this.root.querySelector(`input[name="${n}"], input[id="${n}"]`);
      if (el) return [el];
    }
    const label = Profile.label(key);
    const labels = [...this.root.querySelectorAll('label')];
    for (const l of labels) {
      if (l.textContent.trim().startsWith(label) && l.htmlFor) {
        const el = this.root.getElementById
          ? this.root.getElementById(l.htmlFor)
          : document.getElementById(l.htmlFor);
        if (el) return [el];
      }
    }
    return [];
  }

  /** Envelopeの正規形（ISO日付・数字のみの電話番号）を、対象サイトの表記へ変換する。 */
  formatForTarget(key, value, spec, elementCount) {
    const format = spec?.format;
    const split = spec?.split;

    let v = value;
    if ((key === 'birthday' || key === 'move_in_date') && format) {
      v = formatDate(value, format);
    }

    if (!split || elementCount <= 1) return [v];

    if (split === 'jp-phone') return splitJpPhone(v, elementCount);
    return v.split(split);
  }
}

export function formatDate(iso, format) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!m) return iso;
  const [, y, mo, d] = m;
  switch (format) {
    case 'YYYY/MM/DD': return `${y}/${mo}/${d}`;
    case 'YYYYMMDD': return `${y}${mo}${d}`;
    case 'wareki': {
      const year = Number(y);
      const era = WAREKI_ERAS.find((e) => year >= e.start);
      if (!era) return iso;
      return `${era.name}${year - era.start + 1}年${Number(mo)}月${Number(d)}日`;
    }
    case 'ISO':
    default:
      return iso;
  }
}

export function splitJpPhone(digits, parts) {
  if (parts < 3) return [digits];
  if (digits.length === 11) return [digits.slice(0, 3), digits.slice(3, 7), digits.slice(7)];
  if (digits.length === 10) {
    if (TWO_DIGIT_AREA_CODES.includes(digits.slice(0, 2))) {
      return [digits.slice(0, 2), digits.slice(2, 6), digits.slice(6)];
    }
    return [digits.slice(0, 3), digits.slice(3, 6), digits.slice(6)];
  }
  return [digits];
}

function randomHex(bytes) {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
}

function shortId(id) {
  return typeof id === 'string' ? `${id.slice(0, 8)}…` : 'none';
}

/**
 * ページに組み込むための入口。
 * ネイティブ側は window.SLO._session / window.SLO._deliver を呼ぶだけでよい。
 */
export function installBridge(options) {
  const bridge = new SloBridge(options);

  window.SLO = {
    version: BRIDGE_VERSION,
    ready: () => bridge.ready(),
    _session: (payload) => bridge.session(payload),
    _deliver: (envelope) => bridge.deliver(envelope),
    state: () => bridge.state,
    audit: () => auditLog.format(),
    bridge,
  };

  // 別ウィンドウ（入居フォーム等）からの受け取り。オリジン許可リストで弾く（SPEC.md 7.1）。
  window.addEventListener('message', (ev) => {
    const allowed = bridge.allowedHostOrigins.includes(ev.origin);
    const data = ev.data;
    if (!data || typeof data !== 'object' || typeof data.type !== 'string') return;
    if (!data.type.startsWith('slo.')) return;
    if (!allowed) {
      auditLog.add('ORIGIN_DENIED', { reason: E_ORIGIN });
      return;
    }
    bridge.hostWindow = ev.source;
    bridge.hostOrigin = ev.origin;
    if (data.type === 'slo.session') bridge.session(data);
    if (data.type === 'slo.deliver') bridge.deliver(data.envelope);
  });

  return bridge;
}

export { Normalizer, Profile, auditLog };
