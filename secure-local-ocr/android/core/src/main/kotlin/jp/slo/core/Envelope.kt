package jp.slo.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SLO Handoff Envelope v1.0（SPEC.md 3, 6）。
 *
 * Envelopeは「人間が確認済みの項目値」だけを載せる。原画像もOCR生テキスト全文も載せない（INV-2）。
 */
object Envelope {

    const val PROTOCOL = "slo-handoff/1.0"
    const val DEFAULT_TTL_SECONDS = 300L
    const val MAX_TTL_SECONDS = 900L

    const val E_PROTOCOL = "E_PROTOCOL"
    const val E_EXPIRED = "E_EXPIRED"
    const val E_INTEGRITY = "E_INTEGRITY"
    const val E_UNCONFIRMED = "E_UNCONFIRMED"
    const val E_VALIDATION = "E_VALIDATION"

    data class FieldValue(
        val value: String,
        val origin: String,
        val confidence: Double?,
        val edited: Boolean
    )

    data class Source(
        val kind: String,
        val app: String,
        val version: String,
        val engine: String? = null,
        val offlineCapture: Boolean? = null
    )

    data class Verification(val ok: Boolean, val error: String? = null, val fieldCount: Int = 0)

    /**
     * 確認済み項目からEnvelopeを構築する。
     *
     * @param confirmed 人間の確認が完了しているか。false のまま呼ぶことは設計上の誤り。
     */
    fun build(
        handoffId: String,
        documentType: String,
        source: Source,
        fields: Map<String, FieldValue>,
        issuedAtEpochSeconds: Long,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
        confirmed: Boolean = true
    ): JsonValue.Obj {
        require(confirmed) { "INV-1: 未確認データをEnvelope化してはならない" }
        require(fields.isNotEmpty()) { "fields must not be empty" }
        require(ttlSeconds in 1..MAX_TTL_SECONDS) { "ttl out of range" }

        val o = JsonValue.Obj()
        o["protocol"] = JsonValue.Str(PROTOCOL)
        o["handoff_id"] = JsonValue.Str(handoffId)
        o["issued_at"] = JsonValue.Str(Rfc3339.format(issuedAtEpochSeconds))
        o["expires_at"] = JsonValue.Str(Rfc3339.format(issuedAtEpochSeconds + ttlSeconds))
        o["document_type"] = JsonValue.Str(documentType)
        o["profile"] = JsonValue.Str(Profile.ID)

        val src = JsonValue.Obj()
        src["kind"] = JsonValue.Str(source.kind)
        src["app"] = JsonValue.Str(source.app)
        src["version"] = JsonValue.Str(source.version)
        source.engine?.let { src["engine"] = JsonValue.Str(it) }
        source.offlineCapture?.let { src["offline_capture"] = JsonValue.Bool(it) }
        o["source"] = src

        o["confirmed"] = JsonValue.Bool(true)

        val f = JsonValue.Obj()
        for ((k, v) in fields) {
            val fo = JsonValue.Obj()
            fo["value"] = JsonValue.Str(v.value)
            fo["origin"] = JsonValue.Str(v.origin)
            fo["confidence"] = v.confidence?.let { JsonValue.Num(it) } ?: JsonValue.Null
            fo["edited"] = JsonValue.Bool(v.edited)
            fo["confirmed"] = JsonValue.Bool(true)
            f[k] = fo
        }
        o["fields"] = f
        return o
    }

    /** integrity を除いた正準化JSONにHMAC-SHA256を付与する（SPEC.md 6）。 */
    fun sign(envelope: JsonValue.Obj, keyId: String, key: ByteArray): JsonValue.Obj {
        val mac = hmac(key, canonicalWithoutIntegrity(envelope))
        val integrity = JsonValue.Obj()
        integrity["alg"] = JsonValue.Str("HMAC-SHA256")
        integrity["key_id"] = JsonValue.Str(keyId)
        integrity["value"] = JsonValue.Str(Base64Url.encode(mac))
        envelope["integrity"] = integrity
        return envelope
    }

    fun canonicalWithoutIntegrity(envelope: JsonValue.Obj): String {
        val copy = JsonValue.Obj(LinkedHashMap(envelope.entries))
        copy.entries.remove("integrity")
        return Json.canonical(copy)
    }

    /**
     * 受け取り側の検証。プロトコル・確認済みフラグ・失効・HMAC・正規化の再計算をすべて確認する。
     * 1つでも落ちたら取り込まない（INV-3, INV-6）。
     */
    fun verify(
        envelope: JsonValue.Obj,
        key: ByteArray?,
        nowEpochSeconds: Long,
        today: SimpleDate = SimpleDate.today()
    ): Verification {
        if (envelope["protocol"]?.asString != PROTOCOL) return Verification(false, E_PROTOCOL)
        if (envelope["profile"]?.asString != Profile.ID) return Verification(false, E_PROTOCOL)
        if (envelope["confirmed"]?.asBoolean != true) return Verification(false, E_UNCONFIRMED)

        val expires = envelope["expires_at"]?.asString?.let { Rfc3339.parse(it) }
            ?: return Verification(false, E_PROTOCOL)
        if (nowEpochSeconds > expires) return Verification(false, E_EXPIRED)

        val fields = envelope["fields"]?.asObj ?: return Verification(false, E_PROTOCOL)
        if (fields.entries.isEmpty()) return Verification(false, E_PROTOCOL)

        for ((k, v) in fields.entries) {
            val fo = v.asObj ?: return Verification(false, E_PROTOCOL)
            if (fo["confirmed"]?.asBoolean != true) return Verification(false, E_UNCONFIRMED)
            val value = fo["value"]?.asString ?: return Verification(false, E_PROTOCOL)
            val r = Normalizer.normalize(k, value, today)
            if (!r.ok || r.value != value) return Verification(false, E_VALIDATION)
        }

        if (key != null) {
            val integrity = envelope["integrity"]?.asObj ?: return Verification(false, E_INTEGRITY)
            if (integrity["alg"]?.asString != "HMAC-SHA256") return Verification(false, E_INTEGRITY)
            val expected = Base64Url.encode(hmac(key, canonicalWithoutIntegrity(envelope)))
            val actual = integrity["value"]?.asString ?: return Verification(false, E_INTEGRITY)
            if (!constantTimeEquals(expected, actual)) return Verification(false, E_INTEGRITY)
        }

        return Verification(true, null, fields.entries.size)
    }

    private fun hmac(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(ALPHABET[b0 ushr 2])
            if (b1 < 0) {
                sb.append(ALPHABET[(b0 and 0x03) shl 4])
                break
            }
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (b2 < 0) {
                sb.append(ALPHABET[(b1 and 0x0F) shl 2])
                break
            }
            sb.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            sb.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    fun decodeHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** RFC3339(UTC, 秒精度)の最小実装。端末のタイムゾーン設定に結果が左右されないようUTC固定。 */
object Rfc3339 {
    private const val SECONDS_PER_DAY = 86400L

    fun format(epochSeconds: Long): String {
        var days = Math.floorDiv(epochSeconds, SECONDS_PER_DAY)
        val secOfDay = Math.floorMod(epochSeconds, SECONDS_PER_DAY)
        var year = 1970
        while (true) {
            val len = if (SimpleDate.isLeap(year)) 366 else 365
            if (days >= len) { days -= len; year++ } else if (days < 0) { year--; days += if (SimpleDate.isLeap(year)) 366 else 365 } else break
        }
        var month = 1
        while (true) {
            val dim = SimpleDate.daysInMonth(year, month)
            if (days >= dim) { days -= dim; month++ } else break
        }
        val day = days.toInt() + 1
        val h = (secOfDay / 3600).toInt()
        val mi = ((secOfDay % 3600) / 60).toInt()
        val s = (secOfDay % 60).toInt()
        return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(year, month, day, h, mi, s)
    }

    fun parse(text: String): Long? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})Z$").find(text) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        var days = 0L
        var year = 1970
        while (year < y.toInt()) { days += if (SimpleDate.isLeap(year)) 366 else 365; year++ }
        while (year > y.toInt()) { year--; days -= if (SimpleDate.isLeap(year)) 366 else 365 }
        for (mm in 1 until mo.toInt()) days += SimpleDate.daysInMonth(y.toInt(), mm)
        days += d.toInt() - 1
        return days * SECONDS_PER_DAY + h.toInt() * 3600L + mi.toInt() * 60L + s.toInt()
    }
}
