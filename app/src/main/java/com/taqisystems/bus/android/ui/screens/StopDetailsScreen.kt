package com.taqisystems.bus.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.taqisystems.bus.android.data.model.ArrivalStatus
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.ui.navigation.Routes
import com.taqisystems.bus.android.ui.theme.*
import com.taqisystems.bus.android.ui.viewmodel.StopDetailsViewModel
import com.taqisystems.bus.android.ui.viewmodel.StopDetailsViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailsScreen(
    navController: NavController,
    stopId: String,
    stopName: String,
    stopCode: String,
    viewModel: StopDetailsViewModel = viewModel(factory = StopDetailsViewModelFactory()),
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedStops by viewModel.savedStops.collectAsState()
    val activeReminders by viewModel.activeReminders.collectAsState()
    val isSaved = savedStops.any { it.id == stopId }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(uiState.reminderMessage) {
        val msg = uiState.reminderMessage
        if (!msg.isNullOrBlank()) {
            val canUndo = uiState.lastCancelledReminder != null
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = if (canUndo) "Undo" else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoCancelReminder()
            } else {
                viewModel.clearReminderMessage()
            }
        }
    }

    LaunchedEffect(stopId) { viewModel.load(stopId, stopName) }

    // Reminder bottom sheet — handles both "set" and "cancel" flows
    if (uiState.reminderSheetArrival != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val arrival = uiState.reminderSheetArrival!!
        val sheetHasReminder = activeReminders.any { it.tripId == arrival.tripId }
        val arrivalEpochMs = if (arrival.predicted) arrival.predictedArrivalTime else arrival.scheduledArrivalTime
        val timeFmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }

        ModalBottomSheet(
            onDismissRequest = { viewModel.closeReminderSheet() },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (sheetHasReminder) "Reminder set" else "Set a reminder",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${arrival.routeShortName} → ${arrival.tripHeadsign.ifBlank { arrival.routeLongName }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                if (sheetHasReminder) {
                    // ── Cancel flow ───────────────────────────────────────────
                    val existingReminder = activeReminders.find { it.tripId == arrival.tripId }
                    if (existingReminder != null) {
                        val notifyAtMs = arrivalEpochMs - existingReminder.minutesBefore * 60_000L
                        Text(
                            "You’ll be notified at ${timeFmt.format(java.util.Date(notifyAtMs))} " +
                            "(${existingReminder.minutesBefore} min before)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.cancelReminder(arrival) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    ) {
                        Icon(Icons.Default.NotificationsOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel reminder")
                    }
                } else {
                    // ── Set flow ─────────────────────────────────────────────
                    val minutesOptions = listOf(5, 10, 15)
                    val arrivalMinutes = arrival.minutesUntilArrival
                    val allDisabled = minutesOptions.all { arrivalMinutes <= it }

                    if (allDisabled) {
                        Text(
                            "The bus arrives too soon to set a reminder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "Notify me before arrival:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            minutesOptions.forEach { mins ->
                                val enabled = arrivalMinutes > mins
                                val notifyAtMs = arrivalEpochMs - mins * 60_000L
                                val notifyAtLabel = timeFmt.format(java.util.Date(notifyAtMs))
                                FilterChip(
                                    selected = false,
                                    enabled = enabled,
                                    onClick = { viewModel.setReminder(arrival, mins) },
                                    label = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("$mins min")
                                            Text(
                                                "at $notifyAtLabel",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (enabled)
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                else
                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (uiState.reminderLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stopName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (stopCode.isNotBlank()) {
                            Text(
                                "#$stopCode",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.toggleSaved(stopId, stopName, stopCode)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (isSaved) "Stop removed from saved" else "Stop saved",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }) {
                        Icon(
                            if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (isSaved) "Remove from saved" else "Save stop",
                            tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.loading && uiState.arrivals.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.error != null && uiState.arrivals.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(uiState.error ?: "Error loading arrivals", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) { Text("Retry") }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            if (uiState.loading) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (uiState.arrivals.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("No upcoming arrivals", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            items(uiState.arrivals) { arrival ->
                                val hasReminder = activeReminders.any { it.tripId == arrival.tripId }
                                DetailedArrivalRow(
                                    arrival = arrival,
                                    sidecarEnabled = uiState.sidecarEnabled,
                                    hasReminder = hasReminder,
                                    reminderLoading = uiState.reminderLoading,
                                    onBellClick = {
                                        viewModel.openReminderSheet(arrival)
                                    },
                                    onClick = {
                                        navController.navigate(
                                            Routes.routeDetails(
                                                tripId = arrival.tripId,
                                                routeId = arrival.routeId,
                                                routeShort = arrival.routeShortName,
                                                routeLong = arrival.routeLongName,
                                                headsign = arrival.tripHeadsign,
                                                stopId = stopId,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailedArrivalRow(
    arrival: ObaArrival,
    onClick: () -> Unit,
    sidecarEnabled: Boolean = false,
    hasReminder: Boolean = false,
    reminderLoading: Boolean = false,
    onBellClick: () -> Unit = {},
) {
    val statusColor = when (arrival.status) {
        ArrivalStatus.ON_TIME   -> StatusOnTime
        ArrivalStatus.DELAYED   -> StatusDelayed
        ArrivalStatus.EARLY     -> StatusEarly
        ArrivalStatus.SCHEDULED -> MaterialTheme.colorScheme.onSurfaceVariant
        ArrivalStatus.UNKNOWN   -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when (arrival.status) {
        ArrivalStatus.ON_TIME   -> "On Time"
        ArrivalStatus.DELAYED   -> "Delayed"
        ArrivalStatus.EARLY     -> "Early"
        ArrivalStatus.SCHEDULED -> null
        ArrivalStatus.UNKNOWN   -> null
    }
    val minutes = arrival.minutesUntilArrival
    val headwayMins = arrival.headwaySecs?.let { it / 60 }
    val headwayUntil = arrival.headwayEndTime?.let {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        " until " + fmt.format(java.util.Date(it))
    } ?: ""

    // For headway+no-realtime, use muted colour just like SCHEDULED
    val timeColor = when {
        arrival.isHeadway && !arrival.predicted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> statusColor
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .defaultMinSize(minHeight = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── CENTER: chip + destination + status ───────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Row 1: transit chip + destination
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.DirectionsBus,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                arrival.routeShortName.take(6),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Text(
                        arrival.tripHeadsign.ifBlank { arrival.routeLongName },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Row 2: via / route long name
                val via = if (arrival.tripHeadsign.isNotBlank() && arrival.routeLongName.isNotBlank())
                    arrival.routeLongName else null
                if (via != null) {
                    Text(
                        via,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                // Row 3: status / headway info
                if (arrival.isHeadway && !arrival.predicted) {
                    // Frequency service with no real-time data
                    val intervalText = if (headwayMins != null) "Every ~$headwayMins min$headwayUntil" else "Frequency service$headwayUntil"
                    Text(
                        intervalText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (arrival.isHeadway && arrival.predicted) {
                    // Headway with real-time — show status + interval + until
                    val statusNote = when (arrival.status) {
                        ArrivalStatus.ON_TIME -> "On Time"
                        ArrivalStatus.DELAYED -> "Delayed (+${arrival.deviationMinutes} min)"
                        ArrivalStatus.EARLY   -> "Early"
                        else -> null
                    }
                    val freqNote = if (headwayMins != null) " · Every ~$headwayMins min$headwayUntil" else headwayUntil
                    if (statusNote != null) {
                        Text(
                            statusNote + freqNote,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                        )
                    }
                } else if (statusLabel != null) {
                    val delayNote = if (arrival.status == ArrivalStatus.DELAYED && arrival.deviationMinutes > 0)
                        " (+${arrival.deviationMinutes} min)" else ""
                    Text(
                        statusLabel + delayNote,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                }
            }

            // ── Bell icon (reminder) ──────────────────────────────────────────
            if (sidecarEnabled) {
                IconButton(
                    onClick = onBellClick,
                    enabled = !reminderLoading,
                    modifier = Modifier.size(40.dp),
                ) {
                    if (hasReminder) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = "Cancel reminder",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.NotificationsNone,
                            contentDescription = "Set reminder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // ── RIGHT: arrival time ───────────────────────────────────────────
            Column(
                modifier = Modifier.width(56.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    arrival.isHeadway && !arrival.predicted && headwayMins != null -> {
                        // Headway, no real-time — show interval
                        Text(
                            "~$headwayMins",
                            style = MaterialTheme.typography.headlineMedium,
                            color = timeColor,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                        Text(
                            "MIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                    minutes <= 0 -> {
                        Text(
                            "Now",
                            style = MaterialTheme.typography.headlineMedium,
                            color = StatusOnTime,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    else -> {
                        val prefix = if (arrival.isHeadway && arrival.predicted) "~" else ""
                        Text(
                            prefix + if (minutes < 60) "$minutes" else "${minutes / 60}h\n${minutes % 60}m",
                            style = MaterialTheme.typography.headlineMedium,
                            color = timeColor,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                        if (minutes < 60) {
                            Text(
                                "MIN",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.sp,
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                    }
                }
            }
        }
    }
}