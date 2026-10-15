package app.vdl.browser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Protections du navigateur intégré (inspirées de Brave) :
 * liste de blocage des pubs / traqueurs / domaines malveillants, et réglage par site.
 */
object Shields {
    /** Liste « unifiée » StevenBlack (pubs + logiciels malveillants), licence MIT. */
    private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val ASSET = "blocklist/domains.txt"
    private const val LOCAL = "blocklist.txt"
    private const val REFRESH_MS = 7L * 24 * 3600 * 1000

    // Régies « popunder » et de redirection très présentes sur les sites de streaming,
    // ajoutées au cas où la liste téléchargée ne les contiendrait pas encore.
    private val EXTRA = setOf(
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adsterra.com", "adsterratech.com",
        "onclickads.net", "onclkds.com", "exoclick.com", "exosrv.com", "juicyads.com", "hilltopads.net",
        "clickadu.com", "adcash.com", "monetag.com", "galaksion.com", "admaven.com", "trafficjunky.net",
        "trafficstars.com", "popmyads.com", "clickaine.com", "adspyglass.com", "richpartners.com",
        "pushwhy.com", "notix.io", "evadav.com", "rollerads.com", "a-ads.com", "dolohen.com",
    )

    @Volatile private var domains: Set<String> = EXTRA
    val size get() = domains.size

    /** Charge la liste (copie locale à jour, sinon celle embarquée dans l'app). */
    suspend fun load(ctx: Context) = withContext(Dispatchers.IO) {
        val local = File(ctx.filesDir, LOCAL)
        val lines = runCatching {
            if (local.exists()) local.readLines()
            else ctx.assets.open(ASSET).bufferedReader().use { it.readLines() }
        }.getOrDefault(emptyList())
        domains = HashSet<String>(lines.size + EXTRA.size).apply {
            lines.forEach { l -> l.trim().takeIf { it.isNotEmpty() }?.let(::add) }
            addAll(EXTRA)
        }
    }

    /** Met la liste à jour une fois par semaine. */
    suspend fun refreshIfStale(ctx: Context) = withContext(Dispatchers.IO) {
        val local = File(ctx.filesDir, LOCAL)
        if (local.exists() && System.currentTimeMillis() - local.lastModified() < REFRESH_MS) return@withContext
        runCatching {
            val text = (URL(LIST_URL).openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 30_000
                inputStream.bufferedReader().use { it.readText() }
            }
            val parsed = parseHosts(text)
            if (parsed.size > 10_000) {          // garde-fou : jamais remplacer par une liste vide ou tronquée
                val tmp = File(ctx.filesDir, "$LOCAL.tmp")
                tmp.writeText(parsed.joinToString("\n"))
                tmp.renameTo(local)
                load(ctx)
            }
        }
    }

    fun parseHosts(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("0.0.0.0 ") }
        .map { it.substringAfter(' ').substringBefore('#').trim() }
        .filter { it.isNotEmpty() && it != "0.0.0.0" }
        .toList()

    /** Le domaine ou l'un de ses parents est-il dans la liste ? (pub.exemple.com → exemple.com) */
    fun isBlocked(host: String?): Boolean {
        var h = host?.lowercase()?.removePrefix("www.") ?: return false
        val set = domains
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false   // on s'arrête au domaine principal
            h = h.substring(dot + 1)
        }
    }

    // ------------------------------------------------------------------ sites

    private val TWO_LEVEL = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.br", "com.au", "co.jp", "co.kr", "co.in", "co.za",
        "com.mx", "com.ar", "com.tr", "com.cn", "com.tw", "com.hk", "co.nz", "com.sg", "co.id", "com.ng",
    )

    /** « m.video.exemple.co.uk » → « exemple.co.uk » (approximation du domaine enregistré). */
    fun siteOf(host: String?): String {
        val parts = host?.lowercase()?.split('.')?.filter { it.isNotEmpty() } ?: return ""
        if (parts.size <= 2) return parts.joinToString(".")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_LEVEL) parts.takeLast(3).joinToString(".") else lastTwo
    }

    fun siteOfUrl(url: String?): String = siteOf(runCatching { Uri.parse(url).host }.getOrNull())

    fun sameSite(a: String?, b: String?) = siteOfUrl(a).let { it.isNotEmpty() && it == siteOfUrl(b) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shields", Context.MODE_PRIVATE)

    /** Protection désactivée par l'utilisateur pour ce site (site qui s'affiche mal). */
    fun isDisabledFor(ctx: Context, site: String) = site in prefs(ctx).getStringSet("off", emptySet())!!

    fun setDisabledFor(ctx: Context, site: String, disabled: Boolean) {
        val set = prefs(ctx).getStringSet("off", emptySet())!!.toMutableSet()
        if (disabled) set += site else set -= site
        prefs(ctx).edit().putStringSet("off", set).apply()
    }

    /** Masque les emplacements publicitaires courants (filtrage « cosmétique »). */
    const val COSMETIC_CSS = """
        [id^="google_ads"], [id^="div-gpt-ad"], ins.adsbygoogle, iframe[src*="doubleclick"],
        iframe[src*="googlesyndication"], [class*="ad-banner"], [class*="adsbox"], [class*="ad-container"],
        [id*="ad-container"], [class^="advert"], [id^="advert"], [class*="sponsored-ad"], [aria-label="Advertisement"],
        div[data-ad], div[data-ad-slot], a[href*="//popads."], a[href*="//adsterra"] { display: none !important; }
    """
}
