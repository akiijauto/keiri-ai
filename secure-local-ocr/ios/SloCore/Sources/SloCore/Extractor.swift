import Foundation

/// OCR結果（行の並び）から業務項目を抽出する（企画書 Step 4）。
///
/// Kotlin の `jp.slo.core.Extractor` と同一のルール。
/// 完全に端末内で動く決定的な処理であり、外部APIも生成AIも使わない。
public enum SloExtractor {

    public struct Line {
        public let text: String
        public let confidence: Double

        public init(text: String, confidence: Double = 1.0) {
            self.text = text
            self.confidence = confidence
        }
    }

    public struct Field {
        public let key: String
        /// 正規化に成功した値。失敗時は空文字。
        public let value: String
        /// OCRが読み取った生の文字列。確認画面で「元の読み取り」として表示する。
        public let raw: String
        public let confidence: Double
        public let valid: Bool
        public let error: String?
        public let origin: String

        public func needsReview(threshold: Double = 0.80) -> Bool {
            !valid || confidence < threshold
        }
    }

    static let sameLine = 0.90
    static let nextLine = 0.75
    static let patternOnly = 0.60
    static let addressMaxContinuation = 2

    static let separators = "^[\\s\\u{3000}:：=・>＞\\]］|｜]+"
    static let postalLine = "^[\\s\\u{3000}]*〒?[\\s\\u{3000}]*([0-9]{3}[-ー－][0-9]{4})[\\s\\u{3000}]*$"
    static let phoneLine =
        "^[\\s\\u{3000}]*[(（]?(0[0-9]{1,3})[)）]?[-ー－\\s]?([0-9]{2,4})[-ー－\\s]?([0-9]{4})[\\s\\u{3000}]*$"
    static let emailLine = "^[\\s\\u{3000}]*([^\\s\\u{3000}@]+@[^\\s\\u{3000}@]+\\.[^\\s\\u{3000}@]+)[\\s\\u{3000}]*$"

    public static func extract(
        lines: [Line],
        documentType: String = "generic",
        today: SloDate = SloDate.today()
    ) -> [String: Field] {
        var out: [String: Field] = [:]
        var order: [String] = []

        // 1) ラベル照合（同一行 → 次行）
        for i in lines.indices {
            guard let hit = findLabel(lines[i].text) else { continue }
            let (key, endOffset) = hit
            if out[key] != nil { continue }

            let chars = Array(lines[i].text)
            var rawValue = replacingPattern(String(chars[endOffset...]), separators, with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            var base = sameLine

            if rawValue.isEmpty {
                guard let next = nextContentLine(lines, from: i) else { continue }
                if findLabel(next.text) != nil { continue }
                rawValue = next.text.trimmingCharacters(in: .whitespacesAndNewlines)
                base = nextLine
            } else if key == SloProfile.address {
                rawValue += collectAddressContinuation(lines, from: i)
            }

            if rawValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            out[key] = build(key: key, raw: rawValue, confidence: base * lines[i].confidence, today: today)
            order.append(key)
        }

        // 2) ラベルが無い行のパターン照合（未取得の項目のみ）
        for line in lines {
            if findLabel(line.text) != nil { continue }
            guard let (key, value) = matchPattern(line.text) else { continue }
            if out[key] != nil { continue }
            out[key] = build(key: key, raw: value, confidence: patternOnly * line.confidence, today: today)
            order.append(key)
        }

        return out
    }

    /// 必須項目のうち未取得のものを返す。確認画面で「未入力」として提示する。
    public static func missingRequired(_ fields: [String: Field], documentType: String) -> [String] {
        SloProfile.requiredFor(documentType).filter { key in
            guard let f = fields[key] else { return true }
            return !f.valid || f.value.isEmpty
        }
    }

    private static func build(key: String, raw: String, confidence: Double, today: SloDate) -> Field {
        let r = SloNormalizer.normalize(key, raw, today: today)
        if r.ok, let v = r.value {
            return Field(key: key, value: v, raw: raw, confidence: round2(confidence),
                         valid: true, error: nil, origin: "ocr")
        }
        return Field(key: key, value: "", raw: raw, confidence: 0.0, valid: false, error: r.error, origin: "ocr")
    }

    private static func round2(_ v: Double) -> Double {
        (v * 100).rounded() / 100
    }

    /// 行の中で最も左、同着なら最も長いラベルを採用する。返り値は (項目キー, ラベル終端の文字位置)。
    static func findLabel(_ text: String) -> (String, Int)? {
        let chars = Array(text)
        var bestPos = Int.max
        var bestLen = 0
        var bestKey: String?
        for (label, key) in SloProfile.labels {
            guard let range = text.range(of: label) else { continue }
            let pos = text.distance(from: text.startIndex, to: range.lowerBound)
            let len = label.count
            if pos < bestPos || (pos == bestPos && len > bestLen) {
                bestPos = pos
                bestLen = len
                bestKey = key
            }
        }
        guard let key = bestKey else { return nil }
        return (key, min(bestPos + bestLen, chars.count))
    }

    private static func nextContentLine(_ lines: [Line], from: Int) -> Line? {
        for j in (from + 1)..<lines.count {
            if lines[j].text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            return lines[j]
        }
        return nil
    }

    /// 住所は次行へ折り返すことが多いので、ラベルの無い後続行を連結する。
    private static func collectAddressContinuation(_ lines: [Line], from: Int) -> String {
        var out = ""
        var added = 0
        var j = from + 1
        while j < lines.count && added < addressMaxContinuation {
            let t = lines[j].text
            if t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { break }
            if findLabel(t) != nil { break }
            if matchPattern(t) != nil { break }
            out += "　" + t.trimmingCharacters(in: .whitespacesAndNewlines)
            added += 1
            j += 1
        }
        return out
    }

    /// ラベルの無い行から、行全体が1つの値になっているものだけを拾う。
    static func matchPattern(_ text: String) -> (String, String)? {
        if let g = SloNormalizer.capture(text, emailLine) { return (SloProfile.email, g[1]) }
        if SloNormalizer.matches(text, phoneLine) {
            return (SloProfile.phone, text.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        if let g = SloNormalizer.capture(text, postalLine) { return (SloProfile.postalCode, g[1]) }
        return nil
    }

    private static func replacingPattern(_ s: String, _ pattern: String, with replacement: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }
        let range = NSRange(s.startIndex..., in: s)
        return re.stringByReplacingMatches(in: s, range: range, withTemplate: replacement)
    }
}
