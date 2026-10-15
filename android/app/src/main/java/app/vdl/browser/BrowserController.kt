package app.vdl.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

/**
 * Navigateur intégré : un WebView dont on observe les requêtes réseau.
 * Toute vidéo lue (MP4, flux HLS/DASH) passe par le réseau → elle est détectée,
 * quel que soit le site, comme le fait l'extension sur ordinateur.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(context: Context, private val vm: MainViewModel) {
    val webView = WebView(context)
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
            // Certains sites refusent les WebView (« ; wv ») : on se présente comme Chrome mobile
            userAgentString = userAgentString.replace("; wv", "")
        }
        userAgent = webView.settings.userAgentString
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(), "VDLBridge")
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
    }

    // ------------------------------------------------------------------ navigation

    fun load(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val target = when {
            text.startsWith("http://", true) || text.startsWith("https://", true) -> text
            Regex("""^[\w-]+(\.[\w-]+)+(/.*)?$""").matches(text) -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
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

    private fun refreshNav() {
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    // ------------------------------------------------------------------ détection

    private fun headersFor(mediaUrl: String, requestHeaders: Map<String, String>?): Map<String, String> {
        val h = linkedMapOf<String, String>()
        requestHeaders?.forEach { (k, v) -> if (MediaSniffer.KEEP_HEADERS.matches(k)) h[k] = v }
        if (h.keys.none { it.equals("Referer", true) } && url.isNotEmpty()) h["Referer"] = url
        CookieManager.getInstance().getCookie(mediaUrl)?.let { h["Cookie"] = it }
        h["User-Agent"] = userAgent
        return h
    }

    private fun report(mediaUrl: String, requestHeaders: Map<String, String>?) {
        val kind = MediaSniffer.classify(mediaUrl) ?: return
        vm.onMediaDetected(DetectedMedia(mediaUrl, kind, headersFor(mediaUrl, requestHeaders), url))
    }

    private inner class Bridge {
        /** Appelé par le script injecté quand une vidéo démarre. */
        @JavascriptInterface
        fun onPlay(src: String?) {
            main.post {
                vm.onVideoPlaying()
                if (!src.isNullOrBlank() && src.startsWith("http")) {
                    report(src, null)
                    if (MediaSniffer.classify(src) == null) {
                        // <video src="…"> sans extension reconnaissable : c'est quand même la vidéo lue
                        vm.onMediaDetected(DetectedMedia(src, DetectedMedia.Kind.VIDEO, headersFor(src, null), url))
                    }
                }
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return true
            // Liens « intent:// », « market:// »… (ouvrir l'app du site) : ignorés, on reste dans VDL
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
            url = pageUrl
            vm.onPageStarted()
            refreshNav()
        }

        override fun onPageFinished(view: WebView, pageUrl: String) {
            url = pageUrl
            view.evaluateJavascript(INJECTED_JS, null)
            refreshNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
            url = pageUrl   // sites « une page » (YouTube, TikTok…) qui changent d'adresse sans recharger
            refreshNav()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            report(request.url.toString(), request.requestHeaders)
            return null     // on observe seulement, la page charge normalement
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, newTitle: String?) {
            title = newTitle.orEmpty()
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
