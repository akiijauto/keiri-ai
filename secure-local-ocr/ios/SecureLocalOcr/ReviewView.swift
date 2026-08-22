import SwiftUI
import SloCore

/// OCR結果の確認・修正画面（企画書 Step 5 / 9 Human-in-the-loop）。
///
/// ここを通らずにWeb入力へ進む経路は存在しない。
/// 各項目の「確認」を人間が付けて初めて、その値が引き渡し対象になる（INV-1）。
struct ReviewView: View {
    @ObservedObject var session: SessionModel
    let onProceed: () -> Void
    let onRetake: () -> Void
    let onDiscard: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("OCR結果の確認").font(.title3).bold()
            Text("読み取り結果は候補です。1項目ずつ内容を確かめて「確認」を付けてください。")
                .font(.footnote).foregroundStyle(.secondary)
            Text("認識 \(session.ocrLineCount)行 / \(session.ocrElapsedMillis)ms"
                 + (session.offlineCapture ? " / 機内モードで取込済み" : ""))
                .font(.caption2).foregroundStyle(.secondary)

            let missing = session.missingRequired()
            if !missing.isEmpty {
                Text("未確認の必須項目: " + missing.map { SloProfile.label($0) }.joined(separator: "、"))
                    .font(.footnote).foregroundStyle(.orange)
            }

            List {
                ForEach(Array(session.fields.enumerated()), id: \.element.id) { index, field in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(field.label).bold()
                            Text(confidenceLabel(field))
                                .font(.caption2)
                                .foregroundStyle(field.needsReview ? .red : .secondary)
                        }

                        TextField(field.label, text: Binding(
                            get: { session.fields[index].input },
                            set: { session.edit(index, $0) }
                        ))
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()

                        if !field.valid, let error = field.error {
                            Text(errorMessage(error)).font(.caption2).foregroundStyle(.red)
                        }
                        if !field.raw.isEmpty && field.raw != field.input {
                            Text("読み取り: \(field.raw)").font(.caption2).foregroundStyle(.secondary)
                        }

                        Toggle("この内容で確認しました", isOn: Binding(
                            get: { session.fields[index].confirmed },
                            set: { session.setConfirmed(index, $0) }
                        ))
                        .disabled(!field.valid || field.input.isEmpty)
                        .font(.footnote)
                    }
                    .padding(.vertical, 4)
                }
            }
            .listStyle(.plain)

            HStack {
                Button("Web入力へ進む", action: onProceed)
                    .buttonStyle(.borderedProminent)
                    .disabled(!session.canHandoff)
                Button("撮り直す", action: onRetake)
                Button("破棄", action: onDiscard)
            }
        }
        .padding(16)
    }

    private func confidenceLabel(_ field: SessionModel.EditableField) -> String {
        if field.origin == "manual" { return "手入力" }
        if !field.valid { return "要修正" }
        if field.confidence >= 0.85 { return "信頼度 高" }
        if field.confidence >= 0.70 { return "信頼度 中 — 要確認" }
        return "信頼度 低 — 要確認"
    }

    private func errorMessage(_ code: String) -> String {
        switch code {
        case "E_VALIDATION": return "形式が正しくありません。内容を確認してください。"
        case "E_PARSE": return "読み取れませんでした。手入力してください。"
        case "E_MISSING": return "必須項目です。入力してください。"
        default: return "確認が必要です（\(code)）"
        }
    }
}
