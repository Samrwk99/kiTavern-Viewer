package com.shinydiscoballsdev.kifossk

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Long-lived, user-enabled foreground service. Its lifetime is governed by the
 * Keep Alive preference, not by MainActivity's lifecycle or notification detail
 * preference. It deliberately does not own the WebView: Android WebView instances
 * need an Activity context and UI attachment.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "sillytavern_keepalive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val appContext = context.applicationContext
            if (!KioskPrefs.getKeepAlive(appContext)) return

            val intent = Intent(appContext, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                // Starting an FGS can be rejected by Android policy/state restrictions.
                Log.e(TAG, "Unable to start keep-alive foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, KeepAliveService::class.java)
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY can restart the service with a null Intent. Always re-read the setting.
        if (!KioskPrefs.getKeepAlive(this)) {
            releaseWakeLock()
            stopForeground(true)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Requires android:foregroundServiceType="specialUse" plus its manifest permission/property.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            acquireWakeLock()
        } catch (e: Exception) {
            Log.e(TAG, "Could not promote keep-alive service to foreground", e)
            releaseWakeLock()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Android may recreate this service after process reclamation. The setting
        // is checked again above before allowing it to stay alive.
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val showDetails = KioskPrefs.getShowNotification(this)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("SillyTavern")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (showDetails) {
            builder.setContentText("Keep Alive is enabled")
        } else {
            // Keep the required FGS notification as unobtrusive as possible.
            // Android does not allow an app to run an FGS with no notification.
            builder.setContentText(null)
        }

        return builder.build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:KeepAlive"
        ).also { wakeLock = it }
        lock.setReferenceCounted(false)
        if (!lock.isHeld) {
            // Intentionally held only while the user enables Keep Alive. This increases battery use.
            lock.acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Wake lock release failed", e)
        } finally {
            wakeLock = null
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Keep Alive",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the user-enabled SillyTavern session service active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
