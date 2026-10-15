package app.vdl

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

/** Un téléchargement = un travail WorkManager au premier plan (continue écran éteint / app fermée). */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val itemId get() = inputData.getString(KEY_ID)!!

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val item = DownloadStore.get(itemId) ?: DownloadItem(itemId, "", "VDL", "video", "best")
        return foregroundInfo(item)
    }

    private fun foregroundInfo(item: DownloadItem): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = Notifications.progress(applicationContext, item, cancel)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.idFor(item.id), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.idFor(item.id), notification)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        DownloadStore.load(ctx)
        val item = DownloadStore.get(itemId) ?: return Result.failure()
        if (item.status == Status.CANCELED || item.status == Status.DONE) return Result.success()

        Engine.ensureInit(ctx)
        DownloadStore.update(itemId) { it.copy(status = Status.RUNNING, error = "") }
        runCatching { setForeground(foregroundInfo(DownloadStore.get(itemId)!!)) }

        val workDir = File(ctx.cacheDir, "dl/$itemId")
        var lastNotify = 0L
        return try {
            val file = coroutineScope {
                // Annulation (bouton « Annuler » ou WorkManager) : on tue le processus yt-dlp
                val killer = launch { try { awaitCancellation() } finally { Engine.cancel(itemId) } }
                val f = runInterruptible(Dispatchers.IO) {
                    Engine.download(item, workDir) { progress, eta, line ->
                        val processing = line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") ||
                            line.startsWith("[Metadata]") || line.startsWith("[VideoConvertor]")
                        val (size, speed) = Engine.parseProgressLine(line)
                        DownloadStore.update(itemId, persist = false) {
                            it.copy(
                                status = if (processing) Status.PROCESSING else Status.RUNNING,
                                progress = if (progress >= 0) progress else it.progress,
                                eta = eta,
                                size = size ?: it.size,
                                speed = if (processing) "" else speed ?: it.speed,
                            )
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 1000) {
                            lastNotify = now
                            DownloadStore.get(itemId)?.let { notifyProgress(it) }
                        }
                    }
                }
                killer.cancel()
                f
            }
            DownloadStore.update(itemId) { it.copy(status = Status.PROCESSING) }
            val (uri, mime) = Storage.publish(ctx, file)
            DownloadStore.update(itemId) {
                it.copy(status = Status.DONE, progress = 100f, eta = 0, speed = "", fileName = file.name,
                    fileUri = uri.toString(), mime = mime)
            }
            DownloadStore.get(itemId)?.let { Notifications.finished(ctx, it) }
            Result.success()
        } catch (e: CancellationException) {
            DownloadStore.update(itemId) { it.copy(status = Status.CANCELED, eta = 0) }
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            DownloadStore.update(itemId) { it.copy(status = Status.CANCELED, eta = 0) }
            Result.success()
        } catch (e: Exception) {
            DownloadStore.update(itemId) {
                it.copy(status = Status.ERROR, eta = 0, error = e.message ?: e.javaClass.simpleName)
            }
            DownloadStore.get(itemId)?.let { Notifications.finished(ctx, it) }
            Result.success()     // l'erreur est affichée ; WorkManager ne doit pas réessayer seul
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun notifyProgress(item: DownloadItem) {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(Notifications.idFor(item.id), Notifications.progress(applicationContext, item, cancel))
        }
    }

    companion object {
        private const val KEY_ID = "id"

        fun enqueue(ctx: Context, itemId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ID to itemId))
                .addTag("download")
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(itemId, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(ctx: Context, itemId: String) {
            Engine.cancel(itemId)
            WorkManager.getInstance(ctx).cancelUniqueWork(itemId)
            DownloadStore.update(itemId) { it.copy(status = Status.CANCELED, eta = 0) }
        }
    }
}
