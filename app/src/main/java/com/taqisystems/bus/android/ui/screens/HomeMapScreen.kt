package com.taqisystems.bus.android.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import com.taqisystems.bus.android.data.model.ArrivalStatus
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.ObaStop
import com.taqisystems.bus.android.data.model.PlaceResult
import com.taqisystems.bus.android.ui.navigation.Routes
import com.taqisystems.bus.android.ui.theme.*
import com.taqisystems.bus.android.ui.viewmodel.HomeSearchResult
import com.taqisystems.bus.android.ui.map.VehicleMarkerFactory
import com.taqisystems.bus.android.ui.viewmodel.HomeViewModel
import com.taqisystems.bus.android.ui.viewmodel.HomeViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeMapScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory()),
) {
    val uiState by viewModel.uiState.collectAsState()

    // ── Voice recognition ─────────────────────────────────────────────────────
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
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultLatlng, 14f)
    }

    // Sheet state — persistent BottomSheetScaffold with peek
    val selectedStop = uiState.selectedStop
    val scaffoldState = rememberBottomSheetScaffoldState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show a snackbar whenever the user switches regions from Settings
    val regionSwitchMessage = uiState.regionSwitchMessage
    LaunchedEffect(regionSwitchMessage) {
        if (regionSwitchMessage != null) {
            snackbarHostState.showSnackbar(
                message = regionSwitchMessage,
                duration = SnackbarDuration.Short,
            )
            viewModel.clearRegionSwitchMessage()
        }
    }
    val scope = rememberCoroutineScope()
    val savedStops by viewModel.savedStops.collectAsState()

    // Collapse the sheet back to handle-only whenever the selected stop is cleared.
    // When a stop is selected we do NOT expand — a floating mini card appears instead,
    // keeping the map fully accessible for tapping other stops.
    LaunchedEffect(selectedStop) {
        if (selectedStop == null) {
            scaffoldState.bottomSheetState.partialExpand()
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

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

    // Fly to a manually selected region (Settings → pick region → back to map)
    LaunchedEffect(uiState.pendingCameraCenter) {
        uiState.pendingCameraCenter?.let { (lat, lon) ->
            cameraPositionState.animate(
                com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
                    LatLng(lat, lon), 12f,
                ),
            )
            viewModel.markCameracentered()
            viewModel.clearPendingCameraCenter()
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
            // Build a bounds that contains both the bus and the stop, then pad it
            val bounds = LatLngBounds.Builder()
                .include(busLatLng)
                .include(stopLatLng)
                .build()
            com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(bounds, 120)
        } else {
            // No stop known — fall back to zooming straight to the bus
            com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(busLatLng, 16f)
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

    // The sheet always stays at handle height — the floating StopMiniCard replaces
    // the auto-expand so the map is never obscured when the user selects a stop.
    val handleBarHeight = 52.dp
    val peekHeight      = handleBarHeight
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomNavBar(
                selected = 0,
                onSelect = { idx ->
                    when (idx) {
                        1 -> navController.navigate(Routes.PLAN_PLAIN)
                        2 -> navController.navigate(Routes.SAVED)
                        3 -> navController.navigate(Routes.MORE)
                    }
                },
            )
        },
    ) { innerPadding ->
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetDragHandle = {
                // Opaque drag handle — matches the sheet surface so it never looks transparent
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        )
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            },
            sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContent = {
                val isExpanded = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded
                if (selectedStop != null && isExpanded) {
                    StopBottomSheet(
                        stop = selectedStop,
                        arrivals = uiState.arrivals,
                        loading = uiState.arrivalsLoading,
                        lastUpdated = uiState.arrivalsLastUpdated,
                        isSaved = savedStops.any { it.id == selectedStop.id },
                        focusedVehicleId = uiState.focusedVehicle?.vehicleId,
                        extraBottomPadding = innerPadding.calculateBottomPadding(),
                        onToggleSave = {
                            val wasSaved = savedStops.any { it.id == selectedStop.id }
                            viewModel.toggleSaved(selectedStop)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (wasSaved) "Stop removed from saved" else "Stop saved",
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        },
                        onViewDetails = {
                            navController.navigate(
                                Routes.stopDetails(selectedStop.id, selectedStop.name, selectedStop.code),
                            )
                        },
                        onDismiss = { viewModel.clearSelectedStop() },
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
                                    stopId = selectedStop.id,
                                ),
                            )
                        },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "Tap a bus stop on the map to see live arrivals",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

                // ── Map ──────────────────────────────────────────────────────────────
        LaunchedEffect(cameraPositionState.isMoving) {
            if (!cameraPositionState.isMoving) {
                val center = cameraPositionState.position.target
                viewModel.loadStopsForLocation(center.latitude, center.longitude)
            }
        }

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = uiState.locationPermissionGranted),
            uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = false),
            onMapClick = { scope.launch { scaffoldState.bottomSheetState.partialExpand() } },
        ) {
            val mapContext = LocalContext.current
            val brandRed  = Primary.toArgb()
            // When a route is highlighted, only show stops that serve that route
            val visibleStops = uiState.routeHighlight
                ?.let { r -> uiState.stops.filter { s -> s.routeIds.contains(r.id) } }
                ?: uiState.stops
            visibleStops.forEach { stop ->
                val isSelected = stop.id == uiState.selectedStop?.id
                val icon = remember(isSelected) {
                    createStopMarkerBitmap(mapContext, isSelected, brandRed)
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
                        )
                    }
                    Marker(
                        state  = MarkerState(LatLng(vLat, vLon)),
                        title  = "${arrival.routeShortName} — ${arrival.tripHeadsign}",
                        icon   = markerResult.descriptor,
                        anchor = Offset(0.5f, markerResult.anchorV),
                        onClick = {
                            viewModel.focusVehicle(arrival)
                            scope.launch { scaffoldState.bottomSheetState.expand() }
                            false // allow default title to show
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
            if (!err.isNullOrBlank()) {
                snackbarHostState.showSnackbar(err, duration = SnackbarDuration.Short)
                viewModel.clearLocationError()
            }
        }

        // ── My Location FAB — always visible, overlaid on map ─────────────────
        val fabContext = LocalContext.current
        SmallFloatingActionButton(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    fabContext, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    viewModel.fetchUserLocation(recenterCamera = true)
                } else {
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            "Location permission required",
                            actionLabel = "Settings",
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
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = peekHeight + 16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            elevation = FloatingActionButtonDefaults.elevation(2.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (uiState.isLocatingUser) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = "My Location",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // ── Stop mini card — floating above the sheet handle ─────────────────
        // Visible whenever a stop is selected but the sheet is not fully expanded.
        // Keeps the map accessible so the user can tap other stops.
        val isSheetExpanded = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded
        if (selectedStop != null && !isSheetExpanded) {
            StopMiniCard(
                stop = selectedStop,
                arrivals = uiState.arrivals,
                loading = uiState.arrivalsLoading,
                onExpand = { scope.launch { scaffoldState.bottomSheetState.expand() } },
                onDismiss = { viewModel.clearSelectedStop() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 60.dp, bottom = peekHeight + 8.dp),
            )
        }

        // ── "Where to?" search bar (original design: inline, not a sheet) ────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = if (uiState.searchActive) 6.dp else 3.dp, shape = RoundedCornerShape(12.dp))
                    // Tapping anywhere on the bar activates search, even if the
                    // TextField already holds focus (in which case onFocusChanged
                    // would not fire, leaving the bar stuck in an inactive state).
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
                            Icon(Icons.Default.ArrowBack, contentDescription = "Close search", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
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
                                    if (isListening) "Listening…" else "Where to?",
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
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
                                contentDescription = "Voice search",
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

            // ── Results dropdown ─────────────────────────────────────────────
            if (uiState.searchActive) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Column {
                        if (uiState.searchQuery.isEmpty()) {
                            // Empty state — quick actions
                            ListItem(
                                headlineContent = { Text("Plan a full trip", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text("Set origin, destination & time") },
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
                                    "ROUTES",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                routeResults.forEach { result ->
                                    val route = result.route
                                    val displayName = when {
                                        route.shortName.isNotBlank() && route.longName.isNotBlank() ->
                                            "Route ${route.shortName} · ${route.longName}"
                                        route.shortName.isNotBlank() -> "Route ${route.shortName}"
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
                                                Text("Show", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                                    "NEARBY STOPS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                stopResults.forEach { result ->
                                    val stop = result.stop
                                    ListItem(
                                        headlineContent = { Text(stop.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
                                        supportingContent = { Text("${stop.routeIds.size} route${if (stop.routeIds.size != 1) "s" else ""}", style = MaterialTheme.typography.bodySmall) },
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
                                    "DESTINATIONS",
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
                                                Text("Plan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                                    Text("No results for \"${uiState.searchQuery}\"", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                val label = buildString {
                    append("Route ")
                    if (routeHighlight.shortName.isNotBlank()) append(routeHighlight.shortName)
                    if (routeHighlight.longName.isNotBlank()) { append(" · "); append(routeHighlight.longName) }
                }
                FilterChip(
                    selected = true,
                    onClick = { viewModel.clearRouteHighlight() },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Clear route filter", modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor     = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
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
    val nextMinutes    = nextArrival?.minutesUntilArrival

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
                    "Loading\u2026",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                arrivals.isEmpty() -> Text(
                    "No upcoming services",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val chipLabel = if (liveCount > 0) "$liveCount live service${if (liveCount > 1) "s" else ""}" else "${arrivals.size} scheduled"
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
                        "Now",
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
                            "MIN",
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
                contentDescription = "Close",
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
    extraBottomPadding: Dp = 0.dp,
    onToggleSave: () -> Unit = {},
    onViewDetails: () -> Unit,
    onDismiss: () -> Unit,
    onArrivalFocus: (ObaArrival) -> Unit,
    onArrivalDetails: (ObaArrival) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(bottom = extraBottomPadding),
    ) {
        // ── Header ─────────────────────────────────────────────────────────
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
            }
            // Bookmark / save toggle
            IconButton(onClick = onToggleSave) {
                Icon(
                    if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (isSaved) "Remove saved stop" else "Save stop",
                    tint = if (isSaved) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Arrivals section ───────────────────────────────────────────────
        // Tick every second so the "updated X ago" label stays current
        var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(1_000); nowMs = System.currentTimeMillis() } }
        val updatedLabel: String? = lastUpdated?.let { ts ->
            val elapsed = ((nowMs - ts) / 1000L).coerceAtLeast(0L).toInt()
            val m = elapsed / 60; val s = elapsed % 60
            when {
                elapsed < 5  -> "Just updated"
                m == 0       -> "Updated ${s}s ago"
                s == 0       -> "Updated ${m} min ago"
                else         -> "Updated ${m} min ${s}s ago"
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
                "Arrivals",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // Arrival count summary chip — visible in peek state so the user
            // immediately knows how many services are coming
            if (!loading && arrivals.isNotEmpty()) {
                val liveCount = arrivals.count { it.status != ArrivalStatus.SCHEDULED }
                val label = if (liveCount > 0) "$liveCount live" else "${arrivals.size} scheduled"
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

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (arrivals.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "No services in the next 6 hours",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val liveArrivals = arrivals.filter { it.status != ArrivalStatus.SCHEDULED }
            val scheduledOnly = arrivals.filter { it.status == ArrivalStatus.SCHEDULED }
            val hasLive = liveArrivals.isNotEmpty()

            if (!hasLive && scheduledOnly.isNotEmpty()) {
                // No active buses right now — show a friendly nudge then list scheduled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "No active buses right now. Next scheduled services:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Box {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(arrivals.take(6)) { arrival ->
                        ArrivalRow(
                            arrival = arrival,
                            isSelected = arrival.vehicleId != null
                                && arrival.vehicleId == focusedVehicleId,
                            onClick = { onArrivalFocus(arrival) },
                            onViewDetails = { onArrivalDetails(arrival) },
                        )
                    }
                }
                // Bottom-fade gradient overlaid on the list — hints more rows below the peek line
                if (arrivals.size > 1) {
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
        }

        // ── Full schedule link — placed after arrivals so rows are visible at peek ─
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedButton(
            onClick = onViewDetails,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                Icons.Default.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "View Full Schedule",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun ArrivalRow(
    arrival: ObaArrival,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
) {
    val statusColor = when (arrival.status) {
        ArrivalStatus.ON_TIME   -> StatusOnTime
        ArrivalStatus.DELAYED   -> StatusDelayed
        ArrivalStatus.EARLY     -> StatusEarly
        ArrivalStatus.SCHEDULED -> MaterialTheme.colorScheme.onSurfaceVariant
        ArrivalStatus.UNKNOWN   -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val minutes = arrival.minutesUntilArrival
    val missed = minutes < 0
    val headwayMins = arrival.headwaySecs?.let { it / 60 }

    // Headway (frequency-based) with no real-time prediction → always scheduled grey
    val badgeColor = when {
        missed -> MaterialTheme.colorScheme.onSurfaceVariant
        arrival.isHeadway && !arrival.predicted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> statusColor
    }

    val headwayUntil = arrival.headwayEndTime?.let {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        " until " + fmt.format(java.util.Date(it))
    } ?: ""
    val statusLabel = when {
        missed -> "Already departed"
        arrival.isHeadway && !arrival.predicted ->
            if (headwayMins != null) "Every ~$headwayMins min$headwayUntil" else null
        arrival.isHeadway && arrival.predicted ->
            when (arrival.status) {
                ArrivalStatus.ON_TIME -> "On Time · ~$headwayMins min$headwayUntil"
                ArrivalStatus.DELAYED -> "Delayed · ~$headwayMins min$headwayUntil"
                ArrivalStatus.EARLY   -> "Early · ~$headwayMins min$headwayUntil"
                else -> if (headwayMins != null) "Every ~$headwayMins min$headwayUntil" else null
            }
        arrival.status == ArrivalStatus.ON_TIME   -> "On Time"
        arrival.status == ArrivalStatus.DELAYED   -> "Delayed"
        arrival.status == ArrivalStatus.EARLY     -> "Early"
        else -> null
    }

    // For selected rows, background and border are driven by the bus status colour
    val selectionColor = if (missed) MaterialTheme.colorScheme.onSurfaceVariant else statusColor

    Surface(
        modifier = Modifier
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
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
                // Row 2: via / long name (optional)
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
                // Row 3: status label (Already departed / Early / Delayed / On Time)
                if (statusLabel != null) {
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                }
            }

            // ── RIGHT: arrival time badge + route details button ────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                // Arrival time — coloured badge so status is impossible to miss
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = badgeColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .widthIn(min = 44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (missed) {
                            // Show how many minutes ago the bus left
                            val agoMin = -minutes
                            Text(
                                if (agoMin < 60) "-$agoMin" else "-${agoMin / 60}h ${agoMin % 60}m",
                                style = MaterialTheme.typography.titleLarge,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Text(
                                "AGO",
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor.copy(alpha = 0.75f),
                                letterSpacing = 1.sp,
                            )
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
                                "MIN",
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor.copy(alpha = 0.75f),
                                letterSpacing = 1.sp,
                            )
                        } else if (minutes == 0) {
                            Text(
                                "NOW",
                                style = MaterialTheme.typography.titleLarge,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            // Prefix "~" for headway trips that have real-time prediction
                            val prefix = if (arrival.isHeadway && arrival.predicted) "~" else ""
                            Text(
                                prefix + if (minutes < 60) "$minutes" else "${minutes / 60}h ${minutes % 60}m",
                                style = MaterialTheme.typography.titleLarge,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Text(
                                if (minutes < 60) "MIN" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor.copy(alpha = 0.75f),
                                letterSpacing = 1.sp,
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
                        contentDescription = "View route details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ─── Bus teardrop marker bitmap ───────────────────────────────────────────────
/**
 * Draws a bus STOP marker: a rounded-square badge with a classic bus-stop pole
 * symbol (▐▌ bar + pole), anchored at the bottom-centre.
 *
 * - Unselected: white fill, brand-red border, red symbol
 * - Selected:   solid brand-red fill, white symbol, white outer glow ring
 *
 * Visually distinct from the teardrop VEHICLE markers.
 */
private fun createStopMarkerBitmap(
    context: Context,
    isSelected: Boolean,
    brandColor: Int,
): BitmapDescriptor {
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

    // ─ bus stop symbol: horizontal canopy bar + vertical pole ─
    val symColor = if (isSelected) android.graphics.Color.WHITE else brandColor
    val symPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = symColor
        style = android.graphics.Paint.Style.FILL
    }
    val bx     = left + size * 0.5f          // symbol centre x
    val by     = top  + size * 0.5f          // symbol centre y
    val barW   = size * 0.56f
    val barH   = size * 0.14f
    val barTop = by - size * 0.18f
    // canopy bar
    canvas.drawRoundRect(
        android.graphics.RectF(bx - barW / 2, barTop, bx + barW / 2, barTop + barH),
        barH / 2, barH / 2, symPaint,
    )
    // pole
    val poleW = size * 0.10f
    canvas.drawRoundRect(
        android.graphics.RectF(bx - poleW / 2, barTop + barH, bx + poleW / 2, by + size * 0.28f),
        poleW / 2, poleW / 2, symPaint,
    )

    return BitmapDescriptorFactory.fromBitmap(bmp)
}

