package jp.slo.android.handoff

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import jp.slo.core.Json
import jp.slo.core.JsonValue
import jp.slo.core.Profile

/**
 * WebView と登録先ページの間のブリッジ（Transport T3 / SPEC.md 7.1）。
 *
 * ページ側は slo-bridge.js を読み込むだけでよい。ネイティブ側の責務は
 *   1. オリジン許可リストの検証
 *   2. セッション鍵の受け渡し
 *   3. 人間が「入力する」を押した後にだけ Envelope を注入すること
 *   4. 送信ボタンは絶対に押さないこと（INV-4）
 * の4つ。
 */
class SloWebViewBridge(
    private val session: HandoffSession,
    private val allowedOrigins: List<String>,
    private val onReady: (fields: List<String>) -> Unit,
    private val onFilled: (fieldCount: Int) -> Unit,
    private val onRejected: (reason: String) -> Unit,
    private val onOriginDenied: () -> Unit
) {

    companion object {
        const val JS_INTERFACE_NAME = "SLOHost"
        const val E_ORIGIN = "E_ORIGIN"
        const val E_PROTOCOL = "E_PROTOCOL"
        const val E_NONCE = "E_NONCE"
    }

    private var webView: WebView? = null

    /** ページ現在のオリジンが許可リストに含まれるか。 */
    fun isOriginAllowed(url: String?): Boolean {
        if (url == null) return false
        val origin = runCatching {
            val u = java.net.URI(url)
            val port = if (u.port == -1) "" else ":${u.port}"
            "${u.scheme}://${u.host}$port"
        }.getOrNull() ?: return false
        return allowedOrigins.contains(origin)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(view: WebView) {
        webView = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = false
        view.settings.databaseEnabled = false
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        view.settings.saveFormData = false
        view.addJavascriptInterface(this, JS_INTERFACE_NAME)
    }

    fun detach() {
        webView?.removeJavascriptInterface(JS_INTERFACE_NAME)
        webView = null
    }

    /** ページ（slo-bridge.js）からのメッセージ受信。 */
    @JavascriptInterface
    fun postMessage(json: String) {
        val view = webView ?: return
        view.post {
            if (!isOriginAllowed(view.url)) {
                onOriginDenied()
                return@post
            }
            val message = runCatching { Json.parse(json).asObj }.getOrNull()
            if (message == null) {
                onRejected(E_PROTOCOL)
                return@post
            }
            when (message["type"]?.asString) {
                "slo.ready" -> handleReady(message)
                "slo.filled" -> onFilled((message["field_count"]?.asDouble ?: 0.0).toInt())
                "slo.rejected" -> onRejected(message["reason"]?.asString ?: E_PROTOCOL)
            }
        }
    }

    private fun handleReady(message: JsonValue.Obj) {
        if (message["profile"]?.asString != Profile.ID) {
            onRejected(E_PROTOCOL)
            return
        }
        val nonce = message["nonce"]?.asString
        if (nonce == null || !session.acceptNonce(nonce)) {
            onRejected(E_NONCE)
            return
        }
        // セッション鍵を渡す。ここまでは値を一切渡していない。
        evaluate(
            "window.SLO._session(${
                Json.canonical(JsonValue.Obj().apply {
                    this["key_id"] = JsonValue.Str(session.keyId)
                    this["key_hex"] = JsonValue.Str(session.keyHex())
                    this["nonce"] = JsonValue.Str(nonce)
                })
            })"
        )
        val fields = message["fields"]?.asArr?.items?.mapNotNull { it.asString } ?: emptyList()
        onReady(fields)
    }

    /**
     * 人間が確認画面で「入力する」を押したときにだけ呼ばれる。
     * ここが Human-in-the-loop の境界（企画書 9）。
     */
    fun deliver(envelope: JsonValue.Obj) {
        evaluate("window.SLO._deliver(${Json.canonical(envelope)})")
    }

    private fun evaluate(script: String) {
        webView?.evaluateJavascript(script, null)
    }
}
