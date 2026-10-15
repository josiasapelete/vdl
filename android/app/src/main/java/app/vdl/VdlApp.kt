package app.vdl

import android.app.Application
import app.vdl.browser.Shields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VdlApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        DownloadStore.load(this)
        scope.launch {
            // Prépare Python/yt-dlp/ffmpeg dès le lancement (première fois : quelques secondes)
            runCatching { Engine.ensureInit(this@VdlApp) }
            runCatching { Engine.autoUpdate(this@VdlApp) }
        }
        scope.launch {
            // Liste de blocage du navigateur (pubs, traqueurs, domaines malveillants)
            Shields.load(this@VdlApp)
            Shields.refreshIfStale(this@VdlApp)
        }
        // Téléchargements interrompus (téléphone redémarré, app tuée) : on les relance
        DownloadStore.items.value.filter { it.status.active }.forEach { DownloadWorker.enqueue(this, it.id) }
    }
}
