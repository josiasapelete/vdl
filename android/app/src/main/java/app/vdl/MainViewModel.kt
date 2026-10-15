package app.vdl

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

sealed interface ProbeState {
    data object Idle : ProbeState
    data class Loading(val url: String) : ProbeState
    data class Ready(val result: ProbeResult) : ProbeState
    data class Failed(val message: String) : ProbeState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    val downloads: StateFlow<List<DownloadItem>> = DownloadStore.items

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probe: StateFlow<ProbeState> = _probe

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _appUpdate = MutableStateFlow<Pair<String, String>?>(null)
    val appUpdate: StateFlow<Pair<String, String>?> = _appUpdate

    init {
        checkAppUpdate()
    }

    fun analyze(raw: String) {
        val url = extractUrl(raw) ?: run {
            _probe.value = ProbeState.Failed("Aucun lien http(s) trouvé.")
            return
        }
        _probe.value = ProbeState.Loading(url)
        viewModelScope.launch {
            _probe.value = try {
                ProbeState.Ready(Engine.probe(ctx, url))
            } catch (e: Exception) {
                ProbeState.Failed(e.message ?: "Analyse impossible")
            }
        }
    }

    fun dismissProbe() {
        _probe.value = ProbeState.Idle
    }

    fun download(result: ProbeResult, mode: String, quality: String, wholePlaylist: Boolean) {
        val targets = if (result.isPlaylist && wholePlaylist) result.entries.map { it.url to it.title }
        else listOf(result.url to result.title)
        for ((url, title) in targets) {
            val item = DownloadItem(
                id = UUID.randomUUID().toString().take(12),
                url = url,
                title = title.ifEmpty { url },
                mode = mode,
                quality = quality,
                thumbnail = if (result.isPlaylist) "" else result.thumbnail,
            )
            DownloadStore.add(item)
            DownloadWorker.enqueue(ctx, item.id)
        }
        _probe.value = ProbeState.Idle
        _message.value = if (targets.size > 1) "${targets.size} téléchargements ajoutés" else "Téléchargement lancé"
    }

    fun cancel(id: String) = DownloadWorker.cancel(ctx, id)

    fun retry(item: DownloadItem) {
        DownloadStore.update(item.id) { it.copy(status = Status.QUEUED, progress = 0f, error = "") }
        DownloadWorker.enqueue(ctx, item.id)
    }

    fun remove(id: String) {
        DownloadWorker.cancel(ctx, id)
        DownloadStore.remove(id)
    }

    fun clearFinished() = DownloadStore.clearFinished()

    fun updateEngine() {
        _message.value = "Mise à jour de yt-dlp…"
        viewModelScope.launch { _message.value = Engine.update(ctx) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun checkAppUpdate() {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isEmpty()) return
        viewModelScope.launch {
            _appUpdate.value = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val tag = json.getString("tag_name").removePrefix("v")
                    if (isNewer(tag, BuildConfig.VERSION_NAME)) tag to json.getString("html_url") else null
                }.getOrNull()
            }
        }
    }

    companion object {
        private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

        /** Les applis partagent souvent « Regarde ça ! https://… » : on extrait le lien. */
        fun extractUrl(text: String?): String? = text?.let { URL_RE.find(it)?.value?.trimEnd('.', ',', ')', '!') }

        fun isNewer(a: String, b: String): Boolean {
            val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
            val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
