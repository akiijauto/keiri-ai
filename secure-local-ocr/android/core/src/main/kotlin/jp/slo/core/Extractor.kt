package jp.slo.core

/**
 * OCR結果（行の並び）から業務項目を抽出する（企画書 Step 4）。
 *
 * 完全に端末内で動く決定的なルールベース処理であり、外部APIも生成AIも使わない。
 * 抽出そのものは「候補の提示」であって確定ではない。確定は必ず人間が行う（INV-1）。
 */
object Extractor {

    /**
     * OCRエンジンが返す行の外接矩形（画像のピクセル座標。原点は左上）。
     *
     * 帳票は「ラベル列 | 値列」の表であることが多い。OCRエンジンは列ごとに
     * 別ブロックとして返すことがあり、その場合、行の並び順だけでラベルと値を
     * 対応づけると全く別の値と結びついてしまう。位置で対応づけるために持つ。
     */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Int get() = (left + right) / 2

        /** 縦方向の重なりが互いの高さの半分以上なら「同じ行」とみなす。 */
        fun sameRow(other: Box): Boolean = overlapRatio(top, bottom, other.top, other.bottom) >= 0.5

        /** 横方向の重なりが半分以上なら「同じ列」とみなす。 */
        fun sameColumn(other: Box): Boolean = overlapRatio(left, right, other.left, other.right) >= 0.5

        private fun overlapRatio(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int): Double {
            val span = minOf(aTo - aFrom, bTo - bFrom)
            if (span <= 0) return 0.0
            val overlap = minOf(aTo, bTo) - maxOf(aFrom, bFrom)
            return if (overlap <= 0) 0.0 else overlap.toDouble() / span
        }
    }

    /**
     * OCRの1行。confidence はOCRエンジンが返す行単位の信頼度。
     * box は取得できた場合のみ。無い場合は行の並び順で対応づける（従来動作）。
     */
    data class Line(val text: String, val confidence: Double = 1.0, val box: Box? = null)

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
    /** 位置で「ラベルの右隣のセル」と特定できた場合。並び順頼みより確からしい。 */
    private const val ADJACENT_CELL = 0.85
    private const val NEXT_LINE = 0.75
    private const val PATTERN = 0.60

    /** ラベルの右隣を探す横方向の上限。ラベル高さの倍数。離れすぎた列を誤って拾わない。 */
    private const val MAX_GAP_IN_LABEL_HEIGHTS = 8.0

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

        // 1) ラベル照合（同一行に値がある → 位置で右隣/直下のセルを探す → 並び順で次行）
        for (i in lines.indices) {
            val hit = findLabel(lines[i].text) ?: continue
            val (key, endIndex) = hit
            if (out.containsKey(key)) continue

            var rawValue = SEPARATORS.replace(lines[i].text.substring(endIndex), "").trim()
            var base = SAME_LINE
            var valueIndex = i

            if (rawValue.isEmpty()) {
                val found = findValueCell(lines, i) ?: continue
                rawValue = lines[found.index].text.trim()
                base = found.base
                valueIndex = found.index
            }

            if (key == Profile.ADDRESS) {
                rawValue += collectAddressContinuation(lines, valueIndex)
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

    private data class ValueCell(val index: Int, val base: Double)

    /**
     * ラベル行だけで値を持たない場合に、値が書かれている行を探す。
     *
     * 外接矩形が取れる場合は位置で探す。ラベル列と値列が別ブロックとして
     * 返されると並び順は「ラベル、ラベル、…、値、値、…」になり得るため、
     * 並び順に頼ると全項目が隣のラベルと衝突して取れなくなる（実機で確認）。
     * 位置が分かっているのに見つからなかったときは、並び順へは戻らない。
     * 戻ると無関係な行を値として拾ってしまうため（例: 入居予定日に顧客番号）。
     */
    private fun findValueCell(lines: List<Line>, labelIndex: Int): ValueCell? {
        val labelBox = lines[labelIndex].box
        if (labelBox != null) {
            rightOfLabel(lines, labelIndex, labelBox)?.let { return ValueCell(it, ADJACENT_CELL) }
            belowLabel(lines, labelIndex, labelBox)?.let { return ValueCell(it, NEXT_LINE) }
            return null
        }
        val next = nextContentLine(lines, labelIndex) ?: return null
        if (findLabel(lines[next].text) != null) return null
        return ValueCell(next, NEXT_LINE)
    }

    /** 同じ行で、ラベルの右側にある最も近い行。表の「ラベル | 値」に対応する。 */
    private fun rightOfLabel(lines: List<Line>, labelIndex: Int, labelBox: Box): Int? {
        val maxGap = labelBox.height * MAX_GAP_IN_LABEL_HEIGHTS
        var best: Int? = null
        var bestLeft = Int.MAX_VALUE
        for (j in lines.indices) {
            if (j == labelIndex) continue
            val box = lines[j].box ?: continue
            if (lines[j].text.isBlank()) continue
            if (!labelBox.sameRow(box)) continue
            if (box.centerX <= labelBox.right) continue
            if (box.left - labelBox.right > maxGap) continue
            if (findLabel(lines[j].text) != null) continue
            if (box.left < bestLeft) {
                bestLeft = box.left
                best = j
            }
        }
        return best
    }

    /** 同じ列で、ラベルのすぐ下にある行。ラベルが値の上に置かれる帳票に対応する。 */
    private fun belowLabel(lines: List<Line>, labelIndex: Int, labelBox: Box): Int? {
        val maxGap = labelBox.height * MAX_GAP_IN_LABEL_HEIGHTS
        var best: Int? = null
        var bestTop = Int.MAX_VALUE
        for (j in lines.indices) {
            if (j == labelIndex) continue
            val box = lines[j].box ?: continue
            if (lines[j].text.isBlank()) continue
            if (box.top < labelBox.bottom) continue
            if (box.top - labelBox.bottom > maxGap) continue
            if (!labelBox.sameColumn(box)) continue
            if (findLabel(lines[j].text) != null) continue
            if (hasLabelToLeft(lines, j, box)) continue
            if (box.top < bestTop) {
                bestTop = box.top
                best = j
            }
        }
        return best
    }

    /** その行の左側（同じ行）に既知のラベルがあるか。別項目の値を掴んでいないかの判定。 */
    private fun hasLabelToLeft(lines: List<Line>, index: Int, box: Box): Boolean =
        anyToLeft(lines, index, box) { findLabel(it) != null }

    /** その行の左側（同じ行）に何か書かれているか。表の行かセル内の折り返しかの判定。 */
    private fun hasContentToLeft(lines: List<Line>, index: Int, box: Box): Boolean =
        anyToLeft(lines, index, box) { it.isNotBlank() }

    private inline fun anyToLeft(
        lines: List<Line>,
        index: Int,
        box: Box,
        predicate: (String) -> Boolean
    ): Boolean {
        for (j in lines.indices) {
            if (j == index) continue
            val other = lines[j].box ?: continue
            if (!box.sameRow(other)) continue
            if (other.centerX >= box.left) continue
            if (predicate(lines[j].text)) return true
        }
        return false
    }

    private fun nextContentLine(lines: List<Line>, from: Int): Int? {
        for (j in from + 1 until lines.size) {
            if (lines[j].text.isBlank()) continue
            return j
        }
        return null
    }

    /**
     * 折り返した住所の続きを集める。
     *
     * 外接矩形が取れる場合は、値と同じ列の直下だけを続きとみなす。
     * 並び順で集めると、表では隣の列の別項目まで住所へ連結してしまう。
     */
    private fun collectAddressContinuation(lines: List<Line>, from: Int): String {
        val fromBox = lines[from].box
        val sb = StringBuilder()
        var added = 0

        if (fromBox != null) {
            var currentBottom = fromBox.bottom
            val maxGap = fromBox.height * 1.5
            while (added < ADDRESS_MAX_CONTINUATION) {
                var next: Int? = null
                var nextTop = Int.MAX_VALUE
                for (j in lines.indices) {
                    if (j == from) continue
                    val box = lines[j].box ?: continue
                    if (lines[j].text.isBlank()) continue
                    if (box.top < currentBottom) continue
                    if (box.top - currentBottom > maxGap) continue
                    if (!fromBox.sameColumn(box)) continue
                    if (box.top < nextTop) { nextTop = box.top; next = j }
                }
                val j = next ?: break
                val t = lines[j].text
                if (findLabel(t) != null) break
                if (matchPattern(t) != null) break
                // 表では、値の左側にラベルのセルがある。左に何か書かれている行は
                // 別項目の行であって、住所セル内の折り返しではない。
                // ラベル語彙に無い項目（勤務先など）でも同じ判定ができる。
                if (hasContentToLeft(lines, j, lines[j].box!!)) break
                sb.append('　').append(t.trim())
                currentBottom = lines[j].box!!.bottom
                added++
            }
            return sb.toString()
        }

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
