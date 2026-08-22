package jp.slo.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import jp.slo.core.Envelope
import jp.slo.core.Extractor
import jp.slo.core.Normalizer
import jp.slo.core.Profile

/**
 * 1件分の取り込みセッションの状態。
 *
 * 値はすべてメモリ上のみに保持し、画面を抜けるとき・再認証が必要になったときに破棄する
 * （企画書 11「保存しない設計を優先する」）。ディスクへは書かない。
 */
class SessionViewModel : ViewModel() {

    enum class Step { LOCKED, CAPTURE, OCR, REVIEW, HANDOFF, DONE, VERIFY }

    data class EditableField(
        val key: String,
        val label: String,
        var input: String,
        val raw: String,
        val confidence: Double,
        val origin: String,
        val valid: Boolean,
        val error: String?,
        val edited: Boolean,
        val confirmed: Boolean
    ) {
        val needsReview: Boolean get() = !valid || confidence < 0.80
    }

    var step by mutableStateOf(Step.LOCKED)
        private set

    var documentType by mutableStateOf("residency_application")

    var statusMessage by mutableStateOf<String?>(null)

    var offlineCapture by mutableStateOf(true)
        private set

    var lastOcrElapsedMillis by mutableStateOf(0L)
        private set

    var ocrLineCount by mutableStateOf(0)
        private set

    val fields = mutableStateListOf<EditableField>()

    fun goTo(next: Step) {
        step = next
    }

    fun markOfflineCapture(offline: Boolean) {
        offlineCapture = offline
    }

    /** OCR結果の項目候補を取り込む。この時点ではどれも未確認。 */
    fun loadExtracted(extracted: Map<String, Extractor.Field>, elapsedMillis: Long, lineCount: Int) {
        fields.clear()
        lastOcrElapsedMillis = elapsedMillis
        ocrLineCount = lineCount
        for (key in Profile.KEYS) {
            val f = extracted[key] ?: continue
            fields.add(
                EditableField(
                    key = key,
                    label = Profile.label(key),
                    input = if (f.valid) f.value else f.raw,
                    raw = f.raw,
                    confidence = f.confidence,
                    origin = f.origin,
                    valid = f.valid,
                    error = f.error,
                    edited = false,
                    confirmed = false
                )
            )
        }
        // 必須なのに読み取れなかった項目は、空欄として並べて手入力を促す
        for (key in Profile.requiredFor(documentType)) {
            if (fields.none { it.key == key }) {
                fields.add(
                    EditableField(key, Profile.label(key), "", "", 0.0, "manual", false, "E_MISSING", false, false)
                )
            }
        }
    }

    /** 人間が値を直したときの再検証。判定規則は登録先と共通（INV-6）。 */
    fun edit(index: Int, newValue: String) {
        val f = fields[index]
        val r = Normalizer.normalize(f.key, newValue)
        fields[index] = f.copy(
            input = newValue,
            valid = r.ok,
            error = r.error,
            edited = true,
            origin = if (f.origin == "ocr") "ocr" else "manual",
            confirmed = false
        )
    }

    fun setConfirmed(index: Int, confirmed: Boolean) {
        fields[index] = fields[index].copy(confirmed = confirmed)
    }

    fun confirmAllValid() {
        for (i in fields.indices) {
            if (fields[i].valid && fields[i].input.isNotBlank()) setConfirmed(i, true)
        }
    }

    /** 必須項目のうち、確認済みの値が揃っていないもの。 */
    fun missingRequired(): List<String> =
        Profile.requiredFor(documentType).filter { key ->
            val f = fields.firstOrNull { it.key == key }
            f == null || !f.valid || f.input.isBlank() || !f.confirmed
        }

    fun canHandoff(): Boolean =
        fields.any { it.confirmed } &&
                fields.none { it.confirmed && !it.valid } &&
                missingRequired().isEmpty()

    /** Envelopeに載せる確認済み項目だけを取り出す。 */
    fun confirmedFields(): Map<String, Envelope.FieldValue> {
        val out = LinkedHashMap<String, Envelope.FieldValue>()
        for (f in fields) {
            if (!f.confirmed || !f.valid || f.input.isBlank()) continue
            val normalized = Normalizer.normalize(f.key, f.input)
            if (!normalized.ok) continue
            out[f.key] = Envelope.FieldValue(
                value = normalized.value!!,
                origin = if (f.origin == "ocr" && !f.edited) "ocr" else if (f.origin == "ocr") "ocr" else "manual",
                confidence = if (f.origin == "ocr") f.confidence else null,
                edited = f.edited
            )
        }
        return out
    }

    fun confirmedKeys(): List<String> = confirmedFields().keys.toList()

    /** セッション終了。項目値をメモリから落とす。 */
    fun discard() {
        fields.clear()
        statusMessage = null
        lastOcrElapsedMillis = 0
        ocrLineCount = 0
        step = Step.CAPTURE
    }
}
