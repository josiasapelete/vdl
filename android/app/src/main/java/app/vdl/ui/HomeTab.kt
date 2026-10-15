package app.vdl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vdl.DownloadItem

data class Site(val name: String, val letter: String, val color: Color, val url: String)

val POPULAR_SITES = listOf(
    Site("YouTube", "Y", Color(0xFFE53935), "https://m.youtube.com"),
    Site("TikTok", "T", Color(0xFFFE2C55), "https://www.tiktok.com"),
    Site("Instagram", "I", Color(0xFFC13584), "https://www.instagram.com"),
    Site("Facebook", "f", Color(0xFF1877F2), "https://m.facebook.com"),
    Site("X", "X", Color(0xFF3A3F4B), "https://x.com"),
    Site("Vimeo", "V", Color(0xFF17A2D6), "https://vimeo.com"),
    Site("Dailymotion", "D", Color(0xFF0060D6), "https://www.dailymotion.com"),
    Site("SoundCloud", "S", Color(0xFFFF5500), "https://soundcloud.com"),
)

@Composable
fun HomeContent(
    url: String,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onPaste: () -> Unit,
    clipSuggestion: String?,
    onUseClip: (String) -> Unit,
    engineReady: Boolean,
    appUpdate: String?,                 // version disponible
    updateProgress: Float?,             // téléchargement de la mise à jour en cours (0..1)
    onUpdate: () -> Unit,
    recent: List<DownloadItem>,
    onSeeAll: () -> Unit,
    onOpenSite: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column {
                Text("VDL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text("Que voulez-vous\ntélécharger ?", style = MaterialTheme.typography.headlineMedium)
            }
        }

        appUpdate?.let { version ->
            item {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (updateProgress == null) "VDL $version est disponible"
                                else "Téléchargement de VDL $version… ${(updateProgress * 100).toInt()} %",
                                Modifier.weight(1f).padding(vertical = if (updateProgress == null) 0.dp else 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (updateProgress == null) TextButton(onClick = onUpdate) { Text("Mettre à jour") }
                        }
                        if (updateProgress != null) {
                            LinearProgressIndicator(progress = { updateProgress }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Collez un lien", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        trailingIcon = { IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Coller") } },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    )
                    Button(
                        onClick = onSubmit,
                        enabled = url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Télécharger")
                    }
                    if (clipSuggestion != null) {
                        Surface(
                            onClick = { onUseClip(clipSuggestion) },
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Lien copié détecté", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(clipSuggestion, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Text("Utiliser", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (!engineReady) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Préparation du moteur (première ouverture)…", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Naviguer et télécharger", style = MaterialTheme.typography.titleMedium)
                Text("Ouvrez un site, lancez la vidéo : le bouton Télécharger apparaît.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                POPULAR_SITES.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { site ->
                            Column(
                                Modifier.width(76.dp).clickable { onOpenSite(site.url) }.padding(vertical = 6.dp),
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
                OutlinedButton(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Public, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ouvrir un autre site")
                }
            }
        }

        if (recent.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Récents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSeeAll) { Text("Tout voir") }
                }
            }
            recent.take(3).forEach { item ->
                item(key = item.id) { DownloadCard(item, actions = null) }
            }
        }
    }
}
