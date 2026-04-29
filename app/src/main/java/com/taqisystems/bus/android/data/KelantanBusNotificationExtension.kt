package com.taqisystems.bus.android.data

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.onesignal.notifications.INotificationReceivedEvent
import com.onesignal.notifications.INotificationServiceExtension
import com.taqisystems.bus.android.KelantanBusApplication
import com.taqisystems.bus.android.MainActivity
import com.taqisystems.bus.android.R
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.data.model.InboxNotification
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Called by OneSignal for every notification regardless of app state
 * (foreground, background, or process-killed).
 *
 * Registered in AndroidManifest via:
 *   <meta-data android:name="com.onesignal.NotificationServiceExtension"
 *              android:value="com.taqisystems.bus.android.data.KelantanBusNotificationExtension"/>
 */
class KelantanBusNotificationExtension : INotificationServiceExtension {

    override fun onNotificationReceived(event: INotificationReceivedEvent) {
        val n = event.notification
        // Read optional deep-link set via OneSignal "Additional Data" dashboard field.
        // Supports keys: "deepLink" or "url" (both are common conventions).
        val deepLink = runCatching {
            n.additionalData?.let { data ->
                data.optString("deepLink").takeIf { it.isNotBlank() }
                    ?: data.optString("url").takeIf { it.isNotBlank() }
            }
        }.getOrNull()

        val entry = InboxNotification(
            id         = n.notificationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            title      = n.title?.takeIf { it.isNotBlank() } ?: "Kelantan Bus",
            body       = n.body  ?: "",
            receivedAt = System.currentTimeMillis(),
            isRead     = false,
            deepLink   = deepLink,
        )

        runBlocking {
            // ServiceLocator.init() is always called in Application.onCreate before any
            // service or extension, so preferences is always available here.
            ServiceLocator.preferences.addNotification(entry)

            // If this is an arrival reminder firing, clear it from active reminders
            // so the bell icon resets on the stop details screen.
            runCatching {
                val data = n.additionalData
                if (data?.optString("type") == "arrival_reminder") {
                    val tripId = data.optString("trip_id").takeIf { it.isNotBlank() }
                    if (tripId != null) {
                        ServiceLocator.preferences.removeActiveReminder(tripId)
                    }
                }
            }
        }

        // Suppress OneSignal's default display so we can re-post on our custom channel
        // (kelantanbus_reminders), which has the alert.wav sound attached.
        event.preventDefault()

        val context = ServiceLocator.application
        val notifId = System.currentTimeMillis().toInt()

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            deepLink?.let { putExtra("deepLink", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, KelantanBusApplication.CHANNEL_ID_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_onesignal_default)
            .setContentTitle(entry.title)
            .setContentText(entry.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }
    }
}
