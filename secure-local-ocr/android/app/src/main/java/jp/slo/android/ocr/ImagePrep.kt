package jp.slo.android.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect

/**
 * 端末内の画像補正（企画書 Step 2）。
 *
 * 外部ライブラリを使わず、Android標準のグラフィックスAPIだけで行う。
 * 原画像はここでもメモリ上だけで扱い、ディスクへは書かない（企画書 11, 12）。
 *
 * 各関数は入力のBitmapを破棄しない。破棄は呼び出し側が行う。
 * OCRを条件を変えて複数回試すには、元の画像が残っている必要があるため。
 */
object ImagePrep {

    /** 撮影時の回転量を実際のピクセルへ反映する。回転不要なら同じインスタンスを返す。 */
    fun applyRotation(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * 指定した相対矩形で切り出す。
     *
     * 注意: プレビュー上のガイド枠と撮影画像の座標系は一致しない
     * （PreviewView は既定で画面を埋めるようセンサー画像を切り詰めて表示する）。
     * ガイド枠に合わせたつもりの切り出しは、実際には別の領域を切り取ることになる。
     * そのため撮影経路ではこの関数を使わず、画像全体をOCRへ渡している。
     * 帳票の位置が固定できる運用（書画台など）でのみ意味を持つ。
     *
     * @param guide 0.0-1.0 で表した相対矩形
     */
    fun cropToGuide(source: Bitmap, guide: RelativeRect): Bitmap {
        val rect = Rect(
            (guide.left * source.width).toInt().coerceIn(0, source.width - 1),
            (guide.top * source.height).toInt().coerceIn(0, source.height - 1),
            (guide.right * source.width).toInt().coerceIn(1, source.width),
            (guide.bottom * source.height).toInt().coerceIn(1, source.height)
        )
        if (rect.width() <= 0 || rect.height() <= 0) return source
        return Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
    }

    /**
     * グレースケール化とコントラスト強調。
     *
     * 薄い印字や陰のある紙には効くが、明るい画面を撮影した写真では
     * 文字が飛んだり潰れたりして、かえって読めなくなることがある。
     * そのため常時適用せず、補正なしで読めなかったときの再試行に使う。
     */
    fun enhance(source: Bitmap, contrast: Float = 1.6f, brightness: Float = -20f): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayscale.postConcat(contrastMatrix)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(grayscale) }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    data class RelativeRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        companion object {
            /**
             * 撮影ガイド枠の比率。
             * 画面上の目安であって、切り出しには使わない（cropToGuide のコメント参照）。
             */
            val DOCUMENT_GUIDE = RelativeRect(0.04f, 0.10f, 0.96f, 0.90f)
        }
    }
}
