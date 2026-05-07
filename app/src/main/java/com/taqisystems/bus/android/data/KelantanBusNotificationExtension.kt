// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.data

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.taqisystems.bus.android.data.receiver.NotificationDeleteReceiver
import com.onesignal.notifications.INotificationReceivedEvent
import com.onesignal.notifications.INotificationServiceExtension
import com.taqisystems.bus.android.BuildConfig
import com.taqisystems.bus.android.KelantanBusApplication
import com.taqisystems.bus.android.MainActivity
import com.taqisystems.bus.android.R
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.data.model.InboxNotification
import kotlinx.coroutines.flow.first
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

        val notifId = System.currentTimeMillis().toInt()

        // For arrival_reminder pushes, compose the title/body locally from the
        // stored ActiveReminder so the text respects the device locale once
        // translations are added to strings.xml.
        val localData = n.additionalData
        val isArrivalReminder = localData?.optString("type") == "arrival_reminder"
        val tripIdFromPush = if (isArrivalReminder)
            localData?.optString("trip_id")?.takeIf { it.isNotBlank() } else null

        var localTitle: String? = null
        var localBody: String? = null
        if (tripIdFromPush != null) {
            runCatching {
                val reminders = runBlocking {
                    ServiceLocator.preferences.activeReminders.first()
                }
                val reminder = reminders.find { it.tripId == tripIdFromPush }
                if (reminder != null) {
                    val ctx = ServiceLocator.application
                    localTitle = ctx.getString(R.string.notif_reminder_title)
                    localBody = if (reminder.stopName.isNotBlank())
                        ctx.getString(R.string.notif_reminder_body, reminder.minutesBefore, reminder.stopName)
                    else
                        ctx.getString(R.string.notif_reminder_body_no_stop, reminder.minutesBefore)
                }
            }
        }

        val entry = InboxNotification(
            id            = n.notificationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            title         = localTitle ?: n.title?.takeIf { it.isNotBlank() } ?: BuildConfig.APP_NAME,
            body          = localBody  ?: n.body  ?: "",
            receivedAt    = System.currentTimeMillis(),
            isRead        = false,
            deepLink      = deepLink,
            systemNotifId = notifId,
        )

        runBlocking {
            // ServiceLocator.init() is always called in Application.onCreate before any
            // service or extension, so preferences is always available here.
            ServiceLocator.preferences.addNotification(entry)

            // Clear the active reminder so the bell icon resets on the arrivals sheet.
            if (tripIdFromPush != null) {
                runCatching { ServiceLocator.preferences.removeActiveReminder(tripIdFromPush) }
            }
        }

        // Suppress OneSignal's default display so we can re-post on our custom channel
        // (kelantanbus_reminders), which has the alert.wav sound attached.
        event.preventDefault()

        val context = ServiceLocator.application

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            deepLink?.let { putExtra("deepLink", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

// Delete intent: when user swipes the notification away from the tray,
        // remove it from the in-app inbox too.
        val deletePi = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            Intent(context, NotificationDeleteReceiver::class.java).apply {
                putExtra(NotificationDeleteReceiver.EXTRA_NOTIF_ID, entry.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, KelantanBusApplication.channelIdReminders(context.packageName))
                .setSmallIcon(R.drawable.ic_stat_onesignal_default)
            .setContentTitle(entry.title)
            .setContentText(entry.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.body))
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }
    }
}
