import Foundation
import CryptoKit

/// 正準化JSON（RFC 8785 サブセット / SPEC.md 6.1）と Envelope（SPEC.md 3）。
///
/// Kotlin の `jp.slo.core.Json` / `Envelope`、JavaScript の `canonicalJson` / `buildEnvelope` と
/// バイト単位で同じ出力になることが要件。protocol/testdata/canonical-vectors.json で検証する。

/// 正準化のために順序と型を保持する最小のJSON表現。
public indirect enum SloJson: Equatable {
    case object([(String, SloJson)])
    case array([SloJson])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    public static func == (a: SloJson, b: SloJson) -> Bool {
        SloJson.canonical(a) == SloJson.canonical(b)
    }

    /// 正準化JSONへ直列化する。キーはUTF-16コードユニット昇順、空白なし。
    public static func canonical(_ value: SloJson) -> String {
        switch value {
        case .null: return "null"
        case .bool(let b): return b ? "true" : "false"
        case .number(let d): return formatNumber(d)
        case .string(let s): return writeString(s)
        case .array(let items): return "[" + items.map(canonical).joined(separator: ",") + "]"
        case .object(let entries):
            let sorted = entries.sorted { compareUtf16($0.0, $1.0) < 0 }
            return "{" + sorted.map { writeString($0.0) + ":" + canonical($0.1) }.joined(separator: ",") + "}"
        }
    }

    /// 整数値は "1"、非整数は最短往復表現。Kotlin/JS と同じ結果になるよう揃えている。
    static func formatNumber(_ d: Double) -> String {
        precondition(d.isFinite, "non-finite number is not representable in JSON")
        if d == d.rounded(.down) && abs(d) < 1e15 {
            return String(Int64(d))
        }
        return shortestRoundTrip(d)
    }

    static func shortestRoundTrip(_ d: Double) -> String {
        for precision in 1...17 {
            let s = String(format: "%.\(precision)g", d)
            if Double(s) == d { return s }
        }
        return String(d)
    }

    static func compareUtf16(_ a: String, _ b: String) -> Int {
        let ua = Array(a.utf16)
        let ub = Array(b.utf16)
        for i in 0..<min(ua.count, ub.count) where ua[i] != ub[i] {
            return Int(ua[i]) - Int(ub[i])
        }
        return ua.count - ub.count
    }

    static func writeString(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }

    /// Foundation の JSONSerialization 経由で読み込む（順序はキーソートで再構築するため問題にならない）。
    public static func parse(_ text: String) -> SloJson? {
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else { return nil }
        return convert(obj)
    }

    static func convert(_ any: Any) -> SloJson {
        if any is NSNull { return .null }
        if let n = any as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
            return .number(n.doubleValue)
        }
        if let s = any as? String { return .string(s) }
        if let a = any as? [Any] { return .array(a.map(convert)) }
        if let d = any as? [String: Any] { return .object(d.map { ($0.key, convert($0.value)) }) }
        return .null
    }

    public subscript(key: String) -> SloJson? {
        if case .object(let entries) = self {
            return entries.first { $0.0 == key }?.1
        }
        return nil
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var doubleValue: Double? {
        if case .number(let d) = self { return d }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var objectEntries: [(String, SloJson)]? {
        if case .object(let e) = self { return e }
        return nil
    }
}

public enum SloEnvelope {

    public static let protocolId = "slo-handoff/1.0"
    public static let defaultTtlSeconds: Int = 300
    public static let maxTtlSeconds: Int = 900

    public static let eProtocol = "E_PROTOCOL"
    public static let eExpired = "E_EXPIRED"
    public static let eIntegrity = "E_INTEGRITY"
    public static let eUnconfirmed = "E_UNCONFIRMED"
    public static let eValidation = "E_VALIDATION"

    public struct FieldValue {
        public let value: String
        public let origin: String
        public let confidence: Double?
        public let edited: Bool

        public init(value: String, origin: String, confidence: Double?, edited: Bool) {
            self.value = value
            self.origin = origin
            self.confidence = confidence
            self.edited = edited
        }
    }

    public struct Source {
        public let kind: String
        public let app: String
        public let version: String
        public let engine: String?
        public let offlineCapture: Bool?

        public init(kind: String, app: String, version: String, engine: String? = nil, offlineCapture: Bool? = nil) {
            self.kind = kind
            self.app = app
            self.version = version
            self.engine = engine
            self.offlineCapture = offlineCapture
        }
    }

    public struct Verification {
        public let ok: Bool
        public let error: String?
        public let fieldCount: Int
    }

    /// 確認済み項目からEnvelopeを構築する。
    /// INV-1: 未確認データを載せることは設計上の誤りなので、confirmed を偽にする経路は用意しない。
    public static func build(
        handoffId: String,
        documentType: String,
        source: Source,
        fields: [(String, FieldValue)],
        issuedAtEpochSeconds: Int,
        ttlSeconds: Int = defaultTtlSeconds
    ) -> SloJson {
        precondition(!fields.isEmpty, "fields must not be empty")
        precondition(ttlSeconds >= 1 && ttlSeconds <= maxTtlSeconds, "ttl out of range")

        var sourceEntries: [(String, SloJson)] = [
            ("kind", .string(source.kind)),
            ("app", .string(source.app)),
            ("version", .string(source.version))
        ]
        if let engine = source.engine { sourceEntries.append(("engine", .string(engine))) }
        if let offline = source.offlineCapture { sourceEntries.append(("offline_capture", .bool(offline))) }

        let fieldEntries: [(String, SloJson)] = fields.map { key, v in
            (key, .object([
                ("value", .string(v.value)),
                ("origin", .string(v.origin)),
                ("confidence", v.confidence.map { SloJson.number($0) } ?? .null),
                ("edited", .bool(v.edited)),
                ("confirmed", .bool(true))
            ]))
        }

        return .object([
            ("protocol", .string(protocolId)),
            ("handoff_id", .string(handoffId)),
            ("issued_at", .string(SloRfc3339.format(issuedAtEpochSeconds))),
            ("expires_at", .string(SloRfc3339.format(issuedAtEpochSeconds + ttlSeconds))),
            ("document_type", .string(documentType)),
            ("profile", .string(SloProfile.id)),
            ("source", .object(sourceEntries)),
            ("confirmed", .bool(true)),
            ("fields", .object(fieldEntries))
        ])
    }

    public static func canonicalWithoutIntegrity(_ envelope: SloJson) -> String {
        guard case .object(let entries) = envelope else { return SloJson.canonical(envelope) }
        return SloJson.canonical(.object(entries.filter { $0.0 != "integrity" }))
    }

    public static func sign(_ envelope: SloJson, keyId: String, key: Data) -> SloJson {
        guard case .object(var entries) = envelope else { return envelope }
        let mac = hmac(key: key, message: canonicalWithoutIntegrity(envelope))
        entries.removeAll { $0.0 == "integrity" }
        entries.append(("integrity", .object([
            ("alg", .string("HMAC-SHA256")),
            ("key_id", .string(keyId)),
            ("value", .string(base64url(mac)))
        ])))
        return .object(entries)
    }

    /// 受け取り側の検証。プロトコル・確認済みフラグ・失効・正規化の再計算・HMACをすべて確認する。
    public static func verify(
        _ envelope: SloJson,
        key: Data?,
        nowEpochSeconds: Int,
        today: SloDate = SloDate.today()
    ) -> Verification {
        guard envelope["protocol"]?.stringValue == protocolId,
              envelope["profile"]?.stringValue == SloProfile.id
        else { return Verification(ok: false, error: eProtocol, fieldCount: 0) }

        guard envelope["confirmed"]?.boolValue == true else {
            return Verification(ok: false, error: eUnconfirmed, fieldCount: 0)
        }

        guard let expiresText = envelope["expires_at"]?.stringValue,
              let expires = SloRfc3339.parse(expiresText)
        else { return Verification(ok: false, error: eProtocol, fieldCount: 0) }
        if nowEpochSeconds > expires { return Verification(ok: false, error: eExpired, fieldCount: 0) }

        guard let fields = envelope["fields"]?.objectEntries, !fields.isEmpty else {
            return Verification(ok: false, error: eProtocol, fieldCount: 0)
        }

        for (fieldKey, fieldValue) in fields {
            guard fieldValue["confirmed"]?.boolValue == true else {
                return Verification(ok: false, error: eUnconfirmed, fieldCount: 0)
            }
            guard let value = fieldValue["value"]?.stringValue else {
                return Verification(ok: false, error: eProtocol, fieldCount: 0)
            }
            let r = SloNormalizer.normalize(fieldKey, value, today: today)
            if !r.ok || r.value != value {
                return Verification(ok: false, error: eValidation, fieldCount: 0)
            }
        }

        if let key = key {
            guard let integrity = envelope["integrity"],
                  integrity["alg"]?.stringValue == "HMAC-SHA256",
                  let actual = integrity["value"]?.stringValue
            else { return Verification(ok: false, error: eIntegrity, fieldCount: 0) }
            let expected = base64url(hmac(key: key, message: canonicalWithoutIntegrity(envelope)))
            if !constantTimeEquals(expected, actual) {
                return Verification(ok: false, error: eIntegrity, fieldCount: 0)
            }
        }

        return Verification(ok: true, error: nil, fieldCount: fields.count)
    }

    public static func hmac(key: Data, message: String) -> Data {
        let mac = HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: SymmetricKey(data: key))
        return Data(mac)
    }

    public static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let ua = Array(a.utf8)
        let ub = Array(b.utf8)
        if ua.count != ub.count { return false }
        var diff: UInt8 = 0
        for i in ua.indices { diff |= ua[i] ^ ub[i] }
        return diff == 0
    }
}

/// RFC3339(UTC, 秒精度)。端末のタイムゾーン設定に結果が左右されないようUTC固定。
public enum SloRfc3339 {
    static let secondsPerDay = 86400

    public static func format(_ epochSeconds: Int) -> String {
        var days = epochSeconds / secondsPerDay
        var secOfDay = epochSeconds % secondsPerDay
        if secOfDay < 0 {
            secOfDay += secondsPerDay
            days -= 1
        }
        var year = 1970
        while true {
            let len = SloDate.isLeap(year) ? 366 : 365
            if days >= len { days -= len; year += 1 } else { break }
        }
        var month = 1
        while true {
            let dim = SloDate.daysInMonth(year, month)
            if days >= dim { days -= dim; month += 1 } else { break }
        }
        return String(format: "%04d-%02d-%02dT%02d:%02d:%02dZ",
                      year, month, days + 1,
                      secOfDay / 3600, (secOfDay % 3600) / 60, secOfDay % 60)
    }

    public static func parse(_ text: String) -> Int? {
        guard let g = SloNormalizer.capture(
            text, "^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})Z$"
        ) else { return nil }
        let y = Int(g[1]) ?? 0
        var days = 0
        var year = 1970
        while year < y { days += SloDate.isLeap(year) ? 366 : 365; year += 1 }
        let month = Int(g[2]) ?? 1
        if month > 1 {
            for m in 1..<month { days += SloDate.daysInMonth(y, m) }
        }
        days += (Int(g[3]) ?? 1) - 1
        return days * secondsPerDay + (Int(g[4]) ?? 0) * 3600 + (Int(g[5]) ?? 0) * 60 + (Int(g[6]) ?? 0)
    }
}
