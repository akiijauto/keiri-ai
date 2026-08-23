package jp.slo.android.handoff

import jp.slo.core.Envelope
import jp.slo.core.JsonValue
import jp.slo.core.Json
import java.security.SecureRandom
import java.util.UUID

/**
 * 1回の引き渡しに対応するセッション（SPEC.md 7.1）。
 *
 * セッション鍵は端末のメモリ上だけに存在し、外部へ送信しない。
 * 目的は秘匿ではなく「同一端末内での取り違え・改ざん・リプレイの検知」。
 */
class HandoffSession(
    val documentType: String,
    val appName: String,
    val appVersion: String,
    private val random: SecureRandom = SecureRandom()
) {

    val sessionKey: ByteArray = ByteArray(32).also { random.nextBytes(it) }
    val keyId: String = "session:" + sessionKey.take(4).joinToString("") { "%02x".format(it) }

    /** ページ側が生成した nonce。使い回しを防ぐため1回だけ受け付ける。 */
    var nonce: String? = null
        private set

    private var nonceConsumed = false
    private var deliveredHandoffId: String? = null

    fun acceptNonce(value: String): Boolean {
        if (nonceConsumed) return false
        nonce = value
        nonceConsumed = true
        return true
    }

    fun keyHex(): String = sessionKey.joinToString("") { "%02x".format(it) }

    /**
     * 人間が確認した項目からEnvelopeを作り、署名する。
     * 未確認の項目は呼び出し側で除外しておくこと（INV-1）。
     */
    fun buildSignedEnvelope(
        fields: Map<String, Envelope.FieldValue>,
        offlineCapture: Boolean,
        engine: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000
    ): JsonValue.Obj {
        val handoffId = UUID.randomUUID().toString()
        deliveredHandoffId = handoffId
        val envelope = Envelope.build(
            handoffId = handoffId,
            documentType = documentType,
            source = Envelope.Source(
                kind = "ondevice-ocr",
                app = appName,
                version = appVersion,
                engine = engine,
                offlineCapture = offlineCapture
            ),
            fields = fields,
            issuedAtEpochSeconds = nowEpochSeconds
        )
        return Envelope.sign(envelope, keyId, sessionKey)
    }

    fun envelopeJson(envelope: JsonValue.Obj): String = Json.canonical(envelope)

    /** 引き渡し完了後に鍵と値の痕跡を消す（企画書 11）。 */
    fun destroy() {
        sessionKey.fill(0)
        nonce = null
        deliveredHandoffId = null
    }
}
