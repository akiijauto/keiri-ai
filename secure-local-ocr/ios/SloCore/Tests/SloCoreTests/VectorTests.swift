import XCTest
@testable import SloCore

/// protocol/testdata 配下の共通ベクタに対する Swift 実装の検証。
///
/// 同じベクタを Kotlin(Android) と JavaScript(Web) の実装も読む。
/// 3実装が同じ結果を返すことが INV-6 の担保になる。
final class VectorTests: XCTestCase {

    /// ベクタの判定が実行日に左右されないよう固定日を使う（他実装のテストと同じ日）。
    let fixedToday = SloDate(year: 2026, month: 8, day: 22)

    private func testdataDirectory() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("protocol/testdata")
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("protocol/testdata が見つかりません")
    }

    private func load(_ name: String) throws -> [String: Any] {
        let url = try testdataDirectory().appendingPathComponent(name)
        let data = try Data(contentsOf: url)
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw XCTSkip("\(name) の形式が不正です")
        }
        return obj
    }

    func testNormalizationVectors() throws {
        let doc = try load("normalization-vectors.json")
        let cases = doc["cases"] as? [[String: Any]] ?? []
        XCTAssertGreaterThanOrEqual(cases.count, 50, "ベクタ件数が少なすぎます")

        var failures: [String] = []
        for c in cases {
            let id = c["id"] as? String ?? "?"
            let field = c["field"] as? String ?? ""
            let input = c["input"] as? String ?? ""
            let r = SloNormalizer.normalize(field, input, today: fixedToday)

            if let expected = c["expected"] as? String {
                if !r.ok {
                    failures.append("\(id): エラー \(r.error ?? "") (期待値 '\(expected)')")
                } else if r.value != expected {
                    failures.append("\(id): '\(r.value ?? "")' != 期待値 '\(expected)'")
                }
            } else if let expectedError = c["error"] as? String {
                if r.ok {
                    failures.append("\(id): 成功 '\(r.value ?? "")' したが \(expectedError) を期待")
                } else if r.error != expectedError {
                    failures.append("\(id): \(r.error ?? "") != 期待 \(expectedError)")
                }
            }
        }
        XCTAssertTrue(failures.isEmpty, "正規化ベクタ不一致:\n" + failures.joined(separator: "\n"))
    }

    func testExtractionVectors() throws {
        let doc = try load("extraction-vectors.json")
        let cases = doc["cases"] as? [[String: Any]] ?? []
        var failures: [String] = []

        for c in cases {
            let id = c["id"] as? String ?? "?"
            let documentType = c["document_type"] as? String ?? "generic"
            let lineConfidence = c["line_confidence"] as? Double ?? 1.0
            // 行は文字列、または {"text":..., "box":[left,top,right,bottom]} の形で書く。
            let lines: [SloExtractor.Line] = (c["lines"] as? [Any] ?? []).compactMap { item in
                if let t = item as? String {
                    return SloExtractor.Line(text: t, confidence: lineConfidence)
                }
                guard let o = item as? [String: Any], let t = o["text"] as? String else { return nil }
                var box: SloExtractor.Box?
                if let b = o["box"] as? [Int], b.count == 4 {
                    box = SloExtractor.Box(left: b[0], top: b[1], right: b[2], bottom: b[3])
                }
                return SloExtractor.Line(text: t, confidence: lineConfidence, box: box)
            }
            let expected = c["expected"] as? [String: [String: Any]] ?? [:]
            let actual = SloExtractor.extract(lines: lines, documentType: documentType, today: fixedToday)

            let extraKeys = Set(actual.keys).subtracting(expected.keys)
            if !extraKeys.isEmpty { failures.append("\(id): 余分な項目 \(extraKeys.sorted())") }

            for (key, e) in expected {
                guard let a = actual[key] else {
                    failures.append("\(id)/\(key): 抽出されませんでした")
                    continue
                }
                if let v = e["value"] as? String, a.value != v {
                    failures.append("\(id)/\(key): value '\(a.value)' != '\(v)'")
                }
                if let conf = e["confidence"] as? Double, abs(a.confidence - conf) > 1e-9 {
                    failures.append("\(id)/\(key): confidence \(a.confidence) != \(conf)")
                }
                if let valid = e["valid"] as? Bool, a.valid != valid {
                    failures.append("\(id)/\(key): valid \(a.valid) != \(valid)")
                }
                if let raw = e["raw"] as? String, a.raw != raw {
                    failures.append("\(id)/\(key): raw '\(a.raw)' != '\(raw)'")
                }
                if let err = e["error"] as? String, a.error != err {
                    failures.append("\(id)/\(key): error \(a.error ?? "nil") != \(err)")
                }
            }
        }
        XCTAssertTrue(failures.isEmpty, "抽出ベクタ不一致:\n" + failures.joined(separator: "\n"))
    }

    func testCanonicalJsonAndHmacVectors() throws {
        let doc = try load("canonical-vectors.json")
        let cases = doc["cases"] as? [[String: Any]] ?? []
        for c in cases {
            let id = c["id"] as? String ?? "?"
            guard let input = c["input"] as? String,
                  let parsed = SloJson.parse(input) else {
                XCTFail("\(id): 入力を解析できません")
                continue
            }
            let canonical = SloJson.canonical(parsed)
            XCTAssertEqual(canonical, c["canonical"] as? String, "\(id): canonical不一致")

            let keyHex = c["hmac_key_hex"] as? String ?? ""
            let key = Data(stride(from: 0, to: keyHex.count, by: 2).map { i -> UInt8 in
                let start = keyHex.index(keyHex.startIndex, offsetBy: i)
                let end = keyHex.index(start, offsetBy: 2)
                return UInt8(keyHex[start..<end], radix: 16) ?? 0
            })
            let mac = SloEnvelope.base64url(SloEnvelope.hmac(key: key, message: canonical))
            XCTAssertEqual(mac, c["hmac_b64url"] as? String, "\(id): HMAC不一致")
        }
    }

    func testEnvelopeRoundTrip() throws {
        let issued = SloRfc3339.parse("2026-08-22T09:15:00Z")!
        let key = Data(repeating: 0xAB, count: 32)

        var env = SloEnvelope.build(
            handoffId: "6f1d2c9a-6b1e-4f52-9d33-2a1b0c4e77aa",
            documentType: "residency_application",
            source: SloEnvelope.Source(kind: "ondevice-ocr", app: "SecureLocalOCR-iOS",
                                       version: "0.1.0", engine: "apple-vision", offlineCapture: true),
            fields: [
                ("name", SloEnvelope.FieldValue(value: "山田　太郎", origin: "ocr", confidence: 0.9, edited: false)),
                ("phone", SloEnvelope.FieldValue(value: "09012345678", origin: "ocr", confidence: 0.72, edited: true))
            ],
            issuedAtEpochSeconds: issued
        )
        env = SloEnvelope.sign(env, keyId: "session:test", key: key)

        let ok = SloEnvelope.verify(env, key: key, nowEpochSeconds: issued + 10, today: fixedToday)
        XCTAssertTrue(ok.ok, "検証に失敗: \(ok.error ?? "")")
        XCTAssertEqual(ok.fieldCount, 2)

        // 失効後は拒否される（INV-3）
        let expired = SloEnvelope.verify(env, key: key,
                                         nowEpochSeconds: issued + SloEnvelope.defaultTtlSeconds + 1,
                                         today: fixedToday)
        XCTAssertEqual(expired.error, SloEnvelope.eExpired)

        // 鍵が違えば拒否される
        let wrong = SloEnvelope.verify(env, key: Data(repeating: 0xFF, count: 32),
                                       nowEpochSeconds: issued + 10, today: fixedToday)
        XCTAssertEqual(wrong.error, SloEnvelope.eIntegrity)
    }

    func testRfc3339RoundTrip() {
        for s in ["1970-01-01T00:00:00Z", "2000-02-29T12:34:56Z", "2026-08-22T09:15:00Z"] {
            let e = SloRfc3339.parse(s)
            XCTAssertNotNil(e, "parse失敗: \(s)")
            XCTAssertEqual(SloRfc3339.format(e!), s)
        }
    }

    func testAuditLogRejectsPersonalInformation() throws {
        // 許可された属性だけなら通る
        let entry = try SloAuditLog.entry(timestamp: "2026-08-22T09:15:00Z", event: .formFilled,
                                          attributes: ["filled": "6", "guessed": "1"])
        XCTAssertTrue(entry.format().contains("FORM_FILLED"))

        // 値を書こうとしたら例外
        for attrs in [["field": "09012345678"], ["field": "taro@example.com"],
                      ["field": "山田　太郎"], ["field": "ヤマダタロウ"]] {
            XCTAssertThrowsError(
                try SloAuditLog.entry(timestamp: "2026-08-22T09:15:00Z", event: .ocrSuccess, attributes: attrs),
                "個人情報を含むログが通ってしまいました: \(attrs)"
            )
        }

        // 未知の属性キーも拒否
        XCTAssertThrowsError(
            try SloAuditLog.entry(timestamp: "2026-08-22T09:15:00Z", event: .ocrSuccess, attributes: ["name": "x"])
        )
    }
}
