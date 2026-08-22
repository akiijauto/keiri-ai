import Foundation
import WebKit
import SwiftUI
import SloCore

/// WKWebView と登録先ページの間のブリッジ（Transport T3 / SPEC.md 7.1）。
///
/// ページ側は Android と同じ slo-bridge.js を読み込むだけでよい。
/// ネイティブ側の責務は、オリジン検証・セッション鍵の受け渡し・
/// 人間が押した後にだけEnvelopeを注入すること・送信ボタンを押さないこと。
final class WebHandoffBridge: NSObject, WKScriptMessageHandler, WKNavigationDelegate {

    static let messageHandlerName = "slo"

    private let session: HandoffSession
    private let allowedOrigins: [String]
    private let onReady: ([String]) -> Void
    private let onFilled: (Int) -> Void
    private let onRejected: (String) -> Void
    private let onOriginDenied: () -> Void

    weak var webView: WKWebView?

    init(session: HandoffSession,
         allowedOrigins: [String],
         onReady: @escaping ([String]) -> Void,
         onFilled: @escaping (Int) -> Void,
         onRejected: @escaping (String) -> Void,
         onOriginDenied: @escaping () -> Void) {
        self.session = session
        self.allowedOrigins = allowedOrigins
        self.onReady = onReady
        self.onFilled = onFilled
        self.onRejected = onRejected
        self.onOriginDenied = onOriginDenied
    }

    func makeWebView() -> WKWebView {
        let controller = WKUserContentController()
        controller.add(self, name: Self.messageHandlerName)

        let config = WKWebViewConfiguration()
        config.userContentController = controller
        // 個人情報が入るページの痕跡を端末に残さない
        config.websiteDataStore = .nonPersistent()

        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = self
        webView = view
        return view
    }

    /// ページ現在のオリジンが許可リストに含まれるか。
    func isOriginAllowed(_ url: URL?) -> Bool {
        guard let url = url, let scheme = url.scheme, let host = url.host else { return false }
        let port = url.port.map { ":\($0)" } ?? ""
        return allowedOrigins.contains("\(scheme)://\(host)\(port)")
    }

    /// 許可リストにないURLへは遷移させない（企画書 7）。
    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if isOriginAllowed(navigationAction.request.url) {
            decisionHandler(.allow)
        } else {
            onOriginDenied()
            decisionHandler(.cancel)
        }
    }

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard isOriginAllowed(webView?.url) else {
            onOriginDenied()
            return
        }
        guard let body = message.body as? [String: Any],
              let type = body["type"] as? String else {
            onRejected(SloEnvelope.eProtocol)
            return
        }

        switch type {
        case "slo.ready":
            handleReady(body)
        case "slo.filled":
            onFilled((body["field_count"] as? Int) ?? 0)
        case "slo.rejected":
            onRejected((body["reason"] as? String) ?? SloEnvelope.eProtocol)
        default:
            break
        }
    }

    private func handleReady(_ body: [String: Any]) {
        guard body["profile"] as? String == SloProfile.id else {
            onRejected(SloEnvelope.eProtocol)
            return
        }
        guard let nonce = body["nonce"] as? String, session.acceptNonce(nonce) else {
            onRejected("E_NONCE")
            return
        }
        // セッション鍵を渡す。ここまでは値を一切渡していない。
        let payload = SloJson.object([
            ("key_id", .string(session.keyId)),
            ("key_hex", .string(session.keyHex)),
            ("nonce", .string(nonce))
        ])
        evaluate("window.SLO._session(\(SloJson.canonical(payload)))")
        onReady((body["fields"] as? [String]) ?? [])
    }

    /// 人間が確認画面で「入力する」を押したときにだけ呼ばれる。
    func deliver(_ envelope: SloJson) {
        evaluate("window.SLO._deliver(\(SloJson.canonical(envelope)))")
    }

    private func evaluate(_ script: String) {
        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript(script, completionHandler: nil)
        }
    }

    func detach() {
        webView?.configuration.userContentController
            .removeScriptMessageHandler(forName: Self.messageHandlerName)
        webView = nil
    }
}

/// 1回の引き渡しに対応するセッション（SPEC.md 7.1）。
/// セッション鍵は端末のメモリ上だけに存在し、外部へ送信しない。
final class HandoffSession {

    let documentType: String
    let appName: String
    let appVersion: String
    private(set) var sessionKey: Data
    let keyId: String
    private(set) var nonce: String?
    private var nonceConsumed = false

    init(documentType: String, appName: String, appVersion: String) {
        self.documentType = documentType
        self.appName = appName
        self.appVersion = appVersion
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        self.sessionKey = Data(bytes)
        self.keyId = "session:" + bytes.prefix(4).map { String(format: "%02x", $0) }.joined()
    }

    var keyHex: String {
        sessionKey.map { String(format: "%02x", $0) }.joined()
    }

    func acceptNonce(_ value: String) -> Bool {
        if nonceConsumed { return false }
        nonce = value
        nonceConsumed = true
        return true
    }

    /// 人間が確認した項目からEnvelopeを作り、署名する。
    func buildSignedEnvelope(fields: [(String, SloEnvelope.FieldValue)],
                            offlineCapture: Bool,
                            engine: String = "apple-vision") -> SloJson {
        let envelope = SloEnvelope.build(
            handoffId: UUID().uuidString.lowercased(),
            documentType: documentType,
            source: SloEnvelope.Source(kind: "ondevice-ocr", app: appName, version: appVersion,
                                       engine: engine, offlineCapture: offlineCapture),
            fields: fields,
            issuedAtEpochSeconds: Int(Date().timeIntervalSince1970)
        )
        return SloEnvelope.sign(envelope, keyId: keyId, key: sessionKey)
    }

    /// 引き渡し完了後に鍵の痕跡を消す（企画書 11）。
    func destroy() {
        sessionKey = Data(repeating: 0, count: sessionKey.count)
        nonce = nil
    }
}

/// SwiftUI から WKWebView を使うためのラッパ。
struct HandoffWebView: UIViewRepresentable {
    let bridge: WebHandoffBridge
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let view = bridge.makeWebView()
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
