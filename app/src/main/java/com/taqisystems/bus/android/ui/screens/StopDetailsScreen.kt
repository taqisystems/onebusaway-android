// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.screens

import androidx.compose.ui.res.stringResource
import com.taqisystems.bus.android.R

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.taqisystems.bus.android.data.model.SavedRoute
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
    val savedStops  by viewModel.savedStops.collectAsState()
    val savedRoutes by viewModel.savedRoutes.collectAsState()
    val activeReminders by viewModel.activeReminders.collectAsState()
    val isSaved = savedStops.any { it.id == stopId }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoText = stringResource(R.string.action_undo)
    val stopSavedText = stringResource(R.string.stop_saved_snack)
    val stopRemovedText = stringResource(R.string.stop_removed_snack)
    LaunchedEffect(uiState.reminderMessage) {
        val msg = uiState.reminderMessage
        if (!msg.isNullOrBlank()) {
            val canUndo = uiState.lastCancelledReminder != null
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = if (canUndo) undoText else null,
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
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── Header ──────────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (sheetHasReminder) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (sheetHasReminder) Icons.Default.NotificationsActive
                            else Icons.Default.NotificationsNone,
                            contentDescription = null,
                            tint = if (sheetHasReminder) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (sheetHasReminder) stringResource(R.string.reminder_set_snack) else stringResource(R.string.reminder_set_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${arrival.routeShortName} → ${arrival.tripHeadsign.ifBlank { arrival.routeLongName }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                if (sheetHasReminder) {
                    // ── Active reminder info card ────────────────────────────
                    val existingReminder = activeReminders.find { it.tripId == arrival.tripId }
                    if (existingReminder != null) {
                        val notifyAtMs = arrivalEpochMs - existingReminder.minutesBefore * 60_000L
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        timeFmt.format(java.util.Date(notifyAtMs)),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        "${existingReminder.minutesBefore} minutes before arrival",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                    )
                                }
                            }
                        }
                    }
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
                        Text(stringResource(R.string.reminder_cancel))
                    }
                } else {
                    // ── Set flow ────────────────────────────────────────────
                    val minutesOptions = listOf(5, 10, 15)
                    val arrivalMinutes = arrival.liveMinutesUntilArrival()
                    val allDisabled = minutesOptions.all { arrivalMinutes <= it }

                    if (allDisabled) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    stringResource(R.string.reminder_too_soon),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.reminder_notify_before),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            minutesOptions.forEach { mins ->
                                val enabled = arrivalMinutes > mins
                                val notifyAtMs = arrivalEpochMs - mins * 60_000L
                                val notifyAtLabel = timeFmt.format(java.util.Date(notifyAtMs))
                                val bgColor = if (enabled)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                val contentColor = if (enabled)
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(enabled = enabled) { viewModel.setReminder(arrival, mins) },
                                    shape = RoundedCornerShape(14.dp),
                                    color = bgColor,
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp, horizontal = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Notifications,
                                            contentDescription = null,
                                            tint = contentColor,
                                            modifier = Modifier.size(22.dp),
                                        )
                                        Text(
                                            "$mins min",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = contentColor,
                                        )
                                        Text(
                                            "at $notifyAtLabel",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = contentColor.copy(alpha = if (enabled) 0.75f else 1f),
                                        )
                                    }
                                }
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
            MediumTopAppBar(
                title = {
                    Column {
                        Text(
                            stopName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.toggleSaved(stopId, stopName, stopCode)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (isSaved) stopRemovedText else stopSavedText,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }) {
                        Icon(
                            if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (isSaved) stringResource(R.string.stop_remove_saved) else stringResource(R.string.stop_save),
                            tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.mediumTopAppBarColors(
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
                        Text(uiState.error ?: stringResource(R.string.stop_error_loading), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.action_retry)) }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            if (uiState.loading) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        // ── Routes row (always visible) ───────────────────
                        if (uiState.knownRoutes.isNotEmpty()) {
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    items(uiState.knownRoutes, key = { "chip_" + it.routeId }) { route ->
                                        AssistChip(
                                            onClick = {
                                                navController.navigate(
                                                    Routes.routeDetails(
                                                        tripId     = route.tripId,
                                                        routeId    = route.routeId,
                                                        routeShort = route.shortName,
                                                        routeLong  = route.longName,
                                                        headsign   = route.headsign,
                                                        stopId     = stopId,
                                                    )
                                                )
                                            },
                                            label = { Text(route.shortName) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.DirectionsBus,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        if (uiState.arrivals.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(stringResource(R.string.stop_no_upcoming), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            // Show routes known to serve this stop (from cache) so the user can still bookmark them
                            if (uiState.knownRoutes.isNotEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.stop_routes_serving),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                }
                                items(uiState.knownRoutes, key = { "known_" + it.routeId }) { route ->
                                    KnownRouteRow(
                                        route       = route,
                                        onClick     = {
                                            navController.navigate(
                                                Routes.routeDetails(
                                                    tripId     = route.tripId,
                                                    routeId    = route.routeId,
                                                    routeShort = route.shortName,
                                                    routeLong  = route.longName,
                                                    headsign   = route.headsign,
                                                    stopId     = stopId,
                                                )
                                            )
                                        },
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                                }
                            }
                        } else {
                            items(uiState.arrivals, key = { "${it.tripId}_${it.scheduledArrivalTime}" }) { arrival ->
                                val hasReminder = activeReminders.any { it.tripId == arrival.tripId }
                                DetailedArrivalRow(
                                    arrival = arrival,
                                    sidecarEnabled = uiState.sidecarEnabled,
                                    hasReminder = hasReminder,
                                    reminderLoading = uiState.reminderLoading,
                                    onBellClick = { viewModel.openReminderSheet(arrival) },
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

// ─── Known-route row (shown when arrivals are empty) ────────────────────────

@Composable
private fun KnownRouteRow(
    route: SavedRoute,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Route-number tile
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (route.shortName.isNotBlank()) {
                Text(
                    route.shortName,
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines   = 1,
                )
            } else {
                Icon(
                    Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                route.headsign.ifBlank { route.longName }.ifBlank { route.shortName },
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (route.longName.isNotBlank() && route.longName != route.headsign) {
                Text(
                    route.longName,
                    style   = MaterialTheme.typography.bodySmall,
                    color   = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─── Detailed arrival row ────────────────────────────────────────────────────

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
        ArrivalStatus.ON_TIME   -> stringResource(R.string.status_on_time)
        ArrivalStatus.DELAYED   -> stringResource(R.string.status_delayed)
        ArrivalStatus.EARLY     -> stringResource(R.string.status_early)
        ArrivalStatus.SCHEDULED -> null
        ArrivalStatus.UNKNOWN   -> null
    }
    val minutes = arrival.liveMinutesUntilArrival()
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
                    val intervalText = if (headwayMins != null)
                        stringResource(R.string.arrival_every_headway, headwayMins) + headwayUntil
                    else
                        stringResource(R.string.arrival_frequency_service) + headwayUntil
                    Text(
                        intervalText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (arrival.isHeadway && arrival.predicted) {
                    // Headway with real-time — show status + interval + until
                    val statusNote = when (arrival.status) {
                        ArrivalStatus.ON_TIME -> stringResource(R.string.status_on_time)
                        ArrivalStatus.DELAYED -> stringResource(R.string.status_delayed)
                        ArrivalStatus.EARLY   -> stringResource(R.string.status_early)
                        else -> null
                    }
                    val freqNote = if (headwayMins != null)
                        " · " + stringResource(R.string.arrival_every_headway, headwayMins) + headwayUntil
                    else headwayUntil
                    if (statusNote != null) {
                        Text(
                            statusNote + freqNote,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                        )
                    }
                } else if (statusLabel != null) {
                    // non-headway: only show label for missed buses; deviation is shown on the sched line
                }
                // Scheduled time + real-time deviation
                if (!arrival.isHeadway && arrival.scheduledArrivalTime > 0) {
                    val schedFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    val schedStr = schedFmt.format(java.util.Date(arrival.scheduledArrivalTime))
                    val notRealTimeStr = stringResource(R.string.arrival_not_realtime)
                    val minLateStr = stringResource(R.string.arrival_min_late, arrival.deviationMinutes)
                    val minEarlyStr = stringResource(R.string.arrival_min_early, kotlin.math.abs(arrival.deviationMinutes))
                    val onTimeStr = stringResource(R.string.arrival_on_time_note)
                    val deviationText = when {
                        !arrival.predicted ->
                            " · $notRealTimeStr"
                        arrival.predicted && arrival.status == ArrivalStatus.DELAYED && arrival.deviationMinutes > 0 ->
                            " · $minLateStr"
                        arrival.predicted && arrival.status == ArrivalStatus.EARLY && arrival.deviationMinutes != 0 ->
                            " · $minEarlyStr"
                        arrival.predicted && arrival.status == ArrivalStatus.ON_TIME ->
                            " · $onTimeStr"
                        else -> ""
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        Text(
                            stringResource(R.string.arrival_sched, schedStr),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (deviationText.isNotEmpty()) {
                            Text(
                                deviationText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = timeColor,
                            )
                        }
                    }
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
                            contentDescription = stringResource(R.string.reminder_cancel),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.NotificationsNone,
                            contentDescription = stringResource(R.string.reminder_set_title),
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
                            stringResource(R.string.status_now),
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