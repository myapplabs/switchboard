package llc.applabs.switchboard.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import io.nayuki.qrcodegen.QrCode as Nayuki

@Composable
actual fun QrCode(text: String, modifier: Modifier) {
    val qr = remember(text) { Nayuki.encodeText(text, Nayuki.Ecc.MEDIUM) }
    Canvas(modifier.aspectRatio(1f)) {
        val border = 2
        val cells = qr.size + border * 2
        val cell = size.minDimension / cells
        drawRect(Color.White)
        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.getModule(x, y))
            drawRect(Color.Black, Offset((x + border) * cell, (y + border) * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}
