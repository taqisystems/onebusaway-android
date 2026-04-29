package com.taqisystems.bus.android.ui.screens

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.taqisystems.bus.android.data.model.SavedRoute
import com.taqisystems.bus.android.ui.viewmodel.RouteDetailsViewModel
import com.taqisystems.bus.android.ui.viewmodel.RouteDetailsViewModelFactory
import java.util.Calendar

// ── App colour palette — Transit Red light theme ──────────────────────────────
private val BgDeep       = Color(0xFFFFFBFB)  // Background (off-white)
private val BgHeader     = Color(0xFFC62828)  // Primary red header
private val BgStatusBar  = Color(0xFFFFCDD2)  // PrimaryContainer — soft red tint
private val DividerColor = Color(0xFFD9C4C4)  // OutlineVariant
private val AccentRed    = Color(0xFFC62828)  // Primary — Transit Red
private val GreenOnTime  = Color(0xFF16A34A)  // on time
private val BlueDelayed  = Color(0xFF1A73E8)  // delayed
private val RedEarly     = Color(0xFFDC2626)  // early
private val TextPrimary  = Color(0xFF1C1B1B)  // OnSurface
private val TextDim      = Color(0xFF4E4040)  // OnSurfaceVariant
private val TimelineGray = Color(0xFF857070)  // Outline — passed stops
private val TimelineOff  = Color(0xFFD9C4C4)  // OutlineVariant — future stops

private fun deviationColor(sec: Double): Color = when {
    Math.abs(sec) < 60.0 -> GreenOnTime
    sec > 0              -> BlueDelayed
    else                 -> RedEarly
}

private fun deviationLabel(sec: Double): String = when {
    Math.abs(sec) < 60.0 -> "On time"
    sec > 0 -> "${Math.round(Math.abs(sec) / 60)} min late"
    else    -> "${Math.round(Math.abs(sec) / 60)} min early"
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
    return "$hour:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')} $period"
}

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
    val uiState by viewModel.uiState.collectAsState()
    val savedRoutes by viewModel.savedRoutes.collectAsState()
    LaunchedEffect(tripId) { viewModel.load(tripId, stopId) }

    val details      = uiState.details
    val currentIndex = uiState.currentStopIndex
    val routeLabel   = routeShortName.ifBlank { routeId }
    val headsign     = details?.headsign?.takeIf { it.isNotBlank() }
        ?: tripHeadsign.takeIf { it.isNotBlank() }
        ?: routeLongName
    val isSaved = savedRoutes.any { it.routeId == routeId }

    // Pulse animation for current-stop dot when live
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = if (details?.predicted == true) 1.4f else 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotPulse",
    )

    // Auto-scroll to current stop
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 1) listState.animateScrollToItem(currentIndex - 1)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgHeader)
                .statusBarsPadding()
                .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick        = { navController.popBackStack() },
                modifier       = Modifier.size(44.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("←", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }

            Spacer(Modifier.width(4.dp))

            // Route number badge — white background, red bold text on red header
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(routeLabel, color = AccentRed, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }

            Spacer(Modifier.width(10.dp))

            Text(
                headsign,
                color      = Color.White,
                fontSize   = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier   = Modifier.weight(1f),
                maxLines   = 1,
            )

            Spacer(Modifier.width(8.dp))

            when {
                details?.predicted == true ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF43A047))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    }
                tripId.isNotBlank() ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.25f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("SCHED", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    }
                else -> {}
            }

            // Bookmark button
            IconButton(onClick = {
                viewModel.toggleSavedRoute(
                    SavedRoute(
                        routeId = routeId,
                        tripId = tripId,
                        shortName = routeShortName,
                        longName = routeLongName,
                        headsign = headsign,
                    )
                )
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (isSaved) "Route removed from saved" else "Route saved",
                        duration = SnackbarDuration.Short,
                    )
                }
            }) {
                Icon(
                    imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (isSaved) "Remove saved route" else "Save route",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // ── Status strip ─────────────────────────────────────────────────────
        if (details != null) {
            val devColor = deviationColor(details.scheduleDeviation)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgStatusBar)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(deviationLabel(details.scheduleDeviation), color = devColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (currentIndex >= 0) {
                        Spacer(Modifier.width(12.dp))
                        Text("Stop ${currentIndex + 1} / ${details.stops.size}", color = TextDim, fontSize = 12.sp)
                    }
                }
                if (uiState.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AccentRed, strokeWidth = 2.dp)
                } else {
                    uiState.lastUpdated?.let { ts ->
                        Text("Updated ${formatUpdated(ts)}", color = TextDim, fontSize = 10.sp)
                    }
                }
            }
            HorizontalDivider(color = DividerColor, thickness = 1.dp)
        }

        // ── Body ─────────────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.loading && uiState.stops.isEmpty() -> {
                    Column(
                        modifier            = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = AccentRed)
                        Spacer(Modifier.height(12.dp))
                        Text("Loading trip…", color = TextDim, fontSize = 14.sp)
                    }
                }

                uiState.error != null && uiState.stops.isEmpty() -> {
                    Text(
                        uiState.error ?: "Error",
                        color     = AccentRed,
                        fontSize  = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                }

                tripId.isBlank() -> {
                    Column(
                        modifier            = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("No trip selected.", color = AccentRed, fontSize = 15.sp)
                        Text(
                            "Tap an arrival from the stop screen to see the live bus position and stop sequence.",
                            color      = TextDim,
                            fontSize   = 13.sp,
                            textAlign  = TextAlign.Center,
                            lineHeight = 20.sp,
                        )
                    }
                }

                else -> {
                    val stops = uiState.stops
                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp, end = 20.dp),
                    ) {
                        if (uiState.loading) {
                            item {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentRed)
                            }
                        }

                        itemsIndexed(stops, key = { i, s -> "${s.stopId}_$i" }) { index, stop ->
                            val isCurrent = stop.stopId == details?.closestStopId
                            val isNext    = !isCurrent && stop.stopId == details?.nextStopId
                            val isPassed  = currentIndex >= 0 && index < currentIndex
                            val isMyStop  = stop.stopId == stopId
                            // time: passed → readable muted grey; current → deep red on pink card;
                            // upcoming → deviation colour if live, else dark ink
                            val timeColor = when {
                                isPassed  -> Color(0xFF78716C)  // stone-500 — legible muted on white
                                isCurrent -> Color(0xFF7F0000)  // deep red on PrimaryContainer card
                                else      -> if (details?.predicted == true)
                                                 deviationColor(details.scheduleDeviation)
                                             else Color(0xFF374151) // slate-700 — dark on white
                            }

                            Row(modifier = Modifier.fillMaxWidth()) {
                                // ── Timeline column (48dp) ───────────────────
                                Column(
                                    modifier            = Modifier.width(48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (index != 0) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.dp)
                                                .height(20.dp)
                                                .background(if (isPassed) TimelineGray else TimelineOff),
                                        )
                                    } else {
                                        Spacer(Modifier.height(20.dp))
                                    }

                                    when {
                                        isCurrent ->
                                            Box(
                                                modifier = Modifier
                                                    .scale(pulseScale)
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .background(AccentRed)
                                                    .border(3.dp, Color.White, CircleShape),
                                            )
                                        isNext ->
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .background(BgDeep)
                                                    .border(2.dp, AccentRed, CircleShape),
                                            )
                                        isPassed ->
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFE0D4D4))
                                                    .border(2.dp, TimelineGray, CircleShape),
                                            )
                                        else ->
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .background(TimelineOff)
                                                    .border(2.dp, TimelineGray, CircleShape),
                                            )
                                    }

                                    if (index != stops.lastIndex) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.dp)
                                                .weight(1f)
                                                .defaultMinSize(minHeight = 16.dp)
                                                .background(if (isPassed) TimelineGray else TimelineOff),
                                        )
                                    }
                                }

                                // ── Stop card ─────────────────────────────────
                                val cardMod = if (isCurrent)
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFFFCDD2)) // PrimaryContainer
                                        .border(1.dp, AccentRed.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                else
                                    Modifier.clip(RoundedCornerShape(10.dp))

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .graphicsLayer(alpha = if (isPassed) 0.7f else 1f)
                                        .then(cardMod)
                                        .padding(horizontal = 12.dp)
                                        .padding(top = 2.dp, bottom = 16.dp),
                                ) {
                                    // Name row + optional "You" pin
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier          = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            stop.stopName,
                                            color      = when {
                                                isCurrent -> Color(0xFF7F0000) // OnPrimaryContainer
                                                isPassed  -> Color(0xFF6B5E5E) // readable muted
                                                else      -> Color(0xFF1C1B1B) // OnSurface — full contrast
                                            },
                                            fontSize   = if (isCurrent) 16.sp else 15.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                            lineHeight  = 20.sp,
                                            modifier   = Modifier.weight(1f),
                                        )
                                        if (isMyStop) {
                                            Spacer(Modifier.width(6.dp))
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFFB45309).copy(alpha = 0.12f))
                                                    .border(1.dp, Color(0xFFB45309).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text("📍", fontSize = 11.sp)
                                                Spacer(Modifier.width(2.dp))
                                                Text("You", color = Color(0xFF92400E), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(3.dp))

                                    // Meta: time + BUS HERE / NEXT pill
                                    Row(
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(formatTime(stop.arrivalTime), color = timeColor, fontSize = 12.sp)

                                        if (isCurrent) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(AccentRed.copy(alpha = 0.12f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            ) {
                                                Text("● BUS HERE", color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                                            }
                                        }

                                        if (isNext) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(AccentRed.copy(alpha = 0.07f))
                                                    .border(1.dp, AccentRed.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            ) {
                                                Text("NEXT", color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(Modifier.height(4.dp)) }
                    }
                }
            }
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
    )
    } // Box
}
