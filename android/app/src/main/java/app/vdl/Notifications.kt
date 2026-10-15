package app.vdl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CHANNEL = "downloads"

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    /** Identifiant de notification stable par téléchargement. */
    fun idFor(itemId: String) = itemId.hashCode() and 0x7fffffff

    private fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(ctx: Context, item: DownloadItem, cancel: PendingIntent?) =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.title.ifEmpty { item.url })
            .setContentText(
                when (item.status) {
                    Status.PROCESSING -> "Traitement…"
                    else -> "${item.progress.toInt()} %" + if (item.eta > 0) " · ${formatEta(item.eta)}" else ""
                }
            )
            .setProgress(100, item.progress.toInt(), item.progress <= 0f || item.status == Status.PROCESSING)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(ctx))
            .apply { if (cancel != null) addAction(0, "Annuler", cancel) }
            .build()

    fun finished(ctx: Context, item: DownloadItem) {
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
        if (item.status == Status.DONE) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.fileUri), item.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentTitle("Téléchargement terminé")
                .setContentText(item.title)
                .setContentIntent(PendingIntent.getActivity(ctx, idFor(item.id), view,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            builder.setContentTitle("Échec du téléchargement")
                .setContentText(item.error.ifEmpty { item.title })
                .setStyle(NotificationCompat.BigTextStyle().bigText("${item.title}\n${item.error}"))
                .setContentIntent(openAppIntent(ctx))
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(idFor(item.id) + 1, builder.build()) }
    }

    fun formatEta(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
