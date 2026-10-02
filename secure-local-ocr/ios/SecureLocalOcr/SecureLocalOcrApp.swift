import SwiftUI
import VisionKit
import SloCore

/// アプリ入口。
///
/// 意図的に「何もしない」ことが重要な場所でもある。
/// 解析SDK・広告SDK・クラッシュレポータ・外部ログ収集は一切組み込まない（企画書 5.1, 15）。
@main
struct SecureLocalOcrApp: App {
    @StateObject private var session = SessionModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var backgroundedAt: Date?

    private let audit = AuditFileLog()

    var body: some Scene {
        WindowGroup {
            RootView(session: session, audit: audit)
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .background, .inactive:
                        backgroundedAt = Date()
                    case .active:
                        // 一定時間経過後の再認証（企画書 15）
                        if let at = backgroundedAt,
                           Date().timeIntervalSince(at) > AppLock.reauthAfterSeconds,
                           session.step != .locked {
                            session.discard()
                            session.step = .locked
                            audit.add(.sessionEnded, ["reason": "reauth_required"])
                        }
                        backgroundedAt = nil
                    @unknown default:
                        break
                    }
                }
        }
    }
}

struct RootView: View {
    @ObservedObject var session: SessionModel
    let audit: AuditFileLog

    /// 登録先の業務Webサイト。実運用ではMDM等で配布する構成から読み込む。
    private let targetUrl = URL(string: "https://form.example.co.jp/registration/")!
    private let allowedOrigins = ["https://form.example.co.jp"]

    @State private var lockMessage: String?
    @State private var handoffSession: HandoffSession?
    @State private var bridge: WebHandoffBridge?
    @State private var showScanner = false

    var body: some View {
        Group {
            switch session.step {
            case .locked, .done:
                LockView(message: lockMessage ?? (session.step == .done
                    ? "セッションを終了しました。データは破棄済みです。" : nil)) {
                    unlock()
                }

            case .capture, .ocr:
                CaptureView(session: session, audit: audit)

            case .review:
                ReviewView(session: session,
                           onProceed: { startHandoff() },
                           onRetake: { session.discard(); session.step = .capture },
                           onDiscard: { endSession("discarded_by_user") })

            case .handoff:
                HandoffView(session: session,
                            bridge: bridge,
                            targetUrl: targetUrl,
                            onDeliver: { deliver() },
                            onFinish: { endSession("finished_by_user") })
            }
        }
        // スクリーンショット抑止はiOSでは完全には行えないため、
        // 少なくともアプリスイッチャーでの内容表示を隠す（docs/security-design.html 参照）
        .privacySensitive()
    }

    private func unlock() {
        guard AppLock.availability() == .available else {
            lockMessage = "端末に画面ロックを設定してください。"
            return
        }
        AppLock.authenticate(reason: "業務データを扱うため認証してください") { ok, code in
            if ok {
                audit.add(.appUnlocked)
                lockMessage = nil
                session.step = .capture
            } else {
                lockMessage = "認証できませんでした（\(code ?? "E_AUTH")）"
            }
        }
    }

    private func startHandoff() {
        audit.add(.userConfirmed, SloAuditLog.fieldKeysAttribute(session.confirmedKeys()))
        let s = HandoffSession(documentType: session.documentType,
                               appName: "SecureLocalOCR-iOS",
                               appVersion: "0.1.0")
        handoffSession = s
        bridge = WebHandoffBridge(
            session: s,
            allowedOrigins: allowedOrigins,
            onReady: { fields in
                audit.add(.handoffRequested, ["fields": String(fields.count)])
                session.statusMessage = "登録先の準備ができました。内容を確認して「この内容を入力する」を押してください。"
            },
            onFilled: { count in
                audit.add(.formFilled, ["filled": String(count)])
                session.statusMessage = "\(count)項目を入力しました。登録ボタンはご自身で押してください。"
            },
            onRejected: { reason in
                audit.add(.handoffRejected, ["reason": reason])
                session.statusMessage = "登録先が受け取りを拒否しました（\(reason)）"
            },
            onOriginDenied: {
                audit.add(.originDenied, ["reason": "E_ORIGIN"])
                session.statusMessage = "許可されていないサイトです。値は渡していません。"
            }
        )
        session.step = .handoff
    }

    /// 人間が「この内容を入力する」を押したときにだけ、値がWebViewへ渡る。
    private func deliver() {
        guard let s = handoffSession, let b = bridge else { return }
        let fields = session.confirmedFields()
        guard !fields.isEmpty else {
            session.statusMessage = "確認済みの項目がありません。"
            return
        }
        let envelope = s.buildSignedEnvelope(fields: fields, offlineCapture: session.offlineCapture)
        audit.add(.handoffDelivered, [
            "fields": String(fields.count),
            "handoff_id": envelope["handoff_id"]?.stringValue ?? "none"
        ])
        b.deliver(envelope)
    }

    private func endSession(_ reason: String) {
        bridge?.detach()
        bridge = nil
        handoffSession?.destroy()
        handoffSession = nil
        session.discard()
        session.step = .done
        audit.add(.sessionEnded, ["reason": reason])
        audit.add(.imageDiscarded, ["result": "ok"])
    }
}

struct LockView: View {
    let message: String?
    let onUnlock: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text("Secure Local OCR").font(.title2).bold()
            Text("個人情報は端末内だけで処理します").font(.footnote).foregroundStyle(.secondary)
            Button("認証して開始", action: onUnlock).buttonStyle(.borderedProminent).padding(.top, 12)
            if let message {
                Text(message).font(.footnote).foregroundStyle(.red)
            }
        }
        .padding(24)
    }
}
