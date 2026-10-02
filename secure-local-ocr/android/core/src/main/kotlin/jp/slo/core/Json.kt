package jp.slo.core

/**
 * 依存ライブラリを持たない最小限のJSON実装。
 *
 * 外部ライブラリを避ける理由は2つある。
 *  1. OCRフェーズで動くコードの依存を最小化し、意図しない通信を行うSDKを混入させないため。
 *  2. 正準化(RFC 8785サブセット)の出力をKotlin/TypeScript/Swiftの3実装で完全一致させるため。
 */
sealed class JsonValue {
    data class Obj(val entries: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue() {
        operator fun get(key: String): JsonValue? = entries[key]
        operator fun set(key: String, v: JsonValue) { entries[key] = v }
    }
    data class Arr(val items: MutableList<JsonValue> = mutableListOf()) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()

    val asString: String? get() = (this as? Str)?.value
    val asDouble: Double? get() = (this as? Num)?.value
    val asBoolean: Boolean? get() = (this as? Bool)?.value
    val asObj: Obj? get() = this as? Obj
    val asArr: Arr? get() = this as? Arr
}

object Json {

    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWs()
        val v = p.parseValue()
        p.skipWs()
        require(p.eof()) { "trailing content at ${p.pos}" }
        return v
    }

    /**
     * 正準化JSON (SPEC.md 6.1)。
     * キーはUTF-16コードユニット昇順、空白なし、数値は整数なら小数点なしの最短表現。
     */
    fun canonical(v: JsonValue): String = StringBuilder().also { writeCanonical(v, it) }.toString()

    private fun writeCanonical(v: JsonValue, sb: StringBuilder) {
        when (v) {
            is JsonValue.Obj -> {
                sb.append('{')
                val keys = v.entries.keys.sortedWith { a, b -> compareUtf16(a, b) }
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    writeString(k, sb)
                    sb.append(':')
                    writeCanonical(v.entries.getValue(k), sb)
                }
                sb.append('}')
            }
            is JsonValue.Arr -> {
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    writeCanonical(item, sb)
                }
                sb.append(']')
            }
            is JsonValue.Str -> writeString(v.value, sb)
            is JsonValue.Num -> sb.append(formatNumber(v.value))
            is JsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            JsonValue.Null -> sb.append("null")
        }
    }

    /** 整数値は "1"、非整数は最短往復表現。JS/Swiftと同じ結果になるよう揃えている。 */
    fun formatNumber(d: Double): String {
        require(!d.isNaN() && !d.isInfinite()) { "non-finite number is not representable in JSON" }
        if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            return d.toLong().toString()
        }
        return d.toString()
    }

    private fun compareUtf16(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val ca = a[i].code
            val cb = b[i].code
            if (ca != cb) return ca - cb
        }
        return a.length - b.length
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(val src: String) {
        var pos = 0
        fun eof() = pos >= src.length
        fun skipWs() {
            while (pos < src.length && (src[pos] == ' ' || src[pos] == '\n' || src[pos] == '\r' || src[pos] == '\t')) pos++
        }

        fun parseValue(): JsonValue {
            skipWs()
            require(!eof()) { "unexpected end of input" }
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else -> parseNumber()
            }
        }

        fun expect(lit: String) {
            require(src.startsWith(lit, pos)) { "expected $lit at $pos" }
            pos += lit.length
        }

        fun parseObject(): JsonValue.Obj {
            expect("{")
            val o = JsonValue.Obj()
            skipWs()
            if (!eof() && src[pos] == '}') { pos++; return o }
            while (true) {
                skipWs()
                val k = parseString()
                skipWs()
                expect(":")
                o[k] = parseValue()
                skipWs()
                require(!eof()) { "unterminated object" }
                when (src[pos]) {
                    ',' -> { pos++ }
                    '}' -> { pos++; return o }
                    else -> throw IllegalArgumentException("expected , or } at $pos")
                }
            }
        }

        fun parseArray(): JsonValue.Arr {
            expect("[")
            val a = JsonValue.Arr()
            skipWs()
            if (!eof() && src[pos] == ']') { pos++; return a }
            while (true) {
                a.items.add(parseValue())
                skipWs()
                require(!eof()) { "unterminated array" }
                when (src[pos]) {
                    ',' -> { pos++ }
                    ']' -> { pos++; return a }
                    else -> throw IllegalArgumentException("expected , or ] at $pos")
                }
            }
        }

        fun parseString(): String {
            expect("\"")
            val sb = StringBuilder()
            while (true) {
                require(!eof()) { "unterminated string" }
                val c = src[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val e = src[pos++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun parseNumber(): JsonValue.Num {
            val start = pos
            if (!eof() && (src[pos] == '-' || src[pos] == '+')) pos++
            while (!eof() && (src[pos].isDigit() || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E' ||
                        ((src[pos] == '-' || src[pos] == '+') && (src[pos - 1] == 'e' || src[pos - 1] == 'E')))) pos++
            return JsonValue.Num(src.substring(start, pos).toDouble())
        }
    }
}
