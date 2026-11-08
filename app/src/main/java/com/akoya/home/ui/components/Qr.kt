package com.akoya.home.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Black-on-white QR code for [text]. */
fun qrBitmap(text: String, size: Int = 480): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) 0xFF141311.toInt() else 0xFFF1EEE8.toInt()
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
