import Foundation

/// Profile `jp.personal.basic/1` の項目定義（SPEC.md 5）。
public enum SloProfile {
    public static let id = "jp.personal.basic/1"

    public static let name = "name"
    public static let nameKana = "name_kana"
    public static let birthday = "birthday"
    public static let postalCode = "postal_code"
    public static let address = "address"
    public static let phone = "phone"
    public static let email = "email"
    public static let customerNo = "customer_no"
    public static let moveInDate = "move_in_date"

    public struct FieldDef {
        public let key: String
        public let label: String
        public let inputHint: String
        public let maxLength: Int
    }

    public static let fields: [FieldDef] = [
        FieldDef(key: name, label: "氏名", inputHint: "text", maxLength: 64),
        FieldDef(key: nameKana, label: "フリガナ", inputHint: "kana", maxLength: 64),
        FieldDef(key: birthday, label: "生年月日", inputHint: "date", maxLength: 10),
        FieldDef(key: postalCode, label: "郵便番号", inputHint: "postal", maxLength: 8),
        FieldDef(key: address, label: "住所", inputHint: "text", maxLength: 128),
        FieldDef(key: phone, label: "電話番号", inputHint: "tel", maxLength: 11),
        FieldDef(key: email, label: "メールアドレス", inputHint: "email", maxLength: 254),
        FieldDef(key: customerNo, label: "顧客番号", inputHint: "text", maxLength: 32),
        FieldDef(key: moveInDate, label: "入居予定日", inputHint: "date", maxLength: 10)
    ]

    public static var keys: [String] { fields.map(\.key) }

    public static func label(_ key: String) -> String {
        fields.first { $0.key == key }?.label ?? key
    }

    public static let required: [String: [String]] = [
        "residency_application": [name, nameKana, birthday, postalCode, address, phone],
        "contact_registration": [name, phone],
        "generic": []
    ]

    public static func requiredFor(_ documentType: String) -> [String] {
        required[documentType] ?? []
    }

    /// OCRテキスト中の見出し語。長いものから照合する。
    public static let labels: [(String, String)] = [
        ("申込者氏名", name), ("ご氏名", name), ("お名前", name), ("氏名", name), ("名前", name),
        ("氏名(カナ)", nameKana), ("氏名（カナ）", nameKana),
        ("フリガナ", nameKana), ("ふりがな", nameKana), ("カナ", nameKana), ("かな", nameKana),
        ("生年月日", birthday), ("誕生日", birthday),
        ("郵便番号", postalCode), ("〒", postalCode),
        ("現住所", address), ("ご住所", address), ("住所", address),
        ("携帯電話番号", phone), ("携帯電話", phone), ("電話番号", phone),
        ("連絡先電話", phone), ("電話", phone),
        ("ＴＥＬ", phone), ("TEL", phone), ("Tel", phone), ("tel", phone),
        ("メールアドレス", email), ("Ｅメール", email), ("E-mail", email), ("e-mail", email),
        ("E-MAIL", email), ("Email", email), ("EMAIL", email), ("email", email), ("メール", email),
        ("お客様番号", customerNo), ("顧客番号", customerNo),
        ("会員番号", customerNo), ("契約番号", customerNo),
        ("入居予定日", moveInDate), ("入居日", moveInDate)
    ].sorted { $0.0.count > $1.0.count }
}

/// Foundation の Calendar に依存しない最小の日付。
/// 3実装で閏年判定と和暦換算を完全に一致させるために自前で持つ。
public struct SloDate: Comparable, Equatable {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    public static func isLeap(_ y: Int) -> Bool {
        (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    }

    public static func daysInMonth(_ y: Int, _ m: Int) -> Int {
        switch m {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11: return 30
        case 2: return isLeap(y) ? 29 : 28
        default: return 0
        }
    }

    public var isValid: Bool {
        guard (1...12).contains(month), day >= 1 else { return false }
        return day <= SloDate.daysInMonth(year, month)
    }

    public var iso: String {
        String(format: "%04d-%02d-%02d", year, month, day)
    }

    public static func < (a: SloDate, b: SloDate) -> Bool {
        if a.year != b.year { return a.year < b.year }
        if a.month != b.month { return a.month < b.month }
        return a.day < b.day
    }

    public static func today() -> SloDate {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone.current
        let c = calendar.dateComponents([.year, .month, .day], from: Date())
        return SloDate(year: c.year ?? 1970, month: c.month ?? 1, day: c.day ?? 1)
    }
}

public struct NormalizeResult {
    public let value: String?
    public let error: String?
    public var ok: Bool { error == nil }

    static func ok(_ v: String) -> NormalizeResult { NormalizeResult(value: v, error: nil) }
    static func err(_ code: String) -> NormalizeResult { NormalizeResult(value: nil, error: code) }
}

/// 正規化と検証（SPEC.md 4, 5）。
///
/// Kotlin の `jp.slo.core.Normalizer` / JavaScript の `Normalizer` と同一の結果を返さねばならない。
/// 一致は protocol/testdata/normalization-vectors.json で検証する（INV-6）。
public enum SloNormalizer {

    public static let eParse = "E_PARSE"
    public static let eValidation = "E_VALIDATION"
    public static let eUnknownField = "E_UNKNOWN_FIELD"

    static let eras: [String: Int] = [
        "明治": 1868, "M": 1868, "m": 1868,
        "大正": 1912, "T": 1912, "t": 1912,
        "昭和": 1926, "S": 1926, "s": 1926,
        "平成": 1989, "H": 1989, "h": 1989,
        "令和": 2019, "R": 2019, "r": 2019
    ]

    static let minBirthYear = 1900

    private static let eraPattern =
        "^(明治|大正|昭和|平成|令和|[MTSHRmtshr])\\s*([0-9]{1,2})\\s*(?:年|[./-])\\s*([0-9]{1,2})\\s*(?:月|[./-])\\s*([0-9]{1,2})\\s*日?$"
    private static let westernPattern =
        "^([0-9]{4})\\s*(?:年|[./-])\\s*([0-9]{1,2})\\s*(?:月|[./-])\\s*([0-9]{1,2})\\s*日?$"
    private static let compactPattern = "^([0-9]{4})([0-9]{2})([0-9]{2})$"
    private static let emailPattern = "^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$"
    private static let customerPattern = "^[A-Z0-9-]{1,32}$"
    private static let kanaPattern = "^[ァ-ヶー\\u3000]+$"

    public static func normalize(_ field: String, _ input: String, today: SloDate = SloDate.today()) -> NormalizeResult {
        let s0 = SloText.stripControlChars(input)
        switch field {
        case SloProfile.name: return normalizeName(s0)
        case SloProfile.nameKana: return normalizeKana(s0)
        case SloProfile.birthday: return normalizeDate(s0, minYear: minBirthYear, notAfter: today)
        case SloProfile.moveInDate: return normalizeDate(s0, minYear: minBirthYear, notAfter: nil)
        case SloProfile.postalCode: return normalizePostal(s0)
        case SloProfile.address: return normalizeAddress(s0)
        case SloProfile.phone: return normalizePhone(s0)
        case SloProfile.email: return normalizeEmail(s0)
        case SloProfile.customerNo: return normalizeCustomerNo(s0)
        default: return .err(eUnknownField)
        }
    }

    public static func normalizeName(_ input: String) -> NormalizeResult {
        var s = SloText.halfwidthKatakanaToFullwidth(input)
        s = SloText.collapseSpacesToIdeographic(s)
        if s.isEmpty || s.count > 64 { return .err(eValidation) }
        if s.contains(where: { ($0 >= "0" && $0 <= "9") || ($0 >= "０" && $0 <= "９") }) { return .err(eValidation) }
        if s.contains("@") { return .err(eValidation) }
        return .ok(s)
    }

    public static func normalizeKana(_ input: String) -> NormalizeResult {
        var s = SloText.halfwidthKatakanaToFullwidth(input)
        s = SloText.hiraganaToKatakana(s)
        s = SloText.collapseSpacesToIdeographic(s)
        if s.isEmpty || s.count > 64 { return .err(eValidation) }
        if !matches(s, kanaPattern) { return .err(eValidation) }
        return .ok(s)
    }

    public static func normalizeDate(_ input: String, minYear: Int, notAfter: SloDate?) -> NormalizeResult {
        let raw = SloText.removeAllSpaces(SloText.toHalfwidthAscii(input))
        guard let date = parseDate(raw) else { return .err(eParse) }
        if !date.isValid { return .err(eValidation) }
        if date.year < minYear { return .err(eValidation) }
        if let limit = notAfter, date > limit { return .err(eValidation) }
        return .ok(date.iso)
    }

    /// 和暦・西暦・区切り記号ゆれ・OCR誤読数字を吸収する。読めなければ nil。
    private static func parseDate(_ raw: String) -> SloDate? {
        if let g = capture(raw, eraPattern), let base = eras[g[1]] {
            return SloDate(year: base + (Int(g[2]) ?? 0) - 1, month: Int(g[3]) ?? 0, day: Int(g[4]) ?? 0)
        }
        // 元号として読めなかった場合に限り数字のOCR誤読補正を適用する
        // （元号記号 S/H/R を数字へ潰さないための順序）。
        let fixed = SloText.fixOcrDigits(raw)
        if let g = capture(fixed, westernPattern) {
            return SloDate(year: Int(g[1]) ?? 0, month: Int(g[2]) ?? 0, day: Int(g[3]) ?? 0)
        }
        if let g = capture(fixed, compactPattern) {
            return SloDate(year: Int(g[1]) ?? 0, month: Int(g[2]) ?? 0, day: Int(g[3]) ?? 0)
        }
        return nil
    }

    public static func normalizePostal(_ input: String) -> NormalizeResult {
        let s = SloText.digitsOnly(SloText.fixOcrDigits(SloText.toHalfwidthAscii(input)))
        guard s.count == 7 else { return .err(eValidation) }
        let idx = s.index(s.startIndex, offsetBy: 3)
        return .ok("\(s[s.startIndex..<idx])-\(s[idx...])")
    }

    public static func normalizeAddress(_ input: String) -> NormalizeResult {
        var s = SloText.halfwidthKatakanaToFullwidth(input)
        s = SloText.digitsToHalfwidth(s)
        s = SloText.normalizeHyphensBetweenDigits(s)
        s = SloText.collapseSpacesToIdeographic(s)
        if s.isEmpty || s.count > 128 { return .err(eValidation) }
        return .ok(s)
    }

    public static func normalizePhone(_ input: String) -> NormalizeResult {
        var s = SloText.toHalfwidthAscii(input)
        s = SloText.fixOcrDigits(s)
        s = SloText.removeAllSpaces(s)
        if s.hasPrefix("+81") { s = "0" + s.dropFirst(3) }
        if s.hasPrefix("+") { return .err(eValidation) }
        let digits = SloText.digitsOnly(s)
        guard digits.count >= 10, digits.count <= 11 else { return .err(eValidation) }
        guard digits.hasPrefix("0") else { return .err(eValidation) }
        return .ok(digits)
    }

    public static func normalizeEmail(_ input: String) -> NormalizeResult {
        var s = SloText.toHalfwidthAscii(input)
        s = SloText.removeAllSpaces(s).lowercased()
        if s.isEmpty || s.count > 254 { return .err(eValidation) }
        if !matches(s, emailPattern) { return .err(eValidation) }
        return .ok(s)
    }

    public static func normalizeCustomerNo(_ input: String) -> NormalizeResult {
        var s = SloText.toHalfwidthAscii(input)
        s = SloText.normalizeAllHyphens(s)
        s = SloText.removeAllSpaces(s).uppercased()
        if !matches(s, customerPattern) { return .err(eValidation) }
        return .ok(s)
    }

    // MARK: - 正規表現ヘルパ

    /// パターンは定数なので、組み立てに失敗するのはプログラムの誤り。
    ///
    /// 以前は `try?` で握りつぶしていたため、無効なパターン
    /// （ICUが解さない `\u{3000}` を書いていた）が「一致しない」として
    /// 静かに素通りし、正規化と抽出が丸ごと働いていないことに気づけなかった。
    /// 握りつぶさず、その場で落とす。
    private static var cache: [String: NSRegularExpression] = [:]
    private static let cacheLock = NSLock()

    static func regex(_ pattern: String) -> NSRegularExpression {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        if let re = cache[pattern] { return re }
        guard let re = try? NSRegularExpression(pattern: pattern) else {
            preconditionFailure("正規表現が不正です: \(pattern)")
        }
        cache[pattern] = re
        return re
    }

    static func matches(_ s: String, _ pattern: String) -> Bool {
        let re = regex(pattern)
        let range = NSRange(s.startIndex..., in: s)
        return re.firstMatch(in: s, range: range) != nil
    }

    static func capture(_ s: String, _ pattern: String) -> [String]? {
        let re = regex(pattern)
        let range = NSRange(s.startIndex..., in: s)
        guard let m = re.firstMatch(in: s, range: range) else { return nil }
        var groups: [String] = []
        for i in 0..<m.numberOfRanges {
            if let swiftRange = Range(m.range(at: i), in: s) {
                groups.append(String(s[swiftRange]))
            } else {
                groups.append("")
            }
        }
        return groups
    }
}
