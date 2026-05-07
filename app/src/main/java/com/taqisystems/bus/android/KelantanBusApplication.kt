// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class KelantanBusApplication : Application() {

    companion object {
        /**
         * Channel IDs are scoped to the app package so that different white-label
         * builds never share a channel — important because Android locks a channel's
         * sound the first time it is created. Sharing a channel ID across builds
         * would prevent a white-labeler from using their own custom sound.
         *
         * Replace the channel IDs by calling [channelId] with the Application context.
         */
        fun channelIdReminders(pkg: String)   = "${pkg}_reminders"
        fun channelIdDestination(pkg: String) = "${pkg}_destination"

        /** @deprecated use [channelIdReminders] with context instead */
        const val CHANNEL_ID_REMINDERS    = "kelantanbus_reminders"
        /** @deprecated use [channelIdDestination] with context instead */
        const val CHANNEL_ID_DESTINATION  = "kelantanbus_destination"
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

        registerNotificationChannels()

        // OneSignal push notifications
        OneSignal.Debug.logLevel = LogLevel.NONE
        OneSignal.initWithContext(this, BuildConfig.ONESIGNAL_APP_ID)
        CoroutineScope(Dispatchers.IO).launch {
            OneSignal.Notifications.requestPermission(false)
        }
        // Restore saved region URL from preferences so repositories use the correct server on launch
        CoroutineScope(Dispatchers.IO).launch {
            val savedObaUrl = ServiceLocator.preferences.obaBaseUrl.firstOrNull()
            if (!savedObaUrl.isNullOrBlank()) {
                ServiceLocator.applyRegionUrls(savedObaUrl)
            }
        }
    }

    /**
     * Registers the reminder notification channel with the custom alert.wav sound.
     * Channels are only created on Android O+ (API 26+); on older versions this is a no-op
     * because the OS handles custom sounds via the notification itself.
     *
     * ⚠️ Once a channel is created its sound can only be changed by the user or by deleting
     * and recreating the channel. Uninstall/reinstall the app if you need to update the sound
     * during development.
     *
     * ── White-label sound override ──────────────────────────────────────────────────────────
     * To use a custom notification sound, place your audio file (WAV/OGG/MP3) at:
     *   app/src/<flavourName>/res/raw/alert.wav
     * The flavour resource shadows the default. Supported formats: WAV, OGG (recommended),
     * MP3. Keep the file under ~1 MB and under 30 seconds for best compatibility.
     * ────────────────────────────────────────────────────────────────────────────────────────
     */
    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val remindersId   = channelIdReminders(packageName)
        val destinationId = channelIdDestination(packageName)

        val soundUri = Uri.parse(
            "android.resource://${packageName}/${R.raw.alert}"
        )
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            remindersId,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Bus departure reminders"
            setSound(soundUri, audioAttrs)
            enableVibration(true)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        // ── Destination alert foreground service channel (silent, low priority) ──
        val destChannel = NotificationChannel(
            destinationId,
            "Destination Alerts",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Live destination monitoring while on the bus"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(destChannel)
    }
}
