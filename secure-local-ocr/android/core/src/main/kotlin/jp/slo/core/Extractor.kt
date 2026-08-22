package jp.slo.core

/**
 * OCR結果（行の並び）から業務項目を抽出する（企画書 Step 4）。
 *
 * 完全に端末内で動く決定的なルールベース処理であり、外部APIも生成AIも使わない。
 * 抽出そのものは「候補の提示」であって確定ではない。確定は必ず人間が行う（INV-1）。
 */
object Extractor {

    /** OCRの1行。confidence はOCRエンジンが返す行単位の信頼度。 */
    data class Line(val text: String, val confidence: Double = 1.0)

    data class Field(
        val key: String,
        /** 正規化に成功した値。失敗時は空文字。 */
        val value: String,
        /** OCRが読み取った生の文字列。人間の確認画面で「元の読み取り」として表示する。 */
        val raw: String,
        val confidence: Double,
        val valid: Boolean,
        val error: String? = null,
        val origin: String = "ocr"
    ) {
        /** 信頼度が低い、または検証に落ちた項目は人間の確認を強く促す。 */
        fun needsReview(threshold: Double = 0.80): Boolean = !valid || confidence < threshold
    }

    private const val SAME_LINE = 0.90
    private const val NEXT_LINE = 0.75
    private const val PATTERN = 0.60

    private val SEPARATORS = Regex("^[\\s\\u3000:：=・>＞\\]］|｜]+")
    private val POSTAL_LINE = Regex("^[\\s\\u3000]*〒?[\\s\\u3000]*(\\d{3}[-\\u30FC\\uFF0D]\\d{4})[\\s\\u3000]*$")
    private val PHONE_LINE = Regex("^[\\s\\u3000]*[(（]?(0\\d{1,3})[)）]?[-\\u30FC\\uFF0D\\s]?(\\d{2,4})[-\\u30FC\\uFF0D\\s]?(\\d{4})[\\s\\u3000]*$")
    private val EMAIL_LINE = Regex("^[\\s\\u3000]*([^\\s\\u3000@]+@[^\\s\\u3000@]+\\.[^\\s\\u3000@]+)[\\s\\u3000]*$")

    /** 住所は次行へ折り返すことが多いので、ラベルの無い後続行を連結する。 */
    private const val ADDRESS_MAX_CONTINUATION = 2

    fun extract(
        lines: List<Line>,
        documentType: String = "generic",
        today: SimpleDate = SimpleDate.today()
    ): Map<String, Field> {
        val out = LinkedHashMap<String, Field>()

        // 1) ラベル照合（同一行 → 次行）
        for (i in lines.indices) {
            val hit = findLabel(lines[i].text) ?: continue
            val (key, endIndex) = hit
            if (out.containsKey(key)) continue

            var rawValue = SEPARATORS.replace(lines[i].text.substring(endIndex), "").trim()
            var base = SAME_LINE

            if (rawValue.isEmpty()) {
                val next = nextContentLine(lines, i) ?: continue
                if (findLabel(next.text) != null) continue
                rawValue = next.text.trim()
                base = NEXT_LINE
            } else if (key == Profile.ADDRESS) {
                rawValue += collectAddressContinuation(lines, i)
            }

            if (rawValue.isBlank()) continue
            out[key] = build(key, rawValue, base * lines[i].confidence, today)
        }

        // 2) ラベルが無い行のパターン照合（未取得の項目のみ）
        for (line in lines) {
            if (findLabel(line.text) != null) continue
            val m = matchPattern(line.text) ?: continue
            val (key, value) = m
            if (out.containsKey(key)) continue
            out[key] = build(key, value, PATTERN * line.confidence, today)
        }

        return out
    }

    /** 必須項目のうち未取得のものを返す。確認画面で「未入力」として提示する。 */
    fun missingRequired(fields: Map<String, Field>, documentType: String): List<String> =
        Profile.requiredFor(documentType).filter { key ->
            val f = fields[key]
            f == null || !f.valid || f.value.isEmpty()
        }

    private fun build(key: String, raw: String, confidence: Double, today: SimpleDate): Field {
        val r = Normalizer.normalize(key, raw, today)
        return if (r.ok) {
            Field(key, r.value!!, raw, round2(confidence), true)
        } else {
            Field(key, "", raw, 0.0, false, r.error)
        }
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    /** 行の中で最も左、同着なら最も長いラベルを採用する。返り値は (項目キー, ラベル終端index)。 */
    private fun findLabel(text: String): Pair<String, Int>? {
        var bestPos = Int.MAX_VALUE
        var bestLen = 0
        var bestKey: String? = null
        for ((label, key) in Profile.LABELS) {
            val pos = text.indexOf(label)
            if (pos < 0) continue
            if (pos < bestPos || (pos == bestPos && label.length > bestLen)) {
                bestPos = pos
                bestLen = label.length
                bestKey = key
            }
        }
        return bestKey?.let { it to (bestPos + bestLen) }
    }

    private fun nextContentLine(lines: List<Extractor.Line>, from: Int): Line? {
        for (j in from + 1 until lines.size) {
            if (lines[j].text.isBlank()) continue
            return lines[j]
        }
        return null
    }

    private fun collectAddressContinuation(lines: List<Line>, from: Int): String {
        val sb = StringBuilder()
        var added = 0
        for (j in from + 1 until lines.size) {
            if (added >= ADDRESS_MAX_CONTINUATION) break
            val t = lines[j].text
            if (t.isBlank()) break
            if (findLabel(t) != null) break
            if (matchPattern(t) != null) break
            sb.append('　').append(t.trim())
            added++
        }
        return sb.toString()
    }

    /** ラベルの無い行から、行全体が1つの値になっているものだけを拾う。誤検出を避けるため必ず行全体で照合する。 */
    private fun matchPattern(text: String): Pair<String, String>? {
        EMAIL_LINE.find(text)?.let { return Profile.EMAIL to it.groupValues[1] }
        PHONE_LINE.find(text)?.let { return Profile.PHONE to text.trim() }
        POSTAL_LINE.find(text)?.let { return Profile.POSTAL_CODE to it.groupValues[1] }
        return null
    }
}
