package com.tvbox.app.import

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tvbox.app.core.TvLog

/**
 * 二维码生成（只用 zxing-core，不引 camera 模块；TV 端只显示不扫描）。
 *
 * 包体：zxing-core ≈500KB，符合 SOURCE_IMPORT_SPEC §6 验收项。
 */
object QrCodes {

    private const val TAG = "QrCodes"

    /**
     * 生成二维码 Bitmap（ARGB_8888）。
     * @param sizePx 边长像素；低端盒子建议 360–480（再大只是浪费内存）。
     */
    fun bitmap(content: String, sizePx: Int = 420): Bitmap? {
        if (content.isBlank()) return null
        val size = sizePx.coerceIn(160, 1024)
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 1
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        } catch (t: Throwable) {
            TvLog.e(TAG, "二维码生成失败", t)
            null
        }
    }
}
