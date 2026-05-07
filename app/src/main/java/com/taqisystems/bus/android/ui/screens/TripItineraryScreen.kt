// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.taqisystems.bus.android.service.DestinationAlertService
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taqisystems.bus.android.data.model.OtpItinerary
import com.taqisystems.bus.android.data.model.OtpLeg
import com.taqisystems.bus.android.data.repository.ObaRepository
import com.taqisystems.bus.android.ui.navigation.Routes
import com.taqisystems.bus.android.ui.theme.*
import com.taqisystems.bus.android.ui.viewmodel.TripPlannerViewModel
import com.taqisystems.bus.android.ui.viewmodel.TripPlannerViewModelFactory
import com.taqisystems.bus.android.ui.viewmodel.SelectedItineraryHolder
import java.text.SimpleDateFormat
import java.util.*

// ─── Colour constants & transit-mode helpers ────────────────────────────────
private val WalkColor = Color(0xFF9E9E9E)

/**
 * Returns the brand color for a given OTP [mode] string.
 * Colors match [TransitType.mapColor] so markers and route lines are consistent.
 * To adapt for a different country, change the hex values here and in TransitType.
 */
private fun legColor(mode: String): Color = when (mode.uppercase()) {
    "BUS"      -> Color(0xFFDC2626)  // red   – bus
    "TRAM"     -> Color(0xFF9B2335)  // ruby  – LRT (Ampang / Kelana Jaya)
    "RAIL"     -> Color(0xFFE35205)  // orange – KTM Komuter
    "SUBWAY"   -> Color(0xFF007C3E)  // green  – MRT (Kajang / Putrajaya)
    "FERRY"    -> Color(0xFF0369A1)  // ocean blue
    "MONORAIL" -> Color(0xFF5CB85C)  // light green – KL Monorail
    else       -> Color(0xFFDC2626)
}

/** Returns the appropriate Material icon for a given OTP [mode] string. */
private fun legIcon(mode: String) = when (mode.uppercase()) {
    "TRAM"     -> Icons.Default.Tram
    "RAIL"     -> Icons.Default.Train
    "SUBWAY"   -> Icons.Default.DirectionsSubway
    "FERRY"    -> Icons.Default.DirectionsBoat
    "MONORAIL" -> Icons.Default.Train
    else       -> Icons.Default.DirectionsBus
}

/** Short human-readable label for a given OTP [mode] string. */
private fun legModeLabel(mode: String): String = when (mode.uppercase()) {
    "BUS"      -> "Bus"
    "TRAM"     -> "LRT"
    "RAIL"     -> "Commuter Rail"
    "SUBWAY"   -> "MRT"
    "FERRY"    -> "Ferry"
    "MONORAIL" -> "Monorail"
    "WALK"     -> "Walk"
    else       -> mode
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripItineraryScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val viewModel: TripPlannerViewModel = viewModel(viewModelStoreOwner = activity, factory = TripPlannerViewModelFactory())
    val uiState by viewModel.uiState.collectAsState()
    // SelectedItineraryHolder is set synchronously before navigate() is called,
    // so it is always non-null when this screen first composes.
    val itinerary = SelectedItineraryHolder.itinerary ?: uiState.selectedItinerary

    // ── Destination Alert state ───────────────────────────────────────────────
    var alertActive by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }

    // Last transit leg provides the destination + before-stop coordinates
    val lastTransitLeg = itinerary?.legs?.lastOrNull { it.transitLeg }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            lastTransitLeg?.let { leg -> startDestinationAlert(context, leg, onStarted = { alertActive = true }) }
        } else {
            showPermissionRationale = true
        }
    }

    fun toggleAlert() {
        if (alertActive) {
            context.stopService(Intent(context, DestinationAlertService::class.java))
            alertActive = false
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                lastTransitLeg?.let { leg -> startDestinationAlert(context, leg, onStarted = { alertActive = true }) }
            } else {
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text("Location Permission Required") },
            text = { Text("Destination alerts need your GPS location to detect when you're approaching your stop. Please grant location permission in Settings.") },
            confirmButton = { TextButton(onClick = { showPermissionRationale = false }) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Trip Plan", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        if (itinerary != null) {
                            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                            val dur = itinerary.duration / 60
                            Text("${dur} min · Arrive ${fmt.format(Date(itinerary.endTime))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            )
        },
        floatingActionButton = {
            if (lastTransitLeg != null) {
                ExtendedFloatingActionButton(
                    onClick = { toggleAlert() },
                    icon = {
                        Icon(
                            if (alertActive) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(if (alertActive) "Stop Alert" else "Destination Alert")
                    },
                    containerColor = if (alertActive) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { innerPadding ->
        if (itinerary == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.DirectionsBus,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text(
                        "Trip details unavailable",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Go back and select a route option",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { navController.popBackStack() }) {
                        Text("Go Back")
                    }
                }
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            // ── Map: top 42% ─────────────────────────────────────────────────
            Box(Modifier.fillMaxWidth().weight(0.42f).shadow(4.dp)) {
                ItineraryMap(itinerary)
            }
            // ── Step list: bottom 58% ─────────────────────────────────────────
            LazyColumn(
                Modifier.fillMaxWidth().weight(0.58f)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                item { ItinerarySummaryHeader(itinerary) }
                itemsIndexed(itinerary.legs) { _, leg -> LegItem(leg) }
                itinerary.legs.lastOrNull()?.let { last ->
                    item { DestinationNode(last) }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

// ─── Map panel ────────────────────────────────────────────────────────────────

@Composable
private fun ItineraryMap(itinerary: OtpItinerary) {
    // Decode encoded-polyline geometry for every leg once
    val legPoints = remember(itinerary) {
        itinerary.legs.map { leg ->
            if (!leg.legGeometry.isNullOrBlank())
                ObaRepository.decodePolyline(leg.legGeometry).map { LatLng(it.lat, it.lon) }
            else emptyList()
        }
    }

    // All points that matter for camera bounds
    val allPoints = remember(legPoints, itinerary) {
        buildList {
            legPoints.forEach { addAll(it) }
            itinerary.legs.forEach { leg ->
                add(LatLng(leg.from.lat, leg.from.lon))
                add(LatLng(leg.to.lat, leg.to.lon))
                leg.intermediateStops.forEach { add(LatLng(it.lat, it.lon)) }
            }
        }.filter { it.latitude != 0.0 || it.longitude != 0.0 }
    }

    val defaultLatLng = allPoints.firstOrNull() ?: LatLng(5.5, 102.2)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultLatLng, 13f)
    }

    // Fit camera to entire route on first load
    LaunchedEffect(allPoints) {
        if (allPoints.size >= 2) {
            val builder = LatLngBounds.builder()
            allPoints.forEach { builder.include(it) }
            val bounds = runCatching { builder.build() }.getOrNull() ?: return@LaunchedEffect
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngBounds(bounds, 72),
                durationMs = 900,
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
            ),
        ) {
            // ── Draw polylines ───────────────────────────────────────────────
            itinerary.legs.forEachIndexed { idx, leg ->
                val pts = legPoints[idx].ifEmpty {
                    // Fallback to straight line from→to when no geometry
                    listOf(LatLng(leg.from.lat, leg.from.lon), LatLng(leg.to.lat, leg.to.lon))
                }
                if (pts.size >= 2) {
                    if (leg.transitLeg) {
                        Polyline(points = pts, color = Color.White, width = 20f, zIndex = 1f)
                        Polyline(points = pts, color = legColor(leg.mode), width = 12f, zIndex = 2f)
                    } else {
                        // Walk: grey, thinner
                        Polyline(points = pts, color = Color.White, width = 12f, zIndex = 1f)
                        Polyline(points = pts, color = WalkColor.copy(alpha = 0.75f), width = 6f, zIndex = 2f)
                    }
                }
            }

            // ── Intermediate bus-stop markers (transit legs only) ────────────
            itinerary.legs.forEach { leg ->
                if (leg.transitLeg) {
                    leg.intermediateStops.forEach { stop ->
                        if (stop.lat != 0.0 || stop.lon != 0.0) {
                            Marker(
                                state = MarkerState(LatLng(stop.lat, stop.lon)),
                                title = stop.name,
                                icon = BitmapDescriptorFactory.defaultMarker(
                                    BitmapDescriptorFactory.HUE_CYAN),
                                alpha = 0.85f,
                                zIndex = 3f,
                            )
                        }
                    }
                }
            }

            // ── Board / alight markers ───────────────────────────────────────
            itinerary.legs.filter { it.transitLeg }.forEach { leg ->
                Marker(
                    state = MarkerState(LatLng(leg.from.lat, leg.from.lon)),
                    title = "Board here",
                    snippet = leg.from.name + (leg.routeShortName?.let { " · Route $it" } ?: ""),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                    zIndex = 4f,
                )
                Marker(
                    state = MarkerState(LatLng(leg.to.lat, leg.to.lon)),
                    title = "Alight here",
                    snippet = leg.to.name,
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                    zIndex = 4f,
                )
            }

            // ── Origin (green) & Destination (red) ──────────────────────────
            itinerary.legs.firstOrNull()?.let { first ->
                Marker(
                    state = MarkerState(LatLng(first.from.lat, first.from.lon)),
                    title = first.from.name.ifBlank { "Origin" },
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                    zIndex = 5f,
                )
            }
            itinerary.legs.lastOrNull()?.let { last ->
                Marker(
                    state = MarkerState(LatLng(last.to.lat, last.to.lon)),
                    title = last.to.name.ifBlank { "Destination" },
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                    zIndex = 5f,
                )
            }
        }

        // ── Legend chip ──────────────────────────────────────────────────────
        // Dynamic legend: one dot per distinct transit mode present + Walk
        val legendModes = remember(itinerary) {
            itinerary.legs.filter { it.transitLeg }
                .map { it.mode.uppercase() }.distinct()
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            legendModes.forEach { mode ->
                Box(Modifier.size(10.dp).background(legColor(mode), CircleShape))
                Text(legModeLabel(mode), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Box(Modifier.size(10.dp).background(WalkColor, CircleShape))
            Text("Walk", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ─── Summary header ───────────────────────────────────────────────────────────

@Composable
private fun ItinerarySummaryHeader(itinerary: OtpItinerary) {
    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    val start = fmt.format(Date(itinerary.startTime))
    val end = fmt.format(Date(itinerary.endTime))
    val durationMin = itinerary.duration / 60
    val transitLegs = itinerary.legs.filter { it.transitLeg }
    val totalDist = itinerary.legs.sumOf { (it.endTime - it.startTime).toDouble() }.toFloat()
        .coerceAtLeast(1f)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        tonalElevation = 0.dp, shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top) {
                Column {
                    Text("$durationMin min", style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Arrive by $end", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    transitLegs.forEach { leg ->
                        Surface(shape = RoundedCornerShape(50),
                            color = legColor(leg.mode)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(legIcon(leg.mode), null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(leg.routeShortName?.takeIf { it.isNotBlank() } ?: leg.mode,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))) {
                itinerary.legs.forEach { leg ->
                    val fraction = (leg.endTime - leg.startTime).toFloat() / totalDist
                    Box(Modifier.weight(fraction).fillMaxHeight().background(
                        if (leg.transitLeg) legColor(leg.mode)
                        else MaterialTheme.colorScheme.secondaryContainer))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(start, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(end, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ─── Leg row ─────────────────────────────────────────────────────────────────

@Composable
private fun LegItem(leg: OtpLeg) {
    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    val startTime = fmt.format(Date(leg.startTime))
    val durationMin = ((leg.endTime - leg.startTime) / 60_000).toInt()
    val lineColor = if (leg.transitLeg) legColor(leg.mode) else MaterialTheme.colorScheme.outlineVariant
    val dotColor  = if (leg.transitLeg) legColor(leg.mode) else MaterialTheme.colorScheme.outline

    Column {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(32.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(dotColor))
                Box(Modifier.width(2.dp).fillMaxHeight()
                    .defaultMinSize(minHeight = 80.dp)
                    .background(lineColor.copy(alpha = 0.4f)))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp, bottom = 12.dp)) {
                Text(leg.from.name.ifBlank { "Current location" },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                Text(startTime, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (leg.transitLeg) {
                    val modeColor = legColor(leg.mode)
                    Card(shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = modeColor.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(legIcon(leg.mode), null,
                                    tint = modeColor, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Board · ${legModeLabel(leg.mode)}", fontWeight = FontWeight.Bold,
                                    color = modeColor,
                                    style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(modeColor)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)) {
                                    Text(
                                        leg.routeShortName?.takeIf { it.isNotBlank() }
                                            ?: leg.route.takeIf { it.isNotBlank() } ?: leg.mode,
                                        color = Color.White, fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                            if (!leg.headsign.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text("towards ${leg.headsign}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (!leg.routeLongName.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(leg.routeLongName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("$durationMin min · ${leg.intermediateStops.size} stop(s) · Alight at ${leg.to.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!leg.agencyName.isNullOrBlank()) {
                                Text(leg.agencyName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    val distM = leg.distance.toInt()
                    val distLabel = if (distM >= 1000) "%.1f km".format(leg.distance / 1000.0)
                                    else "$distM m"
                    Card(shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DirectionsWalk, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Walk $distLabel", fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text("$durationMin min · towards ${leg.to.name.ifBlank { "next stop" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Destination node ─────────────────────────────────────────────────────────

@Composable
private fun DestinationNode(lastLeg: OtpLeg) {
    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Box(Modifier.width(32.dp).padding(top = 2.dp),
            contentAlignment = Alignment.TopCenter) {
            Box(Modifier.size(18.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LocationOn, null, tint = Color.White,
                    modifier = Modifier.size(12.dp))
            }
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(lastLeg.to.name.ifBlank { "Destination" },
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium)
            Text("Arrive ${fmt.format(Date(lastLeg.endTime))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─── Destination Alert helpers ────────────────────────────────────────────────

/**
 * Starts the [DestinationAlertService] for the given transit [leg].
 *
 * The "before" stop is the last intermediate stop in the leg (the stop just before the
 * destination). If there are no intermediate stops, the leg's origin (`from`) is used as
 * the before-stop so stage-1 "Get Ready" is triggered when the bus leaves that stop.
 */
private fun startDestinationAlert(
    context: android.content.Context,
    leg: com.taqisystems.bus.android.data.model.OtpLeg,
    onStarted: () -> Unit,
) {
    val beforeStop = leg.intermediateStops.lastOrNull()
    val beforeLat  = beforeStop?.lat  ?: leg.from.lat
    val beforeLon  = beforeStop?.lon  ?: leg.from.lon

    val intent = Intent(context, DestinationAlertService::class.java).apply {
        action = DestinationAlertService.ACTION_START
        putExtra(DestinationAlertService.EXTRA_DEST_NAME,  leg.to.name.ifBlank { "Destination" })
        putExtra(DestinationAlertService.EXTRA_DEST_LAT,   leg.to.lat)
        putExtra(DestinationAlertService.EXTRA_DEST_LON,   leg.to.lon)
        putExtra(DestinationAlertService.EXTRA_BEFORE_LAT, beforeLat)
        putExtra(DestinationAlertService.EXTRA_BEFORE_LON, beforeLon)
    }
    context.startForegroundService(intent)
    onStarted()
}
