package app.vdl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.coroutines.resume

/** Enregistre les fichiers dans le dossier public « Téléchargements/VDL » (visible dans la galerie). */
object Storage {
    private const val FOLDER = "VDL"

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    fun mimeOf(file: File): String = mimeOf(file.name)

    /** Fichier téléchargé par VDL. */
    suspend fun publish(ctx: Context, file: File): Pair<Uri, String> =
        publishStream(ctx, file.name, mimeOf(file)) { file.inputStream() }

    /**
     * Copie un contenu (fichier partagé par Telegram, statut WhatsApp…) dans Téléchargements/VDL[/sousDossier].
     * Retourne l'adresse du fichier enregistré et son type.
     */
    suspend fun publishStream(
        ctx: Context, name: String, mime: String, subfolder: String? = null, open: () -> InputStream,
    ): Pair<Uri, String> = withContext(Dispatchers.IO) {
        val relative = listOfNotNull(Environment.DIRECTORY_DOWNLOADS, FOLDER, subfolder).joinToString("/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Engine.EngineError("Impossible d'enregistrer dans Téléchargements")
            try {
                resolver.openOutputStream(uri)!!.use { out -> open().use { it.copyTo(out) } }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri to mime
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                listOfNotNull(FOLDER, subfolder).joinToString("/"))
            dir.mkdirs()
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var dest = File(dir, name)
            var n = 1
            while (dest.exists()) dest = File(dir, "$base ($n)" + if (ext.isNotEmpty()) ".$ext" else "").also { n++ }
            dest.outputStream().use { out -> open().use { it.copyTo(out) } }
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
