package app.vdl.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.vdl.DownloadItem
import app.vdl.Engine
import app.vdl.MainViewModel
import app.vdl.Notifications
import app.vdl.ProbeResult
import app.vdl.ProbeState
import app.vdl.Status
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val downloads by vm.downloads.collectAsState()
    val probe by vm.probe.collectAsState()
    val message by vm.message.collectAsState()
    val appUpdate by vm.appUpdate.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var url by rememberSaveable { mutableStateOf("") }
    var clipSuggestion by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    // Lien copié : proposé quand l'app revient au premier plan
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            val found = MainViewModel.extractUrl(clip)
            val prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
            if (found != null && found != prefs.getString("last_clip", null) && url.isBlank()) {
                clipSuggestion = found
                prefs.edit().putString("last_clip", found).apply()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VDL", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Mettre à jour yt-dlp") }, onClick = { menuOpen = false; vm.updateEngine() })
                        DropdownMenuItem(text = { Text("Effacer les terminés") }, onClick = { menuOpen = false; vm.clearFinished() })
                        DropdownMenuItem(
                            text = { Text("Version de yt-dlp") },
                            onClick = {
                                menuOpen = false
                                scope.launch { snackbar.showSnackbar("yt-dlp ${Engine.version(ctx)}") }
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            appUpdate?.let { (version, link) ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("VDL $version est disponible", Modifier.weight(1f))
                            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }) {
                                Text("Télécharger")
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Lien de la vidéo ou du fichier") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.analyze(url) }),
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            MainViewModel.extractUrl(clip)?.let { url = it }
                        }) { Icon(Icons.Default.ContentPaste, "Coller") }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.analyze(url) },
                    enabled = url.isNotBlank() && probe !is ProbeState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Analyser") }
                clipSuggestion?.let { s ->
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = { url = s; clipSuggestion = null; vm.analyze(s) },
                        label = { Text("Lien copié : ${s.take(40)}…", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)) },
                    )
                }
            }

            when (val p = probe) {
                is ProbeState.Loading -> item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Analyse du lien…")
                    }
                }
                is ProbeState.Failed -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(16.dp)) {
                            Icon(Icons.Default.ErrorOutline, null)
                            Spacer(Modifier.width(12.dp))
                            Text(p.message, Modifier.weight(1f))
                            IconButton(onClick = vm::dismissProbe, Modifier.size(24.dp)) { Icon(Icons.Default.Close, "Fermer") }
                        }
                    }
                }
                is ProbeState.Ready -> item {
                    ProbeCard(p.result, onDownload = { mode, quality, all ->
                        vm.download(p.result, mode, quality, all)
                        url = ""
                    }, onDismiss = vm::dismissProbe)
                }
                ProbeState.Idle -> {}
            }

            if (downloads.isNotEmpty()) {
                item {
                    Text("Téléchargements", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp))
                }
            } else if (probe == ProbeState.Idle) {
                item {
                    Text(
                        "Collez un lien, ou depuis YouTube, TikTok, Instagram ou votre navigateur : " +
                            "Partager → VDL.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(downloads, key = { it.id }) { item ->
                DownloadRow(item, vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProbeCard(result: ProbeResult, onDownload: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf("video") }
    var quality by rememberSaveable(result.url) { mutableStateOf("best") }
    var wholePlaylist by rememberSaveable(result.url) { mutableStateOf(true) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (result.thumbnail.isNotEmpty()) {
                    AsyncImage(
                        model = result.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(112.dp).height(63.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(result.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        result.extractor.takeIf { it.isNotEmpty() && it != "Generic" },
                        result.uploader.takeIf { it.isNotEmpty() },
                        result.duration.takeIf { it > 0 }?.let { Notifications.formatEta(it.toLong()) },
                        if (result.isPlaylist) "${result.entries.size} éléments" else null,
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, Modifier.size(24.dp)) { Icon(Icons.Default.Close, "Fermer") }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = mode == "video", onClick = { mode = "video" },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Default.Videocam, null, Modifier.size(18.dp)) }) { Text("Vidéo") }
                SegmentedButton(selected = mode == "audio", onClick = { mode = "audio" },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Default.Audiotrack, null, Modifier.size(18.dp)) }) { Text("MP3") }
            }

            if (mode == "video" && result.qualities.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    result.qualities.take(5).forEach { q ->
                        FilterChip(selected = quality == q, onClick = { quality = q },
                            label = { Text(if (q == "best") "Max" else "${q}p") })
                    }
                }
            }

            if (result.isPlaylist) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Toute la playlist (${result.entries.size})", Modifier.weight(1f))
                    Switch(checked = wholePlaylist, onCheckedChange = { wholePlaylist = it })
                }
            }

            Button(
                onClick = { onDownload(mode, quality, wholePlaylist) },
                enabled = !result.isPlaylist || wholePlaylist,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Télécharger")
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, vm: MainViewModel) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (item.mode == "audio") Icons.Default.Audiotrack else Icons.Default.Videocam, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    val detail = when (item.status) {
                        Status.RUNNING -> "${item.progress.toInt()} %" +
                            if (item.eta > 0) " · ${Notifications.formatEta(item.eta)} restant" else ""
                        Status.ERROR -> item.error
                        Status.DONE -> item.fileName
                        else -> item.status.label
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                        color = if (item.status == Status.ERROR) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (item.status) {
                    Status.QUEUED, Status.RUNNING, Status.PROCESSING ->
                        IconButton(onClick = { vm.cancel(item.id) }) { Icon(Icons.Default.Close, "Annuler") }
                    Status.DONE -> {
                        IconButton(onClick = { open(ctx, item) }) { Icon(Icons.Default.PlayArrow, "Ouvrir") }
                        IconButton(onClick = { share(ctx, item) }) { Icon(Icons.Default.Share, "Partager") }
                    }
                    Status.ERROR, Status.CANCELED ->
                        IconButton(onClick = { vm.retry(item) }) { Icon(Icons.Default.Refresh, "Réessayer") }
                }
                if (!item.status.active) {
                    IconButton(onClick = { vm.remove(item.id) }) { Icon(Icons.Default.Delete, "Retirer") }
                }
            }
            if (item.status.active) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.padding(end = 12.dp)) {
                    if (item.status == Status.QUEUED || item.status == Status.PROCESSING || item.progress <= 0f) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

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
