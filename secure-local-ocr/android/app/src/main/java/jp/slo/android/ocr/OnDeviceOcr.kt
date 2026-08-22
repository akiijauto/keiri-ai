package jp.slo.android.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import jp.slo.core.Extractor

/**
 * 完全オンデバイスOCR（企画書 Step 3 / 原則3）。
 *
 * ML Kit の日本語テキスト認識をモデル同梱（bundled）で使う。
 * モデルはAPKに含まれるため、初回起動時であってもモデル取得の通信は発生しない。
 * 画像もテキストも、このクラスの外（ネットワーク）へは一切出ない。
 */
class OnDeviceOcr(
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
) {

    data class Result(val lines: List<Extractor.Line>, val elapsedMillis: Long) {
        val lineCount: Int get() = lines.size
    }

    /**
     * @param bitmap 前処理済みのビットマップ。呼び出し側は使用後すぐに破棄すること。
     * @param onResult 認識結果（行の並び）。値はメモリ上にのみ存在する。
     */
    fun recognize(
        bitmap: Bitmap,
        onResult: (Result) -> Unit,
        onError: (String) -> Unit
    ) {
        val startedAt = System.currentTimeMillis()
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = mutableListOf<Extractor.Line>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        // confidence は端末・モデルによっては返らない。その場合は 1.0 として扱う。
                        val confidence = (line.confidence as? Float)?.toDouble() ?: 1.0
                        lines.add(Extractor.Line(line.text, confidence.coerceIn(0.0, 1.0)))
                    }
                }
                onResult(Result(lines, System.currentTimeMillis() - startedAt))
            }
            .addOnFailureListener { e ->
                // 例外メッセージに読み取り内容が混ざらないよう、種別だけを渡す。
                onError("E_OCR_${e.javaClass.simpleName}")
            }
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
