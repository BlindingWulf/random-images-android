package com.blindingwulf.randomimages

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the slideshow alive while the activity is closed or the user is in
 * another app, and owns the overlay window's lifecycle.
 *
 * Android 14+ demands a foreground-service type, so the manifest declares
 * specialUse (an image slideshow matches none of the functional types) and the
 * call passes the same constant through ServiceCompat.
 */
class SlideshowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OverlayController? = null
    private var observeJob: Job? = null

    private val engine: SlideshowEngine by lazy { RivApplication.from(this).engine }
    private val settings: Settings by lazy { RivApplication.from(this).settings }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        observeEngine()

        when (intent?.action) {
            ACTION_START -> engine.start()
            ACTION_PAUSE -> engine.pause()
            ACTION_NEXT -> engine.next()
            ACTION_PREVIOUS -> engine.previous()
            // The pop-out window is only ever a direct request: nothing here
            // attaches it as a side effect of starting or restarting the
            // slideshow, and a sticky restart (null intent) never brings it back.
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_HIDE_OVERLAY -> hideOverlay()
            ACTION_STOP -> {
                engine.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        observeJob?.cancel()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(engine.state.value), type)
    }

    /**
     * Attaches the pop-out window. Only ever reached from an explicit request
     * (the viewer's pop-out button, the Settings switch, or its test button),
     * and reports failure by simply not showing anything when the system
     * permission is missing — the callers route the user to the permission page.
     */
    private fun showOverlay() {
        if (!OverlayPermission.isGranted(this)) return
        if (overlay == null) {
            overlay = OverlayController(this, engine, settings) { hideOverlay() }
            overlay?.show()
        }
        overlay?.render(engine.state.value)
    }

    private fun hideOverlay() {
        overlay?.hide()
        overlay = null
    }

    /** Whether the pop-out window is on screen right now. */
    val overlayShowing: Boolean get() = overlay?.isShowing == true

    /** Re-applies overlay preferences (currently the size) to a live window. */
    fun refreshOverlay() {
        overlay?.resize()
    }

    private fun observeEngine() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            engine.state.collect { state ->
                updateNotification(state)
                overlay?.render(state)
            }
        }
    }

    private fun updateNotification(state: EngineState) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: EngineState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            immutableFlag() or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val running = state.running
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(state.status.ifBlank { getString(R.string.notifier_idle) })
            .setContentIntent(open)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(
                if (running) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (running) R.string.pause else R.string.start),
                action(if (running) ACTION_PAUSE else ACTION_START)
            )
            .addAction(R.drawable.ic_next, getString(R.string.next), action(ACTION_NEXT))
            .addAction(R.drawable.ic_close, getString(R.string.stop), action(ACTION_STOP))
            .build()
    }

    private fun action(actionName: String) = PendingIntent.getService(
        this, actionName.hashCode(),
        Intent(this, SlideshowService::class.java).setAction(actionName),
        immutableFlag() or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** FLAG_IMMUTABLE only exists on API 23+; older releases ignore it. */
    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notifier_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notifier_channel_hint)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "slideshow"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.blindingwulf.randomimages.START"
        const val ACTION_PAUSE = "com.blindingwulf.randomimages.PAUSE"
        const val ACTION_STOP = "com.blindingwulf.randomimages.STOP"
        const val ACTION_NEXT = "com.blindingwulf.randomimages.NEXT"
        const val ACTION_PREVIOUS = "com.blindingwulf.randomimages.PREVIOUS"
        const val ACTION_SHOW_OVERLAY = "com.blindingwulf.randomimages.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.blindingwulf.randomimages.HIDE_OVERLAY"

        /** Null unless the service is running, for the activity's start/stop UI. */
        @Volatile
        var instance: SlideshowService? = null
            private set

        fun start(context: Context, actionName: String) {
            val intent = Intent(context, SlideshowService::class.java).setAction(actionName)
            // Context.startForegroundService is API 26; the compat wrapper
            // degrades to startService on 21-25, where that is already the
            // behaviour we want.
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
