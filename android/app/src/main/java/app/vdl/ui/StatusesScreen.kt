package app.vdl.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.vdl.Storage
import app.vdl.statuses.StatusItem
import app.vdl.statuses.WhatsAppStatuses
import app.vdl.statuses.WhatsAppStatuses.Source
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** Écran « Statuts WhatsApp » (avec état : autorisation, liste, enregistrement). */
@Composable
fun StatusesScreen(onBack: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf(Source.WHATSAPP) }
    var tree by remember(source) { mutableStateOf(WhatsAppStatuses.grantedTree(ctx, source)) }
    var items by remember(source) { mutableStateOf<List<StatusItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val saving = remember { mutableStateListOf<String>() }
    var refresh by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            WhatsAppStatuses.saveGrant(ctx, source, uri)
            tree = uri
            if (!WhatsAppStatuses.looksRight(uri)) {
                onMessage("Ce dossier ne semble pas être celui des statuts. Choisissez « .Statuses ».")
            }
        }
    }

    // Recharge à l'ouverture, au changement de source et au retour dans l'app (nouveaux statuts vus)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tree, refresh) {
        val t = tree ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            loading = true
            items = runCatching { WhatsAppStatuses.list(ctx, t) }.getOrDefault(emptyList())
            loading = false
        }
    }

    StatusesContent(
        source = source,
        onSource = { source = it },
        granted = tree != null,
        loading = loading,
        items = items,
        isSaved = { WhatsAppStatuses.isSaved(ctx, it) || it.name in saving },
        onGrant = { picker.launch(WhatsAppStatuses.initialUri(source)) },
        onRefresh = { refresh++ },
        onOpen = { open(ctx, it) },
        onSave = { item ->
            saving += item.name
            scope.launch {
                runCatching {
                    Storage.publishStream(ctx, item.name, Storage.mimeOf(item.name), "Statuts") {
                        ctx.contentResolver.openInputStream(item.uri)!!
                    }
                }.onSuccess {
                    WhatsAppStatuses.markSaved(ctx, item)
                    onMessage(if (item.isVideo) "Vidéo enregistrée dans Téléchargements/VDL/Statuts"
                    else "Photo enregistrée dans Téléchargements/VDL/Statuts")
                }.onFailure {
                    saving -= item.name
                    onMessage("Enregistrement impossible : ${it.message}")
                }
            }
        },
        onBack = onBack,
    )
}

private fun open(ctx: Context, item: StatusItem) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

/** Écran « Statuts WhatsApp » sans état (aussi utilisé par les captures d'écran). */
@Composable
fun StatusesContent(
    source: Source,
    onSource: (Source) -> Unit,
    granted: Boolean,
    loading: Boolean,
    items: List<StatusItem>,
    isSaved: (StatusItem) -> Boolean,
    onGrant: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (StatusItem) -> Unit,
    onSave: (StatusItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            Text("Statuts WhatsApp", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (granted) IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Actualiser") }
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Source.entries.forEach { s ->
                FilterChip(selected = source == s, onClick = { onSource(s) }, label = { Text(s.label) })
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            !granted -> GrantCard(source, onGrant)
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> EmptyState(
                Icons.Default.PhotoLibrary, "Aucun statut",
                "Regardez des statuts dans ${source.label}, puis revenez ici : ils apparaîtront automatiquement.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.name }) { item ->
                    StatusTile(item, isSaved(item), onOpen = { onOpen(item) }, onSave = { onSave(item) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Ces statuts appartiennent à vos contacts : ne les republiez pas sans leur accord.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GrantCard(source: Source, onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("Autoriser l'accès aux statuts", style = MaterialTheme.typography.titleMedium)
        Text(
            "Android va ouvrir le dossier des statuts de ${source.label}. Touchez « Utiliser ce dossier » " +
                "puis « Autoriser ». À faire une seule fois.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onGrant, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text("Autoriser l'accès")
        }
    }
}

@Composable
private fun StatusTile(item: StatusItem, saved: Boolean, onOpen: () -> Unit, onSave: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onOpen),
    ) {
        AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.isVideo) {
            Box(Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(Color(0x66000000)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Surface(
            onClick = onSave,
            enabled = !saved,
            shape = CircleShape,
            color = if (saved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (saved) Icons.Default.Check else Icons.Default.Download,
                    if (saved) "Enregistré" else "Enregistrer", Modifier.size(18.dp))
            }
        }
    }
}

