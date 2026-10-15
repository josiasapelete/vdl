package app.vdl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vdl.DownloadItem
import app.vdl.Status

class DownloadActions(
    val onCancel: (DownloadItem) -> Unit,
    val onRetry: (DownloadItem) -> Unit,
    val onOpen: (DownloadItem) -> Unit,
    val onShare: (DownloadItem) -> Unit,
    val onRemove: (DownloadItem) -> Unit,
)

private enum class Filter(val label: String) { ALL("Tous"), ACTIVE("En cours"), DONE("Terminés") }

@Composable
fun DownloadsContent(
    items: List<DownloadItem>,
    actions: DownloadActions,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val shown = when (filter) {
        Filter.ALL -> items
        Filter.ACTIVE -> items.filter { it.status.active }
        Filter.DONE -> items.filter { it.status == Status.DONE }
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Téléchargements", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (items.any { !it.status.active }) TextButton(onClick = onClearFinished) { Text("Nettoyer") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    val count = when (f) {
                        Filter.ALL -> items.size
                        Filter.ACTIVE -> items.count { it.status.active }
                        Filter.DONE -> items.count { it.status == Status.DONE }
                    }
                    FilterChip(selected = filter == f, onClick = { filter = f },
                        label = { Text(if (count > 0) "${f.label} · $count" else f.label) })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    Icons.Default.DownloadDone,
                    if (filter == Filter.ACTIVE) "Rien en cours" else "Aucun téléchargement",
                    "Collez un lien dans l'accueil, partagez une vidéo vers VDL, ou ouvrez un site dans le navigateur.",
                )
            }
        }
        items(shown, key = { it.id }) { item -> DownloadCard(item, actions) }
    }
}

@Composable
fun DownloadCard(item: DownloadItem, actions: DownloadActions?, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.thumbnail.isNotBlank()) {
                    Thumbnail(item.thumbnail, item.mode, Modifier.width(88.dp).height(50.dp), corner = 10.dp)
                } else {
                    FileBadge(item.mode)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        statusLine(item), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = when (item.status) {
                            Status.ERROR -> MaterialTheme.colorScheme.error
                            Status.DONE -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (actions != null) ActionsFor(item, actions)
            }
            if (item.status.active) {
                Spacer(Modifier.height(10.dp))
                if (item.status == Status.RUNNING && item.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), strokeCap = StrokeCap.Round)
                }
            }
        }
    }
}

private fun statusLine(item: DownloadItem): String = when (item.status) {
    Status.RUNNING -> listOfNotNull(
        "${item.progress.toInt()} %",
        item.size.takeIf { it.isNotBlank() }?.let { frenchSize(it) },
        item.speed.takeIf { it.isNotBlank() }?.let { frenchSize(it) },
        item.eta.takeIf { it > 0 }?.let { "${formatEta(it)} restant" },
    ).joinToString(" · ")
    Status.PROCESSING -> "Finalisation…"
    Status.QUEUED -> "En attente…"
    Status.DONE -> listOfNotNull(
        if (item.mode == "audio") "MP3" else "Vidéo",
        item.size.takeIf { it.isNotBlank() }?.let { frenchSize(it) },
        "Terminé",
    ).joinToString(" · ")
    Status.ERROR -> item.error.ifBlank { "Erreur" }
    Status.CANCELED -> "Annulé"
}

@Composable
private fun ActionsFor(item: DownloadItem, a: DownloadActions) {
    var menu by remember { mutableStateOf(false) }
    when (item.status) {
        Status.QUEUED, Status.RUNNING, Status.PROCESSING ->
            IconButton(onClick = { a.onCancel(item) }) { Icon(Icons.Default.Close, "Annuler") }
        Status.DONE -> IconButton(onClick = { a.onOpen(item) }) {
            Icon(Icons.Default.PlayArrow, "Ouvrir", tint = MaterialTheme.colorScheme.primary)
        }
        Status.ERROR, Status.CANCELED -> IconButton(onClick = { a.onRetry(item) }) { Icon(Icons.Default.Refresh, "Réessayer") }
    }
    if (!item.status.active) {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "Plus") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (item.status == Status.DONE) {
                    DropdownMenuItem(text = { Text("Partager") }, leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = { menu = false; a.onShare(item) })
                }
                DropdownMenuItem(text = { Text("Retirer de la liste") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                    onClick = { menu = false; a.onRemove(item) })
            }
        }
    }
}
