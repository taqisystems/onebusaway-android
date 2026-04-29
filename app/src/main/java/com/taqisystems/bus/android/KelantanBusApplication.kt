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
        /** Channel ID used for reminder notifications. Set this same value in the
         *  OneSignal dashboard under App Settings → Messaging → Android Channel. */
        const val CHANNEL_ID_REMINDERS = "kelantanbus_reminders"
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
     */
    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val soundUri = Uri.parse(
            "android.resource://${packageName}/${R.raw.alert}"
        )
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID_REMINDERS,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Bus departure reminders"
            setSound(soundUri, audioAttrs)
            enableVibration(true)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
