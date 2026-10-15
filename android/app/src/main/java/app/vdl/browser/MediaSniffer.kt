package app.vdl.browser

import android.net.Uri
import app.vdl.DetectedMedia
import java.net.HttpURLConnection
import java.net.URL

/** Reconnaît les médias dans les requêtes du navigateur intégré (même logique que l'extension PC). */
object MediaSniffer {
    private val STREAM_HLS = Regex("""\.m3u8(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val STREAM_DASH = Regex("""\.mpd(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val VIDEO_EXT = Regex("""\.(mp4|m4v|webm|mkv|mov|3gp)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val AUDIO_EXT = Regex("""\.(mp3|m4a|aac|ogg|opus|flac|wav)(\?|#|$)""", RegexOption.IGNORE_CASE)

    // Fragments de flux et flux internes inutiles seuls (sur ces sites, la page est passée à yt-dlp)
    private val IGNORE = Regex(
        """googlevideo\.com|\.ts(\?|$)|\.m4s(\?|$)|/seg[-_]?\d+|/fragment|/chunk[-_]?\d+|/range/|[?&]range=|""" +
            """[?&]bytestart=|init\.mp4|/ads?/|doubleclick|googlesyndication""",
        RegexOption.IGNORE_CASE,
    )

    // Piste son seule d'un lecteur HLS (« index-a1.m3u8 ») : à ne jamais retenir comme vidéo
    private val AUDIO_TRACK = Regex("""([_\-/.]a\d+[_\-.]|audio|[_\-/]aud[_\-.]|mp4a|/a/)""", RegexOption.IGNORE_CASE)

    // Sites bien gérés par yt-dlp : on lui donne l'adresse de la page (meilleures qualités + titre)
    private val KNOWN_SITE = Regex(
        """(^|\.)(youtube\.com|youtu\.be|tiktok\.com|facebook\.com|fb\.watch|instagram\.com|x\.com|twitter\.com|""" +
            """vimeo\.com|dailymotion\.com|twitch\.tv|reddit\.com|soundcloud\.com|bilibili\.com|pinterest\.[a-z.]+|""" +
            """snapchat\.com|threads\.net|linkedin\.com|kick\.com|rumble\.com)$""",
        RegexOption.IGNORE_CASE,
    )

    val KEEP_HEADERS = Regex("""^(referer|origin|authorization|x-[\w-]+)$""", RegexOption.IGNORE_CASE)

    fun classify(url: String): DetectedMedia.Kind? {
        if (!url.startsWith("http", ignoreCase = true) || IGNORE.containsMatchIn(url)) return null
        return when {
            STREAM_HLS.containsMatchIn(url) -> DetectedMedia.Kind.HLS
            STREAM_DASH.containsMatchIn(url) -> DetectedMedia.Kind.DASH
            VIDEO_EXT.containsMatchIn(url) -> DetectedMedia.Kind.VIDEO
            AUDIO_EXT.containsMatchIn(url) -> DetectedMedia.Kind.AUDIO
            else -> null
        }
    }

    fun isKnownSite(pageUrl: String): Boolean {
        val host = runCatching { Uri.parse(pageUrl).host }.getOrNull() ?: return false
        return KNOWN_SITE.containsMatchIn(host)
    }

    fun looksLikeAudioTrack(url: String) = AUDIO_TRACK.containsMatchIn(url)

    /** Ordre de préférence : playlist maître, flux vidéo, fichiers vidéo, puis le reste. */
    fun ranked(media: List<DetectedMedia>): List<DetectedMedia> = media.sortedWith(
        compareBy<DetectedMedia>(
            { if (it.isStream && Regex("master", RegexOption.IGNORE_CASE).containsMatchIn(it.url)) 0 else 1 },
            { if (looksLikeAudioTrack(it.url) || it.kind == DetectedMedia.Kind.AUDIO) 1 else 0 },
            { if (it.isStream) 0 else 1 },
            { it.time },     // le plus ancien d'abord : le lecteur charge la playlist principale en premier
        )
    )

    /**
     * Parmi les playlists HLS captées, trouve la maître (#EXT-X-STREAM-INF : elle référence
     * l'image ET le son). Bloquant : à appeler hors du thread principal.
     */
    fun pickBest(media: List<DetectedMedia>): DetectedMedia? {
        val ordered = ranked(media)
        for (m in ordered.filter { it.kind == DetectedMedia.Kind.HLS }.take(10)) {
            val text = runCatching {
                (URL(m.url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000
                    readTimeout = 8000
                    m.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    inputStream.bufferedReader().use { it.readText().take(200_000) }
                }
            }.getOrNull() ?: continue
            if ("#EXT-X-STREAM-INF" in text) return m
        }
        return ordered.firstOrNull()
    }
}
