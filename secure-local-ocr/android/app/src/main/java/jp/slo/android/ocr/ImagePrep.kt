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
 * OCR精度に効くのは主に「傾き」「明るさ」「コントラスト」なので、
 * MVPではその3つと切り出しに絞る。
 *
 * 原画像はここでもメモリ上だけで扱い、ディスクへは書かない（企画書 11, 12）。
 */
object ImagePrep {

    /** 撮影時の回転量を実際のピクセルへ反映する。 */
    fun applyRotation(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated != source) source.recycle()
        return rotated
    }

    /**
     * 撮影ガイド枠に対応する領域を切り出す。
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
        val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        if (cropped != source) source.recycle()
        return cropped
    }

    /**
     * グレースケール化とコントラスト強調。
     * 反射や薄い鉛筆書きで文字が背景に沈むのを軽減する。
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
        if (source != out) source.recycle()
        return out
    }

    /** 撮影 → OCR に渡すまでの標準的な前処理。 */
    fun prepare(source: Bitmap, rotationDegrees: Int, guide: RelativeRect? = null): Bitmap {
        var bmp = applyRotation(source, rotationDegrees)
        if (guide != null) bmp = cropToGuide(bmp, guide)
        return enhance(bmp)
    }

    data class RelativeRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        companion object {
            /** 画面中央の書類ガイド枠。周囲の余白や机の写り込みを落とす。 */
            val DOCUMENT_GUIDE = RelativeRect(0.04f, 0.10f, 0.96f, 0.90f)
        }
    }
}
