package app.vdl.ui

import android.view.ViewGroup
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Badge
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.vdl.BrowserNotice
import app.vdl.browser.BrowserController
import app.vdl.browser.Shields
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.TextButton
import kotlinx.coroutines.delay
import app.vdl.browser.MediaSniffer
import androidx.compose.foundation.clickable

@Composable
fun BrowserTab(
    controller: BrowserController,
    detectedCount: Int,
    videoPlaying: Boolean,
    request: String?,
    onRequestConsumed: () -> Unit,
    onDownload: (pageUrl: String, pageTitle: String) -> Unit,
    notice: BrowserNotice?,
    onNoticeDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shieldsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(request) {
        if (request != null) {
            controller.load(request)
            onRequestConsumed()
        }
    }
    val hasPage = controller.url.isNotEmpty()
    val known = hasPage && MediaSniffer.isKnownSite(controller.url)

    Column(modifier.fillMaxSize()) {
        AddressBar(controller, onShields = { shieldsOpen = true })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    // Le même WebView est réutilisé d'un onglet à l'autre (la page reste chargée)
                    (controller.webView.parent as? ViewGroup)?.removeView(controller.webView)
                    controller.webView
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!hasPage) {
                StartPage(onOpen = controller::load, modifier = Modifier.fillMaxSize())
            }
            // Avertissement : pop-up ou redirection bloquée (5 s)
            notice?.let { n ->
                LaunchedEffect(n.id) {
                    delay(5000)
                    onNoticeDismiss(n.id)
                }
                NoticeBanner(
                    n.text,
                    n.action?.let { action -> { onNoticeDismiss(n.id); action() } },
                    Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = hasPage && (known || detectedCount > 0),
                enter = scaleIn(), exit = scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                DownloadFab(
                    label = if (videoPlaying || known) "Télécharger cette vidéo" else "Télécharger",
                    badge = if (known) 0 else detectedCount,
                    onClick = { onDownload(controller.url, controller.title) },
                )
            }
        }
    }

    if (shieldsOpen) {
        ShieldsSheet(
            info = ShieldsInfo(
                site = Shields.siteOfUrl(controller.url),
                enabled = controller.shieldsOn,
                blockedRequests = controller.blockedRequests,
                blockedPopups = controller.blockedPopups,
                blockedRedirects = controller.blockedRedirects,
                listSize = Shields.size,
            ),
            onToggle = controller::setShields,
            onDismiss = { shieldsOpen = false },
        )
    }
}

@Composable
private fun AddressBar(controller: BrowserController, onShields: () -> Unit) = AddressBarContent(
    url = controller.url,
    canGoBack = controller.canGoBack,
    canGoForward = controller.canGoForward,
    progress = controller.progress,
    shieldsOn = controller.shieldsOn,
    blockedTotal = controller.blockedTotal,
    onBack = controller::back,
    onForward = controller::forward,
    onReload = controller::reload,
    onLoad = controller::load,
    onShields = onShields,
)

/** Barre d'adresse sans état (réutilisée par les captures d'écran du site). */
@Composable
fun AddressBarContent(
    url: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    progress: Int,
    shieldsOn: Boolean,
    blockedTotal: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onLoad: (String) -> Unit,
    onShields: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Précédent")
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.weight(1f).height(42.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (url.startsWith("https") && !editing) Icons.Default.Lock else Icons.Default.Search,
                            null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = if (editing) text else displayUrl(url),
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                onLoad(text)
                                focus.clearFocus()
                            }),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (editing && text.isEmpty() || !editing && url.isEmpty()) {
                                        Text("Adresse ou recherche", style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f).onFocusChanged {
                                if (it.isFocused && !editing) text = url
                                editing = it.isFocused
                            },
                        )
                    }
                }
                if (canGoForward) {
                    IconButton(onClick = onForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Suivant") }
                }
                IconButton(onClick = onReload, enabled = url.isNotEmpty()) {
                    Icon(Icons.Default.Refresh, "Recharger")
                }
                // Bouclier : nombre d'éléments bloqués sur la page
                IconButton(onClick = onShields) {
                    BadgedBox(badge = {
                        if (shieldsOn && blockedTotal > 0) {
                            Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                                Text(if (blockedTotal > 99) "99+" else "$blockedTotal")
                            }
                        }
                    }) {
                        Icon(
                            if (shieldsOn) Icons.Default.GppGood else Icons.Default.GppMaybe, "Protection",
                            tint = if (shieldsOn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** Avertissement « pop-up / redirection bloquée ». */
@Composable
fun NoticeBanner(text: String, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GppGood, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.inversePrimary)
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            if (onAction != null) {
                TextButton(onClick = onAction) { Text("Ouvrir", color = MaterialTheme.colorScheme.inversePrimary) }
            }
        }
    }
}

/** Bouton flottant « Télécharger cette vidéo ». */
@Composable
fun DownloadFab(label: String, badge: Int, onClick: () -> Unit) {
    BadgedBox(badge = { if (badge > 1) Badge { Text("$badge") } }) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(Icons.Default.Download, null) },
            text = { Text(label) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private fun displayUrl(url: String) = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")

@Composable
fun StartPage(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val go = {
        if (query.isNotBlank()) {
            onOpen(query)
            focus.clearFocus()
        }
    }
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Navigateur", style = MaterialTheme.typography.headlineSmall)
            Text("Ouvrez n'importe quel site et lancez une vidéo : VDL la détecte et affiche le bouton Télécharger.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Rechercher ou saisir une adresse", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = go) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Aller") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
            )
            Text("Sites populaires", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            POPULAR_SITES.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { site ->
                        Column(
                            Modifier.width(76.dp).clickable { onOpen(site.url) }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SiteDot(site.letter, site.color)
                            Spacer(Modifier.height(6.dp))
                            Text(site.name, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
