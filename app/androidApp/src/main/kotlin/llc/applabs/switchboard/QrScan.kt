package llc.applabs.switchboard

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.tasks.await

/**
 * Scans a QR code with Google's code scanner. The scanning screen runs in Google Play services, so the app
 * needs no camera permission and never sees the camera image. Null when cancelled or unavailable.
 */
suspend fun scanQrCode(context: Context): String? = runCatching {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    val scanner = GmsBarcodeScanning.getClient(context, options)
    // First use on a phone downloads the scanner module; wait for it.
    ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build()).await()
    scanner.startScan().await().rawValue
}.getOrNull()
