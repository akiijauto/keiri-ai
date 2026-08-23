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

    /// Vision の正規化座標（0-1・原点は左下）を、Extractor が使う画像のピクセル座標
    /// （原点は左上）へ直す。上下を反転しないと「同じ行」「直下」の判定が逆になる。
    private static func pixelBox(_ rect: CGRect, width: Int, height: Int) -> SloExtractor.Box {
        let left = Int((rect.minX * CGFloat(width)).rounded())
        let right = Int((rect.maxX * CGFloat(width)).rounded())
        let top = Int(((1.0 - rect.maxY) * CGFloat(height)).rounded())
        let bottom = Int(((1.0 - rect.minY) * CGFloat(height)).rounded())
        return SloExtractor.Box(left: left, top: top, right: max(right, left + 1), bottom: max(bottom, top + 1))
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
            // Vision は orientation を適用した後の座標系で返す。cgImage.width/height は
            // 回転前の値なので、90度回転した写真では縦横が入れ替わり、行と列の判定が狂う。
            // UIImage.size は orientation 適用済みなので、そちらから画素数を出す。
            let width = Int((image.size.width * image.scale).rounded())
            let height = Int((image.size.height * image.scale).rounded())
            var lines: [SloExtractor.Line] = []
            for observation in observations {
                guard let candidate = observation.topCandidates(1).first else { continue }
                lines.append(SloExtractor.Line(
                    text: candidate.string,
                    confidence: min(max(Double(candidate.confidence), 0.0), 1.0),
                    box: pixelBox(observation.boundingBox, width: width, height: height)
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
