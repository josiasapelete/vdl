package app.vdl.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.vdl.DownloadItem
import app.vdl.MainViewModel
import app.vdl.Tab
import app.vdl.browser.BrowserController

@Composable
fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val tab by vm.tab.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val probe by vm.probe.collectAsState()
    val message by vm.message.collectAsState()
    val appUpdate by vm.appUpdate.collectAsState()
    val engineReady by vm.engineReady.collectAsState()
    val detected by vm.detected.collectAsState()
    val videoPlaying by vm.videoPlaying.collectAsState()
    val browserRequest by vm.browserRequest.collectAsState()
    val browserNotice by vm.browserNotice.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var url by rememberSaveable { mutableStateOf("") }
    var clipSuggestion by remember { mutableStateOf<String?>(null) }
    val browser = remember { BrowserController(ctx, vm) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    // Lien copié : proposé quand l'app revient au premier plan
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val found = MainViewModel.extractUrl(readClipboard(ctx))
            val prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
            if (found != null && found != prefs.getString("last_clip", null) && url.isBlank()) {
                clipSuggestion = found
                prefs.edit().putString("last_clip", found).apply()
            }
        }
    }

    BackHandler(enabled = tab == Tab.BROWSER) {
        if (!browser.handleBack()) vm.selectTab(Tab.HOME)
    }
    BackHandler(enabled = tab == Tab.DOWNLOADS) { vm.selectTab(Tab.HOME) }

    val actions = remember {
        DownloadActions(
            onCancel = { vm.cancel(it.id) },
            onRetry = vm::retry,
            onOpen = { open(ctx, it) },
            onShare = { share(ctx, it) },
            onRemove = { vm.remove(it.id) },
        )
    }
    val activeCount = downloads.count { it.status.active }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            VdlBottomBar(tab, vm::selectTab, activeCount, browserDot = detected.isNotEmpty())
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Le navigateur reste en place (page conservée) même quand un autre onglet est affiché
            BrowserTab(
                controller = browser,
                detectedCount = detected.size,
                videoPlaying = videoPlaying,
                request = browserRequest,
                onRequestConsumed = vm::consumeBrowserRequest,
                onDownload = vm::downloadFromBrowser,
                notice = browserNotice,
                onNoticeDismiss = vm::dismissBrowserNotice,
                modifier = if (tab == Tab.BROWSER) Modifier.zIndex(1f) else Modifier.zIndex(0f),
            )
            if (tab != Tab.BROWSER) {
                Box(Modifier.fillMaxSize().zIndex(2f)) {
                    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                        when (tab) {
                            Tab.HOME -> HomeContent(
                                url = url,
                                onUrlChange = { url = it },
                                onSubmit = { vm.analyze(url) },
                                onPaste = { MainViewModel.extractUrl(readClipboard(ctx))?.let { url = it } },
                                clipSuggestion = clipSuggestion,
                                onUseClip = { url = it; clipSuggestion = null; vm.analyze(it) },
                                engineReady = engineReady,
                                appUpdate = appUpdate,
                                onOpenUpdate = { link -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) },
                                recent = downloads,
                                onSeeAll = { vm.selectTab(Tab.DOWNLOADS) },
                                onOpenSite = vm::openInBrowser,
                                onOpenBrowser = { vm.selectTab(Tab.BROWSER) },
                            )
                            else -> DownloadsContent(downloads, actions, onClearFinished = vm::clearFinished)
                        }
                    }
                }
            }
        }
    }

    ProbeSheet(
        state = probe,
        onDownload = { state, mode, quality, all ->
            vm.download(state, mode, quality, all)
            url = ""
        },
        onChoose = vm::analyzeMedia,
        onDismiss = vm::dismissProbe,
    )
}

private fun readClipboard(ctx: Context): String? =
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()

private fun open(ctx: Context, item: DownloadItem) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.fileUri), item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

private fun share(ctx: Context, item: DownloadItem) {
    val send = Intent(Intent.ACTION_SEND).setType(item.mime)
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.fileUri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(send, item.title)) }
}

/** Barre d'onglets du bas (sans état : réutilisée par les captures d'écran du site). */
@Composable
fun VdlBottomBar(tab: Tab, onSelect: (Tab) -> Unit, activeCount: Int, browserDot: Boolean) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        NavigationBarItem(
            selected = tab == Tab.HOME, onClick = { onSelect(Tab.HOME) },
            icon = { Icon(if (tab == Tab.HOME) Icons.Filled.Home else Icons.Outlined.Home, null) },
            label = { Text("Accueil") },
        )
        NavigationBarItem(
            selected = tab == Tab.BROWSER, onClick = { onSelect(Tab.BROWSER) },
            icon = {
                BadgedBox(badge = { if (browserDot && tab != Tab.BROWSER) Badge() }) {
                    Icon(if (tab == Tab.BROWSER) Icons.Filled.Public else Icons.Outlined.Public, null)
                }
            },
            label = { Text("Navigateur") },
        )
        NavigationBarItem(
            selected = tab == Tab.DOWNLOADS, onClick = { onSelect(Tab.DOWNLOADS) },
            icon = {
                BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
                    Icon(if (tab == Tab.DOWNLOADS) Icons.Filled.Download else Icons.Outlined.Download, null)
                }
            },
            label = { Text("Téléchargements") },
        )
    }
}
