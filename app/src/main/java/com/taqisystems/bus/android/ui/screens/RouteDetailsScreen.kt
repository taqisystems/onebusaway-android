// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.screens

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.taqisystems.bus.android.R

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.taqisystems.bus.android.data.model.SavedRoute
import com.taqisystems.bus.android.ui.viewmodel.RouteDetailsViewModel
import com.taqisystems.bus.android.ui.viewmodel.RouteDetailsViewModelFactory
import kotlinx.coroutines.launch
import java.util.Calendar

// ── Colour helpers ────────────────────────────────────────────────────────────

private val AccentRed    = Color(0xFFC62828)
private val BgHeader     = Color(0xFFC62828)
private val GreenOnTime  = Color(0xFF16A34A)
private val BlueDelayed  = Color(0xFF1565C0)
private val RedEarly     = Color(0xFFDC2626)
private val TimelinePast = Color(0xFFBDBDBD)
private val TimelineFut  = Color(0xFFE0E0E0)
private val AmberPin     = Color(0xFFF59E0B)
private val AmberText    = Color(0xFF78350F)

private fun deviationColor(sec: Double): Color = when {
    kotlin.math.abs(sec) < 60.0 -> GreenOnTime
    sec > 0                     -> BlueDelayed
    else                        -> RedEarly
}

private fun deviationLabel(sec: Double, onTimeStr: String, lateStr: String, earlyStr: String): String = when {
    kotlin.math.abs(sec) < 60.0 -> onTimeStr
    sec > 0 -> lateStr.format(kotlin.math.round(kotlin.math.abs(sec) / 60).toInt())
    else    -> earlyStr.format(kotlin.math.round(kotlin.math.abs(sec) / 60).toInt())
}

private fun formatTime(secondsFromMidnight: Int): String {
    val hh     = (secondsFromMidnight / 3600) % 24
    val m      = (secondsFromMidnight % 3600) / 60
    val period = if (hh < 12) "AM" else "PM"
    val hour   = if (hh % 12 == 0) 12 else hh % 12
    return "$hour:${m.toString().padStart(2, '0')} $period"
}

private fun formatUpdated(ts: Long): String {
    val c      = Calendar.getInstance().apply { timeInMillis = ts }
    val h      = c.get(Calendar.HOUR_OF_DAY)
    val m      = c.get(Calendar.MINUTE)
    val s      = c.get(Calendar.SECOND)
    val period = if (h < 12) "AM" else "PM"
    val hour   = if (h % 12 == 0) 12 else h % 12
    return "$hour:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')} $period"
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailsScreen(
    navController: NavController,
    tripId: String,
    routeId: String,
    routeShortName: String,
    routeLongName: String,
    tripHeadsign: String,
    stopId: String,
    viewModel: RouteDetailsViewModel = viewModel(factory = RouteDetailsViewModelFactory()),
) {
    val uiState     by viewModel.uiState.collectAsState()
    val savedRoutes by viewModel.savedRoutes.collectAsState()
    LaunchedEffect(tripId) { viewModel.load(tripId, stopId) }

    val details      = uiState.details
    val currentIndex = uiState.currentStopIndex
    val routeLabel   = routeShortName.ifBlank { routeId }
    val headsign     = details?.headsign?.takeIf { it.isNotBlank() }
        ?: tripHeadsign.takeIf { it.isNotBlank() }
        ?: routeLongName
    val isSaved      = savedRoutes.any { it.routeId == routeId }
    val isLive       = details?.predicted == true

    // Route brand color — from live arrival data, falls back to AccentRed
    val headerBg: Color = uiState.currentArrival?.routeColor?.let {
        runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull()
    } ?: AccentRed
    val badgeTextColor: Color = uiState.currentArrival?.routeTextColor?.let {
        runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull()
    } ?: headerBg

    // Progress along route (0..1)
    val routeProgress = if ((details?.totalDistance ?: 0.0) > 0.0)
        (details!!.distanceAlongTrip / details.totalDistance).coerceIn(0.0, 1.0).toFloat()
    else null

    // Pulse animation for current-stop dot
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue  = 0.9f,
        targetValue   = if (isLive) 1.35f else 0.9f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotPulse",
    )

    val listState         = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val routeSavedText = stringResource(R.string.route_saved_snack)
    val routeRemovedText = stringResource(R.string.route_removed_snack)
    val devOnTime = stringResource(R.string.route_deviation_on_time)
    val devLate = stringResource(R.string.route_deviation_late)
    val devEarly = stringResource(R.string.route_deviation_early)
    val scope             = rememberCoroutineScope()

    // Scroll to bus position on load/update
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 1) listState.animateScrollToItem(currentIndex - 1)
    }

    // Scroll to the user's stop when bus position is unknown
    LaunchedEffect(uiState.stops) {
        if (currentIndex < 0 && uiState.stops.isNotEmpty()) {
            val myIdx = uiState.stops.indexOfFirst { it.stopId == stopId }
            if (myIdx >= 0) listState.animateScrollToItem(myIdx)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.navigationBarsPadding())
        },
        floatingActionButton = {
            if (currentIndex >= 0) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(currentIndex) } },
                    containerColor = headerBg,
                    contentColor = Color.White,
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.route_jump_to_bus_cd))
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            // ── Red header ────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .statusBarsPadding()
                    .padding(bottom = 14.dp),
            ) {
                // Row 1: back + route badge + headsign + live/sched chip + bookmark
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = Color.White,
                        )
                    }

                    // Route-number badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            routeLabel,
                            color      = badgeTextColor,
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    Text(
                        headsign,
                        color      = Color.White,
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier   = Modifier.weight(1f),
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.width(4.dp))

                    // Live / Scheduled chip
                    if (isLive) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2E7D32))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF81C784)),
                                )
                                Text(stringResource(R.string.route_live_chip), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                            }
                        }
                    } else if (tripId.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.20f))
                                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(stringResource(R.string.route_sched_chip), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                        }
                    }

                    // Bookmark
                    IconButton(onClick = {
                        viewModel.toggleSavedRoute(
                            SavedRoute(
                                routeId   = routeId,
                                tripId    = tripId,
                                shortName = routeShortName,
                                longName  = routeLongName,
                                headsign  = headsign,
                            )
                        )
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (isSaved) routeRemovedText else routeSavedText,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }) {
                        Icon(
                            if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (isSaved) stringResource(R.string.route_remove_saved) else stringResource(R.string.route_save),
                            tint     = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    // Manual refresh
                    IconButton(onClick = { viewModel.refreshNow() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            tint     = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // Row 2: status info strip inside header
                if (details != null) {
                    val devColor       = deviationColor(details.scheduleDeviation)
                    val stopsRemaining = if (currentIndex >= 0) details.stops.size - currentIndex else null

                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Deviation pill
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isLive) devColor else Color.White.copy(alpha = 0.12f),
                        ) {
                            Row(
                                modifier              = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    if (isLive) Icons.Default.Sensors else Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint     = if (isLive) Color.White else Color.White.copy(alpha = 0.70f),
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    deviationLabel(details.scheduleDeviation, devOnTime, devLate, devEarly),
                                    color      = if (isLive) Color.White else Color.White.copy(alpha = 0.70f),
                                    fontSize   = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        // Stop count pill
                        if (currentIndex >= 0) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.15f),
                            ) {
                                Row(
                                    modifier              = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Default.LinearScale, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(12.dp))
                                    Text(
                                        stringResource(R.string.route_stop_count, currentIndex + 1, details.stops.size),
                                        color    = Color.White.copy(alpha = 0.85f),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }

                        // Stops remaining
                        if (stopsRemaining != null && stopsRemaining > 0) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    pluralStringResource(R.plurals.route_stops_left, stopsRemaining, stopsRemaining),
                                    color    = Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // Last updated / refreshing indicator
                        if (uiState.refreshing) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(12.dp),
                                color       = Color.White,
                                strokeWidth = 1.5.dp,
                            )
                        } else {
                            uiState.lastUpdated?.let { ts ->
                                Text(
                                    "↻ ${formatUpdated(ts)}",
                                    color    = Color.White.copy(alpha = 0.6f),
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }

                    // Route progress bar
                    if (routeProgress != null) {
                        Spacer(Modifier.height(8.dp))
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    details.stops.firstOrNull()?.stopName?.take(20) ?: "",
                                    color    = Color.White.copy(alpha = 0.55f),
                                    fontSize = 8.sp,
                                )
                                Text(
                                    details.stops.lastOrNull()?.stopName?.take(20) ?: "",
                                    color    = Color.White.copy(alpha = 0.55f),
                                    fontSize = 8.sp,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            LinearProgressIndicator(
                                progress  = routeProgress,
                                modifier  = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color      = Color.White,
                                trackColor = Color.White.copy(alpha = 0.25f),
                            )
                        }
                    }
                }
            }

            // ── Body ──────────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.loading && uiState.stops.isEmpty() -> {
                        Column(
                            modifier            = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(color = headerBg)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Loading trip\u2026",
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                        }
                    }

                    uiState.error != null && uiState.stops.isEmpty() -> {
                        Column(
                            modifier            = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = null,
                                tint     = headerBg.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                uiState.error ?: stringResource(R.string.route_error_generic),
                                color     = headerBg,
                                fontSize  = 15.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    tripId.isBlank() -> {
                        Column(
                            modifier            = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Default.DirectionsBus,
                                contentDescription = null,
                                tint     = headerBg.copy(alpha = 0.35f),
                                modifier = Modifier.size(56.dp),
                            )
                            Text(stringResource(R.string.route_no_trip), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.route_no_trip_hint),
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize  = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    else -> {
                        val stops = uiState.stops

                        if (uiState.loading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter),
                                color = headerBg,
                            )
                        }

                        LazyColumn(
                            state          = listState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, end = 16.dp),
                        ) {
                            itemsIndexed(stops, key = { i, s -> "${s.stopId}_$i" }) { index, stop ->
                                val isCurrent = stop.stopId == details?.closestStopId
                                val isNext    = !isCurrent && stop.stopId == details?.nextStopId
                                val isPassed  = currentIndex >= 0 && index < currentIndex
                                val isMyStop  = stop.stopId == stopId

                                val lineColor = if (isPassed || isCurrent) TimelinePast else TimelineFut

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    // ── Timeline column ───────────────────────
                                    Column(
                                        modifier            = Modifier.width(52.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        // Top connecting line
                                        Box(
                                            modifier = Modifier
                                                .width(2.dp)
                                                .height(if (index == 0) 18.dp else 22.dp)
                                                .background(if (index == 0) Color.Transparent else lineColor),
                                        )

                                        // Node dot
                                        val isFirstStop = index == 0
                                        val isLastStop  = index == stops.lastIndex
                                        when {
                                            isCurrent -> Box(
                                                modifier = Modifier
                                                    .scale(pulseScale)
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .background(headerBg)
                                                    .border(3.dp, Color.White, CircleShape),
                                            )
                                            isNext -> Box(
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .border(2.dp, headerBg, CircleShape),
                                            )
                                            isFirstStop -> Box(
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(if (isPassed) TimelinePast else headerBg.copy(alpha = 0.75f)),
                                            )
                                            isLastStop -> Box(
                                                modifier = Modifier
                                                    .size(13.dp)
                                                    .graphicsLayer(rotationZ = 45f)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(if (isPassed) TimelinePast else headerBg.copy(alpha = 0.75f)),
                                            )
                                            isPassed -> Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(TimelinePast),
                                            )
                                            else -> Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(TimelineFut)
                                                    .border(1.5.dp, TimelinePast, CircleShape),
                                            )
                                        }

                                        // Bottom connecting line
                                        if (index != stops.lastIndex) {
                                            Box(
                                                modifier = Modifier
                                                    .width(2.dp)
                                                    .weight(1f)
                                                    .defaultMinSize(minHeight = 8.dp)
                                                    .background(lineColor),
                                            )
                                        }
                                    }

                                    // ── Stop card ─────────────────────────────
                                    val cardBg = when {
                                        isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                        isMyStop  -> AmberPin.copy(alpha = 0.08f)
                                        else      -> Color.Transparent
                                    }
                                    val borderColor: Color? = when {
                                        isCurrent -> headerBg.copy(alpha = 0.35f)
                                        isMyStop  -> AmberPin.copy(alpha = 0.45f)
                                        else      -> null
                                    }

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .graphicsLayer(alpha = if (isPassed && !isCurrent) 0.55f else 1f)
                                            .let { m ->
                                                if (borderColor != null)
                                                    m.clip(RoundedCornerShape(10.dp))
                                                        .background(cardBg)
                                                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                                else m
                                            }
                                            .padding(horizontal = 10.dp)
                                            .padding(top = 2.dp, bottom = 18.dp),
                                    ) {
                                        // Stop name row with badges
                                        Row(
                                            verticalAlignment     = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier              = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                stop.stopName,
                                                color = when {
                                                    isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                                                    isPassed  -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    else      -> MaterialTheme.colorScheme.onSurface
                                                },
                                                fontSize   = if (isCurrent) 15.sp else 14.sp,
                                                fontWeight = if (isCurrent || isNext) FontWeight.SemiBold else FontWeight.Normal,
                                                lineHeight  = 20.sp,
                                                modifier   = Modifier.weight(1f),
                                            )

                                            if (isCurrent) {
                                                Surface(
                                                    shape = RoundedCornerShape(5.dp),
                                                    color = headerBg,
                                                ) {
                                                    Row(
                                                        modifier              = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        verticalAlignment     = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                    ) {
                                                        Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                                                        Text(stringResource(R.string.route_here_badge), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
                                                    }
                                                }
                                            }

                                            if (isNext) {
                                                Surface(
                                                    shape  = RoundedCornerShape(5.dp),
                                                    color  = headerBg.copy(alpha = 0.08f),
                                                    border = BorderStroke(1.dp, headerBg.copy(alpha = 0.35f)),
                                                ) {
                                                    Text(
                                                        stringResource(R.string.route_next_badge),
                                                        color      = headerBg,
                                                        fontSize   = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 0.6.sp,
                                                        modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }

                                            if (isMyStop && !isCurrent) {
                                                Surface(
                                                    shape  = RoundedCornerShape(6.dp),
                                                    color  = AmberPin.copy(alpha = 0.12f),
                                                    border = BorderStroke(1.dp, AmberPin.copy(alpha = 0.55f)),
                                                ) {
                                                    Row(
                                                        modifier              = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                        verticalAlignment     = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                    ) {
                                                        Icon(
                                                            Icons.Default.LocationOn,
                                                            contentDescription = null,
                                                            tint     = AmberText,
                                                            modifier = Modifier.size(11.dp),
                                                        )
                                                        Text(
                                                            stringResource(R.string.route_your_stop_badge),
                                                            color      = AmberText,
                                                            fontSize   = 9.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            letterSpacing = 0.3.sp,
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(3.dp))

                                        // Time row
                                        val schedColor = when {
                                            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                            isPassed  -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            else      -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        // Server always computes deviation (live or scheduled), so apply it universally
                                        val predictedSec = if (details != null)
                                            stop.arrivalTime + details.scheduleDeviation.toInt() else null
                                        val showPredicted = predictedSec != null && !isPassed

                                        Row(
                                            verticalAlignment     = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (showPredicted && predictedSec != null) {
                                                // Predicted time in deviation colour (live only), gray for scheduled
                                                val predColor = when {
                                                    isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                                    isLive    -> deviationColor(details!!.scheduleDeviation)
                                                    else      -> MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                                Icon(
                                                    if (isLive) Icons.Default.Sensors else Icons.Default.Schedule,
                                                    contentDescription = null,
                                                    tint     = predColor,
                                                    modifier = Modifier.size(11.dp),
                                                )
                                                Text(
                                                    formatTime(predictedSec),
                                                    color      = predColor,
                                                    fontSize   = 11.sp,
                                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                                )
                                                // Show scheduled in muted text alongside
                                                Text(
                                                    stringResource(R.string.route_sched_prefix, formatTime(stop.arrivalTime)),
                                                    color    = schedColor.copy(alpha = 0.55f),
                                                    fontSize = 10.sp,
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.Schedule,
                                                    contentDescription = null,
                                                    tint     = schedColor,
                                                    modifier = Modifier.size(11.dp),
                                                )
                                                Text(
                                                    formatTime(stop.arrivalTime),
                                                    color      = schedColor,
                                                    fontSize   = 11.sp,
                                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }
    }
}
