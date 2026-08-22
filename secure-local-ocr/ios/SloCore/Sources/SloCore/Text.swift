import Foundation

/// 日本語テキストの幅・字種変換ユーティリティ。
///
/// Kotlin の `jp.slo.core.Text` / JavaScript の `Text` と1対1で対応する。
/// NFKCを使わないのは、住所の丸数字・ローマ数字・単位記号を壊すため（SPEC.md 4）。
public enum SloText {

    static let ideographicSpace: Character = "　"

    /// OCRで数字が誤読されやすい文字。数字が期待される文脈でのみ適用する。
    static let ocrDigitFix: [Character: Character] = [
        "O": "0", "o": "0", "〇": "0", "D": "0",
        "I": "1", "l": "1", "｜": "1", "|": "1",
        "S": "5", "s": "5",
        "B": "8",
        "Z": "2",
        "q": "9"
    ]

    static let hyphenLike: Set<Character> = [
        "－", "−", "‐", "‑", "‒", "–", "—", "―", "ー", "ｰ", "⁃", "˗"
    ]

    static let halfwidthKatakana: [Character: Character] = [
        "｡": "。", "｢": "「", "｣": "」", "､": "、", "･": "・",
        "ｦ": "ヲ", "ｧ": "ァ", "ｨ": "ィ", "ｩ": "ゥ", "ｪ": "ェ", "ｫ": "ォ",
        "ｬ": "ャ", "ｭ": "ュ", "ｮ": "ョ", "ｯ": "ッ", "ｰ": "ー",
        "ｱ": "ア", "ｲ": "イ", "ｳ": "ウ", "ｴ": "エ", "ｵ": "オ",
        "ｶ": "カ", "ｷ": "キ", "ｸ": "ク", "ｹ": "ケ", "ｺ": "コ",
        "ｻ": "サ", "ｼ": "シ", "ｽ": "ス", "ｾ": "セ", "ｿ": "ソ",
        "ﾀ": "タ", "ﾁ": "チ", "ﾂ": "ツ", "ﾃ": "テ", "ﾄ": "ト",
        "ﾅ": "ナ", "ﾆ": "ニ", "ﾇ": "ヌ", "ﾈ": "ネ", "ﾉ": "ノ",
        "ﾊ": "ハ", "ﾋ": "ヒ", "ﾌ": "フ", "ﾍ": "ヘ", "ﾎ": "ホ",
        "ﾏ": "マ", "ﾐ": "ミ", "ﾑ": "ム", "ﾒ": "メ", "ﾓ": "モ",
        "ﾔ": "ヤ", "ﾕ": "ユ", "ﾖ": "ヨ",
        "ﾗ": "ラ", "ﾘ": "リ", "ﾙ": "ル", "ﾚ": "レ", "ﾛ": "ロ",
        "ﾜ": "ワ", "ﾝ": "ン"
    ]

    static let dakuten = "カキクケコサシスセソタチツテトハヒフヘホウ"
    static let handakuten = "ハヒフヘホ"

    public static func stripControlChars(_ s: String) -> String {
        var out = ""
        for ch in s.unicodeScalars {
            if ch == "\t" {
                out.append(" ")
            } else if ch.value >= 0x20 {
                out.unicodeScalars.append(ch)
            }
        }
        return out
    }

    /// 全角ASCII(U+FF01-U+FF5E)と全角スペースを半角へ。
    public static func toHalfwidthAscii(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for u in s.unicodeScalars {
            if u.value >= 0xFF01 && u.value <= 0xFF5E {
                out.append(Unicode.Scalar(u.value - 0xFEE0)!)
            } else if u.value == 0x3000 {
                out.append(" ")
            } else {
                out.append(u)
            }
        }
        return String(out)
    }

    /// 全角数字だけを半角へ（住所など、英字は全角のまま残したい場合に使う）。
    public static func digitsToHalfwidth(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for u in s.unicodeScalars {
            if u.value >= 0xFF10 && u.value <= 0xFF19 {
                out.append(Unicode.Scalar(u.value - 0xFEE0)!)
            } else {
                out.append(u)
            }
        }
        return String(out)
    }

    /// 半角カタカナを全角カタカナへ（濁点・半濁点を合成する）。
    public static func halfwidthKatakanaToFullwidth(_ s: String) -> String {
        let chars = Array(s)
        var out = ""
        var i = 0
        while i < chars.count {
            guard let base = halfwidthKatakana[chars[i]] else {
                out.append(chars[i])
                i += 1
                continue
            }
            let next: Character? = i + 1 < chars.count ? chars[i + 1] : nil
            if next == "ﾞ", dakuten.contains(base) {
                out.append(base == "ウ" ? "ヴ" : shift(base, by: 1))
                i += 2
            } else if next == "ﾟ", handakuten.contains(base) {
                out.append(shift(base, by: 2))
                i += 2
            } else {
                out.append(base)
                i += 1
            }
        }
        return out
    }

    public static func hiraganaToKatakana(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for u in s.unicodeScalars {
            if u.value >= 0x3041 && u.value <= 0x3096 {
                out.append(Unicode.Scalar(u.value + 0x60)!)
            } else {
                out.append(u)
            }
        }
        return String(out)
    }

    /// 連続する空白（半角/全角/タブ）を全角スペース1個へ畳み、前後を除去する。
    public static func collapseSpacesToIdeographic(_ s: String) -> String {
        var out = ""
        var inSpace = false
        for ch in s {
            if ch == " " || ch == "\t" || ch == ideographicSpace {
                if !inSpace { out.append(ideographicSpace) }
                inSpace = true
            } else {
                out.append(ch)
                inSpace = false
            }
        }
        while let f = out.first, f == " " || f == "\t" || f == ideographicSpace { out.removeFirst() }
        while let l = out.last, l == " " || l == "\t" || l == ideographicSpace { out.removeLast() }
        return out
    }

    public static func removeAllSpaces(_ s: String) -> String {
        String(s.filter { !$0.isWhitespace && $0 != ideographicSpace })
    }

    /// 数字文脈でのOCR誤読補正。英数字IDや氏名には使わないこと。
    public static func fixOcrDigits(_ s: String) -> String {
        String(s.map { ocrDigitFix[$0] ?? $0 })
    }

    public static func isHyphenLike(_ ch: Character) -> Bool {
        ch == "-" || hyphenLike.contains(ch)
    }

    /// ハイフン類を半角ハイフンへ。数字に挟まれている場合のみ変換する。
    public static func normalizeHyphensBetweenDigits(_ s: String) -> String {
        var chars = Array(s)
        for i in chars.indices {
            guard isHyphenLike(chars[i]) else { continue }
            let prev: Character? = i > 0 ? chars[i - 1] : nil
            let next: Character? = i < chars.count - 1 ? chars[i + 1] : nil
            if let p = prev, let n = next, p.isASCIIDigit, n.isASCIIDigit {
                chars[i] = "-"
            }
        }
        return String(chars)
    }

    /// 電話番号・郵便番号など、値全体が数値の項目に使う。
    public static func normalizeAllHyphens(_ s: String) -> String {
        String(s.map { isHyphenLike($0) ? "-" : $0 })
    }

    public static func digitsOnly(_ s: String) -> String {
        String(s.filter { $0.isASCIIDigit })
    }

    private static func shift(_ ch: Character, by delta: UInt32) -> Character {
        let value = ch.unicodeScalars.first!.value + delta
        return Character(Unicode.Scalar(value)!)
    }
}

extension Character {
    var isASCIIDigit: Bool { self >= "0" && self <= "9" }
}
