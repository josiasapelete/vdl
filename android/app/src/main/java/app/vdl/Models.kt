package app.vdl

import org.json.JSONArray
import org.json.JSONObject

enum class Status(val label: String) {
    QUEUED("En attente"),
    RUNNING("Téléchargement"),
    PROCESSING("Traitement"),
    DONE("Terminé"),
    ERROR("Erreur"),
    CANCELED("Annulé");

    val active get() = this == QUEUED || this == RUNNING || this == PROCESSING
}

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val mode: String,             // "video" ou "audio"
    val quality: String,          // "best" ou hauteur max ("1080"…)
    val thumbnail: String = "",
    val headers: Map<String, String> = emptyMap(),   // Referer, cookies… du navigateur intégré
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,     // 0..100
    val eta: Long = 0,
    val speed: String = "",       // « 2.4MiB/s » (non persisté)
    val size: String = "",        // « 48.20MiB »
    val fileName: String = "",
    val fileUri: String = "",
    val mime: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("title", title).put("mode", mode).put("quality", quality)
        .put("thumbnail", thumbnail).put("headers", JSONObject(headers)).put("status", status.name)
        .put("progress", progress.toDouble()).put("size", size)
        .put("fileName", fileName).put("fileUri", fileUri).put("mime", mime).put("error", error)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = DownloadItem(
            id = o.getString("id"),
            url = o.getString("url"),
            title = o.optString("title"),
            mode = o.optString("mode", "video"),
            quality = o.optString("quality", "best"),
            thumbnail = o.optString("thumbnail"),
            headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }
                ?: emptyMap(),
            status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.ERROR),
            progress = o.optDouble("progress", 0.0).toFloat(),
            size = o.optString("size"),
            fileName = o.optString("fileName"),
            fileUri = o.optString("fileUri"),
            mime = o.optString("mime"),
            error = o.optString("error"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )

        fun listToJson(items: List<DownloadItem>) = JSONArray().apply { items.forEach { put(it.toJson()) } }
    }
}

data class PlaylistEntry(val url: String, val title: String)

data class ProbeResult(
    val url: String,
    val title: String,
    val thumbnail: String,
    val extractor: String,
    val uploader: String,
    val duration: Double,
    val qualities: List<String>,    // "best", puis hauteurs décroissantes
    val isPlaylist: Boolean,
    val entries: List<PlaylistEntry>,
)

/** Média repéré par le navigateur intégré pendant la navigation ou la lecture. */
data class DetectedMedia(
    val url: String,
    val kind: Kind,
    val headers: Map<String, String>,
    val pageUrl: String,
    val time: Long = System.currentTimeMillis(),
) {
    enum class Kind(val label: String) { HLS("Flux HLS"), DASH("Flux DASH"), VIDEO("Vidéo"), AUDIO("Audio") }

    val isStream get() = kind == Kind.HLS || kind == Kind.DASH
}
