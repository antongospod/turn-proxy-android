package com.freeturn.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.freeturn.app.ui.theme.Spacing

/**
 * QR-код для [content] на белой карточке (чёрный на белом - максимум контраста
 * для сканера независимо от темы). Матрица считается вне main-потока.
 */
@Composable
fun QrCode(content: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) { encode(content) }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = Color.White,
        modifier = modifier.aspectRatio(1f)
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                // Без сглаживания: модули QR остаются резкими при масштабировании.
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg)
            )
        }
    }
}

private fun encode(content: String): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[y * w + x] =
                if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}.getOrNull()
