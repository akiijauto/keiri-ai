import Foundation

/// 監査ログ（企画書 13 / SPEC.md 10）。
///
/// INV-5: 個人情報そのものを絶対に書かない。書けるのは
/// イベント種別・項目キー・件数・結果コードのみ。
/// 値を渡そうとした呼び出しは実行時に弾く。
public enum SloAuditEvent: String {
    case appUnlocked = "APP_UNLOCKED"
    case captureStarted = "CAPTURE_STARTED"
    case ocrStart = "OCR_START"
    case ocrSuccess = "OCR_SUCCESS"
    case ocrFailed = "OCR_FAILED"
    case extractDone = "EXTRACT_DONE"
    case fieldEdited = "FIELD_EDITED"
    case userConfirmed = "USER_CONFIRMED"
    case handoffRequested = "HANDOFF_REQUESTED"
    case handoffDelivered = "HANDOFF_DELIVERED"
    case handoffVerified = "HANDOFF_VERIFIED"
    case handoffRejected = "HANDOFF_REJECTED"
    case formFilled = "FORM_FILLED"
    case submitByHuman = "SUBMIT_BY_HUMAN"
    case imageDiscarded = "IMAGE_DISCARDED"
    case sessionEnded = "SESSION_ENDED"
    case originDenied = "ORIGIN_DENIED"
}

public struct SloAuditEntry {
    public let timestamp: String
    public let event: SloAuditEvent
    public let attributes: [String: String]

    public func format() -> String {
        let attrs = attributes.keys.sorted().map { "\($0)=\(attributes[$0]!)" }.joined(separator: " ")
        return attrs.isEmpty ? "\(timestamp)\t\(event.rawValue)" : "\(timestamp)\t\(event.rawValue)\t\(attrs)"
    }
}

public struct SloPiiInLogError: Error {
    public let message: String
}

public enum SloAuditLog {

    static let allowedAttributeKeys: Set<String> = [
        "profile", "document_type", "fields", "field", "filled", "skipped", "guessed",
        "result", "reason", "handoff_id", "origin", "engine", "offline", "count", "elapsed_ms"
    ]

    static let forbiddenValuePatterns = [
        "[０-９0-9]{7,}",                       // 電話番号・郵便番号などの連番
        "[^\\s@]+@[^\\s@]+\\.[^\\s@]+",         // メールアドレス
        "[一-龠]{2,}[\\s\\u{3000}][一-龠]{1,}",  // 氏名らしき漢字の並び
        "[ぁ-んァ-ヶ]{4,}"                       // かな氏名・住所の一部
    ]

    public static func entry(
        timestamp: String,
        event: SloAuditEvent,
        attributes: [String: String] = [:]
    ) throws -> SloAuditEntry {
        for (k, v) in attributes {
            guard allowedAttributeKeys.contains(k) else {
                throw SloPiiInLogError(message: "監査ログに許可されていない属性キー: \(k)")
            }
            if k == "handoff_id" { continue } // UUIDは数字連続チェックの対象外
            for pattern in forbiddenValuePatterns where SloNormalizer.matches(v, pattern) {
                throw SloPiiInLogError(message: "監査ログに個人情報らしき値: key=\(k)")
            }
        }
        return SloAuditEntry(timestamp: timestamp, event: event, attributes: attributes)
    }

    /// 項目キーだけを列挙する。値は決して渡さない。
    public static func fieldKeysAttribute(_ keys: [String]) -> [String: String] {
        ["field": keys.sorted().joined(separator: ","), "count": String(keys.count)]
    }
}
