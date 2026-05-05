package com.taqisystems.bus.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.taqisystems.bus.android.PendingStopFocus
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.data.model.ActiveReminder
import androidx.compose.ui.graphics.Color
import com.taqisystems.bus.android.ui.theme.Blue600
import com.taqisystems.bus.android.ui.theme.Primary
import com.taqisystems.bus.android.ui.navigation.Routes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(navController: NavController) {
    val prefs = ServiceLocator.preferences
    val reminderRepo = ServiceLocator.reminderRepository
    val scope = rememberCoroutineScope()
    val reminders by prefs.activeReminders.collectAsState(initial = emptyList())
    val timeFmt = remember { SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Reminders", fontWeight = FontWeight.Bold)
                        if (reminders.isNotEmpty()) {
                            Text(
                                "${reminders.size} active",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (reminders.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                reminders.forEach { reminder ->
                                    prefs.removeActiveReminder(reminder.tripId)
                                    runCatching { reminderRepo.cancelReminder(reminder) }
                                }
                            }
                        }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Cancel all reminders",
                                tint = Color.White,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor          = MaterialTheme.colorScheme.primary,
                    titleContentColor       = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { innerPadding ->
        if (reminders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AlarmOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No active reminders",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Long-press an arrival row to set a reminder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(reminders.distinctBy { it.tripId }, key = { it.tripId }) { reminder ->
                    ReminderSwipeDismiss(
                        onDismiss = {
                            scope.launch {
                                prefs.removeActiveReminder(reminder.tripId)
                                runCatching { reminderRepo.cancelReminder(reminder) }
                            }
                        },
                    ) {
                        ReminderRow(
                            reminder = reminder,
                            timeFmt  = timeFmt,
                            onClick  = {
                                PendingStopFocus.set(
                                    id   = reminder.stopId,
                                    name = reminder.stopName,
                                    lat  = reminder.stopLat,
                                    lon  = reminder.stopLon,
                                )
                                navController.popBackStack(Routes.HOME, inclusive = false)
                            },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

// ─── Swipe-to-cancel ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSwipeDismiss(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDismiss(); true } else false
        },
        positionalThreshold = { it * 0.40f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val bg by animateColorAsState(
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surface,
                label = "swipe_bg",
            )
            Box(
                modifier = Modifier.fillMaxSize().background(bg).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AlarmOff,
                        contentDescription = "Cancel reminder",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

// ─── Reminder row ─────────────────────────────────────────────────────────────

@Composable
private fun ReminderRow(reminder: ActiveReminder, timeFmt: SimpleDateFormat, onClick: () -> Unit = {}) {
    val notifyAtMs = if (reminder.arrivalEpochMs > 0L)
        reminder.arrivalEpochMs - reminder.minutesBefore * 60_000L
    else 0L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon tile
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Alarm,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Route + headsign — guard against null from old DataStore entries
            val routeShort = reminder.routeShortName.orEmpty()
            val headsign   = reminder.headsign.orEmpty()
            val stopName   = reminder.stopName.orEmpty()
            val routeLabel = when {
                routeShort.isNotBlank() && headsign.isNotBlank() ->
                    "Route $routeShort → $headsign"
                routeShort.isNotBlank() -> "Route $routeShort"
                headsign.isNotBlank()   -> headsign
                else -> "Bus trip"
            }
            Text(
                routeLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Stop
            if (stopName.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        stopName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Notify time chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Primary.copy(alpha = 0.10f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            "${reminder.minutesBefore} min before",
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                        )
                    }
                }
                if (notifyAtMs > 0L) {
                    Text(
                        "at ${timeFmt.format(Date(notifyAtMs))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
