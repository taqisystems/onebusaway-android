package com.taqisystems.bus.android

import android.app.Application
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class KelantanBusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

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
}
