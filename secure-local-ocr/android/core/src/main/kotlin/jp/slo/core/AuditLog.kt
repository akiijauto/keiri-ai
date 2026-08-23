package jp.slo.core

/**
 * 監査ログ（企画書 13, SPEC.md 10）。
 *
 * INV-5: 個人情報そのものを絶対に書かない。書けるのは
 *  - イベント種別
 *  - 項目「キー」（値ではない）
 *  - 件数・結果コード
 * のみ。値を渡そうとした呼び出しは実行時に弾く。
 */
object AuditLog {

    enum class Event {
        APP_UNLOCKED,
        CAPTURE_STARTED,
        OCR_START,
        OCR_SUCCESS,
        OCR_FAILED,
        EXTRACT_DONE,
        FIELD_EDITED,
        USER_CONFIRMED,
        HANDOFF_REQUESTED,
        HANDOFF_DELIVERED,
        HANDOFF_VERIFIED,
        HANDOFF_REJECTED,
        FORM_FILLED,
        SUBMIT_BY_HUMAN,
        IMAGE_DISCARDED,
        SESSION_ENDED,
        ORIGIN_DENIED
    }

    data class Entry(
        val timestamp: String,
        val event: Event,
        val attributes: Map<String, String>
    ) {
        fun format(): String {
            val attrs = attributes.entries.joinToString(" ") { "${it.key}=${it.value}" }
            return if (attrs.isEmpty()) "$timestamp\t$event" else "$timestamp\t$event\t$attrs"
        }
    }

    /** 値らしき文字列が属性に混入していないかの実行時チェック。 */
    private val FORBIDDEN_VALUE_PATTERNS = listOf(
        Regex("[０-９0-9]{7,}"),                       // 電話番号・郵便番号・顧客番号の連番
        Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),          // メールアドレス
        Regex("[一-龠]{2,}[\\s\\u3000][一-龠]{1,}"),    // 氏名らしき漢字の並び
        Regex("[ぁ-んァ-ヶ]{4,}")                       // かな氏名・住所の一部
    )

    private val ALLOWED_ATTR_KEYS = setOf(
        "profile", "document_type", "fields", "field", "filled", "skipped", "guessed",
        "result", "reason", "handoff_id", "origin", "engine", "offline", "count", "elapsed_ms"
    )

    class PiiInLogException(message: String) : IllegalArgumentException(message)

    fun entry(timestamp: String, event: Event, attributes: Map<String, String> = emptyMap()): Entry {
        for ((k, v) in attributes) {
            if (k !in ALLOWED_ATTR_KEYS) {
                throw PiiInLogException("監査ログに許可されていない属性キー: $k")
            }
            // handoff_id はUUIDなので数字連続チェックの対象外にする
            if (k == "handoff_id") continue
            for (p in FORBIDDEN_VALUE_PATTERNS) {
                if (p.containsMatchIn(v)) {
                    throw PiiInLogException("監査ログに個人情報らしき値が含まれています: key=$k")
                }
            }
        }
        return Entry(timestamp, event, attributes)
    }

    /** 項目キーだけを列挙する。値は決して渡さない。 */
    fun fieldKeysAttribute(keys: Collection<String>): Map<String, String> =
        mapOf("field" to keys.sorted().joinToString(","), "count" to keys.size.toString())
}
