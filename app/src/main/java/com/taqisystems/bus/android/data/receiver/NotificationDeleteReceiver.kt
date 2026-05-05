package com.taqisystems.bus.android.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taqisystems.bus.android.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the delete intent fired by Android when the user swipes a
 * Kelantan Bus notification away from the system notification tray.
 *
 * Removes the corresponding [InboxNotification] from the in-app inbox so the
 * two lists stay in sync (system tray ↔ in-app Notifications screen).
 */
class NotificationDeleteReceiver : BroadcastReceiver() {

    companion object {
        /** String extra carrying the [InboxNotification.id] of the dismissed notification. */
        const val EXTRA_NOTIF_ID = "notif_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_NOTIF_ID) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                ServiceLocator.preferences.deleteNotification(id)
            }
        }
    }
}
