package app.vdl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class ShieldsInfo(
    val site: String,
    val enabled: Boolean,
    val blockedRequests: Int,
    val blockedPopups: Int,
    val blockedRedirects: Int,
    val listSize: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldsSheet(info: ShieldsInfo, onToggle: (Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        ShieldsContent(info, onToggle, Modifier.navigationBarsPadding())
    }
}

@Composable
fun ShieldsContent(info: ShieldsInfo, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val on = info.enabled
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(
                    if (on) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (on) Icons.Default.GppGood else Icons.Default.GppMaybe, null, Modifier.size(28.dp),
                    tint = if (on) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (on) "Protection active" else "Protection désactivée", style = MaterialTheme.typography.titleLarge)
                Text(info.site.ifEmpty { "Aucun site ouvert" }, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                StatRow(Icons.Default.Block, "Pubs et traqueurs bloqués", info.blockedRequests)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                StatRow(Icons.Default.OpenInNew, "Pop-ups bloquées", info.blockedPopups)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                StatRow(Icons.Default.TurnRight, "Redirections bloquées", info.blockedRedirects)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Protéger ce site", style = MaterialTheme.typography.bodyLarge)
                Text("Désactivez seulement si la page s'affiche mal.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = onToggle, enabled = info.site.isNotEmpty())
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoLine(Icons.Default.VerifiedUser, "Navigation sécurisée Google : alerte avant les sites d'hameçonnage et les logiciels malveillants.")
            InfoLine(Icons.Default.Shield, "Liste de blocage : ${"%,d".format(info.listSize).replace(',', ' ')} domaines, mise à jour chaque semaine.")
        }
    }
}

@Composable
private fun StatRow(icon: ImageVector, label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("$count", style = MaterialTheme.typography.titleMedium,
            color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
