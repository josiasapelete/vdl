package app.vdl.bubble

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.vdl.R
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Bulle VDL affichée par-dessus les autres applications (autorisation « superposition »).
 * L'utilisateur copie le lien d'une vidéo dans n'importe quelle app, touche la bulle,
 * et le panneau de téléchargement s'ouvre sans quitter l'app.
 */
class BubbleService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var trash: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private val size get() = dp(56)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Bubble.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Bubble.canDraw(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (bubble == null) showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm.removeView(it) } }
        trash?.let { runCatching { wm.removeView(it) } }
        bubble = null
        trash = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification (obligatoire)

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bulle flottante", NotificationManager.IMPORTANCE_MIN))
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bulle VDL active")
            .setContentText("Copiez le lien d'une vidéo, puis touchez la bulle.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, "Masquer la bulle", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ------------------------------------------------------------------ bulle

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val icon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@BubbleService, R.mipmap.ic_launcher_round))
            elevation = dp(6).toFloat()
            contentDescription = "VDL"
        }
        val view = FrameLayout(this).apply {
            addView(icon, FrameLayout.LayoutParams(size, size))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences("bubble", MODE_PRIVATE)
            x = prefs.getInt("x", screenWidth() - size - dp(8))
            y = prefs.getInt("y", screenHeight() / 3)
        }
        view.setOnTouchListener(DragListener())
        wm.addView(view, params)
        bubble = view
    }

    /** Zone « ✕ » en bas de l'écran : y glisser la bulle la masque. */
    private fun showTrash() {
        if (trash != null) return
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1B1D23.toInt())
                setStroke(dp(2), 0x66FFFFFF)
            }
            addView(ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        val p = WindowManager.LayoutParams(
            dp(64), dp(64), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        wm.addView(circle, p)
        trash = circle
    }

    private fun hideTrash() {
        trash?.let { runCatching { wm.removeView(it) } }
        trash = null
    }

    private fun nearTrash(): Boolean {
        val cx = params.x + size / 2f
        val cy = params.y + size / 2f
        val tx = screenWidth() / 2f
        val ty = screenHeight() - dp(48) - dp(32).toFloat()
        return hypot(cx - tx, cy - ty) < dp(90)
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        showTrash()
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(v, params)
                        trash?.alpha = if (nearTrash()) 1f else 0.7f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        openPanel()
                    } else if (nearTrash()) {
                        hideTrash()
                        Bubble.setEnabled(this@BubbleService, false)
                        stopSelf()
                    } else {
                        hideTrash()
                        snapToEdge(v)
                    }
                }
            }
            return true
        }
    }

    /** La bulle se range contre le bord le plus proche. */
    private fun snapToEdge(v: View) {
        val target = if (params.x + size / 2 < screenWidth() / 2) dp(4) else screenWidth() - size - dp(12)
        params.y = params.y.coerceIn(dp(24), screenHeight() - size - dp(80))
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                if (bubble != null) wm.updateViewLayout(v, params)
            }
            start()
        }
        getSharedPreferences("bubble", MODE_PRIVATE).edit().putInt("x", target).putInt("y", params.y).apply()
    }

    private fun openPanel() {
        startActivity(Intent(this, BubbleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels

    companion object {
        private const val CHANNEL = "bubble"
        private const val NOTIFICATION_ID = 7342
        const val ACTION_STOP = "app.vdl.bubble.STOP"
    }
}

/** Activation de la bulle (préférence + autorisation de superposition). */
object Bubble {
    fun canDraw(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences("bubble", Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences("bubble", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    /** Ouvre le réglage Android « Superposition » pour VDL. */
    fun requestPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun start(ctx: Context) {
        if (canDraw(ctx)) ContextCompat.startForegroundService(ctx, Intent(ctx, BubbleService::class.java))
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, BubbleService::class.java))
    }
}
