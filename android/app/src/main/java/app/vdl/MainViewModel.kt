package app.vdl

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vdl.browser.MediaSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

enum class Tab { HOME, BROWSER, DOWNLOADS }

sealed interface ProbeState {
    data object Idle : ProbeState
    data class Loading(val url: String) : ProbeState
    data class Ready(val result: ProbeResult, val headers: Map<String, String>, val titleHint: String?) : ProbeState
    data class Failed(val message: String, val retry: (() -> Unit)?) : ProbeState
    /** Plusieurs médias captés sur la page : l'utilisateur choisit. */
    data class Choose(val media: List<DetectedMedia>, val pageTitle: String) : ProbeState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    val downloads: StateFlow<List<DownloadItem>> = DownloadStore.items
    val engineReady: StateFlow<Boolean> = Engine.readyFlow

    private val _tab = MutableStateFlow(Tab.HOME)
    val tab: StateFlow<Tab> = _tab

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probe: StateFlow<ProbeState> = _probe

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _appUpdate = MutableStateFlow<Pair<String, String>?>(null)
    val appUpdate: StateFlow<Pair<String, String>?> = _appUpdate

    // --- navigateur intégré
    private val _detected = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val detected: StateFlow<List<DetectedMedia>> = _detected
    private val _browserRequest = MutableStateFlow<String?>(null)
    /** Adresse à ouvrir dans le navigateur (consommée par l'onglet Navigateur). */
    val browserRequest: StateFlow<String?> = _browserRequest
    private val _videoPlaying = MutableStateFlow(false)
    val videoPlaying: StateFlow<Boolean> = _videoPlaying

    init {
        checkAppUpdate()
    }

    fun selectTab(tab: Tab) {
        _tab.value = tab
    }

    // ------------------------------------------------------------------ analyse

    fun analyze(raw: String, headers: Map<String, String> = emptyMap(), titleHint: String? = null) {
        val url = extractUrl(raw) ?: run {
            _probe.value = ProbeState.Failed("Aucun lien http(s) trouvé.", null)
            return
        }
        _probe.value = ProbeState.Loading(url)
        viewModelScope.launch {
            _probe.value = try {
                ProbeState.Ready(Engine.probe(ctx, url, headers), headers, titleHint)
            } catch (e: Exception) {
                ProbeState.Failed(e.message ?: "Analyse impossible") { analyze(url, headers, titleHint) }
            }
        }
    }

    fun dismissProbe() {
        _probe.value = ProbeState.Idle
    }

    fun download(state: ProbeState.Ready, mode: String, quality: String, wholePlaylist: Boolean) {
        val result = state.result
        val targets = if (result.isPlaylist && wholePlaylist) result.entries.map { it.url to it.title }
        else listOf(result.url to (state.titleHint?.takeIf { result.extractor == "Generic" } ?: result.title))
        for ((url, title) in targets) {
            val item = DownloadItem(
                id = UUID.randomUUID().toString().take(12),
                url = url,
                title = title.ifEmpty { url },
                mode = mode,
                quality = quality,
                thumbnail = if (result.isPlaylist) "" else result.thumbnail,
                headers = state.headers,
            )
            DownloadStore.add(item)
            DownloadWorker.enqueue(ctx, item.id)
        }
        _probe.value = ProbeState.Idle
        _message.value = if (targets.size > 1) "${targets.size} téléchargements ajoutés" else "Téléchargement lancé"
    }

    // ------------------------------------------------------------------ navigateur

    fun openInBrowser(url: String) {
        _browserRequest.value = url
        _tab.value = Tab.BROWSER
    }

    fun consumeBrowserRequest() {
        _browserRequest.value = null
    }

    fun onPageStarted() {
        _detected.value = emptyList()
        _videoPlaying.value = false
    }

    /** Appelé depuis le thread réseau du WebView. */
    fun onMediaDetected(media: DetectedMedia) {
        _detected.update { list ->
            val base = media.url.substringBefore('#')
            if (list.any { it.url.substringBefore('#') == base }) list else (list + media).takeLast(40)
        }
    }

    fun onVideoPlaying() {
        _videoPlaying.value = true
    }

    /** Bouton « Télécharger » du navigateur. */
    fun downloadFromBrowser(pageUrl: String, pageTitle: String) {
        val media = _detected.value
        when {
            // Site connu de yt-dlp : la page donne les meilleures qualités et le bon titre
            MediaSniffer.isKnownSite(pageUrl) -> analyze(pageUrl)
            media.isEmpty() -> analyze(pageUrl, titleHint = pageTitle)
            else -> {
                _probe.value = ProbeState.Loading(pageUrl)
                viewModelScope.launch {
                    val best = withContext(Dispatchers.IO) { MediaSniffer.pickBest(media) }
                    val others = media.filter { it.url != best?.url && !MediaSniffer.looksLikeAudioTrack(it.url) }
                    if (best != null && others.none { !it.isStream || it.kind != best.kind }) {
                        analyzeMedia(best, pageTitle)
                    } else {
                        _probe.value = ProbeState.Choose(MediaSniffer.ranked(media), pageTitle)
                    }
                }
            }
        }
    }

    fun analyzeMedia(media: DetectedMedia, pageTitle: String) {
        analyze(media.url, media.headers, pageTitle.takeIf { it.isNotBlank() })
    }

    // ------------------------------------------------------------------ téléchargements

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

    fun showMessage(text: String) {
        _message.value = text
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
