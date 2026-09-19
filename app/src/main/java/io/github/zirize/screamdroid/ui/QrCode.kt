package io.github.zirize.screamdroid.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.zirize.screamdroid.R

/**
 * The address, as something another device's camera can read.
 *
 * 🔑 **It exists because the alternative is typing an IP address into a config file by hand**,
 *    from a phone screen, onto a keyboard in another room. A wrong digit there produces exactly
 *    the failure this app is worst at explaining: everything healthy, nothing audible.
 *
 * 🔑 **Drawn black on white, always.** A QR code is read by contrast, and the app's dark theme
 *    would invert it - many readers cope, some do not, and the point of this is not to have to
 *    try twice.
 */
@Composable
fun QrDialog(text: String, onDismiss: () -> Unit) {
    val bitmap = remember(text) { encode(text, QR_PIXELS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        title = { Text(stringResource(R.string.address_qr_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        // 🔑 None: a QR code is a grid of hard edges, and smoothing it is exactly
                        //    the wrong thing - it blurs the very boundaries a reader looks for.
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .size(232.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .padding(8.dp),
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = Mono,
                )
            }
        },
    )
}

/** Big enough that a module is several pixels wide once scaled to the dialog. */
private const val QR_PIXELS = 480

private fun encode(text: String, pixels: Int): Bitmap? = runCatching {
    val hints = mapOf(
        // 🔑 M, not H: the payload is an address of twenty-odd characters, so the extra
        //    correction of H would only make the modules smaller for no gain on a clean screen.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val pixelRow = IntArray(matrix.width)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixelRow[x] = if (matrix.get(x, y)) BLACK else WHITE
        }
        bitmap.setPixels(pixelRow, 0, matrix.width, 0, y, matrix.width, 1)
    }
    bitmap
}.getOrNull()

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
