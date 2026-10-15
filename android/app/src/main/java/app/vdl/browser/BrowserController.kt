package app.vdl.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.vdl.DetectedMedia
import app.vdl.MainViewModel
import java.io.ByteArrayInputStream

/**
 * Navigateur intégré : un WebView dont on observe les requêtes réseau.
 *
 * - Détection des vidéos : toute vidéo lue (MP4, flux HLS/DASH) passe par le réseau,
 *   elle est donc repérée quel que soit le site, comme avec l'extension sur ordinateur.
 * - Protections (façon Brave) : pubs, traqueurs et domaines malveillants bloqués,
 *   pop-ups et redirections automatiques bloquées, navigation sécurisée Google.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(context: Context, private val vm: MainViewModel) {
    val webView = WebView(context)
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val userAgent: String

    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(100)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set

    // Compteurs du bouclier, pour la page affichée
    var blockedRequests by mutableIntStateOf(0)
        private set
    var blockedPopups by mutableIntStateOf(0)
        private set
    var blockedRedirects by mutableIntStateOf(0)
        private set
    val blockedTotal get() = blockedRequests + blockedPopups + blockedRedirects

    /** Protection active pour le site affiché ? */
    var shieldsOn by mutableStateOf(true)
        private set

    @Volatile private var currentUrl = ""          // lisible depuis le thread réseau
    @Volatile private var shieldsActive = true
    @Volatile private var lastUserTouch = 0L

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // Sécurité
            javaScriptCanOpenWindowsAutomatically = false   // pas de fenêtres ouvertes sans clic
            setSupportMultipleWindows(true)                 // les pop-ups passent par onCreateWindow (bloquées)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            // Certains sites refusent les WebView (« ; wv ») : on se présente comme Chrome mobile
            userAgentString = userAgentString.replace("; wv", "")
        }
        userAgent = webView.settings.userAgentString
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) WebView.startSafeBrowsing(appContext, null)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(), "VDLBridge")
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        // Un vrai toucher de l'utilisateur : distingue un clic d'une redirection automatique
        webView.setOnTouchListener { _, _ -> lastUserTouch = System.currentTimeMillis(); false }
        // Fichiers téléchargés par un site (bouton « Télécharger » d'une page) : confiés à VDL
        webView.setDownloadListener { dlUrl, _, _, _, _ ->
            vm.analyze(dlUrl, headersFor(dlUrl, null), title.takeIf { it.isNotBlank() })
        }
    }

    // ------------------------------------------------------------------ navigation

    fun load(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val target = when {
            text.startsWith("https://", true) -> text
            text.startsWith("http://", true) -> text
            Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?(/.*)?$""").matches(text) -> "https://$text"   // HTTPS par défaut
            else -> "https://duckduckgo.com/?q=" + Uri.encode(text)
        }
        webView.loadUrl(target)
    }

    fun back() = webView.goBack()
    fun forward() = webView.goForward()
    fun reload() = webView.reload()

    /** Retour système : quitter le plein écran, sinon page précédente. */
    fun handleBack(): Boolean {
        if (fullscreenView != null) {
            webView.webChromeClient?.onHideCustomView()
            return true
        }
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    /** Active/désactive la protection pour le site affiché, puis recharge la page. */
    fun setShields(enabled: Boolean) {
        val site = Shields.siteOfUrl(url)
        if (site.isEmpty()) return
        Shields.setDisabledFor(appContext, site, !enabled)
        shieldsOn = enabled
        shieldsActive = enabled
        webView.reload()
    }

    private fun refreshNav() {
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    private fun onNewPage(pageUrl: String) {
        url = pageUrl
        currentUrl = pageUrl
        val enabled = !Shields.isDisabledFor(appContext, Shields.siteOfUrl(pageUrl))
        shieldsOn = enabled
        shieldsActive = enabled
        blockedRequests = 0
        blockedPopups = 0
        blockedRedirects = 0
    }

    private fun recentlyTouched() = System.currentTimeMillis() - lastUserTouch < 1500

    // ------------------------------------------------------------------ détection des vidéos

    private fun headersFor(mediaUrl: String, requestHeaders: Map<String, String>?): Map<String, String> {
        val h = linkedMapOf<String, String>()
        requestHeaders?.forEach { (k, v) -> if (MediaSniffer.KEEP_HEADERS.matches(k)) h[k] = v }
        if (h.keys.none { it.equals("Referer", true) } && currentUrl.isNotEmpty()) h["Referer"] = currentUrl
        CookieManager.getInstance().getCookie(mediaUrl)?.let { h["Cookie"] = it }
        h["User-Agent"] = userAgent
        return h
    }

    private fun report(mediaUrl: String, requestHeaders: Map<String, String>?) {
        val kind = MediaSniffer.classify(mediaUrl) ?: return
        vm.onMediaDetected(DetectedMedia(mediaUrl, kind, headersFor(mediaUrl, requestHeaders), currentUrl))
    }

    private inner class Bridge {
        /** Appelé par le script injecté quand une vidéo démarre. */
        @JavascriptInterface
        fun onPlay(src: String?) {
            main.post {
                vm.onVideoPlaying()
                if (!src.isNullOrBlank() && src.startsWith("http")) {
                    if (MediaSniffer.classify(src) != null) report(src, null)
                    else vm.onMediaDetected(DetectedMedia(src, DetectedMedia.Kind.VIDEO, headersFor(src, null), currentUrl))
                }
            }
        }
    }

    // ------------------------------------------------------------------ protections

    private val emptyResponse get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url
            val scheme = target.scheme ?: return true
            // Liens « intent:// », « market:// », « data: »… : jamais ouverts (hameçonnage, apps imposées)
            if (scheme != "http" && scheme != "https") return true
            if (!request.isForMainFrame || !shieldsActive) return false

            val dest = target.toString()
            // Destination connue comme publicitaire ou malveillante
            if (Shields.isBlocked(target.host)) {
                blockedRedirects++
                vm.browserNotice("Redirection bloquée vers ${Shields.siteOf(target.host)}", null)
                return true
            }
            // Redirection automatique vers un autre site, sans que l'utilisateur ait touché la page
            val auto = !request.hasGesture() && !recentlyTouched()
            if (auto && !request.isRedirect && currentUrl.isNotEmpty() && !Shields.sameSite(currentUrl, dest)) {
                blockedRedirects++
                vm.browserNotice("Redirection automatique bloquée vers ${Shields.siteOf(target.host)}") { view.loadUrl(dest) }
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
            onNewPage(pageUrl)
            vm.onPageStarted()
            refreshNav()
        }

        override fun onPageFinished(view: WebView, pageUrl: String) {
            url = pageUrl
            currentUrl = pageUrl
            view.evaluateJavascript(INJECTED_JS, null)
            if (shieldsActive) view.evaluateJavascript(cosmeticJs(), null)
            refreshNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
            url = pageUrl   // sites « une page » (YouTube, TikTok…) qui changent d'adresse sans recharger
            currentUrl = pageUrl
            refreshNav()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Pubs, traqueurs et domaines malveillants : la requête n'est jamais envoyée
            if (shieldsActive && !request.isForMainFrame && Shields.isBlocked(request.url.host)) {
                main.post { blockedRequests++ }
                return emptyResponse
            }
            report(request.url.toString(), request.requestHeaders)
            return null     // sinon on observe seulement, la page charge normalement
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, newTitle: String?) {
            title = newTitle.orEmpty()
        }

        /** Pop-up (window.open, lien target=_blank) : on découvre son adresse, puis on décide. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val probe = WebView(view.context)
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    handlePopup(r.url.toString(), isUserGesture)
                    main.post { v.destroy() }
                    return true
                }

                override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                    handlePopup(u, isUserGesture)
                    v.stopLoading()
                    main.post { v.destroy() }
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()
            return true
        }

        private var lastPopup = "" to 0L

        private fun handlePopup(target: String, gesture: Boolean) {
            if (!target.startsWith("http")) return
            // Une même pop-up signale souvent son adresse deux fois : on ne la traite qu'une fois
            if (lastPopup.first == target && System.currentTimeMillis() - lastPopup.second < 2000) return
            lastPopup = target to System.currentTimeMillis()
            val host = Uri.parse(target).host
            when {
                // Protection désactivée, ou lien du même site ouvert par un vrai clic : on l'ouvre ici
                !shieldsActive || (gesture && Shields.sameSite(currentUrl, target) && !Shields.isBlocked(host)) ->
                    webView.loadUrl(target)
                Shields.isBlocked(host) -> {
                    blockedPopups++
                    vm.browserNotice("Pop-up publicitaire bloquée (${Shields.siteOf(host)})", null)
                }
                else -> {
                    blockedPopups++
                    vm.browserNotice("Pop-up bloquée : ${Shields.siteOf(host)}") { webView.loadUrl(target) }
                }
            }
        }

        // Plein écran des vidéos
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            val activity = webView.context.findActivity() ?: return callback.onCustomViewHidden()
            val decor = activity.window.decorView as ViewGroup
            decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreenView = view
            fullscreenCallback = callback
            WindowCompat.getInsetsController(activity.window, decor).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        override fun onHideCustomView() {
            val activity = webView.context.findActivity() ?: return
            val decor = activity.window.decorView as ViewGroup
            fullscreenView?.let { decor.removeView(it) }
            fullscreenView = null
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            WindowCompat.getInsetsController(activity.window, decor).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun cosmeticJs(): String {
        val css = Shields.COSMETIC_CSS.replace("\n", " ").replace("'", "\\'")
        return "(function(){if(document.getElementById('__vdl_css'))return;var s=document.createElement('style');" +
            "s.id='__vdl_css';s.textContent='$css';(document.head||document.documentElement).appendChild(s);})();"
    }

    companion object {
        private const val INJECTED_JS = """
            (function () {
              if (window.__vdl) return; window.__vdl = 1;
              document.addEventListener('play', function (e) {
                var v = e.target;
                if (!v || !('currentSrc' in v)) return;
                try { VDLBridge.onPlay(v.currentSrc || v.src || ''); } catch (_) {}
              }, true);
            })();
        """
    }
}
