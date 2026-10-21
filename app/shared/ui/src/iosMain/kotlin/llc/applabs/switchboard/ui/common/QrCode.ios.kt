package llc.applabs.switchboard.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image as SkiaImage
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

// Not compiled on Linux (iOS targets build on macOS only) — verify on a Mac / the iOS CI job.
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrCode(text: String, modifier: Modifier) {
    val bitmap = remember(text) {
        runCatching {
            val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return@runCatching null
            @Suppress("CAST_NEVER_SUCCEEDS")
            filter.setValue((text as NSString).dataUsingEncoding(NSUTF8StringEncoding), forKey = "inputMessage")
            filter.setValue("M", forKey = "inputCorrectionLevel")
            val scaled = filter.outputImage?.imageByApplyingTransform(CGAffineTransformMakeScale(10.0, 10.0)) ?: return@runCatching null
            val cg = CIContext().createCGImage(scaled, scaled.extent) ?: return@runCatching null
            val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(cg)) ?: return@runCatching null
            val bytes = ByteArray(png.length.toInt()).apply { usePinned { memcpy(it.addressOf(0), png.bytes, png.length) } }
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }
    bitmap?.let { Image(it, contentDescription = "QR code", filterQuality = FilterQuality.None, modifier = modifier.aspectRatio(1f)) }
}
