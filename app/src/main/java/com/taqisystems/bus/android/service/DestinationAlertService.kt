package com.taqisystems.bus.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.taqisystems.bus.android.KelantanBusApplication
import com.taqisystems.bus.android.R
import java.util.Locale

/**
 * Foreground Service that monitors the user's GPS position and fires TTS voice alerts
 * + vibration when approaching a destination stop on a transit trip.
 *
 * Alert stages:
 *   1. GET_READY  — fired when within [GET_READY_RADIUS_M] of the second-to-last stop:
 *                   TTS "Get ready. Your stop is coming up soon." + short vibration
 *   2. PULL_CORD  — fired when within [PULL_CORD_RADIUS_M] of the destination stop:
 *                   TTS "Your stop is here. Please exit the vehicle now."
 *                   + long vibration + high-priority sound notification
 *
 * Start via:
 *   Intent(context, DestinationAlertService::class.java).apply {
 *       action = DestinationAlertService.ACTION_START
 *       putExtra(EXTRA_DEST_NAME,   "Kota Bharu Sentral")
 *       putExtra(EXTRA_DEST_LAT,    6.1234)
 *       putExtra(EXTRA_DEST_LON,    102.2345)
 *       putExtra(EXTRA_BEFORE_LAT,  6.1200)
 *       putExtra(EXTRA_BEFORE_LON,  102.2310)
 *   }
 *
 * Stop via:
 *   Intent(context, DestinationAlertService::class.java).apply {
 *       action = DestinationAlertService.ACTION_STOP
 *   }
 */
class DestinationAlertService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.taqisystems.bus.DEST_ALERT_START"
        const val ACTION_STOP  = "com.taqisystems.bus.DEST_ALERT_STOP"

        const val EXTRA_DEST_NAME   = "dest_name"
        const val EXTRA_DEST_LAT    = "dest_lat"
        const val EXTRA_DEST_LON    = "dest_lon"
        const val EXTRA_BEFORE_LAT  = "before_lat"
        const val EXTRA_BEFORE_LON  = "before_lon"

        /** Notification ID for the persistent foreground notification. */
        private const val NOTIFICATION_ID_FG = 44100
        /** Notification ID for the final "Get off the bus!" alert notification. */
        private const val NOTIFICATION_ID_ALERT = 44101

        /** Distance (metres) to the second-to-last stop that triggers "Get Ready". */
        private const val GET_READY_RADIUS_M = 300f
        /** Distance (metres) to the destination stop that triggers "Pull Cord". */
        private const val PULL_CORD_RADIUS_M  = 100f

        /** Vibration for "Get Ready": one short pulse. */
        private val VIBRATION_GET_READY = longArrayOf(0, 600)
        /** Vibration for "Pull Cord": three strong pulses. */
        private val VIBRATION_PULL_CORD = longArrayOf(0, 1000, 400, 1000, 400, 1000)
    }

    // ── Location ─────────────────────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // ── TTS ──────────────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // ── Trip parameters ───────────────────────────────────────────────────────
    private var destName  = "Destination"
    private var destLat   = 0.0
    private var destLon   = 0.0
    private var beforeLat = 0.0
    private var beforeLon = 0.0

    // ── State ─────────────────────────────────────────────────────────────────
    private var getReadyFired = false
    private var pullCordFired = false

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        destName  = intent?.getStringExtra(EXTRA_DEST_NAME)       ?: "Destination"
        destLat   = intent?.getDoubleExtra(EXTRA_DEST_LAT,   0.0) ?: 0.0
        destLon   = intent?.getDoubleExtra(EXTRA_DEST_LON,   0.0) ?: 0.0
        beforeLat = intent?.getDoubleExtra(EXTRA_BEFORE_LAT, 0.0) ?: 0.0
        beforeLon = intent?.getDoubleExtra(EXTRA_BEFORE_LON, 0.0) ?: 0.0

        val notification = buildForegroundNotification("Monitoring route to $destName…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID_FG, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID_FG, notification)
        }

        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        tts?.stop()
        tts?.shutdown()
        tts = null
        // Explicitly remove both notifications so they don't linger in the
        // status bar after the service is stopped (by the user or automatically).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_ID_ALERT)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // TTS initialisation
    // ─────────────────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(0.85f)
            ttsReady = true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Location
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocationUpdate(it) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Proximity logic
    // ─────────────────────────────────────────────────────────────────────────

    private fun onLocationUpdate(location: Location) {
        val distToBefore = distanceBetween(location.latitude, location.longitude, beforeLat, beforeLon)
        val distToDest   = distanceBetween(location.latitude, location.longitude, destLat,   destLon)

        // Update the persistent notification with live distance
        val nm = getSystemService(NotificationManager::class.java)
        val distLabel = if (distToDest >= 1000f) "%.1f km".format(distToDest / 1000f)
                        else "${distToDest.toInt()} m"
        nm.notify(NOTIFICATION_ID_FG, buildForegroundNotification("$distLabel to $destName"))

        // ── Stage 1: Get Ready ────────────────────────────────────────────────
        if (!getReadyFired && distToBefore < GET_READY_RADIUS_M) {
            getReadyFired = true
            speak("Get ready. Your stop is coming up soon.")
            vibrate(VIBRATION_GET_READY)
        }

        // ── Stage 2: Pull Cord ────────────────────────────────────────────────
        if (!pullCordFired && distToDest < PULL_CORD_RADIUS_M) {
            pullCordFired = true
            speak("Your stop is here. Please exit the vehicle now.")
            vibrate(VIBRATION_PULL_CORD)
            postArrivalAlertNotification()
            // Give TTS time to finish, then auto-stop the service
            Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 9_000L)
        }
    }

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TTS
    // ─────────────────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "dest_alert_${System.currentTimeMillis()}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vibration
    // ─────────────────────────────────────────────────────────────────────────

    private fun vibrate(pattern: LongArray) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notifications
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persistent low-priority foreground notification shown while monitoring is active.
     * It updates in place with the live distance to the destination.
     */
    private fun buildForegroundNotification(contentText: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, DestinationAlertService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, KelantanBusApplication.CHANNEL_ID_DESTINATION)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle("Destination Alert Active")
            .setContentText(contentText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    /**
     * High-priority alert notification posted when the user reaches their stop.
     * Uses the REMINDERS channel (alert.wav sound) so the user is clearly alerted
     * even if the phone is in their pocket.
     */
    private fun postArrivalAlertNotification() {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, DestinationAlertService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, KelantanBusApplication.CHANNEL_ID_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle("Get off the bus!")
            .setContentText("Your stop \"$destName\" is here.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(0, "Stop Alerts", stopPi)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_ALERT, notif)
    }
}
