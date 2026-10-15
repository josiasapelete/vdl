package app.vdl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Nouvelle version de VDL publiée sur GitHub, avec l'APK adapté à ce téléphone. */
data class AppUpdate(
    val version: String,
    val apkUrl: String,
    val apkName: String,
    val size: Long,
    val sumsUrl: String?,
)

/** Mise à jour de VDL dans l'application : téléchargement, vérification, installation. */
object AppUpdater {
    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** Dernière version publiée, si elle est plus récente que celle installée. */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isEmpty()) return@withContext null
        runCatching {
            val json = JSONObject(get("https://api.github.com/repos/$repo/releases/latest").inputStream
                .bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            if (!MainViewModel.isNewer(version, BuildConfig.VERSION_NAME)) return@runCatching null
            val assets = json.getJSONArray("assets")
            val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
            // L'APK de l'architecture du téléphone (plus léger), sinon l'universel
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val apk = byName.values.firstOrNull { it.getString("name").endsWith("android-$abi.apk") }
                ?: byName.values.firstOrNull { it.getString("name").endsWith("android-universal.apk") }
                ?: return@runCatching null
            AppUpdate(
                version = version,
                apkUrl = apk.getString("browser_download_url"),
                apkName = apk.getString("name"),
                size = apk.optLong("size"),
                sumsUrl = byName["SHA256SUMS.txt"]?.getString("browser_download_url"),
            )
        }.getOrNull()
    }

    /** Télécharge l'APK et vérifie son empreinte SHA-256 (fichier SHA256SUMS.txt de la version). */
    suspend fun download(ctx: Context, update: AppUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }           // anciennes mises à jour
        val file = File(dir, update.apkName)
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = get(update.apkUrl)
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.size
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        update.sumsUrl?.let { sumsUrl ->
            val sums = get(sumsUrl).inputStream.bufferedReader().use { it.readText() }
            val expected = sums.lines().firstOrNull { it.trim().endsWith(update.apkName) }?.trim()?.substringBefore(' ')
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                file.delete()
                throw SecurityException("Fichier de mise à jour altéré : installation annulée.")
            }
        }
        file
    }

    /** Android 8+ : VDL doit être autorisé à installer des applications. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Ouvre l'écran d'installation d'Android (l'utilisateur confirme). */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
