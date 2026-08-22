import Foundation
import Vision
import UIKit
import SloCore

/// 完全オンデバイスOCR（iOS / iPadOS 版・企画書 Step 3, 21）。
///
/// `requiresOnDeviceRecognition = true` が本システムの肝。
/// これを false にすると Apple のサーバ側処理が使われる可能性があるため、
/// 明示的に true を指定し、テストでもこの値を確認する。
enum OnDeviceOcr {

    struct Result {
        let lines: [SloExtractor.Line]
        let elapsedMillis: Int
        var lineCount: Int { lines.count }
    }

    enum OcrError: Error {
        case noCGImage
        case recognitionFailed(String)
    }

    /// 端末内で認識する。画像もテキストもネットワークへ出さない。
    static func recognize(image: UIImage, completion: @escaping (Swift.Result<Result, OcrError>) -> Void) {
        guard let cgImage = image.cgImage else {
            completion(.failure(.noCGImage))
            return
        }
        let startedAt = Date()

        let request = VNRecognizeTextRequest { request, error in
            if let error = error {
                // 例外メッセージに読み取り内容が混ざらないよう、種別だけを渡す。
                completion(.failure(.recognitionFailed("E_OCR_\(type(of: error))")))
                return
            }
            let observations = request.results as? [VNRecognizedTextObservation] ?? []
            var lines: [SloExtractor.Line] = []
            for observation in observations {
                guard let candidate = observation.topCandidates(1).first else { continue }
                lines.append(SloExtractor.Line(
                    text: candidate.string,
                    confidence: min(max(Double(candidate.confidence), 0.0), 1.0)
                ))
            }
            let elapsed = Int(Date().timeIntervalSince(startedAt) * 1000)
            completion(.success(Result(lines: lines, elapsedMillis: elapsed)))
        }

        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["ja-JP", "en-US"]
        request.usesLanguageCorrection = true
        // 原則3: OCRは端末内で完結させる。サーバ側処理へのフォールバックを禁止する。
        request.requiresOnDeviceRecognition = true

        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: image.cgImageOrientation, options: [:])
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try handler.perform([request])
            } catch {
                completion(.failure(.recognitionFailed("E_OCR_\(type(of: error))")))
            }
        }
    }
}

extension UIImage {
    /// UIImage の向きを Vision が期待する CGImagePropertyOrientation へ変換する。
    var cgImageOrientation: CGImagePropertyOrientation {
        switch imageOrientation {
        case .up: return .up
        case .down: return .down
        case .left: return .left
        case .right: return .right
        case .upMirrored: return .upMirrored
        case .downMirrored: return .downMirrored
        case .leftMirrored: return .leftMirrored
        case .rightMirrored: return .rightMirrored
        @unknown default: return .up
        }
    }
}
