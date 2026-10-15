package app.vdl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** Copie le fichier terminé dans le dossier public « Téléchargements/VDL ». */
object Storage {
    private const val FOLDER = "VDL"

    fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    suspend fun publish(ctx: Context, file: File): Pair<Uri, String> {
        val mime = mimeOf(file)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Engine.EngineError("Impossible d'enregistrer dans Téléchargements")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri to mime
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            dir.mkdirs()
            var dest = File(dir, file.name)
            var n = 1
            while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
            file.copyTo(dest)
            scan(ctx, dest, mime) to mime
        }
    }

    private suspend fun scan(ctx: Context, file: File, mime: String): Uri =
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
                cont.resume(uri ?: Uri.fromFile(file))
            }
        }
}
