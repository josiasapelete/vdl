package app.vdl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vdl.DetectedMedia
import app.vdl.ProbeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProbeSheet(
    state: ProbeState,
    onDownload: (ProbeState.Ready, String, String, Boolean) -> Unit,
    onChoose: (DetectedMedia, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == ProbeState.Idle) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        ProbeContent(state, onDownload, onChoose, onDismiss, Modifier.navigationBarsPadding())
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProbeContent(
    state: ProbeState,
    onDownload: (ProbeState.Ready, String, String, Boolean) -> Unit,
    onChoose: (DetectedMedia, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            ProbeState.Idle -> {}

            is ProbeState.Loading -> {
                Text("Analyse du lien…", style = MaterialTheme.typography.titleLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is ProbeState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Text("Impossible d'analyser ce lien", style = MaterialTheme.typography.titleLarge)
                }
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text("Fermer") }
                    state.retry?.let { retry -> Button(onClick = retry, Modifier.weight(1f)) { Text("Réessayer") } }
                }
            }

            is ProbeState.Choose -> {
                Text("Vidéos détectées", style = MaterialTheme.typography.titleLarge)
                Text("Plusieurs médias ont été chargés par cette page. Le premier est généralement le bon.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.media.forEachIndexed { i, m ->
                    Surface(
                        onClick = { onChoose(m, state.pageTitle) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (i == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (m.kind) {
                                    DetectedMedia.Kind.AUDIO -> Icons.Default.Audiotrack
                                    DetectedMedia.Kind.VIDEO -> Icons.Default.Movie
                                    else -> Icons.Default.Stream
                                }, null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.kind.label + if (i == 0) " · recommandé" else "",
                                    style = MaterialTheme.typography.labelLarge)
                                Text(m.url.substringAfterLast('/').substringBefore('?').ifBlank { m.url },
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            is ProbeState.Ready -> ReadyContent(state, onDownload)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(state: ProbeState.Ready, onDownload: (ProbeState.Ready, String, String, Boolean) -> Unit) {
    val r = state.result
    var mode by rememberSaveable { mutableStateOf("video") }
    var quality by rememberSaveable(r.url) { mutableStateOf("best") }
    var wholePlaylist by rememberSaveable(r.url) { mutableStateOf(true) }
    val title = state.titleHint?.takeIf { r.extractor == "Generic" } ?: r.title

    if (r.thumbnail.isNotBlank()) {
        Thumbnail(r.thumbnail, mode, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 16.dp)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.ifBlank { "Vidéo" }, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(
            r.extractor.takeIf { it.isNotEmpty() && it != "Generic" },
            r.uploader.takeIf { it.isNotEmpty() },
            r.duration.takeIf { it > 0 }?.let { formatEta(it.toLong()) },
            if (r.isPlaylist) "${r.entries.size} éléments" else null,
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = mode == "video", onClick = { mode = "video" },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = { Icon(Icons.Default.Videocam, null, Modifier.size(18.dp)) }) { Text("Vidéo MP4") }
        SegmentedButton(selected = mode == "audio", onClick = { mode = "audio" },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = { Icon(Icons.Default.Audiotrack, null, Modifier.size(18.dp)) }) { Text("Audio MP3") }
    }

    if (mode == "video" && r.qualities.size > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Qualité", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.qualities.forEach { q ->
                    FilterChip(
                        selected = quality == q, onClick = { quality = q },
                        label = { Text(if (q == "best") "Meilleure" else if (q == "2160") "4K" else "${q}p",
                            fontWeight = if (quality == q) FontWeight.SemiBold else FontWeight.Normal) },
                    )
                }
            }
        }
    }

    if (r.isPlaylist) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Toute la playlist", style = MaterialTheme.typography.bodyLarge)
                Text("${r.entries.size} éléments", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = wholePlaylist, onCheckedChange = { wholePlaylist = it })
        }
    }

    Button(
        onClick = { onDownload(state, mode, quality, wholePlaylist) },
        enabled = !r.isPlaylist || wholePlaylist,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(Icons.Default.Download, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (r.isPlaylist && wholePlaylist) "Télécharger ${r.entries.size} éléments" else "Télécharger")
    }
}
