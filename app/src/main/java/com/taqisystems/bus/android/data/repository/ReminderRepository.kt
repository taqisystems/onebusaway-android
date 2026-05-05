package com.taqisystems.bus.android.data.repository

import com.onesignal.OneSignal
import com.taqisystems.bus.android.data.model.ActiveReminder
import com.taqisystems.bus.android.data.model.ObaArrival
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Handles HTTP communication with the OBA-compatible arrival-reminder sidecar.
 *
 * API contract (mimics the OBA sidecar used by Sound Transit):
 *   POST  {sidecarBaseUrl}/{regionId}/alarms  — register an alarm
 *   DELETE {sidecarBaseUrl}{deleteUrl}        — cancel an alarm
 *
 * The sidecar returns {"url": "/regionId/alarms/uuid"} on success.
 * That deleteUrl is stored in [ActiveReminder] so the client can cancel later.
 */
class ReminderRepository(http: OkHttpClient = OkHttpClient()) {

    private val http = http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Register an arrival reminder with the sidecar.
     *
     * @param sidecarBaseUrl  Base URL of the sidecar (e.g. https://sidecar.kelantanbus.com)
     * @param regionId        OBA region ID (integer, as string)
     * @param stopId          Stop ID (e.g. "1_1001")
     * @param arrival         The arrival to remind about
     * @param secondsBefore   How many seconds before arrival to push the notification
     * @return                [Result] wrapping the deleteUrl string on success
     */
    suspend fun registerReminder(
        sidecarBaseUrl: String,
        regionId: String,
        stopId: String,
        stopName: String = "",
        arrival: ObaArrival,
        secondsBefore: Int,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val pushId = OneSignal.User.pushSubscription.id
                ?: throw IllegalStateException("OneSignal subscription ID not available. Ensure notifications are enabled.")

            val url = "${sidecarBaseUrl.trimEnd('/')}/$regionId/alarms"
            val body = FormBody.Builder()
                .add("stop_id", stopId)
                .add("stop_name", stopName)
                .add("trip_id", arrival.tripId)
                .add("service_date", arrival.serviceDate.toString())
                .add("stop_sequence", arrival.stopSequence.toString())
                .add("vehicle_id", arrival.vehicleId ?: "")
                .add("user_push_id", pushId)
                .add("seconds_before", secondsBefore.toString())
                .add("operating_system", "android")
                .build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = http.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw IllegalStateException("Empty response from sidecar")

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Sidecar returned HTTP ${response.code}: $responseBody"
                )
            }

            val deleteUrl = JSONObject(responseBody).optString("url")
                ?: throw IllegalStateException("Sidecar response missing 'url' field")

            deleteUrl
        }
    }

    /**
     * Cancel a previously registered reminder.
     *
     * @param reminder  The [ActiveReminder] to cancel — its [ActiveReminder.sidecarBaseUrl]
     *                  and [ActiveReminder.deleteUrl] are used to build the request.
     * @return          [Result.success] regardless of HTTP 404 (alarm already fired/gone)
     *                  so the caller can always clean up local state.
     */
    suspend fun cancelReminder(reminder: ActiveReminder): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${reminder.sidecarBaseUrl.trimEnd('/')}${reminder.deleteUrl}"
                val request = Request.Builder()
                    .url(url)
                    .delete()
                    .build()

                val response = http.newCall(request).execute()
                // 404 = alarm already fired or never existed — still count as success
                if (!response.isSuccessful && response.code != 404) {
                    throw IllegalStateException(
                        "Sidecar DELETE returned HTTP ${response.code}"
                    )
                }
                Unit
            }
        }
}
