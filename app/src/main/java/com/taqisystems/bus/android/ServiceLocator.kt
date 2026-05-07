// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android

import android.app.Application
import android.content.Context
import android.os.Build
import com.taqisystems.bus.android.data.AppPreferences
import com.taqisystems.bus.android.data.repository.GeocodingRepository
import com.taqisystems.bus.android.data.repository.ObaRepository
import com.taqisystems.bus.android.data.repository.OtpRepository
import com.taqisystems.bus.android.data.repository.RegionsRepository
import com.taqisystems.bus.android.data.repository.ReminderRepository
import okhttp3.OkHttpClient

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

    /**
     * Shared OkHttpClient with a User-Agent that identifies the device.
     * Format: KelantanBus/<versionName> (build <versionCode>; Android <sdk>; <manufacturer> <model>)
     * Example: KelantanBus/1.4.2 (build 42; Android 33; samsung SM-A715F)
     */
    lateinit var httpClient: OkHttpClient
        private set

    fun init(context: Context) {
        application = context.applicationContext as Application
        preferences = AppPreferences(context.applicationContext)

        val pm = context.packageManager
        val pkgInfo = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = pkgInfo?.versionName ?: "unknown"
        val versionCode = pkgInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
            else @Suppress("DEPRECATION") it.versionCode.toLong()
        } ?: 0L
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val sdk = Build.VERSION.SDK_INT
        val userAgent = "KelantanBus/$versionName (build $versionCode; Android $sdk; $manufacturer $model)"

        httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                )
            }
            .build()

        regionsRepository = RegionsRepository(httpClient)
        obaRepository = ObaRepository()
        otpRepository = OtpRepository(httpClient)
        geocodingRepository = GeocodingRepository(httpClient)
        reminderRepository = ReminderRepository(httpClient)
    }

    /** Update the active region URLs without recreating repositories. */
    fun applyRegionUrls(obaBaseUrl: String) {
        obaRepository.updateBaseUrl(obaBaseUrl)
    }
}
