import Foundation

/// OCR結果（行の並び）から業務項目を抽出する（企画書 Step 4）。
///
/// Kotlin の `jp.slo.core.Extractor` と同一のルール。
/// 完全に端末内で動く決定的な処理であり、外部APIも生成AIも使わない。
public enum SloExtractor {

    /// OCRエンジンが返す行の外接矩形（画像のピクセル座標。原点は左上）。
    ///
    /// 帳票は「ラベル列 | 値列」の表であることが多い。OCRエンジンは列ごとに
    /// 別ブロックとして返すことがあり、その場合、行の並び順だけでラベルと値を
    /// 対応づけると全く別の値と結びついてしまう。位置で対応づけるために持つ。
    public struct Box {
        public let left: Int
        public let top: Int
        public let right: Int
        public let bottom: Int

        public init(left: Int, top: Int, right: Int, bottom: Int) {
            self.left = left
            self.top = top
            self.right = right
            self.bottom = bottom
        }

        public var width: Int { right - left }
        public var height: Int { bottom - top }
        public var centerX: Int { (left + right) / 2 }

        /// 縦方向の重なりが互いの高さの半分以上なら「同じ行」とみなす。
        public func sameRow(_ other: Box) -> Bool {
            Box.overlapRatio(top, bottom, other.top, other.bottom) >= 0.5
        }

        /// 横方向の重なりが半分以上なら「同じ列」とみなす。
        public func sameColumn(_ other: Box) -> Bool {
            Box.overlapRatio(left, right, other.left, other.right) >= 0.5
        }

        /// other より下の行にあるか。
        ///
        /// 折り返した行の外接矩形は、前の行と数ピクセル重なることがある
        /// （実際のOCR出力で確認）。`top >= other.bottom` で判定すると
        /// 折り返しを取りこぼすため、「同じ行ではなく、より下から始まる」で判定する。
        public func isBelow(_ other: Box) -> Bool { !sameRow(other) && top > other.top }

        /// other の下端からこの矩形の上端までの間隔。重なっている場合は0。
        public func gapBelow(_ other: Box) -> Int { max(0, top - other.bottom) }

        private static func overlapRatio(_ aFrom: Int, _ aTo: Int, _ bFrom: Int, _ bTo: Int) -> Double {
            let span = min(aTo - aFrom, bTo - bFrom)
            if span <= 0 { return 0 }
            let overlap = min(aTo, bTo) - max(aFrom, bFrom)
            return overlap <= 0 ? 0 : Double(overlap) / Double(span)
        }
    }

    /// OCRの1行。box は取得できた場合のみ。無い場合は行の並び順で対応づける（従来動作）。
    public struct Line {
        public let text: String
        public let confidence: Double
        public let box: Box?

        public init(text: String, confidence: Double = 1.0, box: Box? = nil) {
            self.text = text
            self.confidence = confidence
            self.box = box
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
    /// 位置で「ラベルの右隣のセル」と特定できた場合。並び順頼みより確からしい。
    static let adjacentCell = 0.85
    static let nextLine = 0.75
    static let patternOnly = 0.60
    static let addressMaxContinuation = 2
    /// ラベルの右隣を探す横方向の上限。ラベル高さの倍数。離れすぎた列を誤って拾わない。
    static let maxGapInLabelHeights = 8.0

    static let separators = "^[\\s\\u3000:：=・>＞\\]］|｜]+"
    static let postalLine = "^[\\s\\u3000]*〒?[\\s\\u3000]*([0-9]{3}[-ー－][0-9]{4})[\\s\\u3000]*$"
    static let phoneLine =
        "^[\\s\\u3000]*[(（]?(0[0-9]{1,3})[)）]?[-ー－\\s]?([0-9]{2,4})[-ー－\\s]?([0-9]{4})[\\s\\u3000]*$"
    static let emailLine = "^[\\s\\u3000]*([^\\s\\u3000@]+@[^\\s\\u3000@]+\\.[^\\s\\u3000@]+)[\\s\\u3000]*$"

    public static func extract(
        lines: [Line],
        documentType: String = "generic",
        today: SloDate = SloDate.today()
    ) -> [String: Field] {
        var out: [String: Field] = [:]
        var order: [String] = []

        // 1) ラベル照合（同一行に値がある → 位置で右隣/直下のセルを探す → 並び順で次行）
        for i in lines.indices {
            guard let hit = findLabel(lines[i].text) else { continue }
            let (key, endOffset) = hit
            if out[key] != nil { continue }

            let chars = Array(lines[i].text)
            var rawValue = replacingPattern(String(chars[endOffset...]), separators, with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            var base = sameLine
            var valueIndex = i

            if rawValue.isEmpty {
                guard let found = findValueCell(lines, labelIndex: i) else { continue }
                rawValue = lines[found.index].text.trimmingCharacters(in: .whitespacesAndNewlines)
                base = found.base
                valueIndex = found.index
            }

            if key == SloProfile.address {
                rawValue += collectAddressContinuation(lines, from: valueIndex)
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

    private struct ValueCell {
        let index: Int
        let base: Double
    }

    /// ラベル行だけで値を持たない場合に、値が書かれている行を探す。
    ///
    /// 外接矩形が取れる場合は位置で探す。ラベル列と値列が別ブロックとして
    /// 返されると並び順は「ラベル、ラベル、…、値、値、…」になり得るため、
    /// 並び順に頼ると全項目が隣のラベルと衝突して取れなくなる（Androidの実機で確認）。
    /// 位置が分かっているのに見つからなかったときは、並び順へは戻らない。
    /// 戻ると無関係な行を値として拾ってしまうため（例: 入居予定日に顧客番号）。
    private static func findValueCell(_ lines: [Line], labelIndex: Int) -> ValueCell? {
        if let labelBox = lines[labelIndex].box {
            if let j = rightOfLabel(lines, labelIndex: labelIndex, labelBox: labelBox) {
                return ValueCell(index: j, base: adjacentCell)
            }
            if let j = belowLabel(lines, labelIndex: labelIndex, labelBox: labelBox) {
                return ValueCell(index: j, base: nextLine)
            }
            return nil
        }
        guard let next = nextContentLine(lines, from: labelIndex) else { return nil }
        if findLabel(lines[next].text) != nil { return nil }
        return ValueCell(index: next, base: nextLine)
    }

    /// 同じ行で、ラベルの右側にある最も近い行。表の「ラベル | 値」に対応する。
    private static func rightOfLabel(_ lines: [Line], labelIndex: Int, labelBox: Box) -> Int? {
        let maxGap = Double(labelBox.height) * maxGapInLabelHeights
        var best: Int?
        var bestLeft = Int.max
        for j in lines.indices where j != labelIndex {
            guard let box = lines[j].box else { continue }
            if lines[j].text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            if !labelBox.sameRow(box) { continue }
            if box.centerX <= labelBox.right { continue }
            if Double(box.left - labelBox.right) > maxGap { continue }
            if findLabel(lines[j].text) != nil { continue }
            if box.left < bestLeft {
                bestLeft = box.left
                best = j
            }
        }
        return best
    }

    /// 同じ列で、ラベルのすぐ下にある行。ラベルが値の上に置かれる帳票に対応する。
    private static func belowLabel(_ lines: [Line], labelIndex: Int, labelBox: Box) -> Int? {
        let maxGap = Double(labelBox.height) * maxGapInLabelHeights
        var best: Int?
        var bestTop = Int.max
        for j in lines.indices where j != labelIndex {
            guard let box = lines[j].box else { continue }
            if lines[j].text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            if !box.isBelow(labelBox) { continue }
            if Double(box.gapBelow(labelBox)) > maxGap { continue }
            if !labelBox.sameColumn(box) { continue }
            if findLabel(lines[j].text) != nil { continue }
            if hasLabelToLeft(lines, index: j, box: box) { continue }
            if box.top < bestTop {
                bestTop = box.top
                best = j
            }
        }
        return best
    }

    /// その行の左側（同じ行）に既知のラベルがあるか。別項目の値を掴んでいないかの判定。
    private static func hasLabelToLeft(_ lines: [Line], index: Int, box: Box) -> Bool {
        anyToLeft(lines, index: index, box: box) { findLabel($0) != nil }
    }

    /// その行の左側（同じ行）に何か書かれているか。表の行かセル内の折り返しかの判定。
    private static func hasContentToLeft(_ lines: [Line], index: Int, box: Box) -> Bool {
        anyToLeft(lines, index: index, box: box) {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    private static func anyToLeft(
        _ lines: [Line], index: Int, box: Box, predicate: (String) -> Bool
    ) -> Bool {
        for j in lines.indices where j != index {
            guard let other = lines[j].box else { continue }
            if !box.sameRow(other) { continue }
            if other.centerX >= box.left { continue }
            if predicate(lines[j].text) { return true }
        }
        return false
    }

    private static func nextContentLine(_ lines: [Line], from: Int) -> Int? {
        for j in (from + 1)..<lines.count {
            if lines[j].text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            return j
        }
        return nil
    }

    /// 折り返した住所の続きを集める。
    ///
    /// 外接矩形が取れる場合は、値と同じ列の直下だけを続きとみなす。
    /// 並び順で集めると、表では隣の列の別項目まで住所へ連結してしまう。
    private static func collectAddressContinuation(_ lines: [Line], from: Int) -> String {
        var out = ""
        var added = 0

        if let fromBox = lines[from].box {
            var current: Box = fromBox
            let maxGap = Double(fromBox.height) * 1.5
            while added < addressMaxContinuation {
                var next: Int?
                var nextTop = Int.max
                for j in lines.indices where j != from {
                    guard let box = lines[j].box else { continue }
                    if lines[j].text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
                    if !box.isBelow(current) { continue }
                    if Double(box.gapBelow(current)) > maxGap { continue }
                    if !fromBox.sameColumn(box) { continue }
                    if box.top < nextTop {
                        nextTop = box.top
                        next = j
                    }
                }
                guard let j = next else { break }
                let t = lines[j].text
                if findLabel(t) != nil { break }
                if matchPattern(t) != nil { break }
                // 表では、値の左側にラベルのセルがある。左に何か書かれている行は
                // 別項目の行であって、住所セル内の折り返しではない。
                // ラベル語彙に無い項目（勤務先など）でも同じ判定ができる。
                if hasContentToLeft(lines, index: j, box: lines[j].box!) { break }
                out += "　" + t.trimmingCharacters(in: .whitespacesAndNewlines)
                current = lines[j].box!
                added += 1
            }
            return out
        }

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
        let re = SloNormalizer.regex(pattern)
        let range = NSRange(s.startIndex..., in: s)
        return re.stringByReplacingMatches(in: s, range: range, withTemplate: replacement)
    }
}
