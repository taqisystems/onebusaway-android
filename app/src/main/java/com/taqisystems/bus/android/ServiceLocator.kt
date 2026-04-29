package com.taqisystems.bus.android

import android.app.Application
import android.content.Context
import com.taqisystems.bus.android.data.AppPreferences
import com.taqisystems.bus.android.data.repository.GeocodingRepository
import com.taqisystems.bus.android.data.repository.ObaRepository
import com.taqisystems.bus.android.data.repository.OtpRepository
import com.taqisystems.bus.android.data.repository.RegionsRepository
import com.taqisystems.bus.android.data.repository.ReminderRepository

/** Simple singleton service locator (no Hilt to keep the setup minimal). */
object ServiceLocator {
    lateinit var application: Application
    lateinit var obaRepository: ObaRepository
    lateinit var otpRepository: OtpRepository
    lateinit var geocodingRepository: GeocodingRepository
    lateinit var regionsRepository: RegionsRepository
    lateinit var reminderRepository: ReminderRepository
    lateinit var preferences: AppPreferences
    val appPreferences get() = preferences

    fun init(context: Context) {
        application = context.applicationContext as Application
        preferences = AppPreferences(context.applicationContext)
        regionsRepository = RegionsRepository()

        obaRepository = ObaRepository()
        otpRepository = OtpRepository()
        geocodingRepository = GeocodingRepository()
        reminderRepository = ReminderRepository()
    }

    /** Update the active region URLs without recreating repositories. */
    fun applyRegionUrls(obaBaseUrl: String) {
        obaRepository.updateBaseUrl(obaBaseUrl)
    }
}
