import Foundation
import SwiftUI
import SloCore

/// 1件分の取り込みセッションの状態（Android の SessionViewModel と同じ役割）。
///
/// 値はメモリ上のみに保持し、画面を抜けるとき・再認証が必要になったときに破棄する。
@MainActor
final class SessionModel: ObservableObject {

    enum Step { case locked, capture, ocr, review, handoff, done }

    struct EditableField: Identifiable {
        let id = UUID()
        let key: String
        let label: String
        var input: String
        let raw: String
        let confidence: Double
        var origin: String
        var valid: Bool
        var error: String?
        var edited: Bool
        var confirmed: Bool

        var needsReview: Bool { !valid || confidence < 0.80 }
    }

    @Published var step: Step = .locked
    @Published var documentType = "residency_application"
    @Published var statusMessage: String?
    @Published var fields: [EditableField] = []
    @Published private(set) var offlineCapture = true
    @Published private(set) var ocrElapsedMillis = 0
    @Published private(set) var ocrLineCount = 0

    func markOfflineCapture(_ offline: Bool) { offlineCapture = offline }

    /// OCR結果の項目候補を取り込む。この時点ではどれも未確認。
    func loadExtracted(_ extracted: [String: SloExtractor.Field], elapsedMillis: Int, lineCount: Int) {
        fields.removeAll()
        ocrElapsedMillis = elapsedMillis
        ocrLineCount = lineCount

        for key in SloProfile.keys {
            guard let f = extracted[key] else { continue }
            fields.append(EditableField(
                key: key, label: SloProfile.label(key),
                input: f.valid ? f.value : f.raw, raw: f.raw,
                confidence: f.confidence, origin: f.origin,
                valid: f.valid, error: f.error, edited: false, confirmed: false
            ))
        }
        // 必須なのに読み取れなかった項目は空欄として並べ、手入力を促す
        for key in SloProfile.requiredFor(documentType) where !fields.contains(where: { $0.key == key }) {
            fields.append(EditableField(
                key: key, label: SloProfile.label(key), input: "", raw: "",
                confidence: 0, origin: "manual", valid: false, error: "E_MISSING",
                edited: false, confirmed: false
            ))
        }
    }

    /// 人間が値を直したときの再検証。判定規則は登録先と共通（INV-6）。
    func edit(_ index: Int, _ newValue: String) {
        guard fields.indices.contains(index) else { return }
        let r = SloNormalizer.normalize(fields[index].key, newValue)
        fields[index].input = newValue
        fields[index].valid = r.ok
        fields[index].error = r.error
        fields[index].edited = true
        fields[index].confirmed = false
    }

    func setConfirmed(_ index: Int, _ confirmed: Bool) {
        guard fields.indices.contains(index) else { return }
        fields[index].confirmed = confirmed
    }

    func missingRequired() -> [String] {
        SloProfile.requiredFor(documentType).filter { key in
            guard let f = fields.first(where: { $0.key == key }) else { return true }
            return !f.valid || f.input.isEmpty || !f.confirmed
        }
    }

    var canHandoff: Bool {
        fields.contains { $0.confirmed }
            && !fields.contains { $0.confirmed && !$0.valid }
            && missingRequired().isEmpty
    }

    /// Envelopeに載せる確認済み項目だけを取り出す。
    func confirmedFields() -> [(String, SloEnvelope.FieldValue)] {
        var out: [(String, SloEnvelope.FieldValue)] = []
        for key in SloProfile.keys {
            guard let f = fields.first(where: { $0.key == key }),
                  f.confirmed, f.valid, !f.input.isEmpty else { continue }
            let r = SloNormalizer.normalize(key, f.input)
            guard r.ok, let value = r.value else { continue }
            out.append((key, SloEnvelope.FieldValue(
                value: value,
                origin: f.origin == "ocr" ? "ocr" : "manual",
                confidence: f.origin == "ocr" ? f.confidence : nil,
                edited: f.edited
            )))
        }
        return out
    }

    func confirmedKeys() -> [String] { confirmedFields().map(\.0) }

    /// セッション終了。項目値をメモリから落とす。
    func discard() {
        fields.removeAll()
        statusMessage = nil
        ocrElapsedMillis = 0
        ocrLineCount = 0
    }
}
