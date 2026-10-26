package app.memo.platform.android.llm

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import app.memo.R
import app.memo.runtime.InferenceManager

/**
 * Foreground service that keeps generation alive while the app is in the background.
 *
 * It is `specialUse` (subtype in the manifest). It starts only when all of these hold:
 * the app is in the foreground, and one generation has been running for more than
 * [PROMOTE_AFTER_MS]. It stops as soon as the manager goes idle.
 *
 * Wire it with [onManagerState] as the [InferenceManager] state listener.
 */
class InferenceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "app.memo.action.STOP_INFERENCE"
        const val PROMOTE_AFTER_MS = 10_000L
        private const val CHANNEL_ID = "memo_inference"
        private const val NOTIFICATION_ID = 4101

        private val main = Handler(Looper.getMainLooper())
        private var scheduledFor: Long? = null
        private var pending: Runnable? = null
        private var serviceRunning = false

        /**
         * State listener for [InferenceManager]. Called on the manager's worker thread, so the
         * work is handed to the main looper. Only timers and service intents run there, never generation.
         */
        fun onManagerState(context: Context, state: InferenceManager.State) {
            val app = context.applicationContext
            main.post {
                when (state) {
                    is InferenceManager.State.Generating -> schedulePromotion(app, state.requestId)
                    else -> {
                        cancelPromotion()
                        if (serviceRunning) {
                            serviceRunning = false
                            app.stopService(Intent(app, InferenceService::class.java))
                        }
                    }
                }
            }
        }

        private fun schedulePromotion(app: Context, requestId: Long) {
            if (scheduledFor == requestId) return
            cancelPromotion()
            scheduledFor = requestId
            val task = Runnable {
                pending = null
                if (scheduledFor == requestId && !serviceRunning && isAppInForeground(app)) {
                    serviceRunning = true
                    app.startForegroundService(Intent(app, InferenceService::class.java))
                }
            }
            pending = task
            main.postDelayed(task, PROMOTE_AFTER_MS)
        }

        private fun cancelPromotion() {
            pending?.let { main.removeCallbacks(it) }
            pending = null
            scheduledFor = null
        }

        /** True while this process has a foreground activity. Needs no extra permission. */
        fun isAppInForeground(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val me = am.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() } ?: return false
            return me.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Local AI", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        private fun buildNotification(context: Context): Notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_memo_mark)
                .setContentTitle("Memo is generating a reply")
                .setOngoing(true)
                .build()
    }
}
