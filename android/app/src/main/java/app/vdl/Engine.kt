package app.vdl

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Moteur : yt-dlp + ffmpeg embarqués (bibliothèque youtubedl-android). */
object Engine {
    private val initLock = Mutex()
    @Volatile private var ready = false
    private val _ready = MutableStateFlow(false)
    /** false pendant la toute première préparation de Python/yt-dlp (quelques secondes). */
    val readyFlow: StateFlow<Boolean> = _ready
    private const val UPDATE_INTERVAL_MS = 12 * 3600 * 1000L

    class EngineError(message: String) : Exception(message)

    suspend fun ensureInit(ctx: Context) = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (!ready) {
                YoutubeDL.init(ctx.applicationContext)
                FFmpeg.init(ctx.applicationContext)
                ready = true
                _ready.value = true
            }
        }
    }

    /** Met yt-dlp à jour au plus une fois toutes les 12 h (les sites changent souvent). */
    suspend fun autoUpdate(ctx: Context): String? = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_update_check", 0)
        if (System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return@withContext null
        update(ctx).also { prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply() }
    }

    suspend fun update(ctx: Context): String = withContext(Dispatchers.IO) {
        ensureInit(ctx)
        try {
            when (YoutubeDL.updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)) {
                YoutubeDL.UpdateStatus.DONE -> "yt-dlp mis à jour (${version(ctx)})"
                else -> "yt-dlp est à jour (${version(ctx)})"
            }
        } catch (e: Exception) {
            "Mise à jour impossible : ${e.message?.take(120)}"
        }
    }

    fun version(ctx: Context): String = YoutubeDL.versionName(ctx.applicationContext) ?: "?"

    private fun cleanError(e: Throwable): String {
        val raw = e.message ?: e.javaClass.simpleName
        val line = raw.lines().lastOrNull { it.startsWith("ERROR:") } ?: raw.lines().firstOrNull { it.isNotBlank() } ?: raw
        return line.removePrefix("ERROR:").trim().take(300)
    }

    // ------------------------------------------------------------------ analyse

    /** En-têtes du navigateur (Referer, cookies…) : indispensables pour les flux protégés par jeton. */
    private fun YoutubeDLRequest.withHeaders(headers: Map<String, String>): YoutubeDLRequest {
        for ((name, value) in headers) {
            when (name.lowercase()) {
                "user-agent" -> addOption("--user-agent", value)
                "referer" -> addOption("--referer", value)
                else -> addOption("--add-header", "$name:$value")
            }
        }
        return this
    }

    suspend fun probe(ctx: Context, url: String, headers: Map<String, String> = emptyMap()): ProbeResult =
        withContext(Dispatchers.IO) {
        ensureInit(ctx)
        val request = YoutubeDLRequest(url)
            .addOption("--dump-single-json")
            .addOption("--flat-playlist")
            .addOption("--no-warnings")
            .withHeaders(headers)
        val response = try {
            YoutubeDL.execute(request, null, null)
        } catch (e: YoutubeDLException) {
            throw EngineError(cleanError(e))
        }
        parseInfo(url, JSONObject(response.out))
    }

    private fun parseInfo(url: String, info: JSONObject): ProbeResult {
        val type = info.optString("_type")
        if (type == "playlist" || type == "multi_video") {
            val entries = mutableListOf<PlaylistEntry>()
            val arr = info.optJSONArray("entries")
            for (i in 0 until (arr?.length() ?: 0)) {
                val e = arr!!.optJSONObject(i) ?: continue
                val u = e.optString("url").ifEmpty { e.optString("webpage_url") }
                if (u.isNotEmpty()) entries += PlaylistEntry(u, e.optString("title"))
            }
            return ProbeResult(url, info.optString("title", "Playlist"), "", info.optString("extractor_key"),
                info.optString("uploader"), 0.0, listOf("best"), true, entries)
        }
        val heights = sortedSetOf<Int>(compareByDescending { it })
        val formats = info.optJSONArray("formats")
        for (i in 0 until (formats?.length() ?: 0)) {
            val f = formats!!.optJSONObject(i) ?: continue
            val h = f.optInt("height", 0)
            if (h > 0 && f.optString("vcodec") != "none") heights += h
        }
        return ProbeResult(
            url = info.optString("webpage_url").ifEmpty { url },
            title = info.optString("title"),
            thumbnail = info.optString("thumbnail"),
            extractor = info.optString("extractor_key"),
            uploader = info.optString("uploader"),
            duration = info.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
            qualities = listOf("best") + heights.map { it.toString() },
            isPlaylist = false,
            entries = emptyList(),
        )
    }

    // ------------------------------------------------------------------ téléchargement

    /**
     * Télécharge [item] dans [outDir] et retourne le fichier produit.
     * Bloquant : à appeler depuis un thread d'arrière-plan. Annulation : YoutubeDL.destroyProcessById(item.id).
     */
    fun download(item: DownloadItem, outDir: File, onProgress: (Float, Long, String) -> Unit): File {
        outDir.mkdirs()
        val request = YoutubeDLRequest(item.url)
            .addOption("-o", "${outDir.absolutePath}/%(title).120B.%(ext)s")
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--retries", 10)
            .addOption("--fragment-retries", 10)
            .addOption("--concurrent-fragments", 4)
            .addOption("--embed-metadata")
            .withHeaders(item.headers)
        if (item.mode == "audio") {
            request.addOption("-f", "bestaudio/best")
                .addOption("-x")
                .addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "192K")
        } else {
            val res = if (item.quality == "best") "res" else "res:${item.quality}"
            request.addOption("-f", "bv*+ba/b")
                .addOption("-S", "$res,fps,vcodec:h264,acodec:aac,ext:mp4:m4a")
                .addOption("--merge-output-format", "mp4")
        }
        try {
            YoutubeDL.execute(request, item.id, onProgress)
        } catch (e: YoutubeDLException) {
            throw EngineError(cleanError(e))
        }
        return outDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp") }
            ?.maxByOrNull { it.length() }
            ?: throw EngineError("Aucun fichier produit")
    }

    private val SIZE_RE = Regex("""of\s+~?\s*([\d.]+\s*[KMGT]?i?B)""")
    private val SPEED_RE = Regex("""at\s+([\d.]+\s*[KMGT]?i?B/s)""")

    /** Taille et vitesse lues dans une ligne de progression yt-dlp. */
    fun parseProgressLine(line: String): Pair<String?, String?> =
        SIZE_RE.find(line)?.groupValues?.get(1) to SPEED_RE.find(line)?.groupValues?.get(1)

    fun cancel(id: String) {
        YoutubeDL.destroyProcessById(id)
    }
}
