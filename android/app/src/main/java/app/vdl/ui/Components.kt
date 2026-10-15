package app.vdl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/** Miniature de vidéo, avec une icône de repli si l'image manque ou ne charge pas. */
@Composable
fun Thumbnail(url: String, mode: String, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val shape = RoundedCornerShape(corner)
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (mode == "audio") Icons.Default.Audiotrack else Icons.Default.Movie, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp),
            )
        }
    }
    Box(modifier.clip(shape)) {
        if (url.isBlank()) {
            Box(Modifier.matchParentSize()) { fallback() }
        } else {
            SubcomposeAsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                loading = { Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) },
                error = { Box(Modifier.matchParentSize()) { fallback() } },
            )
        }
    }
}

@Composable
fun FileBadge(mode: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when (mode) { "audio" -> Icons.Default.Audiotrack; "file" -> Icons.Default.InsertDriveFile; else -> Icons.Default.Movie },
            null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp),
        )
    }
}

/** Pastille ronde avec initiale, pour les raccourcis de sites (pas de logos de marque). */
@Composable
fun SiteDot(letter: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

fun formatEta(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** « 48.20MiB » → « 48,2 Mo » */
fun frenchSize(raw: String): String {
    val m = Regex("""([\d.]+)\s*([KMGT]?)i?B(/s)?""").find(raw) ?: return raw
    val n = m.groupValues[1].toDoubleOrNull() ?: return raw
    val unit = when (m.groupValues[2]) { "K" -> "Ko"; "M" -> "Mo"; "G" -> "Go"; "T" -> "To"; else -> "o" }
    val num = if (n >= 100) "%.0f".format(n) else "%.1f".format(n).replace('.', ',')
    return "$num $unit" + if (m.groupValues[3].isNotEmpty()) "/s" else ""
}
