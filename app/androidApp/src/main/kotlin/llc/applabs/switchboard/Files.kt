package llc.applabs.switchboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import llc.applabs.switchboard.domain.model.PickedFile
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * The photo and file pickers. The visible activity registers them ([register], in onCreate); the result
 * waits here, outside the activity, so it still arrives if Android recreated the activity meanwhile.
 */
object Pickers {
    private var waiting: CompletableDeferred<Uri?>? = null
    private var photo: (() -> Unit)? = null
    private var file: (() -> Unit)? = null

    fun register(activity: ComponentActivity) {
        val photoLauncher = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { waiting?.complete(it) }
        val fileLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { waiting?.complete(it) }
        photo = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        file = { fileLauncher.launch(arrayOf("*/*")) }
    }

    suspend fun pickPhoto(context: Context): PickedFile? = pick(photo)?.let { readPhoto(context, it) }
    suspend fun pickFile(context: Context): PickedFile? = pick(file)?.let { readFile(context, it) }

    private suspend fun pick(launch: (() -> Unit)?): Uri? {
        launch ?: return null
        val result = CompletableDeferred<Uri?>()
        waiting?.complete(null)
        waiting = result
        withContext(Dispatchers.Main) { launch() }
        return result.await()
    }

    private fun nameOf(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private suspend fun readFile(context: Context, uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw IllegalArgumentException("Files can be up to ${MAX_BYTES / 1_000_000} MB.")
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("That file can't be read.")
        PickedFile(nameOf(context, uri), context.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
    }

    /** A photo, turned upright and made at most [MAX_SIDE] pixels on its long side, as JPEG (small enough to send). */
    private suspend fun readPhoto(context: Context, uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = MAX_SIDE.toFloat() / max(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IllegalArgumentException("That photo can't be read.")
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val name = nameOf(context, uri).substringBeforeLast('.').ifBlank { "photo" } + ".jpg"
        PickedFile(name, "image/jpeg", out.toByteArray())
    }

    /** Open a downloaded file in another app: saved to this app's cache, shared through its FileProvider. */
    fun open(context: Context, name: String, contentType: String, bytes: ByteArray) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // only the latest one is kept
        val file = File(dir, name.replace('/', '_').ifBlank { "file" }).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(chooser) } catch (_: ActivityNotFoundException) { }
    }

    private const val MAX_SIDE = 2048
    private const val MAX_BYTES = 25_000_000
}
