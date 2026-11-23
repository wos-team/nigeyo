package jp.nigeyo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap

internal const val OPEN_EVACUATION = "jp.nigeyo.app.OPEN_EVACUATION"
internal const val GUIDE_SESSION_ID = "guideSessionId"
private const val CONFIRM_SHAKING = "jp.nigeyo.app.CONFIRM_SHAKING"
private const val CHANNEL = "lock-screen-guide"
private const val GUIDE_NOTIFICATION = 2

class GuideNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CONFIRM_SHAKING) return
        val id = intent.getStringExtra(GUIDE_SESSION_ID) ?: return
        (context.applicationContext as NigeyoApplication).training.confirmShakingStopped(id)
    }
}

internal class LockScreenGuide(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun clear() = manager.cancel(GUIDE_NOTIFICATION)

    fun render(session: GuideSession, phase: GuidePhase, strings: Language) {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, strings["android.guideChannel"], NotificationManager.IMPORTANCE_LOW,
        ).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        val titleKey = when (phase) {
            GuidePhase.Protect -> "guide.protect.short"
            GuidePhase.Checks -> "guide.checks.title"
            GuidePhase.Tsunami -> "guide.tsunami.short"
        }
        val body = when (phase) {
            GuidePhase.Protect -> strings["guide.protect.body"]
            GuidePhase.Checks -> listOf("surroundings", "fire", "exit")
                .joinToString(" · ") { strings["guide.$it.title"] }
            GuidePhase.Tsunami -> strings["guide.tsunami.body"]
        }
        val action = when (phase) {
            GuidePhase.Protect -> strings["guide.shakingQuestion"]
            GuidePhase.Checks -> strings["guide.unsafeButton"]
            GuidePhase.Tsunami -> strings["guide.tsunami.checkPlaces"]
        }
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_IMMUTABLE,
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        val actionIntent = if (phase == GuidePhase.Protect) {
            PendingIntent.getBroadcast(context, 3, Intent(context, GuideNotificationReceiver::class.java)
                .setAction(CONFIRM_SHAKING).putExtra(GUIDE_SESSION_ID, session.id), flags)
        } else {
            PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java)
                .setAction(OPEN_EVACUATION).putExtra(GUIDE_SESSION_ID, session.id)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        }

        val icons = pictograms(phase)
        val title = strings[titleKey].replace('\n', ' ')
        manager.notify(GUIDE_NOTIFICATION, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_drill_notification)
            .setLargeIcon(icons)
            .setStyle(Notification.BigPictureStyle().bigPicture(icons).setSummaryText(body))
            .setContentTitle(if (session.isTraining) "${strings["training.badge"]} · $title" else title)
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, action, actionIntent).build())
            .build())
    }

    private fun pictograms(phase: GuidePhase): Bitmap {
        val icons = when (phase) {
            GuidePhase.Protect -> listOf(R.drawable.picto_protect_head)
            GuidePhase.Checks -> listOf(
                R.drawable.picto_check_surroundings, R.drawable.picto_check_fire, R.drawable.picto_secure_exit,
            )
            GuidePhase.Tsunami -> listOf(R.drawable.picto_tsunami_evacuate)
        }
        val size = 144
        return createBitmap(size * icons.size, size).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(247, 246, 240))
            icons.forEachIndexed { index, id ->
                context.getDrawable(id)?.apply {
                    setBounds(index * size + 6, 6, (index + 1) * size - 6, size - 6)
                    draw(canvas)
                }
            }
        }
    }
}
