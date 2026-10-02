package jp.slo.core

/**
 * 日本語テキストの幅・字種変換ユーティリティ。
 *
 * NFKCを使わないのは、住所の丸数字・ローマ数字・単位記号を破壊するため。
 * 必要な変換だけを明示的に行う（SPEC.md 4）。
 */
object Text {

    private const val IDEOGRAPHIC_SPACE = '　'

    /** OCRで数字が誤読されやすい文字。数字が期待される文脈でのみ適用する。 */
    private val OCR_DIGIT_FIX = mapOf(
        'O' to '0', 'o' to '0', '〇' to '0', 'D' to '0',
        'I' to '1', 'l' to '1', '｜' to '1', '|' to '1',
        'S' to '5', 's' to '5',
        'B' to '8',
        'Z' to '2',
        'q' to '9'
    )

    private val HYPHEN_LIKE = setOf(
        '－', '−', '‐', '‑', '‒', '–', '—', '―',
        'ー', 'ｰ', '⁃', '˗'
    )

    private val HALFWIDTH_KATAKANA = mapOf(
        '｡' to '。', '｢' to '「', '｣' to '」', '､' to '、',
        '･' to '・', 'ｦ' to 'ヲ', 'ｧ' to 'ァ', 'ｨ' to 'ィ',
        'ｩ' to 'ゥ', 'ｪ' to 'ェ', 'ｫ' to 'ォ', 'ｬ' to 'ャ',
        'ｭ' to 'ュ', 'ｮ' to 'ョ', 'ｯ' to 'ッ', 'ｰ' to 'ー',
        'ｱ' to 'ア', 'ｲ' to 'イ', 'ｳ' to 'ウ', 'ｴ' to 'エ',
        'ｵ' to 'オ', 'ｶ' to 'カ', 'ｷ' to 'キ', 'ｸ' to 'ク',
        'ｹ' to 'ケ', 'ｺ' to 'コ', 'ｻ' to 'サ', 'ｼ' to 'シ',
        'ｽ' to 'ス', 'ｾ' to 'セ', 'ｿ' to 'ソ', 'ﾀ' to 'タ',
        'ﾁ' to 'チ', 'ﾂ' to 'ツ', 'ﾃ' to 'テ', 'ﾄ' to 'ト',
        'ﾅ' to 'ナ', 'ﾆ' to 'ニ', 'ﾇ' to 'ヌ', 'ﾈ' to 'ネ',
        'ﾉ' to 'ノ', 'ﾊ' to 'ハ', 'ﾋ' to 'ヒ', 'ﾌ' to 'フ',
        'ﾍ' to 'ヘ', 'ﾎ' to 'ホ', 'ﾏ' to 'マ', 'ﾐ' to 'ミ',
        'ﾑ' to 'ム', 'ﾒ' to 'メ', 'ﾓ' to 'モ', 'ﾔ' to 'ヤ',
        'ﾕ' to 'ユ', 'ﾖ' to 'ヨ', 'ﾗ' to 'ラ', 'ﾘ' to 'リ',
        'ﾙ' to 'ル', 'ﾚ' to 'レ', 'ﾛ' to 'ロ', 'ﾜ' to 'ワ',
        'ﾝ' to 'ン'
    )

    /** 濁点・半濁点が付く半角カタカナの合成表。 */
    private val DAKUTEN = "カキクケコサシスセソ" +
            "タチツテトハヒフヘホウ"
    private val HANDAKUTEN = "ハヒフヘホ"

    fun stripControlChars(s: String): String =
        s.filter { it.code >= 0x20 || it == '\t' }.replace("\t", " ")

    /** 全角ASCII(U+FF01-U+FF5E)と全角スペースを半角へ。 */
    fun toHalfwidthAscii(s: String): String = buildString {
        for (ch in s) {
            when {
                ch in '！'..'～' -> append((ch.code - 0xFEE0).toChar())
                ch == IDEOGRAPHIC_SPACE -> append(' ')
                else -> append(ch)
            }
        }
    }

    /** 全角数字だけを半角へ（住所など、英字は全角のまま残したい場合に使う）。 */
    fun digitsToHalfwidth(s: String): String = buildString {
        for (ch in s) {
            if (ch in '０'..'９') append((ch.code - 0xFEE0).toChar()) else append(ch)
        }
    }

    /** 半角カタカナを全角カタカナへ（濁点・半濁点を合成する）。 */
    fun halfwidthKatakanaToFullwidth(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val base = HALFWIDTH_KATAKANA[s[i]]
            if (base == null) {
                sb.append(s[i]); i++; continue
            }
            val next = if (i + 1 < s.length) s[i + 1] else null
            when {
                next == 'ﾞ' && DAKUTEN.contains(base) -> {
                    sb.append(if (base == 'ウ') 'ヴ' else base + 1); i += 2
                }
                next == 'ﾟ' && HANDAKUTEN.contains(base) -> {
                    sb.append(base + 2); i += 2
                }
                else -> { sb.append(base); i++ }
            }
        }
        return sb.toString()
    }

    fun hiraganaToKatakana(s: String): String = buildString {
        for (ch in s) {
            if (ch in 'ぁ'..'ゖ') append((ch.code + 0x60).toChar()) else append(ch)
        }
    }

    /** 連続する空白（半角/全角/タブ）を全角スペース1個へ畳み、前後を除去する。 */
    fun collapseSpacesToIdeographic(s: String): String {
        val collapsed = Regex("[ \\t\\u3000]+").replace(s, IDEOGRAPHIC_SPACE.toString())
        return collapsed.trim { it == ' ' || it == IDEOGRAPHIC_SPACE || it == '\t' }
    }

    fun removeAllSpaces(s: String): String = Regex("[\\s\\u3000]+").replace(s, "")

    /** 数字文脈でのOCR誤読補正。英数字IDや氏名には使わないこと。 */
    fun fixOcrDigits(s: String): String = buildString {
        for (ch in s) append(OCR_DIGIT_FIX[ch] ?: ch)
    }

    fun isHyphenLike(ch: Char): Boolean = ch == '-' || ch in HYPHEN_LIKE

    /** ハイフン類を半角ハイフンへ。数字に挟まれている場合のみ変換する。 */
    fun normalizeHyphensBetweenDigits(s: String): String {
        val chars = s.toCharArray()
        for (i in chars.indices) {
            if (!isHyphenLike(chars[i])) continue
            val prev = if (i > 0) chars[i - 1] else null
            val next = if (i < chars.size - 1) chars[i + 1] else null
            if (prev != null && next != null && prev.isAsciiDigit() && next.isAsciiDigit()) {
                chars[i] = '-'
            }
        }
        return String(chars)
    }

    /** 電話番号・郵便番号など、値全体が数値の項目に使う。 */
    fun normalizeAllHyphens(s: String): String = buildString {
        for (ch in s) append(if (isHyphenLike(ch)) '-' else ch)
    }

    fun digitsOnly(s: String): String = s.filter { it.isAsciiDigit() }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
