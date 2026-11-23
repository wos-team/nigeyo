package jp.nigeyo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RegionalMonitorService : Service() {
    private val training get() = (application as NigeyoApplication).training
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var poller: Job? = null
    private var wasConnected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(MONITOR_NOTIFICATION, notification(wasConnected))
        if (!training.alerts.enabled || !training.alerts.cloudConfigured || !training.notificationsEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (poller?.isActive == true) return START_STICKY
        poller = scope.launch { monitor() }
        return START_STICKY
    }

    private suspend fun monitor() {
        var interval = 10_000L
        while (scope.isActive && training.alerts.enabled) {
            var connected = false
            try {
                if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) break
                training.alerts.synchronize(training.language.id)
                val alerts = training.alerts.poll()
                alerts.forEach { alert ->
                    val fields = alert.keys().asSequence().associateWith(alert::getString)
                    training.ingestInbox(fields)
                }
                connected = true
                training.alerts.markConnected()
                interval = 10_000L
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                training.alerts.markDisconnected(error is RegionalRegistration.RegistrationExpired)
                interval = (interval * 2).coerceAtMost(60_000L)
            }
            if (connected != wasConnected) {
                wasConnected = connected
                training.log(if (connected) "regional monitor connected" else "regional monitor reconnecting")
                getSystemService(NotificationManager::class.java)
                    .notify(MONITOR_NOTIFICATION, notification(connected))
            }
            delay(interval)
        }
        stopSelf()
    }

    private fun notification(connected: Boolean): Notification {
        val strings = training.language
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MONITOR_CHANNEL, strings["android.regionalMonitor"], NotificationManager.IMPORTANCE_LOW),
        )
        val launch = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_drill_notification)
            .setContentTitle(strings["android.regionalMonitor"])
            .setContentText(strings[if (connected) "android.regionalConnected" else "android.regionalReconnecting"])
            .setContentIntent(launch)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val MONITOR_CHANNEL = "regional-monitor"
        const val MONITOR_NOTIFICATION = 3
    }
}

class MonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as NigeyoApplication
        app.training.refreshRegionalMonitor()
    }
}
