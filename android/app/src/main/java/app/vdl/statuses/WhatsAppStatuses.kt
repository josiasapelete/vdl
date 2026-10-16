package app.vdl.statuses

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StatusItem(val uri: Uri, val name: String, val isVideo: Boolean, val modified: Long)

/**
 * Statuts WhatsApp déjà regardés : WhatsApp les garde dans un dossier « .Statuses » de la mémoire
 * du téléphone. L'utilisateur autorise une fois l'accès à ce dossier (sélecteur Android), puis VDL
 * les affiche et les enregistre dans Téléchargements/VDL/Statuts.
 */
object WhatsAppStatuses {
    enum class Source(val label: String, private val modern: String, private val legacy: String) {
        WHATSAPP("WhatsApp",
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses", "WhatsApp/Media/.Statuses"),
        BUSINESS("WhatsApp Business",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses", "WhatsApp Business/Media/.Statuses");

        val path get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) modern else legacy
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("statuses", Context.MODE_PRIVATE)

    /** Dossier proposé d'emblée dans le sélecteur Android : l'utilisateur n'a qu'à valider. */
    fun initialUri(source: Source): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:${source.path}",
    )

    /** Accès déjà autorisé pour cette source ? */
    fun grantedTree(ctx: Context, source: Source): Uri? {
        val saved = prefs(ctx).getString(source.name, null)?.let(Uri::parse) ?: return null
        val stillGranted = ctx.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isReadPermission }
        return saved.takeIf { stillGranted }
    }

    fun saveGrant(ctx: Context, source: Source, tree: Uri) {
        ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(ctx).edit().putString(source.name, tree.toString()).apply()
    }

    /** Le dossier choisi ressemble-t-il bien au dossier des statuts ? */
    fun looksRight(tree: Uri): Boolean = Uri.decode(tree.toString()).contains(".Statuses")

    suspend fun list(ctx: Context, tree: Uri): List<StatusItem> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(ctx, tree) ?: return@withContext emptyList()
        dir.listFiles()
            .filter { it.isFile && it.name?.startsWith(".") == false }
            .mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                val type = f.type.orEmpty()
                val video = type.startsWith("video") || name.endsWith(".mp4", true)
                val image = type.startsWith("image") || name.endsWith(".jpg", true) || name.endsWith(".webp", true)
                if (!video && !image) null else StatusItem(f.uri, name, video, f.lastModified())
            }
            .sortedByDescending { it.modified }
    }

    fun isSaved(ctx: Context, item: StatusItem) = prefs(ctx).getStringSet("saved", emptySet())!!.contains(item.name)

    fun markSaved(ctx: Context, item: StatusItem) {
        val set = prefs(ctx).getStringSet("saved", emptySet())!!.toMutableSet().apply { add(item.name) }
        prefs(ctx).edit().putStringSet("saved", set).apply()
    }
}
