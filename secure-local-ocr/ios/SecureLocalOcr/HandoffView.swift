import SwiftUI
import SloCore

/// Web入力支援画面（企画書 10）。
///
/// 充填するところまでが上限で、送信ボタンは押さない（INV-4）。
struct HandoffView: View {
    @ObservedObject var session: SessionModel
    let bridge: WebHandoffBridge?
    let targetUrl: URL
    let onDeliver: () -> Void
    let onFinish: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Web入力").font(.title3).bold()
            Text("確認済みの項目を登録先の入力欄へ充填します。送信は画面上のボタンをご自身で押してください。")
                .font(.footnote).foregroundStyle(.secondary)

            if let message = session.statusMessage {
                Text(message).font(.footnote).foregroundStyle(.blue)
            }

            HStack {
                Button("この内容を入力する", action: onDeliver)
                    .buttonStyle(.borderedProminent)
                    .disabled(!session.canHandoff)
                Button("終了して破棄", action: onFinish)
            }

            if let bridge {
                HandoffWebView(bridge: bridge, url: targetUrl)
            } else {
                // 通信を使わない運用（Level 1 / 2）。項目ごとにコピーして手入力する。
                List {
                    ForEach(session.fields.filter { $0.confirmed && $0.valid }) { field in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(field.label).bold()
                                Text(field.input)
                            }
                            Spacer()
                            Button("コピー") {
                                UIPasteboard.general.setItems(
                                    [[UIPasteboard.typeAutomatic: field.input]],
                                    options: [.localOnly: true,
                                              .expirationDate: Date().addingTimeInterval(60)]
                                )
                            }
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .padding(16)
    }
}
