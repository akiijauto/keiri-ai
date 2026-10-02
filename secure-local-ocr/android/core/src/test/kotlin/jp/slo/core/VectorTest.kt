package jp.slo.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * protocol/testdata 配下の共通ベクタに対する Kotlin 実装の検証。
 *
 * 同じベクタを TypeScript(web) と Swift(iOS) の実装も読む。
 * 3実装が同じ結果を返すことが INV-6（取込元と登録先で判定が一致する）の担保になる。
 */
class VectorTest {

    private val testdata: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "protocol/testdata")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        fail("protocol/testdata が見つかりません")
    }

    private fun load(name: String): JsonValue.Obj =
        Json.parse(File(testdata, name).readText(Charsets.UTF_8)).asObj
            ?: fail("$name の形式が不正です")

    /** ベクタの日付が「今日」に依存しないよう、検証用の固定日を使う。 */
    private val fixedToday = SimpleDate(2026, 8, 22)

    @Test
    fun `normalization vectors`() {
        val cases = load("normalization-vectors.json")["cases"]!!.asArr!!.items
        assertTrue(cases.size >= 50, "ベクタ件数が少なすぎます: ${cases.size}")

        val failures = mutableListOf<String>()
        for (c in cases) {
            val o = c.asObj!!
            val id = o["id"]!!.asString!!
            val field = o["field"]!!.asString!!
            val input = o["input"]!!.asString!!
            val expected = o["expected"]?.asString
            val expectedError = o["error"]?.asString

            val r = Normalizer.normalize(field, input, fixedToday)
            if (expected != null) {
                if (!r.ok) failures += "$id: エラー ${r.error} (期待値 '$expected')"
                else if (r.value != expected) failures += "$id: '${r.value}' != 期待値 '$expected'"
            } else {
                if (r.ok) failures += "$id: 成功 '${r.value}' したが $expectedError を期待"
                else if (r.error != expectedError) failures += "$id: ${r.error} != 期待 $expectedError"
            }
        }
        assertTrue(failures.isEmpty(), "正規化ベクタ不一致:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `extraction vectors`() {
        val cases = load("extraction-vectors.json")["cases"]!!.asArr!!.items
        val failures = mutableListOf<String>()

        for (c in cases) {
            val o = c.asObj!!
            val id = o["id"]!!.asString!!
            val documentType = o["document_type"]!!.asString!!
            val lineConfidence = o["line_confidence"]?.asDouble ?: 1.0
            // 行は文字列、または {"text":..., "box":[left,top,right,bottom]} の形で書く。
            val lines = o["lines"]!!.asArr!!.items.map { item ->
                item.asString?.let { return@map Extractor.Line(it, lineConfidence) }
                val lo = item.asObj!!
                val b = lo["box"]?.asArr?.items?.map { it.asDouble!!.toInt() }
                Extractor.Line(
                    lo["text"]!!.asString!!,
                    lineConfidence,
                    b?.let { Extractor.Box(it[0], it[1], it[2], it[3]) }
                )
            }
            val expected = o["expected"]!!.asObj!!

            val actual = Extractor.extract(lines, documentType, fixedToday)

            val extraKeys = actual.keys - expected.entries.keys
            if (extraKeys.isNotEmpty()) failures += "$id: 余分な項目 $extraKeys"

            for ((key, ev) in expected.entries) {
                val e = ev.asObj!!
                val a = actual[key]
                if (a == null) { failures += "$id/$key: 抽出されませんでした"; continue }
                val expValue = e["value"]!!.asString!!
                val expConf = e["confidence"]!!.asDouble!!
                val expValid = e["valid"]!!.asBoolean!!
                if (a.value != expValue) failures += "$id/$key: value '${a.value}' != '$expValue'"
                if (a.confidence != expConf) failures += "$id/$key: confidence ${a.confidence} != $expConf"
                if (a.valid != expValid) failures += "$id/$key: valid ${a.valid} != $expValid"
                e["raw"]?.asString?.let { if (a.raw != it) failures += "$id/$key: raw '${a.raw}' != '$it'" }
                e["error"]?.asString?.let { if (a.error != it) failures += "$id/$key: error ${a.error} != $it" }
            }
        }
        assertTrue(failures.isEmpty(), "抽出ベクタ不一致:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `canonical json and hmac vectors`() {
        val cases = load("canonical-vectors.json")["cases"]!!.asArr!!.items
        val failures = mutableListOf<String>()
        for (c in cases) {
            val o = c.asObj!!
            val id = o["id"]!!.asString!!
            val canonical = Json.canonical(Json.parse(o["input"]!!.asString!!))
            val expectedCanonical = o["canonical"]!!.asString!!
            if (canonical != expectedCanonical) {
                failures += "$id: canonical '$canonical' != '$expectedCanonical'"
                continue
            }
            val key = Base64Url.decodeHex(o["hmac_key_hex"]!!.asString!!)
            val expectedMac = o["hmac_b64url"]!!.asString!!
            val env = JsonValue.Obj()
            env["_"] = JsonValue.Null // ダミー: sign() は Obj を要求するため直接HMACを計算する
            val mac = hmacOf(key, canonical)
            if (mac != expectedMac) failures += "$id: hmac '$mac' != '$expectedMac'"
        }
        assertTrue(failures.isEmpty(), "正準化/HMACベクタ不一致:\n" + failures.joinToString("\n"))
    }

    private fun hmacOf(key: ByteArray, message: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return Base64Url.encode(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `envelope round trip`() {
        val issued = Rfc3339.parse("2026-08-22T09:15:00Z")!!
        val key = Base64Url.decodeHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")

        val env = Envelope.build(
            handoffId = "6f1d2c9a-6b1e-4f52-9d33-2a1b0c4e77aa",
            documentType = "residency_application",
            source = Envelope.Source("ondevice-ocr", "SecureLocalOCR-Android", "0.1.0", "mlkit-ja-on-device", true),
            fields = mapOf(
                "name" to Envelope.FieldValue("山田　太郎", "ocr", 0.9, false),
                "phone" to Envelope.FieldValue("09012345678", "ocr", 0.72, true)
            ),
            issuedAtEpochSeconds = issued
        )
        Envelope.sign(env, "session:test", key)

        val ok = Envelope.verify(env, key, issued + 10, SimpleDate(2026, 8, 22))
        assertTrue(ok.ok, "検証に失敗: ${ok.error}")
        assertEquals(2, ok.fieldCount)

        // 失効後は拒否される（INV-3）
        val expired = Envelope.verify(env, key, issued + Envelope.DEFAULT_TTL_SECONDS + 1, fixedToday)
        assertEquals(Envelope.E_EXPIRED, expired.error)

        // 鍵が違えば拒否される
        val wrongKey = Base64Url.decodeHex("ff".repeat(32))
        assertEquals(Envelope.E_INTEGRITY, Envelope.verify(env, wrongKey, issued + 10, fixedToday).error)

        // 値の改ざんは正規化の再計算で検出される（HMACの前に落ちる）
        val tampered = Json.parse(Json.canonical(env)).asObj!!
        tampered["fields"]!!.asObj!!["phone"]!!.asObj!!["value"] = JsonValue.Str("0901234567X")
        assertEquals(Envelope.E_VALIDATION, Envelope.verify(tampered, key, issued + 10, fixedToday).error)
    }

    @Test
    fun `rfc3339 round trip`() {
        for (s in listOf(
            "1970-01-01T00:00:00Z", "2000-02-29T12:34:56Z",
            "2026-08-22T09:15:00Z", "2038-01-19T03:14:07Z"
        )) {
            val e = Rfc3339.parse(s)
            assertNotNull(e, "parse失敗: $s")
            assertEquals(s, Rfc3339.format(e))
        }
    }

    @Test
    fun `audit log rejects personal information`() {
        // 許可された属性だけなら通る
        val ok = AuditLog.entry("2026-08-22T09:15:00Z", AuditLog.Event.FORM_FILLED, mapOf("filled" to "6", "guessed" to "1"))
        assertTrue(ok.format().contains("FORM_FILLED"))

        // 値を書こうとしたら例外
        val cases = listOf(
            mapOf("field" to "09012345678"),
            mapOf("field" to "taro@example.com"),
            mapOf("field" to "山田　太郎"),
            mapOf("field" to "ヤマダタロウ")
        )
        for (c in cases) {
            try {
                AuditLog.entry("2026-08-22T09:15:00Z", AuditLog.Event.OCR_SUCCESS, c)
                fail("個人情報を含むログが通ってしまいました: ${c.keys}")
            } catch (e: AuditLog.PiiInLogException) {
                // 期待どおり
            }
        }

        // 未知の属性キーも拒否
        try {
            AuditLog.entry("2026-08-22T09:15:00Z", AuditLog.Event.OCR_SUCCESS, mapOf("name" to "x"))
            fail("未知の属性キーが通ってしまいました")
        } catch (e: AuditLog.PiiInLogException) {
            // 期待どおり
        }
    }

    @Test
    fun `missing required fields are reported`() {
        val lines = listOf(Extractor.Line("氏名: 山田　太郎"), Extractor.Line("電話番号: 090-1234-5678"))
        val fields = Extractor.extract(lines, "residency_application", fixedToday)
        val missing = Extractor.missingRequired(fields, "residency_application")
        assertEquals(listOf("name_kana", "birthday", "postal_code", "address"), missing)
    }
}
