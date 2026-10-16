package app.vdl

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vdl.browser.MediaSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Tab { HOME, BROWSER, DOWNLOADS, STATUSES }

/** Avertissement du navigateur (pop-up ou redirection bloquée), avec une action facultative. */
data class BrowserNotice(val text: String, val action: (() -> Unit)?, val id: Long = System.nanoTime())

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

    private val _appUpdate = MutableStateFlow<AppUpdate?>(null)
    val appUpdate: StateFlow<AppUpdate?> = _appUpdate
    private val _updateProgress = MutableStateFlow<Float?>(null)
    /** null = pas de téléchargement de mise à jour en cours, sinon 0..1 */
    val updateProgress: StateFlow<Float?> = _updateProgress

    // --- navigateur intégré
    private val _detected = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val detected: StateFlow<List<DetectedMedia>> = _detected
    private val _browserRequest = MutableStateFlow<String?>(null)
    /** Adresse à ouvrir dans le navigateur (consommée par l'onglet Navigateur). */
    val browserRequest: StateFlow<String?> = _browserRequest
    private val _videoPlaying = MutableStateFlow(false)
    val videoPlaying: StateFlow<Boolean> = _videoPlaying
    private val _browserNotice = MutableStateFlow<BrowserNotice?>(null)
    val browserNotice: StateFlow<BrowserNotice?> = _browserNotice

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

    fun browserNotice(text: String, action: (() -> Unit)?) {
        _browserNotice.value = BrowserNotice(text, action)
    }

    fun dismissBrowserNotice(id: Long) {
        _browserNotice.update { if (it?.id == id) null else it }
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

    // ------------------------------------------------------------------ fichiers partagés (Telegram, WhatsApp…)

    /** Vidéos, photos ou sons partagés vers VDL : copiés dans Téléchargements/VDL et ajoutés à la liste. */
    fun importShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _tab.value = Tab.DOWNLOADS
        viewModelScope.launch {
            var ok = 0
            for (uri in uris) {
                runCatching {
                    val resolver = ctx.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "partage-${System.currentTimeMillis()}"
                    val mime = resolver.getType(uri) ?: Storage.mimeOf(name)
                    val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                    } ?: 0L
                    val (saved, savedMime) = Storage.publishStream(ctx, name, mime) { resolver.openInputStream(uri)!! }
                    DownloadStore.add(DownloadItem(
                        id = UUID.randomUUID().toString().take(12), url = uri.toString(), title = name,
                        mode = when { mime.startsWith("audio") -> "audio"; mime.startsWith("video") -> "video"; else -> "file" },
                        quality = "best", status = Status.DONE, progress = 100f, fileName = name,
                        fileUri = saved.toString(), mime = savedMime,
                        size = if (size > 0) "%.2fMiB".format(java.util.Locale.US, size / 1048576.0) else "",
                    ))
                    ok++
                }
            }
            _message.value = when {
                ok == 0 -> "Impossible d'enregistrer ce fichier"
                ok == 1 -> "Enregistré dans Téléchargements/VDL"
                else -> "$ok fichiers enregistrés dans Téléchargements/VDL"
            }
        }
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
        viewModelScope.launch { _appUpdate.value = AppUpdater.check() }
    }

    /** Télécharge la nouvelle version, la vérifie, puis ouvre l'installation d'Android. */
    fun installUpdate() {
        val update = _appUpdate.value ?: return
        if (_updateProgress.value != null) return
        if (!AppUpdater.canInstall(ctx)) {
            _message.value = "Autorisez VDL à installer des applis, puis appuyez de nouveau sur Mettre à jour"
            AppUpdater.openInstallPermission(ctx)
            return
        }
        _updateProgress.value = 0f
        viewModelScope.launch {
            try {
                val apk = AppUpdater.download(ctx, update) { _updateProgress.value = it }
                AppUpdater.install(ctx, apk)
            } catch (e: Exception) {
                _message.value = e.message?.takeIf { e is SecurityException }
                    ?: "Téléchargement de la mise à jour impossible. Vérifiez votre connexion."
            } finally {
                _updateProgress.value = null
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
