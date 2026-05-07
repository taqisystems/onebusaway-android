// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.screens

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.taqisystems.bus.android.R

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.speech.RecognizerIntent
import com.taqisystems.bus.android.data.model.TransitType
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taqisystems.bus.android.data.model.ActiveReminder
import com.taqisystems.bus.android.data.model.ArrivalStatus
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.ObaStop
import com.taqisystems.bus.android.data.model.OverviewRoute
import com.taqisystems.bus.android.data.model.PlaceResult
import com.taqisystems.bus.android.PendingStopFocus
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.ui.navigation.Routes
import com.taqisystems.bus.android.ui.theme.*
import com.taqisystems.bus.android.ui.viewmodel.HomeSearchResult
import com.taqisystems.bus.android.ui.map.VehicleMarkerFactory
import com.taqisystems.bus.android.ui.viewmodel.HomeViewModel
import com.taqisystems.bus.android.ui.viewmodel.HomeViewModelFactory
import com.taqisystems.bus.android.ui.util.resolve
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeMapScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory()),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Refresh arrivals immediately when app returns to foreground if data is stale.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshArrivalsIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Collect pending stop focus from Reminders deep-link — reactive, no lifecycle race.
    LaunchedEffect(Unit) {
        PendingStopFocus.flow.collect { pending ->
            if (pending != null) {
                PendingStopFocus.clear()
                viewModel.focusStopById(pending.id, pending.name, pending.lat, pending.lon)
            }
        }
    }
    var isListening by remember { mutableStateOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        isListening = false
        val matches = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val query = matches?.firstOrNull()?.trim() ?: return@rememberLauncherForActivityResult
        if (query.isNotEmpty()) {
            viewModel.onSearchQueryChanged(query)
            viewModel.setSearchActive(true)
        }
    }
    fun launchVoiceSearch() {
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Where do you want to go?")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechLauncher.launch(intent)
    }

    val defaultLatlng = LatLng(6.1254, 102.2381) // Kota Bharu default
    // If a region change is pending (user picked a new region in Settings), jump straight
    // to that centre on initial composition so the map never flashes the old region.
    // Otherwise restore the last saved camera position, falling back to Kota Bharu.
    val pendingCenter = uiState.pendingCameraCenter
    val initialLatLng = when {
        pendingCenter != null ->
            LatLng(pendingCenter.first, pendingCenter.second)
        uiState.lastCameraLat != 0.0 || uiState.lastCameraLon != 0.0 ->
            LatLng(uiState.lastCameraLat, uiState.lastCameraLon)
        else -> defaultLatlng
    }
    val initialZoom = when {
        pendingCenter != null -> 12f
        uiState.lastCameraZoom > 0f -> uiState.lastCameraZoom
        else -> 14f
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialLatLng, initialZoom)
    }

    // Sheet state — persistent BottomSheetScaffold with peek
    val selectedStop = uiState.selectedStop
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        // Allow the sheet to be dragged freely in both directions.
        // BottomSheetScaffold already routes list scroll events to the LazyColumn,
        // not the sheet drag, so accidental collapse while scrolling is not an issue.
        confirmValueChange = { true },
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot: fly camera to a stop fetched via deep-link (real coords come async)
    LaunchedEffect(uiState.cameraFocusStop?.id) {
        val stop = uiState.cameraFocusStop ?: return@LaunchedEffect
        cameraPositionState.animate(
            com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
                LatLng(stop.lat, stop.lon), 16f,
            )
        )
        scaffoldState.bottomSheetState.partialExpand()
        viewModel.clearCameraFocusStop()
    }

    // Show a snackbar whenever the user switches regions from Settings
    val regionSwitchMessage = uiState.regionSwitchMessage
    LaunchedEffect(regionSwitchMessage) {
        if (regionSwitchMessage != null) {
            snackbarHostState.showSnackbar(
                message = regionSwitchMessage.resolve(context),
                duration = SnackbarDuration.Short,
            )
            viewModel.clearRegionSwitchMessage()
        }
    }
    val scope = rememberCoroutineScope()
    val savedStops by viewModel.savedStops.collectAsState()
    val activeReminders by viewModel.activeReminders.collectAsState()
    val unreadNotifCount by ServiceLocator.preferences.unreadNotificationCount.collectAsState(initial = 0)

    // Translated string resources
    val undoText = stringResource(R.string.action_undo)
    val stopSavedText = stringResource(R.string.stop_saved_snack)
    val stopRemovedText = stringResource(R.string.stop_removed_snack)
    val locationPermText = stringResource(R.string.map_location_permission_required)
    val settingsText = stringResource(R.string.action_settings)

    // Reminder snackbar with optional Undo
    LaunchedEffect(uiState.reminderMessage) {
        val msg = uiState.reminderMessage
        if (msg != null) {
            val canUndo = uiState.lastCancelledReminder != null
            val result = snackbarHostState.showSnackbar(
                message = msg.resolve(context),
                actionLabel = if (canUndo) undoText else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoCancelReminder()
            else viewModel.clearReminderMessage()
        }
    }

    // Partial-expand to 1-row peek when a stop is tapped; collapse when cleared.
    // Auto-pan: centre the map on the selected stop so it appears above the peek sheet.
    LaunchedEffect(selectedStop?.id) {
        if (selectedStop == null) {
            scaffoldState.bottomSheetState.partialExpand()
        } else {
            scaffoldState.bottomSheetState.partialExpand()
            cameraPositionState.animate(
                com.google.android.gms.maps.CameraUpdateFactory.newLatLng(
                    LatLng(selectedStop.lat, selectedStop.lon),
                )
            )
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Dismiss search overlay on system back button
    BackHandler(enabled = uiState.searchActive) { viewModel.setSearchActive(false) }

    // Auto-focus search field when activated; clear focus when deactivated
    // Clearing focus is critical — without it the BasicTextField retains focus after
    // selectRoute() calls setSearchActive(false), so onFocusChanged never re-fires
    // and the bar appears broken on the next tap.
    LaunchedEffect(uiState.searchActive) {
        if (uiState.searchActive) {
            kotlinx.coroutines.delay(50)
            searchFocusRequester.requestFocus()
        } else {
            focusManager.clearFocus(force = true)
        }
    }

    // Fly to user location — only the first time (auto-center), not on every back-navigation.
    // Also fires when the My Location FAB is explicitly tapped (forceCenterOnLocation=true),
    // which bypasses the autoDetectRegion guard.
    LaunchedEffect(uiState.userLocation) {
        val canAutoCenter = !viewModel.hasCenteredOnLocation
            && (uiState.autoDetectRegion || viewModel.forceCenterOnLocation)
            && uiState.pendingCameraCenter == null
        if (canAutoCenter) {
            uiState.userLocation?.let { loc ->
                cameraPositionState.animate(
                    com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
                        LatLng(loc.latitude, loc.longitude), 14f,
                    ),
                )
                viewModel.markCameracentered()
            }
        }
    }

    // Fly to a manually selected region (Settings → pick region → back to map).
    // If the screen re-enters composition with pendingCameraCenter already set the
    // initial position is already correct (see above), but we still animate for the
    // case where the region changes while the map is live. The try-catch ensures
    // clearPendingCameraCenter / loadStopsForLocation always run even if the map
    // isn't fully attached yet and animate() throws.
    LaunchedEffect(uiState.pendingCameraCenter) {
        uiState.pendingCameraCenter?.let { (lat, lon) ->
            try {
                cameraPositionState.animate(
                    com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
                        LatLng(lat, lon), 12f,
                    ),
                )
            } catch (_: Exception) { /* map not yet attached — initial position already set */ }
            viewModel.markCameracentered()
            viewModel.clearPendingCameraCenter()
            // Explicitly reload stops at the new region centre so we don't rely on the
            // camera-idle LaunchedEffect which may fire with the OLD position first.
            viewModel.loadStopsForLocation(lat, lon)
        }
    }

    // Pan to show both the selected stop and the live bus when an arrival row is tapped
    LaunchedEffect(uiState.focusedVehicle?.vehicleId) {
        val v = uiState.focusedVehicle ?: return@LaunchedEffect
        val vLat = v.vehicleLat ?: return@LaunchedEffect
        val vLon = v.vehicleLon ?: return@LaunchedEffect
        if (vLat == 0.0 && vLon == 0.0) return@LaunchedEffect

        val busLatLng  = LatLng(vLat, vLon)
        val stop       = uiState.selectedStop
        val stopLatLng = if (stop != null && (stop.lat != 0.0 || stop.lon != 0.0))
            LatLng(stop.lat, stop.lon)
        else
            null

        val cameraUpdate = if (stopLatLng != null) {
            // Build a bounds that contains both the bus and the stop, with generous
            // padding so the surrounding route is visible and the view isn't too tight
            val bounds = LatLngBounds.Builder()
                .include(busLatLng)
                .include(stopLatLng)
                .build()
            com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(bounds, 280)
        } else {
            // No stop known — fall back to a moderate zoom so route context is still visible
            com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(busLatLng, 14f)
        }
        cameraPositionState.animate(cameraUpdate)
    }

    // When a route is highlighted, pan + zoom to fit the full polyline
    LaunchedEffect(uiState.routeHighlightShape) {
        val shape = uiState.routeHighlightShape
        if (shape.size < 2) return@LaunchedEffect
        val boundsBuilder = LatLngBounds.Builder()
        shape.forEach { boundsBuilder.include(LatLng(it.lat, it.lon)) }
        cameraPositionState.animate(
            com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(
                boundsBuilder.build(), 80,
            ),
        )
    }

    // peekHeight breakdown when a stop is selected:
    //   drag handle   : ~16dp
    //   stop header   : ~80dp  (name + code + route count + padding)
    //   arrivals hdr  : ~40dp
    //   divider       :   1dp
    //   one ArrivalRow: ~92dp  (min-height; a row with via + sched line can reach ~160dp)
    //   buffer        : ~70dp
    //   total (normal): 300dp + nav bar inset
    //   total (pinned): 520dp + nav bar inset  (generous for pinned row with secondary/via line)
    val navBarInset      = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val handleBarHeight  = 52.dp
    val headerRowHeight  = 80.dp  // 12dp pad-top + headlineMedium + sub-labels + 12dp pad-bottom
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomNavBar(
                selected = 0,
                onSelect = { idx ->
                    when (idx) {
                        1 -> navController.navigate(Routes.PLAN_PLAIN) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                        2 -> navController.navigate(Routes.SAVED) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                        3 -> navController.navigate(Routes.MORE) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                    }
                },
            )
        },
    ) { innerPadding ->
        val peekHeight = when {
            selectedStop == null                -> handleBarHeight
            uiState.pinnedTripVehicleId != null -> navBarInset + 330.dp
            else                               -> navBarInset + 300.dp
        }
        Box(Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetDragHandle = {
                // Bounce the pill down once on first appearance to hint "drag me up"
                val dragHint = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(selectedStop?.id) {
                    if (selectedStop != null) {
                        delay(500)
                        dragHint.animateTo(6f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
                        dragHint.animateTo(0f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .offset(y = dragHint.value.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            },
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContent = {
                Crossfade(
                    targetState = selectedStop,
                    animationSpec = tween(220),
                    label = "stopSheet",
                ) { sheetStop ->
                if (sheetStop != null) {
                    StopBottomSheet(
                        stop = sheetStop,
                        arrivals = uiState.arrivals,
                        loading = uiState.arrivalsLoading,
                        lastUpdated = uiState.arrivalsLastUpdated,
                        isSaved = savedStops.any { it.id == sheetStop.id },
                        focusedVehicleId = uiState.focusedVehicle?.vehicleId,
                        pinnedTripVehicleId = uiState.pinnedTripVehicleId,
                        extraBottomPadding = innerPadding.calculateBottomPadding(),
                        onToggleSave = {
                            val wasSaved = savedStops.any { it.id == sheetStop.id }
                            viewModel.toggleSaved(sheetStop)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (wasSaved) stopRemovedText else stopSavedText,
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        },
                        onViewDetails = {
                            navController.navigate(
                                Routes.stopDetails(sheetStop.id, sheetStop.name, sheetStop.code),
                            )
                        },
                        onDismiss = { viewModel.clearSelectedStop() },

                        onUnpinTrip = { viewModel.unpinTrip() },
                        onCenterOnStop = {
                            scope.launch {
                                scaffoldState.bottomSheetState.partialExpand()
                                cameraPositionState.animate(
                                    com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
                                        com.google.android.gms.maps.model.LatLng(sheetStop.lat, sheetStop.lon),
                                        cameraPositionState.position.zoom.coerceAtLeast(16f),
                                    ),
                                )
                            }
                        },
                        sidecarEnabled = uiState.sidecarEnabled,
                        activeReminders = activeReminders,
                        onArrivalReminder = { arrival ->
                            if (uiState.sidecarEnabled) viewModel.openReminderSheet(arrival)
                        },
                        onArrivalFocus = { arrival ->
                            viewModel.focusVehicle(arrival)
                        },
                        onArrivalDetails = { arrival ->
                            navController.navigate(
                                Routes.routeDetails(
                                    tripId = arrival.tripId,
                                    routeId = arrival.routeId,
                                    routeShort = arrival.routeShortName,
                                    routeLong = arrival.routeLongName,
                                    headsign = arrival.tripHeadsign,
                                    stopId = sheetStop.id,
                                ),
                            )
                        },

                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            stringResource(R.string.map_tap_to_see_arrivals),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                } // end Crossfade
            },
        ) {
            // Track the content box height so we can offset the FAB above the live sheet position
            var contentHeightPx by remember { mutableIntStateOf(0) }
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding).onSizeChanged { contentHeightPx = it.height }) {

                // ── Map ──────────────────────────────────────────────────────────────
        LaunchedEffect(cameraPositionState.isMoving) {
            if (!cameraPositionState.isMoving) {
                val pos = cameraPositionState.position
                val center = pos.target
                viewModel.loadStopsForLocation(center.latitude, center.longitude, pos.zoom)
                // Persist so the position is restored if the user navigates to another tab
                viewModel.saveCameraPosition(center.latitude, center.longitude, pos.zoom)
                // Lazily load route coverage lines when zoomed out
                if (pos.zoom < 13f) viewModel.loadOverviewRoutes()
            }
        }

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = uiState.locationPermissionGranted),
            uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = false),
            onMapClick = {
                viewModel.clearFocusedVehicle()
                viewModel.clearSelectedStop()
            },
        ) {
            val mapContext = LocalContext.current
            val currentZoom = cameraPositionState.position.zoom
            // Below zoom 13 the map is too far out for stop markers to be useful;
            // suppress them entirely to avoid a cluttered pin wall.
            val stopsVisible = currentZoom >= 13f

            // ── Overview route coverage lines (zoom < 13, no active highlight) ───────
            // Thin translucent polylines for every route in the region, each with
            // a small route-name chip at its midpoint so commuters can identify routes.
            if (!stopsVisible && uiState.routeHighlight == null && uiState.overviewRoutes.isNotEmpty()) {
                uiState.overviewRoutes.forEach { route ->
                    Polyline(
                        points  = route.points.map { LatLng(it.lat, it.lon) },
                        color   = Primary.copy(alpha = 0.30f),
                        width   = 5f,
                        zIndex  = 0f,
                    )
                    // Label chip at the midpoint of the polyline
                    if (route.shortName.isNotBlank()) {
                        val mid = route.points[route.points.size / 2]
                        val chipIcon = remember(route.shortName) {
                            createRouteLabelBitmap(mapContext, route.shortName)
                        }
                        Marker(
                            state  = MarkerState(LatLng(mid.lat, mid.lon)),
                            icon   = chipIcon,
                            anchor = Offset(0.5f, 0.5f),
                            flat   = true,
                            zIndex = 1f,
                            onClick = { false }, // non-interactive
                        )
                    }
                }
            }
            // When a route is highlighted, only show stops that serve that route
            val visibleStops = if (!stopsVisible) emptyList()
                else uiState.routeHighlight
                    ?.let { r -> uiState.stops.filter { s -> s.routeIds.contains(r.id) } }
                    ?: uiState.stops
            // If the selected stop is not in the visible list (e.g. deep-linked
            // from Reminders before the map has loaded stops for that area),
            // render it as a synthetic selected marker so it always appears.
            val selectedStopForMarker = uiState.selectedStop?.takeIf { sel ->
                sel.lat != 0.0 || sel.lon != 0.0
            }
            if (selectedStopForMarker != null &&
                visibleStops.none { it.id == selectedStopForMarker.id }) {
                val icon = remember(selectedStopForMarker.id, selectedStopForMarker.transitType) {
                    createStopMarkerBitmap(mapContext, true, selectedStopForMarker.transitType)
                }
                Marker(
                    state = MarkerState(LatLng(selectedStopForMarker.lat, selectedStopForMarker.lon)),
                    title = selectedStopForMarker.name,
                    icon = icon,
                    onClick = {
                        viewModel.selectStop(selectedStopForMarker)
                        true
                    },
                )
            }
            visibleStops.forEach { stop ->
                val isSelected = stop.id == uiState.selectedStop?.id
                val icon = remember(isSelected, stop.transitType) {
                    createStopMarkerBitmap(mapContext, isSelected, stop.transitType)
                }
                Marker(
                    state = MarkerState(LatLng(stop.lat, stop.lon)),
                    title = stop.name,
                    icon = icon,
                    onClick = {
                        viewModel.selectStop(stop)
                        true // consume — prevents default info window
                    },
                )
            }

            // ── Bus vehicle markers — teardrop, color-coded by status ──────
            // When an arrival is focused, only show buses on the same route to
            // avoid confusion. Clears back to all markers when nothing is focused.
            val visibleArrivals = uiState.focusedVehicle
                ?.let { fv -> uiState.arrivals.filter { it.routeId == fv.routeId } }
                ?: uiState.arrivals
            visibleArrivals.forEach { arrival ->
                val vLat = arrival.vehicleLat ?: return@forEach
                val vLon = arrival.vehicleLon ?: return@forEach
                if (vLat == 0.0 && vLon == 0.0) return@forEach
                key(arrival.vehicleId ?: "${arrival.routeId}_${arrival.tripId}") {
                    val statusColor = when (arrival.status) {
                        ArrivalStatus.ON_TIME   -> VehicleMarkerFactory.COLOR_ON_TIME
                        ArrivalStatus.DELAYED   -> VehicleMarkerFactory.COLOR_DELAYED
                        ArrivalStatus.EARLY     -> VehicleMarkerFactory.COLOR_EARLY
                        else                    -> VehicleMarkerFactory.COLOR_SCHEDULED
                    }
                    val statusLabel = when (arrival.status) {
                        ArrivalStatus.ON_TIME   -> stringResource(R.string.status_on_time)
                        ArrivalStatus.DELAYED   -> stringResource(R.string.status_delayed)
                        ArrivalStatus.EARLY     -> stringResource(R.string.status_early)
                        else                    -> stringResource(R.string.status_scheduled)
                    }
                    val isFocused = uiState.focusedVehicle?.let { fv ->
                        (fv.vehicleId != null && fv.vehicleId == arrival.vehicleId) ||
                            fv.tripId == arrival.tripId
                    } ?: false
                    val isPinnedMarker = arrival.vehicleId != null &&
                        arrival.vehicleId == uiState.pinnedTripVehicleId
                    val markerResult = remember(
                        arrival.status,
                        arrival.vehicleOrientation,
                        arrival.vehicleLastUpdateTime,
                        arrival.predicted,
                        isFocused,
                        isPinnedMarker,
                    ) {
                        VehicleMarkerFactory.get(
                            context        = mapContext,
                            statusColor    = statusColor,
                            orientationDeg = arrival.vehicleOrientation,
                            routeShortName = arrival.routeShortName,
                            lastUpdateMs   = arrival.vehicleLastUpdateTime,
                            isPredicted    = arrival.predicted,
                            statusLabel    = statusLabel,
                            showLabel      = isFocused || isPinnedMarker,
                            isPinned       = isPinnedMarker,
                            transitType    = arrival.transitType,
                        )
                    }
                    Marker(
                        state  = MarkerState(LatLng(vLat, vLon)),
                        icon   = markerResult.descriptor,
                        anchor = Offset(0.5f, markerResult.anchorV),
                        onClick = {
                            viewModel.focusVehicle(arrival)
                            // Only expand the sheet if a trip isn't already selected/visible
                            if (scaffoldState.bottomSheetState.currentValue != SheetValue.Expanded) {
                                scope.launch { scaffoldState.bottomSheetState.expand() }
                            }
                            true // label is drawn on the marker bitmap; suppress the floating info window
                        },
                    )
                }
            }

            if (uiState.routeShape.size >= 2) {
                val routeColor = when (uiState.selectedArrivalStatus) {
                    ArrivalStatus.ON_TIME   -> StatusOnTime
                    ArrivalStatus.DELAYED   -> StatusDelayed
                    ArrivalStatus.EARLY     -> StatusEarly
                    ArrivalStatus.SCHEDULED -> StatusScheduled
                    else                    -> Blue600
                }
                // White halo underneath for contrast against any map tile
                Polyline(
                    points = uiState.routeShape.map { LatLng(it.lat, it.lon) },
                    color = Color.White,
                    width = 18f,
                )
                Polyline(
                    points = uiState.routeShape.map { LatLng(it.lat, it.lon) },
                    color = routeColor,
                    width = 10f,
                )
            }

            // ── Route highlight polyline (from "Where to?" route search) ──────────────
            if (uiState.routeHighlightShape.size >= 2) {
                Polyline(
                    points = uiState.routeHighlightShape.map { LatLng(it.lat, it.lon) },
                    color = Color.White,
                    width = 20f,
                )
                Polyline(
                    points = uiState.routeHighlightShape.map { LatLng(it.lat, it.lon) },
                    color = Primary,
                    width = 12f,
                )
            }

            // ── Live bus markers for the highlighted route ────────────────────────
            if (uiState.routeHighlight != null) {
                uiState.routeHighlightVehicles.forEach { arrival ->
                    val vLat = arrival.vehicleLat ?: return@forEach
                    val vLon = arrival.vehicleLon ?: return@forEach
                    if (vLat == 0.0 && vLon == 0.0) return@forEach
                    key("hl_${arrival.vehicleId?.ifEmpty { arrival.tripId } ?: arrival.tripId}") {
                        val statusColor = when (arrival.status) {
                            ArrivalStatus.ON_TIME   -> VehicleMarkerFactory.COLOR_ON_TIME
                            ArrivalStatus.DELAYED   -> VehicleMarkerFactory.COLOR_DELAYED
                            ArrivalStatus.EARLY     -> VehicleMarkerFactory.COLOR_EARLY
                            else                    -> VehicleMarkerFactory.COLOR_SCHEDULED
                        }
                        val statusLabel = when (arrival.status) {
                            ArrivalStatus.ON_TIME   -> stringResource(R.string.status_on_time)
                            ArrivalStatus.DELAYED   -> stringResource(R.string.status_delayed)
                            ArrivalStatus.EARLY     -> stringResource(R.string.status_early)
                            else                    -> stringResource(R.string.status_scheduled)
                        }
                        val markerResult = remember(
                            arrival.status,
                            arrival.vehicleOrientation,
                            arrival.vehicleLastUpdateTime,
                            arrival.predicted,
                        ) {
                            VehicleMarkerFactory.get(
                                context        = mapContext,
                                statusColor    = statusColor,
                                orientationDeg = arrival.vehicleOrientation,
                                routeShortName = arrival.routeShortName,
                                lastUpdateMs   = arrival.vehicleLastUpdateTime,
                                isPredicted    = arrival.predicted,
                                statusLabel    = statusLabel,
                                showLabel      = false,
                            )
                        }
                        Marker(
                            state  = MarkerState(LatLng(vLat, vLon)),
                            title  = "${arrival.routeShortName} — ${arrival.tripHeadsign}",
                            icon   = markerResult.descriptor,
                            anchor = Offset(0.5f, markerResult.anchorV),
                            zIndex = 2f,
                        )
                    }
                }
            }
        }

        // ── Loading bar ───────────────────────────────────────────────────────
        if (uiState.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }

        // ── Location error snackbar ───────────────────────────────────────────
        LaunchedEffect(uiState.locationError) {
            val err = uiState.locationError
            if (err != null) {
                snackbarHostState.showSnackbar(err.resolve(context), duration = SnackbarDuration.Short)
                viewModel.clearLocationError()
            }
        }

        // ── Map controls — traffic toggle stacked above My Location FAB ─────
        // Both buttons track the sheet's live drag offset so they're never covered.
        val fabDensity = LocalDensity.current
        val sheetOffsetPx by remember { derivedStateOf {
            try { scaffoldState.bottomSheetState.requireOffset() }
            catch (_: IllegalStateException) { contentHeightPx.toFloat() }
        }}
        val fabBottomPadding = with(fabDensity) {
            (contentHeightPx - sheetOffsetPx).toDp().coerceAtLeast(handleBarHeight)
        } + 16.dp
        val fabContext = LocalContext.current

        // Traffic toggle sits 8dp above the My Location FAB
        // Both are in one Column so they share the same right edge automatically.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Notification bell — with unread badge
            Box {
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(2.dp, RoundedCornerShape(10.dp))
                        .clickable { navController.navigate(Routes.NOTIFICATIONS) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (unreadNotifCount > 0)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, if (unreadNotifCount > 0)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            if (unreadNotifCount > 0) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                            contentDescription = stringResource(R.string.map_notifications_cd),
                            tint = if (unreadNotifCount > 0)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (unreadNotifCount > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                            .size(16.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.error,
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                if (unreadNotifCount > 9) "9+" else unreadNotifCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            // My Location button — same 40dp Surface so sizes match exactly
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(2.dp, RoundedCornerShape(10.dp))
                    .clickable {
                        val granted = ContextCompat.checkSelfPermission(
                            fabContext, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            viewModel.fetchUserLocation(recenterCamera = true)
                        } else {
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    locationPermText,
                                    actionLabel = settingsText,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", fabContext.packageName, null)
                                    }
                                    fabContext.startActivity(intent)
                                }
                            }
                        }
                    },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (uiState.isLocatingUser) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = stringResource(R.string.map_my_location_cd),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        } // close content Box
        } // close BottomSheetScaffold content

        // ── Search scrim — tap-outside-to-dismiss overlay ─────────────────
        if (uiState.searchActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                        viewModel.setSearchActive(false)
                    },
            )
        }

        // ── "Where to?" search bar ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = if (uiState.searchActive) 6.dp else 3.dp, shape = RoundedCornerShape(12.dp))
                    .clickable { viewModel.setSearchActive(true) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            ) {
                Row(
                    modifier = Modifier
                        .height(48.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (uiState.searchActive) {
                        IconButton(
                            onClick = { viewModel.setSearchActive(false) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.map_close_search_cd), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    BasicTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocusRequester)
                            .onFocusChanged { if (it.isFocused) viewModel.setSearchActive(true) },
                        decorationBox = { inner ->
                            if (uiState.searchQuery.isEmpty()) {
                                Text(
                                    if (isListening) stringResource(R.string.map_listening) else stringResource(R.string.map_where_to),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isListening) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                    if (uiState.searchLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    } else if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        // Mic button — pulsing red while listening, primary tint at rest
                        val pulse by rememberInfiniteTransition(label = "micPulse").animateFloat(
                            initialValue = 1f,
                            targetValue  = 1.25f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "scale",
                        )
                        IconButton(
                            onClick = { launchVoiceSearch() },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                if (isListening) Icons.Default.MicNone else Icons.Default.Mic,
                                contentDescription = stringResource(R.string.map_voice_search_cd),
                                tint  = if (isListening) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .then(if (isListening) Modifier.scale(pulse) else Modifier),
                            )
                        }
                    }
                }
            }

            // ── Region pill — shown below search bar when not searching ──────
            if (!uiState.searchActive) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .shadow(2.dp, RoundedCornerShape(50))
                        .clickable { navController.navigate(Routes.SETTINGS) },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (uiState.autoDetectRegion) Icons.Default.MyLocation else Icons.Default.Map,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = uiState.activeRegion?.regionName ?: "Settings",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(R.string.map_switch_region_cd),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Results dropdown ─────────────────────────────────────────────
            if (uiState.searchActive) {
                Spacer(Modifier.height(4.dp))
                // Cap the dropdown height so it never slides under the keyboard.
                // WindowInsets.ime gives us the live keyboard height; we subtract it
                // (plus the search bar + status bar area) from the screen height.
                val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                val screenH = LocalConfiguration.current.screenHeightDp.dp
                // Clamp the dropdown so it never overlaps the arrivals peek sheet.
                // peekHeight already includes the nav bar inset.
                val maxDropdownHeight = (screenH - 170.dp - imeBottom - peekHeight).coerceIn(80.dp, 480.dp)
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxDropdownHeight),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        if (uiState.searchQuery.isEmpty()) {
                            // Empty state — quick actions
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.map_plan_full_trip), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text(stringResource(R.string.map_plan_full_trip_subtitle)) },
                                leadingContent = { Icon(Icons.Default.Directions, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { viewModel.setSearchActive(false); navController.navigate(Routes.PLAN_PLAIN) },
                            )
                        } else {
                            val stopResults  = uiState.searchResults.filterIsInstance<HomeSearchResult.StopResult>()
                            val routeResults = uiState.searchResults.filterIsInstance<HomeSearchResult.RouteResult>()
                            val destResults  = uiState.searchResults.filterIsInstance<HomeSearchResult.DestResult>()

                            // ── Routes section ───────────────────────────────
                            if (routeResults.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.map_section_routes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                routeResults.forEach { result ->
                                    val route = result.route
                                    val displayName = when {
                                        route.shortName.isNotBlank() && route.longName.isNotBlank() ->
                                            stringResource(R.string.map_route_label_full, route.shortName, route.longName)
                                        route.shortName.isNotBlank() -> stringResource(R.string.map_route_label_short, route.shortName)
                                        route.longName.isNotBlank()  -> route.longName
                                        else                         -> route.id
                                    }
                                    ListItem(
                                        headlineContent = {
                                            Text(displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        },
                                        supportingContent = if (route.description.isNotBlank()) ({
                                            Text(route.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }) else null,
                                        leadingContent = {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.primary),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    route.shortName.take(4).ifBlank { "BUS" },
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                )
                                            }
                                        },
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                Text(stringResource(R.string.action_show), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        modifier = Modifier.clickable { viewModel.selectRoute(route) },
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
                                }
                            }

                            // ── Nearby stops section ─────────────────────────
                            if (stopResults.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.map_nearby_stops),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                stopResults.forEach { result ->
                                    val stop = result.stop
                                    ListItem(
                                        headlineContent = { Text(stop.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
                                        supportingContent = { Text(pluralStringResource(R.plurals.map_stop_route_count, stop.routeIds.size, stop.routeIds.size), style = MaterialTheme.typography.bodySmall) },
                                        leadingContent = {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            viewModel.setSearchActive(false)
                                            viewModel.selectStop(stop)
                                        },
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
                                }
                            }

                            // ── Places / destinations section ─────────────────
                            if (destResults.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.map_section_destinations),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                destResults.forEach { result ->
                                    val place = result.place
                                    ListItem(
                                        headlineContent = { Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
                                        supportingContent = if (place.address.isNotBlank()) ({ Text(place.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
                                        leadingContent = {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Icon(Icons.Default.DirectionsTransit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                Text(stringResource(R.string.action_plan), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            viewModel.setSearchActive(false)
                                            navController.navigate(Routes.planWithDest(place.label, place.lat, place.lon))
                                        },
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
                                }
                            }

                            if (uiState.searchResults.isEmpty() && !uiState.searchLoading) {
                                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.map_no_results, uiState.searchQuery), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // ── Active route filter chip ─────────────────────────────────────
            val routeHighlight = uiState.routeHighlight
            if (routeHighlight != null && !uiState.searchActive) {
                Spacer(Modifier.height(6.dp))
                val label = if (routeHighlight.longName.isNotBlank()) {
                    stringResource(R.string.map_route_label_full, routeHighlight.shortName, routeHighlight.longName)
                } else if (routeHighlight.shortName.isNotBlank()) {
                    stringResource(R.string.map_route_label_short, routeHighlight.shortName)
                } else ""
                FilterChip(
                    selected = true,
                    onClick = { viewModel.clearRouteHighlight() },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.map_clear_route_filter_cd), modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor     = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }

            // ── Suggested region banner ──────────────────────────────────────
            val suggestedRegion = uiState.suggestedRegion
            if (suggestedRegion != null && !uiState.searchActive) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.map_region_suggestion, suggestedRegion.regionName),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(
                            onClick = { viewModel.acceptRegionSuggestion() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                stringResource(R.string.map_region_suggestion_switch),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.dismissRegionSuggestion() },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }

        } // close outer Box
    }

    // ── Reminder bottom sheet (long-press on an arrival row) ─────────────────
    if (uiState.reminderSheetArrival != null && uiState.sidecarEnabled) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val arrival = uiState.reminderSheetArrival!!
        val hasReminder = activeReminders.any { it.tripId == arrival.tripId }
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
                // ── Header ───────────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AnimatedContent(
                        targetState = hasReminder,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.6f) + fadeIn(tween(200)))
                                .togetherWith(scaleOut(targetScale = 0.6f) + fadeOut(tween(150)))
                        },
                        label = "reminderBellSwap",
                    ) { isSet ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSet) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.secondaryContainer
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (isSet) Icons.Default.NotificationsActive
                                else Icons.Default.NotificationsNone,
                                contentDescription = null,
                                tint = if (isSet) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (hasReminder) stringResource(R.string.reminder_set_snack) else stringResource(R.string.reminder_set_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${arrival.routeShortName} \u2192 ${arrival.tripHeadsign.ifBlank { arrival.routeLongName }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                if (hasReminder) {
                    // ── Active reminder info card ─────────────────────────────
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
                    // ── Set flow ─────────────────────────────────────────────
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
                                val notifyAtMs2 = arrivalEpochMs - mins * 60_000L
                                val notifyAtLabel = timeFmt.format(java.util.Date(notifyAtMs2))
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
}

// ── Mini card — shown when sheet is at peek height (partially expanded) ───────
@Composable
private fun StopMiniCard(
    stop: ObaStop,
    arrivals: List<ObaArrival>,
    loading: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveCount      = arrivals.count { it.status != ArrivalStatus.SCHEDULED }
    val nextArrival    = arrivals.firstOrNull()
    val nextMinutes    = nextArrival?.liveMinutesUntilArrival()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Left: stop name + code
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stop.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (stop.code.isNotBlank()) {
                Text(
                    "#${stop.code}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            // Arrival summary chip
            when {
                loading -> Text(
                    stringResource(R.string.route_loading_trip),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                arrivals.isEmpty() -> Text(
                    stringResource(R.string.route_no_upcoming),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val chipLabel = if (liveCount > 0) pluralStringResource(R.plurals.map_live_services, liveCount, liveCount) else stringResource(R.string.chip_timetable_only)
                    val chipColor = if (liveCount > 0) MaterialTheme.colorScheme.primaryContainer
                                   else MaterialTheme.colorScheme.surfaceContainerHigh
                    val chipText  = if (liveCount > 0) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(shape = RoundedCornerShape(20.dp), color = chipColor) {
                        Text(
                            chipLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = chipText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        // Right: next bus countdown
        if (nextMinutes != null && !loading) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    nextMinutes <= 0 -> Text(
                        stringResource(R.string.status_now),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    else -> {
                        Text(
                            "$nextMinutes",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.arrival_min_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
        }

        // Close button
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.action_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun StopBottomSheet(
    stop: ObaStop,
    arrivals: List<ObaArrival>,
    loading: Boolean,
    lastUpdated: Long? = null,
    isSaved: Boolean = false,
    focusedVehicleId: String? = null,
    pinnedTripVehicleId: String? = null,
    extraBottomPadding: Dp = 0.dp,
    sidecarEnabled: Boolean = false,
    activeReminders: List<ActiveReminder> = emptyList(),
    onToggleSave: () -> Unit = {},
    onViewDetails: () -> Unit,
    onDismiss: () -> Unit,
    onCenterOnStop: () -> Unit = {},
    onArrivalFocus: (ObaArrival) -> Unit,
    onArrivalDetails: (ObaArrival) -> Unit,
    onArrivalReminder: (ObaArrival) -> Unit = {},
    onUnpinTrip: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = extraBottomPadding),
    ) {
        // ── Header ─────────────────────────────────────────────────────────
        val headerAgencyLabel = remember(arrivals) {
            arrivals.map { it.agencyName }.filter { it.isNotBlank() }.distinct()
                .joinToString(" · ").takeIf { it.isNotBlank() }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stop.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (stop.code.isNotBlank()) {
                    Text(
                        "#${stop.code}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stop.routeIds.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.map_stop_route_count, stop.routeIds.size, stop.routeIds.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (headerAgencyLabel != null) {
                    Text(
                        headerAgencyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Centre map on this stop
            IconButton(onClick = onCenterOnStop) {
                Icon(
                    Icons.Default.Room,
                    contentDescription = stringResource(R.string.map_centre_on_stop_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Bookmark / save toggle
            IconButton(onClick = onToggleSave) {
                Icon(
                    if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (isSaved) stringResource(R.string.stop_remove_saved) else stringResource(R.string.stop_save),
                    tint = if (isSaved) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Arrivals section ───────────────────────────────────────────────
        Column {
        // Tick every second so the "updated X ago" label stays current
        var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(1_000); nowMs = System.currentTimeMillis() } }
        val updatedLabel: String? = lastUpdated?.let { ts ->
            val elapsed = ((nowMs - ts) / 1000L).coerceAtLeast(0L).toInt()
            val m = elapsed / 60; val s = elapsed % 60
            when {
                elapsed < 5  -> stringResource(R.string.map_just_updated)
                m == 0       -> stringResource(R.string.map_updated_s, s)
                s == 0       -> stringResource(R.string.map_updated_m, m)
                else         -> stringResource(R.string.map_updated_ms, m, s)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.map_arrivals),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Arrival count summary chip — visible in peek state so the user
            // immediately knows how many services are coming
            if (!loading && arrivals.isNotEmpty()) {
                val liveCount = arrivals.count { it.status != ArrivalStatus.SCHEDULED }
                val label = if (liveCount > 0)
                    stringResource(R.string.map_live_count, liveCount)
                else
                    stringResource(R.string.map_scheduled_count, arrivals.size)
                val chipColor = if (liveCount > 0)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh
                val chipText = if (liveCount > 0)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = chipColor,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = chipText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            if (updatedLabel != null) {
                Text(
                    updatedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // P5 – shrinking freshness bar: full at t=0, empty at t=30s
        if (lastUpdated != null) {
            val elapsed30 = ((nowMs - lastUpdated) / 1000f).coerceIn(0f, 30f)
            val freshness = 1f - elapsed30 / 30f
            val freshnessColor by animateColorAsState(
                targetValue = when {
                    freshness > 0.5f -> MaterialTheme.colorScheme.primary
                    freshness > 0.2f -> MaterialTheme.colorScheme.tertiary
                    else             -> MaterialTheme.colorScheme.error
                },
                animationSpec = tween(600),
                label = "freshnessColor",
            )
            LinearProgressIndicator(
                progress = { freshness },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = freshnessColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        } else {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (loading && arrivals.isEmpty()) {
            // P4 – shimmer skeleton while first load
            repeat(3) { i ->
                val shimmerAlpha by rememberInfiniteTransition(label = "shimmer$i").animateFloat(
                    initialValue = 0.15f, targetValue = 0.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900 + i * 120, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(i * 160),
                    ),
                    label = "shimmerAlpha$i",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = shimmerAlpha)),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = shimmerAlpha)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.4f)
                                .height(11.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = shimmerAlpha * 0.7f)),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = shimmerAlpha)),
                    )
                }
            }
        } else if (!loading && arrivals.isEmpty()) {
            val emptyFloat by rememberInfiniteTransition(label = "emptyFloat").animateFloat(
                initialValue = 0f, targetValue = -7f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "emptyBusFloat",
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(40.dp).offset(y = emptyFloat.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.route_no_services),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val liveArrivals = arrivals.filter { it.status != ArrivalStatus.SCHEDULED }
            val scheduledOnly = arrivals.filter { it.status == ArrivalStatus.SCHEDULED }
            val hasLive = liveArrivals.isNotEmpty()

            if (!hasLive && scheduledOnly.isNotEmpty() && pinnedTripVehicleId == null) {
                // No live vehicles tracked — show a timetable-mode nudge before the scheduled list
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.map_timetable_mode),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.route_no_live_vehicles),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Sort: pinned trip always first, then live trips by time, then scheduled by time,
            // departed trips last. This means pinning a scheduled trip still shows live arrivals
            // immediately after it rather than burying them below other scheduled trips.
            val sortedArrivals = arrivals.sortedWith(
                compareBy(
                    // pinned trip stays at top
                    { if (pinnedTripVehicleId != null && it.vehicleId == pinnedTripVehicleId) 0 else 1 },
                    // departed (< 0) sink to bottom
                    { if (it.liveMinutesUntilArrival() < 0) 1 else 0 },
                    // live (predicted) trips before scheduled trips
                    { if (it.predicted) 0 else 1 },
                    // within each group, sort by time ascending
                    { it.liveMinutesUntilArrival() },
                ),
            )

            // Reset to 3 whenever the user switches to a different stop
            var visibleCount by remember(stop.id) { mutableIntStateOf(3) }
            val allVisible = visibleCount >= sortedArrivals.size
            val remaining  = (sortedArrivals.size - visibleCount).coerceAtLeast(0)
            val listState = rememberLazyListState()
            // Adaptive list height: fills ~45% of screen height so the expanded sheet
            // makes proper use of the available space instead of capping at 280 dp.
            val screenHeightDp = LocalConfiguration.current.screenHeightDp
            val listMaxHeight = (screenHeightDp * 0.45f).dp

            // Auto-focus the fastest arrival the first time arrivals load for this stop
            var autoFocused by remember(stop.id) { mutableStateOf(false) }
            LaunchedEffect(arrivals) {
                if (!autoFocused && arrivals.isNotEmpty() && pinnedTripVehicleId == null) {
                    sortedArrivals.firstOrNull()?.let { onArrivalFocus(it) }
                    autoFocused = true
                }
            }

            // Scroll to top when a trip is newly pinned (pinned trip is at index 0)
            LaunchedEffect(pinnedTripVehicleId) {
                if (pinnedTripVehicleId != null) {
                    visibleCount = 3
                    listState.animateScrollToItem(0)
                }
            }

            // Scroll to reveal the first newly loaded item
            LaunchedEffect(visibleCount) {
                if (visibleCount > 3) {
                    listState.animateScrollToItem((visibleCount - 3).coerceAtLeast(0))
                }
            }

            // ── Hero row: next upcoming service, pinned above the scroll list ─
            val heroArrival = remember(sortedArrivals) {
                sortedArrivals.firstOrNull { it.liveMinutesUntilArrival() >= 0 }
            }
            AnimatedContent(
                targetState = heroArrival,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label = "heroArrival",
            ) { nextBus ->
                if (nextBus != null) {
                    val heroStatusColor by animateColorAsState(
                        targetValue = when (nextBus.status) {
                            ArrivalStatus.ON_TIME -> StatusOnTime
                            ArrivalStatus.DELAYED -> StatusDelayed
                            ArrivalStatus.EARLY   -> StatusEarly
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(300),
                        label = "heroStatus",
                    )
                    val heroMins = nextBus.liveMinutesUntilArrival()
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { onArrivalFocus(nextBus) },
                        shape = RoundedCornerShape(16.dp),
                        color = heroStatusColor.copy(alpha = 0.08f),
                        border = BorderStroke(1.5.dp, heroStatusColor.copy(alpha = 0.4f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    stringResource(R.string.hero_next),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = heroStatusColor,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                )
                                Text(
                                    "${nextBus.routeShortName} · ${nextBus.tripHeadsign}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (nextBus.predicted) {
                                    Box(
                                        modifier = Modifier
                                            .padding(bottom = 6.dp)
                                            .size(7.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(heroStatusColor),
                                    )
                                }
                                if (heroMins == 0) {
                                    Text(
                                        stringResource(R.string.status_now),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = heroStatusColor,
                                    )
                                } else {
                                    Text(
                                        if (heroMins < 60) "$heroMins" else "${heroMins / 60}h ${heroMins % 60}",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = heroStatusColor,
                                    )
                                    if (heroMins < 60) {
                                        Text(
                                            stringResource(R.string.label_min),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = heroStatusColor.copy(alpha = 0.75f),
                                            modifier = Modifier.padding(bottom = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(0.dp))
                }
            }

            Box {
                val distinctAgencies = remember(arrivals) {
                    arrivals.map { it.agencyName }.filter { it.isNotBlank() }.distinct()
                }
                // Shared item renderer — used by both live and scheduled itemsIndexed blocks
                val renderItem: @Composable (Int, ObaArrival) -> Unit = { index, arrival ->
                    var rowVisible by remember(arrival.tripId) { mutableStateOf(false) }
                    LaunchedEffect(arrival.tripId) {
                        delay(index * 40L)
                        rowVisible = true
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = rowVisible,
                        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
                    ) {
                    val hasReminder = activeReminders.any { it.tripId == arrival.tripId }
                    // Swipe right → open reminder sheet
                    val haptic = LocalHapticFeedback.current
                    val swipeState = rememberSwipeToDismissBoxState(
                        // Allow StartToEnd (swipe to remind) AND Settled (reset() after trigger).
                        // EndToStart is disabled at SwipeToDismissBox level; block it here too.
                        confirmValueChange = { it != SwipeToDismissBoxValue.EndToStart },
                    )
                    LaunchedEffect(swipeState.currentValue) {
                        if (swipeState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onArrivalReminder(arrival)
                            kotlinx.coroutines.delay(250)
                            swipeState.reset()
                        }
                    }
                    SwipeToDismissBox(
                        state = swipeState,
                        enableDismissFromStartToEnd = sidecarEnabled,
                        enableDismissFromEndToStart = false,
                        backgroundContent = {
                            val revealed = swipeState.targetValue == SwipeToDismissBoxValue.StartToEnd
                            val bgAlpha by animateFloatAsState(
                                targetValue = if (revealed) 1f else 0f,
                                animationSpec = tween(200),
                                label = "swipeBgAlpha",
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = bgAlpha))
                                    .padding(start = 20.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        if (hasReminder) Icons.Default.NotificationsActive
                                        else Icons.Outlined.NotificationsNone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        if (hasReminder) stringResource(R.string.reminder_set_snack)
                                        else stringResource(R.string.reminder_set_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        },
                    ) {
                    ArrivalRow(
                        arrival = arrival,
                        isSelected = arrival.vehicleId != null
                            && arrival.vehicleId == focusedVehicleId,
                        isPinned = pinnedTripVehicleId != null
                            && arrival.vehicleId == pinnedTripVehicleId,
                        hasReminder = hasReminder,
                        sidecarEnabled = sidecarEnabled,
                        onClick = { onArrivalFocus(arrival) },
                        onLongClick = { onArrivalReminder(arrival) },
                        onViewDetails = { onArrivalDetails(arrival) },
                        onUnpin = if (pinnedTripVehicleId != null && arrival.vehicleId == pinnedTripVehicleId)
                            onUnpinTrip else null,
                        showAgencyLabel = distinctAgencies.size > 1,
                    )
                    } // end SwipeToDismissBox
                    } // end row AnimatedVisibility
                }

                val visible = sortedArrivals.take(visibleCount)
                val liveItems = visible.filter { it.predicted }
                val scheduledItems = visible.filter { !it.predicted }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = listMaxHeight),
                ) {
                    if (liveItems.isNotEmpty()) {
                        stickyHeader(key = "header_live") {
                            ArrivalSectionHeader(label = stringResource(R.string.section_live), count = liveItems.size)
                        }
                        itemsIndexed(
                            liveItems,
                            key = { _, a -> "live_${a.tripId}_${a.scheduledArrivalTime}" },
                        ) { index, arrival -> renderItem(index, arrival) }
                    }
                    if (scheduledItems.isNotEmpty()) {
                        stickyHeader(key = "header_scheduled") {
                            ArrivalSectionHeader(
                                label = stringResource(R.string.section_scheduled),
                                count = scheduledItems.size,
                                muted = liveItems.isNotEmpty(),
                            )
                        }
                        itemsIndexed(
                            scheduledItems,
                            key = { _, a -> "sched_${a.tripId}_${a.scheduledArrivalTime}" },
                        ) { index, arrival -> renderItem(liveItems.size + index, arrival) }
                    }
                }
                // Bottom-fade gradient — only shown while there are hidden rows
                if (!allVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.surface,
                                    ),
                                ),
                            ),
                    )
                }
            }

            // ── Show more / stop-info row ──────────────────────────────────
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!allVisible) {
                    TextButton(
                        onClick = { visibleCount = (visibleCount + 3).coerceAtMost(sortedArrivals.size) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.map_show_more, minOf(remaining, 3)),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    // All arrivals shown — subtle "all caught up" label
                    Text(
                        stringResource(R.string.map_all_arrivals_shown, sortedArrivals.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                // Compact link to the full stop details screen
                TextButton(
                    onClick = onViewDetails,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.map_stop_info),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = stringResource(R.string.map_open_stop_details_cd),
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        } // end Column
    }
}

@Composable
fun ArrivalRow(
    arrival: ObaArrival,
    isSelected: Boolean = false,
    isPinned: Boolean = false,
    hasReminder: Boolean = false,
    sidecarEnabled: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onViewDetails: () -> Unit,
    onUnpin: (() -> Unit)? = null,
    showAgencyLabel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val targetStatusColor = when (arrival.status) {
        ArrivalStatus.ON_TIME   -> StatusOnTime
        ArrivalStatus.DELAYED   -> StatusDelayed
        ArrivalStatus.EARLY     -> StatusEarly
        ArrivalStatus.SCHEDULED -> MaterialTheme.colorScheme.onSurfaceVariant
        ArrivalStatus.UNKNOWN   -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val minutes = arrival.liveMinutesUntilArrival()
    val missed = minutes < 0
    val headwayMins = arrival.headwaySecs?.let { it / 60 }

    // Headway (frequency-based) with no real-time prediction → always scheduled grey
    val targetBadgeColor = when {
        missed -> MaterialTheme.colorScheme.onSurfaceVariant
        arrival.isHeadway && !arrival.predicted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> targetStatusColor
    }
    val statusColor by animateColorAsState(targetValue = targetStatusColor, animationSpec = tween(300), label = "statusColor")
    val badgeColor by animateColorAsState(targetValue = targetBadgeColor, animationSpec = tween(300), label = "badgeColor")

    val untilFmt = stringResource(R.string.arrival_until)
    val headwayUntil = arrival.headwayEndTime?.let {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        String.format(untilFmt, fmt.format(java.util.Date(it)))
    } ?: ""
    val statusLabel = when {
        missed -> stringResource(R.string.status_already_departed)
        arrival.isHeadway && !arrival.predicted ->
            if (headwayMins != null) stringResource(R.string.status_headway_every, headwayMins ?: 0, headwayUntil) else null
        arrival.isHeadway && arrival.predicted ->
            when (arrival.status) {
                ArrivalStatus.ON_TIME -> stringResource(R.string.status_headway_on_time, headwayMins ?: 0, headwayUntil)
                ArrivalStatus.DELAYED -> stringResource(R.string.status_headway_delayed, headwayMins ?: 0, headwayUntil)
                ArrivalStatus.EARLY   -> stringResource(R.string.status_headway_early, headwayMins ?: 0, headwayUntil)
                else -> if (headwayMins != null) stringResource(R.string.status_headway_every, headwayMins ?: 0, headwayUntil) else null
            }
        arrival.status == ArrivalStatus.ON_TIME   -> stringResource(R.string.status_on_time)
        arrival.status == ArrivalStatus.DELAYED   -> stringResource(R.string.status_delayed)
        arrival.status == ArrivalStatus.EARLY     -> stringResource(R.string.status_early)
        arrival.status == ArrivalStatus.SCHEDULED -> null  // section header already says "Scheduled"
        else -> null
    }

    // For selected rows, background and border are driven by the bus status colour
    val selectionColor = if (missed) MaterialTheme.colorScheme.onSurfaceVariant else statusColor

    // Brand chip colours — derived from GTFS route colour metadata
    val brandChipBg: Color? = arrival.routeColor?.let {
        runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull()
    }
    val brandChipFg: Color? = arrival.routeTextColor?.let {
        runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull()
    } ?: brandChipBg?.let { bg ->
        val lum = bg.red * 0.299f + bg.green * 0.587f + bg.blue * 0.114f
        if (lum < 0.5f) Color.White else Color(0xFF1A1A1F.toInt())
    }

    // Live dot animation — pulsing when real-time data available
    val liveDotScale by if (arrival.predicted && !missed) {
        rememberInfiniteTransition(label = "liveDot").animateFloat(
            initialValue = 0.6f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "liveDotScale",
        )
    } else {
        remember { mutableStateOf(1.0f) }
    }
    val liveDotAlpha by if (arrival.predicted && !missed) {
        rememberInfiniteTransition(label = "liveDotAlpha").animateFloat(
            initialValue = 0.35f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "liveDotAlphaVal",
        )
    } else {
        remember { mutableStateOf(0.0f) }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected)
            selectionColor.copy(alpha = 0.10f)
        else
            MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 0.dp,
        border = if (isSelected)
            BorderStroke(1.5.dp, selectionColor.copy(alpha = 0.6f))
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Box {
            // Status-colour accent bar at the card's left edge
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        color = statusColor.copy(alpha = if (missed) 0.2f else 0.6f),
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
                    .defaultMinSize(minHeight = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            // ── CENTER: chip + destination + status ───────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Row 1: transit chip + destination
                // If routeShortName is a long descriptive name rather than a short code,
                // show only the bus icon in the chip and promote the name to the main label.
                val isLongRouteName = arrival.routeShortName.length > 10
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Pin icon — tappable to unpin the focused trip
                    if (isPinned) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .then(if (onUnpin != null) Modifier.clickable { onUnpin() } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = stringResource(R.string.arrival_unpin_cd),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = brandChipBg ?: MaterialTheme.colorScheme.primaryContainer,
                        border = if (brandChipBg == null) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) else null,
                        modifier = if (isLongRouteName) Modifier else Modifier.widthIn(max = 120.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val chipFg = brandChipFg ?: MaterialTheme.colorScheme.onPrimaryContainer
                            Icon(
                                Icons.Default.DirectionsBus,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = chipFg,
                            )
                            if (!isLongRouteName) {
                                Text(
                                    arrival.routeShortName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = chipFg,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    // For long route names, use the routeShortName itself as the primary label;
                    // for short codes, use the headsign / long name as usual.
                    val primaryLabel = if (isLongRouteName)
                        arrival.routeShortName
                    else
                        arrival.tripHeadsign.ifBlank { arrival.routeLongName }
                    Text(
                        primaryLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Row 2: via / long name (optional)
                // For long-name routes, show headsign as the secondary line if it adds info.
                val via = if (isLongRouteName) {
                    arrival.tripHeadsign
                        .ifBlank { null }
                        ?.takeIf { it.trim().lowercase() != arrival.routeShortName.trim().lowercase() }
                } else if (arrival.tripHeadsign.isNotBlank() && arrival.routeLongName.isNotBlank()) {
                    arrival.routeLongName
                        .takeIf { it.trim().lowercase() != arrival.tripHeadsign.trim().lowercase() }
                        ?.takeIf { !it.trim().lowercase().startsWith(arrival.routeShortName.trim().lowercase()) }
                } else null
                if (via != null) {
                    Text(
                        via,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                // Agency label — shown when multiple agencies serve the stop
                if (showAgencyLabel && arrival.agencyName.isNotBlank()) {
                    Text(
                        arrival.agencyName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                // Row 3: status label (only for missed or headway trips)
                if (statusLabel != null && (missed || arrival.isHeadway)) {
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                }
                // Row 3b: scheduled time + real-time deviation
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
                            stringResource(R.string.route_sched_prefix, schedStr),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (deviationText.isNotEmpty()) {
                            Text(
                                deviationText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeColor,
                            )
                        }
                    }
                }
                // Row 4: reminder indicator
                if (hasReminder) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = stringResource(R.string.reminder_set_snack),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.reminder_set_snack),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ── RIGHT: arrival time badge + route details button ────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                // Arrival time — coloured badge so status is impossible to miss
                // P3 – urgency pulse: badge scales up/down faster as minutes -> 0
                val urgencyActive = !missed && minutes in 0..2
                val urgencyPulse by if (urgencyActive) {
                    rememberInfiniteTransition(label = "urgency").animateFloat(
                        initialValue = 1.00f, targetValue = 1.06f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = (minutes.coerceAtLeast(1) * 300).coerceIn(300, 600),
                                easing = FastOutSlowInEasing,
                            ),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "urgencyScale",
                    )
                } else {
                    remember { mutableStateOf(1f) }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = badgeColor.copy(alpha = if (urgencyActive) 0.18f else 0.12f),
                    border = BorderStroke(if (urgencyActive) 1.5.dp else 1.dp, badgeColor.copy(alpha = if (urgencyActive) 0.6f else 0.35f)),
                    modifier = Modifier.scale(urgencyPulse),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .widthIn(min = 44.dp)
                            .then(
                                if (sidecarEnabled) Modifier.clickable { onLongClick?.invoke() }
                                else Modifier
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // Live dot — pulsing circle above the time number for real-time arrivals
                        if (arrival.predicted && !missed) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .scale(liveDotScale)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(badgeColor.copy(alpha = liveDotAlpha)),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        // P2 – slot-machine: numbers roll upward as minutes decrease
                        AnimatedContent(
                            targetState = minutes,
                            transitionSpec = {
                                if (targetState < initialState) {
                                    // counting down — roll up
                                    (slideInVertically(tween(280)) { it } + fadeIn(tween(280))) togetherWith
                                        (slideOutVertically(tween(280)) { -it } + fadeOut(tween(280)))
                                } else {
                                    // jumped forward (refresh reordered list) — roll down
                                    (slideInVertically(tween(280)) { -it } + fadeIn(tween(280))) togetherWith
                                        (slideOutVertically(tween(280)) { it } + fadeOut(tween(280)))
                                }
                            },
                            label = "timeBadge",
                        ) { mins ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                        val missed = mins < 0
                        if (missed) {
                            // Show how long ago the bus left in plain language
                            val agoMin = -mins
                            if (agoMin == 0) {
                                Text(
                                    stringResource(R.string.status_just_now),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            } else {
                                Text(
                                    if (agoMin < 60) "${agoMin}m" else "${agoMin / 60}h ${agoMin % 60}m",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Text(
                                    stringResource(R.string.status_ago),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor.copy(alpha = 0.75f),
                                    letterSpacing = 1.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        } else if (arrival.isHeadway && !arrival.predicted && headwayMins != null) {
                            // Headway service — no real-time; show interval window
                            Text(
                                "~$headwayMins",
                                style = MaterialTheme.typography.titleLarge,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Text(
                                stringResource(R.string.arrival_min_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor.copy(alpha = 0.75f),
                                letterSpacing = 1.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        } else if (mins == 0) {
                            Text(
                                stringResource(R.string.status_now),
                                style = MaterialTheme.typography.titleLarge,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        } else {
                            // Prefix "~" for headway trips that have real-time prediction
                            val prefix = if (arrival.isHeadway && arrival.predicted) "~" else ""
                            if (mins < 60) {
                                Text(
                                    "$prefix$mins",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Text(
                                    stringResource(R.string.arrival_min_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor.copy(alpha = 0.75f),
                                    letterSpacing = 1.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            } else {
                                Text(
                                    "$prefix${mins / 60}h",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Text(
                                    "${mins % 60}m",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor.copy(alpha = 0.75f),
                                    letterSpacing = 1.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                        } // end timeBadge Column
                        } // end timeBadge AnimatedContent
                        // Bell indicator — tappable to open reminder sheet
                        if (sidecarEnabled) {
                            Spacer(Modifier.height(3.dp))
                            Icon(
                                imageVector = if (hasReminder) Icons.Default.Notifications else Icons.Outlined.NotificationsNone,
                                contentDescription = if (hasReminder) stringResource(R.string.reminder_set_snack) else stringResource(R.string.reminder_set_title),
                                modifier = Modifier
                                    .size(16.dp),  // visual indicator only — whole column is tappable
                                tint = if (hasReminder)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
                // Route details button — vertical divider + chevron
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                )
                IconButton(
                    onClick = onViewDetails,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.map_view_route_details_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        } // end accent bar Box
    }
}

// ─── Arrival list section header ─────────────────────────────────────────────
@Composable
private fun ArrivalSectionHeader(
    label: String,
    count: Int,
    muted: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = if (muted) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

// ─── Bus teardrop marker bitmap ────────────────────────────────────────────────
/**
 * Draws a bus STOP marker: a rounded-square badge with a classic bus-stop pole
 * symbol (▐▌ bar + pole), anchored at the bottom-centre.
 *
 * - Unselected: white fill, transit-type-colored border and symbol
 * - Selected:   solid transit-type-color fill, white symbol, outer glow ring
 *
 * The badge color is driven by [TransitType.mapColor], keeping all country-specific
 * color constants in one place (the TransitType enum in Models.kt).
 * Visually distinct from the teardrop VEHICLE markers.
 */
private fun createStopMarkerBitmap(
    context: Context,
    isSelected: Boolean,
    transitType: TransitType,
): BitmapDescriptor {
    val brandColor = transitType.mapColor
    val dp      = context.resources.displayMetrics.density
    val size    = 22f * dp          // badge side length
    val corner  = 6f  * dp          // rounded corner radius
    val notch   = 6f  * dp          // downward anchor notch height
    val glowRing = if (isSelected) 3f * dp else 0f
    val pad     = glowRing + 1f * dp // canvas padding so glow isn't clipped

    val totalW = (size + pad * 2).toInt()
    val totalH = (size + notch + pad * 2).toInt()

    val bmp    = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)

    val left   = pad
    val top    = pad
    val right  = pad + size
    val bottom = pad + size

    // ─ outer glow ring when selected ─
    if (isSelected) {
        val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = brandColor
            alpha = 60
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = glowRing * 2
        }
        val glowRect = android.graphics.RectF(
            left - glowRing, top - glowRing,
            right + glowRing, bottom + glowRing,
        )
        canvas.drawRoundRect(glowRect, corner + glowRing, corner + glowRing, glowPaint)
    }

    // ─ badge background ─
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelected) brandColor else android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    val rect = android.graphics.RectF(left, top, right, bottom)
    canvas.drawRoundRect(rect, corner, corner, fillPaint)

    // ─ border ─
    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = brandColor
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = (if (isSelected) 2.5f else 1.8f) * dp
    }
    canvas.drawRoundRect(rect, corner, corner, borderPaint)

    // ─ anchor notch (small downward triangle from badge bottom-centre) ─
    val notchPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelected) brandColor else android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    val cx = totalW / 2f
    val notchPath = android.graphics.Path().apply {
        moveTo(cx - notch * 0.55f, bottom)
        lineTo(cx + notch * 0.55f, bottom)
        lineTo(cx, bottom + notch)
        close()
    }
    canvas.drawPath(notchPath, notchPaint)
    // redraw border sides of notch
    val notchBorderPath = android.graphics.Path().apply {
        moveTo(cx - notch * 0.55f, bottom)
        lineTo(cx, bottom + notch)
        lineTo(cx + notch * 0.55f, bottom)
    }
    canvas.drawPath(notchBorderPath, borderPaint)

    // ─ transit-type symbol ─
    val symColor = if (isSelected) android.graphics.Color.WHITE else brandColor
    val symPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = symColor
        style = android.graphics.Paint.Style.FILL
    }
    val bx = left + size * 0.5f   // symbol centre x
    val by = top  + size * 0.5f   // symbol centre y

    when (transitType) {
        TransitType.BUS -> {
            // Bus-stop symbol: horizontal canopy bar + vertical pole
            val barW   = size * 0.56f
            val barH   = size * 0.14f
            val barTop = by - size * 0.18f
            canvas.drawRoundRect(
                android.graphics.RectF(bx - barW / 2, barTop, bx + barW / 2, barTop + barH),
                barH / 2, barH / 2, symPaint,
            )
            val poleW = size * 0.10f
            canvas.drawRoundRect(
                android.graphics.RectF(bx - poleW / 2, barTop + barH, bx + poleW / 2, by + size * 0.28f),
                poleW / 2, poleW / 2, symPaint,
            )
        }

        TransitType.COMMUTER_RAIL -> {
            // KTM Commuter symbol: two heavy parallel rails + three cross-ties (classic heavy-rail track plan view)
            val railW  = size * 0.60f
            val railH  = size * 0.12f
            val railR  = railH / 2
            val topY   = by - size * 0.23f
            val botY   = by + size * 0.10f
            // top rail
            canvas.drawRoundRect(
                android.graphics.RectF(bx - railW / 2, topY, bx + railW / 2, topY + railH),
                railR, railR, symPaint,
            )
            // bottom rail
            canvas.drawRoundRect(
                android.graphics.RectF(bx - railW / 2, botY, bx + railW / 2, botY + railH),
                railR, railR, symPaint,
            )
            // three cross-ties (left, centre, right)
            val tieW = size * 0.11f
            val tieH = botY - topY + railH
            listOf(-railW * 0.38f, 0f, railW * 0.38f).forEach { offsetX ->
                canvas.drawRoundRect(
                    android.graphics.RectF(bx + offsetX - tieW / 2, topY, bx + offsetX + tieW / 2, topY + tieH),
                    tieW / 2, tieW / 2, symPaint,
                )
            }
        }

        TransitType.LRT_TRAM -> {
            // LRT symbol: tram/car silhouette – rounded car body on a single track with two wheels
            val bodyW  = size * 0.56f
            val bodyH  = size * 0.26f
            val bodyTop = by - size * 0.28f
            // car body
            canvas.drawRoundRect(
                android.graphics.RectF(bx - bodyW / 2, bodyTop, bx + bodyW / 2, bodyTop + bodyH),
                bodyH * 0.30f, bodyH * 0.30f, symPaint,
            )
            // track/rail line below the car
            val trackY  = bodyTop + bodyH + size * 0.06f
            val trackH  = size * 0.09f
            canvas.drawRoundRect(
                android.graphics.RectF(bx - bodyW * 0.55f, trackY, bx + bodyW * 0.55f, trackY + trackH),
                trackH / 2, trackH / 2, symPaint,
            )
            // two wheels (small filled circles)
            val wheelR = size * 0.09f
            val wheelY = trackY + trackH / 2
            listOf(-bodyW * 0.28f, bodyW * 0.28f).forEach { ox ->
                canvas.drawCircle(bx + ox, wheelY, wheelR, symPaint)
            }
        }

        TransitType.MRT_METRO -> {
            // MRT/Metro symbol: bold circle ring (universal underground/metro icon)
            val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = symColor
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = size * 0.15f
            }
            val ringR = size * 0.28f
            canvas.drawCircle(bx, by - size * 0.04f, ringR, ringPaint)
            // centre dot for metro "station" feel
            val dotR = size * 0.08f
            canvas.drawCircle(bx, by - size * 0.04f, dotR, symPaint)
        }

        TransitType.MONORAIL -> {
            // Monorail symbol: single elevated beam + short vertical strut below
            val beamW = size * 0.58f
            val beamH = size * 0.13f
            val beamTop = by - size * 0.10f
            canvas.drawRoundRect(
                android.graphics.RectF(bx - beamW / 2, beamTop, bx + beamW / 2, beamTop + beamH),
                beamH / 2, beamH / 2, symPaint,
            )
            val strutW = size * 0.11f
            canvas.drawRoundRect(
                android.graphics.RectF(bx - strutW / 2, beamTop + beamH, bx + strutW / 2, by + size * 0.28f),
                strutW / 2, strutW / 2, symPaint,
            )
        }

        TransitType.FERRY -> {
            // Ferry symbol: two layered wave arcs
            val wavePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = symColor
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = size * 0.12f
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            listOf(-size * 0.14f, size * 0.14f).forEach { offsetY ->
                val path = android.graphics.Path()
                val waveW = size * 0.22f
                path.moveTo(bx - waveW * 2, by + offsetY)
                path.quadTo(bx - waveW, by + offsetY - size * 0.12f, bx, by + offsetY)
                path.quadTo(bx + waveW, by + offsetY + size * 0.12f, bx + waveW * 2, by + offsetY)
                canvas.drawPath(path, wavePaint)
            }
        }

        TransitType.MIXED -> {
            // Mixed-mode symbol: bold "+" (interchange)
            val armLen = size * 0.24f
            val armW   = size * 0.13f
            // horizontal arm
            canvas.drawRoundRect(
                android.graphics.RectF(bx - armLen, by - armW / 2, bx + armLen, by + armW / 2),
                armW / 2, armW / 2, symPaint,
            )
            // vertical arm
            canvas.drawRoundRect(
                android.graphics.RectF(bx - armW / 2, by - armLen, bx + armW / 2, by + armLen),
                armW / 2, armW / 2, symPaint,
            )
        }
    }

    return BitmapDescriptorFactory.fromBitmap(bmp)
}

/**
 * A small pill-shaped chip showing the route short name (e.g. "D11").
 * Placed flat on the map at the midpoint of each overview route polyline.
 */
private fun createRouteLabelBitmap(
    context: Context,
    label: String,
): BitmapDescriptor {
    val dp       = context.resources.displayMetrics.density
    val textSize = 11f * dp
    val hPad     = 7f  * dp
    val vPad     = 4f  * dp
    val corner   = 99f * dp // fully pill-shaped

    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color    = android.graphics.Color.WHITE
        this.textSize = textSize
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds(label, 0, label.length, textBounds)

    val w = (textBounds.width() + hPad * 2).toInt().coerceAtLeast(1)
    val h = (textBounds.height() + vPad * 2).toInt().coerceAtLeast(1)

    val bmp    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)

    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(210, 31, 60, 136) // dark blue, semi-opaque
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawRoundRect(
        android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()),
        corner, corner, bgPaint
    )

    // Draw text centred vertically
    val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, w / 2f, textY, textPaint)

    return BitmapDescriptorFactory.fromBitmap(bmp)
}