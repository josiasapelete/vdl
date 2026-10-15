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
import androidx.compose.material3.Badge
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
import app.vdl.browser.BrowserController
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
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(request) {
        if (request != null) {
            controller.load(request)
            onRequestConsumed()
        }
    }
    val hasPage = controller.url.isNotEmpty()
    val known = hasPage && MediaSniffer.isKnownSite(controller.url)

    Column(modifier.fillMaxSize()) {
        AddressBar(controller)
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
            androidx.compose.animation.AnimatedVisibility(
                visible = hasPage && (known || detectedCount > 0),
                enter = scaleIn(), exit = scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                BadgedBox(badge = { if (detectedCount > 1 && !known) Badge { Text("$detectedCount") } }) {
                    ExtendedFloatingActionButton(
                        onClick = { onDownload(controller.url, controller.title) },
                        icon = { Icon(Icons.Default.Download, null) },
                        text = { Text(if (videoPlaying || known) "Télécharger cette vidéo" else "Télécharger") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressBar(controller: BrowserController) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = controller::back, enabled = controller.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Précédent")
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f).height(42.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (controller.url.startsWith("https") && !editing) Icons.Default.Lock else Icons.Default.Search,
                            null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = if (editing) text else displayUrl(controller.url),
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                controller.load(text)
                                focus.clearFocus()
                            }),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (editing && text.isEmpty() || !editing && controller.url.isEmpty()) {
                                        Text("Rechercher ou saisir une adresse", style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f).onFocusChanged {
                                if (it.isFocused && !editing) text = controller.url
                                editing = it.isFocused
                            },
                        )
                    }
                }
                if (controller.canGoForward) {
                    IconButton(onClick = controller::forward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Suivant") }
                }
                IconButton(onClick = controller::reload, enabled = controller.url.isNotEmpty()) {
                    Icon(Icons.Default.Refresh, "Recharger")
                }
            }
            if (controller.progress in 1..99) {
                LinearProgressIndicator(progress = { controller.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

private fun displayUrl(url: String) = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")

@Composable
fun StartPage(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Navigateur", style = MaterialTheme.typography.headlineSmall)
            Text("Ouvrez n'importe quel site et lancez une vidéo : VDL la détecte et affiche le bouton Télécharger.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
