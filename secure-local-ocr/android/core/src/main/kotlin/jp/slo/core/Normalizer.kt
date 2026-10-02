package jp.slo.core

/**
 * 正規化と検証（SPEC.md 4, 5）。
 *
 * INV-6: 取込元(OCRアプリ・入居フォーム)と登録先の双方で同じ結果が出ることを保証する。
 * 3実装の一致は protocol/testdata/normalization-vectors.json で検証する。
 */
object Normalizer {

    const val E_PARSE = "E_PARSE"
    const val E_VALIDATION = "E_VALIDATION"
    const val E_UNKNOWN_FIELD = "E_UNKNOWN_FIELD"

    data class Result(val value: String?, val error: String?) {
        val ok: Boolean get() = error == null
        companion object {
            fun ok(v: String) = Result(v, null)
            fun err(code: String) = Result(null, code)
        }
    }

    /** 元号の開始西暦。和暦n年 = base + n - 1。 */
    private val ERAS = mapOf(
        "明治" to 1868, "M" to 1868, "m" to 1868,
        "大正" to 1912, "T" to 1912, "t" to 1912,
        "昭和" to 1926, "S" to 1926, "s" to 1926,
        "平成" to 1989, "H" to 1989, "h" to 1989,
        "令和" to 2019, "R" to 2019, "r" to 2019
    )

    private val ERA_RE = Regex(
        "^(明治|大正|昭和|平成|令和|[MTSHRmtshr])\\s*(\\d{1,2})\\s*(?:年|[./\\-])\\s*(\\d{1,2})\\s*(?:月|[./\\-])\\s*(\\d{1,2})\\s*日?$"
    )
    private val WESTERN_RE = Regex(
        "^(\\d{4})\\s*(?:年|[./\\-])\\s*(\\d{1,2})\\s*(?:月|[./\\-])\\s*(\\d{1,2})\\s*日?$"
    )
    private val COMPACT_RE = Regex("^(\\d{4})(\\d{2})(\\d{2})$")
    private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$")
    private val CUSTOMER_RE = Regex("^[A-Z0-9-]{1,32}$")
    private val KANA_RE = Regex("^[ァ-ヶー\\u3000]+$")

    /** 生年月日の下限（SPEC.md 5）。 */
    private const val MIN_BIRTH_YEAR = 1900

    fun normalize(field: String, input: String, today: SimpleDate = SimpleDate.today()): Result {
        val s0 = Text.stripControlChars(input)
        return when (field) {
            Profile.NAME -> normalizeName(s0)
            Profile.NAME_KANA -> normalizeKana(s0)
            Profile.BIRTHDAY -> normalizeDate(s0, minYear = MIN_BIRTH_YEAR, notAfter = today)
            Profile.MOVE_IN_DATE -> normalizeDate(s0, minYear = MIN_BIRTH_YEAR, notAfter = null)
            Profile.POSTAL_CODE -> normalizePostal(s0)
            Profile.ADDRESS -> normalizeAddress(s0)
            Profile.PHONE -> normalizePhone(s0)
            Profile.EMAIL -> normalizeEmail(s0)
            Profile.CUSTOMER_NO -> normalizeCustomerNo(s0)
            else -> Result.err(E_UNKNOWN_FIELD)
        }
    }

    fun normalizeName(input: String): Result {
        var s = Text.halfwidthKatakanaToFullwidth(input)
        s = Text.collapseSpacesToIdeographic(s)
        if (s.isEmpty() || s.length > 64) return Result.err(E_VALIDATION)
        if (s.any { it in '0'..'9' || it in '０'..'９' }) return Result.err(E_VALIDATION)
        if (s.contains('@')) return Result.err(E_VALIDATION)
        return Result.ok(s)
    }

    fun normalizeKana(input: String): Result {
        var s = Text.halfwidthKatakanaToFullwidth(input)
        s = Text.hiraganaToKatakana(s)
        s = Text.collapseSpacesToIdeographic(s)
        if (s.isEmpty() || s.length > 64) return Result.err(E_VALIDATION)
        if (!KANA_RE.matches(s)) return Result.err(E_VALIDATION)
        return Result.ok(s)
    }

    fun normalizeDate(input: String, minYear: Int, notAfter: SimpleDate?): Result {
        val raw = Text.removeAllSpaces(Text.toHalfwidthAscii(input))
        val date = parseDate(raw) ?: return Result.err(E_PARSE)
        if (!date.isValid()) return Result.err(E_VALIDATION)
        if (date.year < minYear) return Result.err(E_VALIDATION)
        if (notAfter != null && date.compareTo(notAfter) > 0) return Result.err(E_VALIDATION)
        return Result.ok(date.toIso())
    }

    /** 和暦・西暦・区切り記号ゆれ・OCR誤読数字を吸収して SimpleDate へ。読めなければ null。 */
    private fun parseDate(raw: String): SimpleDate? {
        ERA_RE.find(raw)?.let { m ->
            val base = ERAS[m.groupValues[1]] ?: return null
            val y = base + m.groupValues[2].toInt() - 1
            return SimpleDate(y, m.groupValues[3].toInt(), m.groupValues[4].toInt())
        }
        // 元号として読めなかった場合に限り数字のOCR誤読補正を適用する
        // （元号記号 S/H/R を数字へ潰さないための順序）。
        val fixed = Text.fixOcrDigits(raw)
        WESTERN_RE.find(fixed)?.let { m ->
            return SimpleDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        COMPACT_RE.find(fixed)?.let { m ->
            return SimpleDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        return null
    }

    fun normalizePostal(input: String): Result {
        val s = Text.digitsOnly(Text.fixOcrDigits(Text.toHalfwidthAscii(input)))
        if (s.length != 7) return Result.err(E_VALIDATION)
        return Result.ok(s.substring(0, 3) + "-" + s.substring(3))
    }

    fun normalizeAddress(input: String): Result {
        var s = Text.halfwidthKatakanaToFullwidth(input)
        s = Text.digitsToHalfwidth(s)
        s = Text.normalizeHyphensBetweenDigits(s)
        s = Text.collapseSpacesToIdeographic(s)
        if (s.isEmpty() || s.length > 128) return Result.err(E_VALIDATION)
        return Result.ok(s)
    }

    fun normalizePhone(input: String): Result {
        var s = Text.toHalfwidthAscii(input)
        s = Text.fixOcrDigits(s)
        s = Text.removeAllSpaces(s)
        if (s.startsWith("+81")) s = "0" + s.substring(3)
        if (s.startsWith("+")) return Result.err(E_VALIDATION)
        val digits = Text.digitsOnly(s)
        if (digits.length !in 10..11) return Result.err(E_VALIDATION)
        if (!digits.startsWith("0")) return Result.err(E_VALIDATION)
        return Result.ok(digits)
    }

    fun normalizeEmail(input: String): Result {
        var s = Text.toHalfwidthAscii(input)
        s = Text.removeAllSpaces(s).lowercase()
        if (s.isEmpty() || s.length > 254) return Result.err(E_VALIDATION)
        if (!EMAIL_RE.matches(s)) return Result.err(E_VALIDATION)
        return Result.ok(s)
    }

    fun normalizeCustomerNo(input: String): Result {
        var s = Text.toHalfwidthAscii(input)
        s = Text.normalizeAllHyphens(s)
        s = Text.removeAllSpaces(s).uppercase()
        if (!CUSTOMER_RE.matches(s)) return Result.err(E_VALIDATION)
        return Result.ok(s)
    }
}

/** java.time に依存しない最小の日付。3実装で閏年判定を完全一致させるために自前で持つ。 */
data class SimpleDate(val year: Int, val month: Int, val day: Int) : Comparable<SimpleDate> {

    fun isValid(): Boolean {
        if (month !in 1..12) return false
        if (day < 1) return false
        return day <= daysInMonth(year, month)
    }

    fun toIso(): String = "%04d-%02d-%02d".format(year, month, day)

    override fun compareTo(other: SimpleDate): Int {
        if (year != other.year) return year - other.year
        if (month != other.month) return month - other.month
        return day - other.day
    }

    companion object {
        fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

        fun daysInMonth(y: Int, m: Int): Int = when (m) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeap(y)) 29 else 28
            else -> 0
        }

        fun parseIso(s: String): SimpleDate? {
            val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(s) ?: return null
            return SimpleDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }

        fun today(): SimpleDate {
            val c = java.util.Calendar.getInstance()
            return SimpleDate(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }
}
