import SwiftUI
import VisionKit
import SloCore

/// OCR専用カメラ画面（企画書 Step 1, 2）。
///
/// VisionKit の書類スキャナを使う。写真ライブラリには保存されず、
/// 補正済みの画像がメモリ上で返ってくるので、そのままOCRへ渡してすぐ破棄する。
struct CaptureView: View {
    @ObservedObject var session: SessionModel
    let audit: AuditFileLog

    @State private var showScanner = false

    var body: some View {
        VStack(spacing: 16) {
            Text("書類を撮影").font(.title3).bold()
            Text("撮影した画像は保存されません。OCRのあと直ちに破棄します。")
                .font(.footnote).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            if let message = session.statusMessage {
                Text(message).font(.footnote).foregroundStyle(.red)
            }

            Button("撮影してOCR") { showScanner = true }
                .buttonStyle(.borderedProminent)
                .disabled(session.step == .ocr)

            if session.step == .ocr {
                ProgressView("端末内で解析しています…")
            }
        }
        .padding(24)
        .sheet(isPresented: $showScanner) {
            DocumentScanner { images in
                showScanner = false
                guard let image = images.first else { return }
                runOcr(on: image)
            } onCancel: {
                showScanner = false
            }
        }
    }

    private func runOcr(on image: UIImage) {
        audit.add(.captureStarted)
        session.step = .ocr
        audit.add(.ocrStart, ["engine": "apple-vision"])

        OnDeviceOcr.recognize(image: image) { result in
            DispatchQueue.main.async {
                switch result {
                case .success(let ocr):
                    let extracted = SloExtractor.extract(lines: ocr.lines, documentType: session.documentType)
                    audit.add(.ocrSuccess, [
                        "count": String(ocr.lineCount),
                        "elapsed_ms": String(ocr.elapsedMillis)
                    ])
                    audit.add(.extractDone, SloAuditLog.fieldKeysAttribute(Array(extracted.keys)))
                    session.loadExtracted(extracted, elapsedMillis: ocr.elapsedMillis, lineCount: ocr.lineCount)
                    session.statusMessage = nil
                    session.step = .review

                case .failure(let error):
                    audit.add(.ocrFailed, ["reason": String(describing: error)])
                    session.statusMessage = "文字を認識できませんでした。明るさと角度を変えて撮り直してください。"
                    session.step = .capture
                }
            }
        }
    }
}

/// VisionKit の書類スキャナ。傾き補正とトリミングはOSが端末内で行う。
struct DocumentScanner: UIViewControllerRepresentable {
    let onComplete: ([UIImage]) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let parent: DocumentScanner

        init(_ parent: DocumentScanner) { self.parent = parent }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                          didFinishWith scan: VNDocumentCameraScan) {
            var images: [UIImage] = []
            for index in 0..<scan.pageCount {
                images.append(scan.imageOfPage(at: index))
            }
            parent.onComplete(images)
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            parent.onCancel()
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                          didFailWithError error: Error) {
            parent.onCancel()
        }
    }
}
