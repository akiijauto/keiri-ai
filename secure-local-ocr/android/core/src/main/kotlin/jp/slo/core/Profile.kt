package jp.slo.core

/**
 * Profile `jp.personal.basic/1` の項目定義（SPEC.md 5）。
 *
 * この定義はOCRアプリと入居Webフォームの双方が参照する「同じ基準」の実体である。
 */
object Profile {

    const val ID = "jp.personal.basic/1"

    const val NAME = "name"
    const val NAME_KANA = "name_kana"
    const val BIRTHDAY = "birthday"
    const val POSTAL_CODE = "postal_code"
    const val ADDRESS = "address"
    const val PHONE = "phone"
    const val EMAIL = "email"
    const val CUSTOMER_NO = "customer_no"
    const val MOVE_IN_DATE = "move_in_date"

    data class FieldDef(
        val key: String,
        val label: String,
        val inputHint: String,
        val maxLength: Int
    )

    val FIELDS: List<FieldDef> = listOf(
        FieldDef(NAME, "氏名", "text", 64),
        FieldDef(NAME_KANA, "フリガナ", "kana", 64),
        FieldDef(BIRTHDAY, "生年月日", "date", 10),
        FieldDef(POSTAL_CODE, "郵便番号", "postal", 8),
        FieldDef(ADDRESS, "住所", "text", 128),
        FieldDef(PHONE, "電話番号", "tel", 11),
        FieldDef(EMAIL, "メールアドレス", "email", 254),
        FieldDef(CUSTOMER_NO, "顧客番号", "text", 32),
        FieldDef(MOVE_IN_DATE, "入居予定日", "date", 10)
    )

    val KEYS: List<String> = FIELDS.map { it.key }

    fun def(key: String): FieldDef? = FIELDS.firstOrNull { it.key == key }

    fun label(key: String): String = def(key)?.label ?: key

    /** document_type ごとの必須項目（SPEC.md 5）。 */
    val REQUIRED: Map<String, List<String>> = mapOf(
        "residency_application" to listOf(NAME, NAME_KANA, BIRTHDAY, POSTAL_CODE, ADDRESS, PHONE),
        "contact_registration" to listOf(NAME, PHONE),
        "generic" to emptyList()
    )

    fun requiredFor(documentType: String): List<String> = REQUIRED[documentType] ?: emptyList()

    /** OCRテキスト中の見出し語。長いものから照合する（「メールアドレス」が「メール」に負けないように）。 */
    val LABELS: List<Pair<String, String>> = listOf(
        "申込者氏名" to NAME, "ご氏名" to NAME, "お名前" to NAME, "氏名" to NAME, "名前" to NAME,
        "氏名(カナ)" to NAME_KANA, "氏名（カナ）" to NAME_KANA,
        "フリガナ" to NAME_KANA, "ふりがな" to NAME_KANA, "カナ" to NAME_KANA, "かな" to NAME_KANA,
        "生年月日" to BIRTHDAY, "誕生日" to BIRTHDAY,
        "郵便番号" to POSTAL_CODE, "〒" to POSTAL_CODE,
        "現住所" to ADDRESS, "ご住所" to ADDRESS, "住所" to ADDRESS,
        "携帯電話番号" to PHONE, "携帯電話" to PHONE, "電話番号" to PHONE,
        "連絡先電話" to PHONE, "電話" to PHONE,
        "ＴＥＬ" to PHONE, "TEL" to PHONE, "Tel" to PHONE, "tel" to PHONE,
        "メールアドレス" to EMAIL, "Ｅメール" to EMAIL, "E-mail" to EMAIL, "e-mail" to EMAIL,
        "E-MAIL" to EMAIL, "Email" to EMAIL, "EMAIL" to EMAIL, "email" to EMAIL, "メール" to EMAIL,
        "お客様番号" to CUSTOMER_NO, "顧客番号" to CUSTOMER_NO,
        "会員番号" to CUSTOMER_NO, "契約番号" to CUSTOMER_NO,
        "入居予定日" to MOVE_IN_DATE, "入居日" to MOVE_IN_DATE
    ).sortedByDescending { it.first.length }
}
