package com.taqisystems.bus.android.data

import com.onesignal.notifications.INotificationReceivedEvent
import com.onesignal.notifications.INotificationServiceExtension
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
        // Do NOT call event.preventDefault() — we want the notification to still appear in the tray.
    }
}
