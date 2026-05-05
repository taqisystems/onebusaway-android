package com.taqisystems.bus.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.taqisystems.bus.android.data.model.OtpItinerary
import com.taqisystems.bus.android.data.model.PlaceResult
import com.taqisystems.bus.android.ui.navigation.Routes
import com.taqisystems.bus.android.ui.theme.Blue600
import com.taqisystems.bus.android.ui.theme.Primary
import com.taqisystems.bus.android.ui.viewmodel.TripPlannerViewModel
import com.taqisystems.bus.android.ui.viewmodel.TripPlannerViewModelFactory
import com.taqisystems.bus.android.ui.viewmodel.SelectedItineraryHolder
import java.text.SimpleDateFormat
import java.util.*

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun buildDateList(): List<Pair<String, String>> {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    return (0..6).map { offset ->
        val cal = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, offset) }
        val date = fmt.format(cal.time)
        val label = when (offset) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> "${dayFmt.format(cal.time)}, ${dateFmt.format(cal.time)}"
        }
        label to date
    }
}

private fun buildTimeSlots(): List<Pair<String, String>> {
    val fmt12 = SimpleDateFormat("h:mm a", Locale.US)
    val fmtHH = SimpleDateFormat("HH:mm:ss", Locale.US)
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
    return (0 until 48).map {
        val label = fmt12.format(cal.time)
        val value = fmtHH.format(cal.time)
        cal.add(Calendar.MINUTE, 30)
        label to value
    }
}

private fun timeBtnLabel(departMode: String, selectedDate: String?, selectedTime: String?): String {
    if (departMode == "now") return "Depart Now"
    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val fmt12 = SimpleDateFormat("h:mm a", Locale.US)
    val fmtHH = SimpleDateFormat("HH:mm:ss", Locale.US)
    val labelFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    val todayStr = dateFmt.format(Date())
    val tomorrowStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
        Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, 1) }.time
    )
    val dateLabel = when (selectedDate) {
        todayStr, null -> "Today"
        tomorrowStr -> "Tomorrow"
        else -> selectedDate?.let { runCatching { labelFmt.format(dateFmt.parse(it)!!) }.getOrElse { it } } ?: "Today"
    }
    val timeLabel = selectedTime?.let { runCatching { fmt12.format(fmtHH.parse(it)!!) }.getOrElse { it } } ?: "Now"
    return if (departMode == "arrive") "Arrive by $timeLabel, $dateLabel" else "Depart at $timeLabel, $dateLabel"
}


// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripPlannerScreen(
    navController: NavController,
    prefilledDestName: String? = null,
    prefilledDestLat: Double? = null,
    prefilledDestLon: Double? = null,
) {
    val activity = LocalContext.current as ComponentActivity
    val viewModel: TripPlannerViewModel = viewModel(viewModelStoreOwner = activity, factory = TripPlannerViewModelFactory())
    val uiState by viewModel.uiState.collectAsState()
    var activeField by remember { mutableStateOf<String?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Pre-fill destination if navigated from "Where to?" search
    LaunchedEffect(prefilledDestName) {
        if (prefilledDestName != null && prefilledDestLat != null && prefilledDestLon != null) {
            viewModel.selectDestination(
                com.taqisystems.bus.android.data.model.PlaceResult(
                    label = prefilledDestName,
                    name = prefilledDestName,
                    lat = prefilledDestLat,
                    lon = prefilledDestLon,
                )
            )
        }
    }

    // ── GPS / current location ───────────────────────────────────────────────
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var gpsLoading by remember { mutableStateOf(false) }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            gpsLoading = true
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    gpsLoading = false
                    loc?.let {
                        viewModel.selectOrigin(
                            PlaceResult(label = "My Location", name = "My Location", lat = it.latitude, lon = it.longitude)
                        )
                    }
                }
                .addOnFailureListener { gpsLoading = false }
        }
    }

    fun onGpsClick() {
        val perm = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
            gpsLoading = true
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    gpsLoading = false
                    loc?.let {
                        viewModel.selectOrigin(
                            PlaceResult(label = "My Location", name = "My Location", lat = it.latitude, lon = it.longitude)
                        )
                    }
                }
                .addOnFailureListener { gpsLoading = false }
        } else {
            locationPermLauncher.launch(perm)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Plan a Trip",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Find bus, rail & transit routes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            )
        },
        bottomBar = {
            BottomNavBar(selected = 1, onSelect = { idx ->
                when (idx) {
                    0 -> navController.popBackStack(Routes.HOME, inclusive = false)
                    2 -> navController.navigate(Routes.SAVED) { popUpTo(Routes.TRIP_FLOW) { inclusive = true; saveState = true }; launchSingleTop = true; restoreState = true }
                    3 -> navController.navigate(Routes.MORE) { popUpTo(Routes.TRIP_FLOW) { inclusive = true; saveState = true }; launchSingleTop = true; restoreState = true }
                }
            })
        },
    ) { innerPadding ->
        // Measure live IME height so suggestion dropdowns can adapt their size
        val imeHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val keyboardOpen = imeHeight > 0.dp
        // Tall when keyboard hidden (show up to ~6 rows), short when open (show ~2.5 rows)
        val suggestionMaxHeight = if (keyboardOpen) 130.dp else 280.dp

        val suggestionsActive = activeField != null && uiState.suggestions.isNotEmpty()
        val listState = rememberLazyListState()

        // Auto-scroll past the form to the first result when results arrive
        LaunchedEffect(uiState.itineraries.isNotEmpty()) {
            if (uiState.itineraries.isNotEmpty() && !suggestionsActive) {
                listState.animateScrollToItem(1) // item 0 = form card, item 1 = "Route Options" header
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding(),
        ) {

            // ── Input card ──────────────────────────────────────────────────
            item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {

                    // ── From / To rows with connector ────────────────────────
                    val connectorColor = MaterialTheme.colorScheme.outlineVariant
                    Row(verticalAlignment = Alignment.Top) {

                        // Left column: icons + dashed connector
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(36.dp),
                        ) {
                            Spacer(Modifier.height(10.dp))
                            // Origin dot
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Primary),
                            )
                            // Dashed connector line
                            val dashColor = connectorColor
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(38.dp)
                                    .drawBehind {
                                        drawLine(
                                            color = dashColor,
                                            start = Offset(size.width / 2, 0f),
                                            end = Offset(size.width / 2, size.height),
                                            strokeWidth = 2.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(
                                                floatArrayOf(6f, 6f), 0f
                                            ),
                                        )
                                    },
                            )
                            // Destination pin
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        // Middle column: text fields
                        Column(modifier = Modifier.weight(1f)) {
                            // From field
                            LocationField(
                                label = "From",
                                hint = "Enter origin",
                                value = uiState.originText,
                                onValueChange = { viewModel.setOriginText(it); activeField = "origin" },
                                onClear = { viewModel.clearOrigin(); activeField = null },
                                isFocused = activeField == "origin",
                                onFocused = { activeField = "origin" },
                            )
                            // Inline dropdown under "From" field
                            if (activeField == "origin" && uiState.suggestions.isNotEmpty()) {
                                SuggestionsDropdown(
                                    suggestions = uiState.suggestions,
                                    maxHeight = suggestionMaxHeight,
                                    onSelect = { viewModel.selectOrigin(it); activeField = null },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            // To field
                            LocationField(
                                label = "To",
                                hint = "Enter destination",
                                value = uiState.destinationText,
                                onValueChange = { viewModel.setDestinationText(it); activeField = "destination" },
                                onClear = { viewModel.clearDestination(); activeField = null },
                                isFocused = activeField == "destination",
                                onFocused = { activeField = "destination" },
                            )
                            // Inline dropdown under "To" field
                            if (activeField == "destination" && uiState.suggestions.isNotEmpty()) {
                                SuggestionsDropdown(
                                    suggestions = uiState.suggestions,
                                    maxHeight = suggestionMaxHeight,
                                    onSelect = { viewModel.selectDestination(it); activeField = null },
                                )
                            }
                        }

                        // Right column: GPS + swap
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(start = 4.dp),
                        ) {
                            if (gpsLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Blue600,
                                )
                            } else {
                                IconButton(onClick = { onGpsClick() }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.MyLocation, contentDescription = "My location", tint = Blue600, modifier = Modifier.size(20.dp))
                                }
                            }
                            // Spacer sized to visually align swap with the To field:
                            // top-label(~16dp) + field(~22dp) + gap(8dp) + divider(0.5dp) + gap(8dp) = ~54dp
                            // minus the GPS button (36dp) = ~18dp between the two buttons
                            Spacer(Modifier.height(18.dp))
                            IconButton(
                                onClick = { viewModel.swapOriginDestination() },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            ) {
                                Icon(Icons.Default.SwapVert, contentDescription = "Swap", tint = Blue600, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(12.dp))

                    // ── When: time picker ────────────────────────────────────
                    Text(
                        "WHEN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            timeBtnLabel(uiState.departMode, uiState.selectedDate, uiState.selectedTime),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Mode chips ───────────────────────────────────────────
                    Text(
                        "MODE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        item { ModeChip("All",      "TRANSIT,WALK",  uiState.selectedMode, viewModel::setMode) }
                        item { ModeChip("Bus",      "BUS,WALK",      uiState.selectedMode, viewModel::setMode) }
                        item { ModeChip("LRT",      "TRAM,WALK",     uiState.selectedMode, viewModel::setMode) }
                        item { ModeChip("MRT",      "SUBWAY,WALK",   uiState.selectedMode, viewModel::setMode) }
                        item { ModeChip("Rail",     "RAIL,WALK",     uiState.selectedMode, viewModel::setMode) }
                        item { ModeChip("Monorail", "MONORAIL,WALK", uiState.selectedMode, viewModel::setMode) }
                    }

                    Spacer(Modifier.height(14.dp))

                    // ── Plan button ──────────────────────────────────────────
                    Button(
                        onClick = { viewModel.planTrip(); activeField = null },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = uiState.origin != null && uiState.destination != null && !uiState.loading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        if (uiState.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Finding Routes…", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Find Routes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            } // end item { Card }

            // ── Error ────────────────────────────────────────────────────────
            if (!suggestionsActive) {
                uiState.error?.let { error ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp).padding(top = 2.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "No route found",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Text(
                                        error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // ── Results ──────────────────────────────────────────────────────
            if (!suggestionsActive && uiState.itineraries.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Route Options",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                "${uiState.itineraries.size} found",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                items(uiState.itineraries) { itinerary ->
                    ItineraryCard(
                        itinerary = itinerary,
                        onClick = {
                            SelectedItineraryHolder.itinerary = itinerary
                            viewModel.selectItinerary(itinerary)
                            navController.navigate(Routes.TRIP_ITINERARY)
                        },
                    )
                }
            }
        }
    }

    // ── Date/time picker bottom sheet ────────────────────────────────────────
    if (showTimePicker) {
        DateTimePickerSheet(
            currentDepartMode = uiState.departMode,
            currentDate = uiState.selectedDate,
            currentTime = uiState.selectedTime,
            onDepartModeChange = viewModel::setDepartMode,
            onConfirm = { date, time ->
                viewModel.setDateTime(date, time)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

// ─── Date/Time Picker Bottom Sheet ───────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerSheet(
    currentDepartMode: String,
    currentDate: String?,
    currentTime: String?,
    onDepartModeChange: (String) -> Unit,
    onConfirm: (date: String, time: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateList = remember { buildDateList() }
    val timeSlots = remember { buildTimeSlots() }
    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val todayStr = remember { dateFmt.format(Date()) }

    var selectedDepartMode by remember { mutableStateOf(currentDepartMode) }
    var selectedDate by remember { mutableStateOf(currentDate ?: todayStr) }
    var selectedTime by remember { mutableStateOf(currentTime ?: timeSlots[0].second) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Departure Options",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Choose when you want to travel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))

            // ── Mode toggle — full-width segmented 3-button row ──────────────────
            val modes = listOf(
                Triple("now",    "Depart Now",  Icons.Default.FlashOn),
                Triple("depart", "Depart At",   Icons.Default.Schedule),
                Triple("arrive", "Arrive By",   Icons.Default.Flag),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                modes.forEachIndexed { idx, (mode, label, icon) ->
                    val isSel = selectedDepartMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (isSel) Blue600
                                else Color.Transparent
                            )
                            .clickable {
                                selectedDepartMode = mode
                                onDepartModeChange(mode)
                                if (mode == "now") onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isSel) Color.White
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                label,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) Color.White
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (selectedDepartMode != "now") {
                Spacer(Modifier.height(16.dp))

                // ── Date chips ───────────────────────────────────────────────────
                Text(
                    "DATE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(dateList) { (label, date) ->
                        val isSel = date == selectedDate
                        Surface(
                            onClick = { selectedDate = date },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSel) Blue600 else MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = if (!isSel) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Time grid — 4 columns, all cells equal tap target ────────────
                Text(
                    "TIME",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(timeSlots) { (label, value) ->
                        val isSel = value == selectedTime
                        Surface(
                            onClick = { selectedTime = value },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSel) Blue600 else MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = if (!isSel) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { onConfirm(selectedDate, selectedTime) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Confirm", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─── Mode chip ────────────────────────────────────────────────────────────────

@Composable
private fun ModeChip(label: String, modeValue: String, selected: String, onSelect: (String) -> Unit) {
    val isSelected = selected == modeValue
    val icon = when (label) {
        "Bus"      -> Icons.Default.DirectionsBus
        "LRT"      -> Icons.Default.Tram
        "MRT"      -> Icons.Default.DirectionsSubway
        "Rail"     -> Icons.Default.Train
        "Monorail" -> Icons.Default.Train
        else       -> Icons.Default.DirectionsTransit  // "All"
    }
    Surface(
        onClick = { onSelect(modeValue) },
        shape = RoundedCornerShape(50),
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ─── Location field ───────────────────────────────────────────────────────────

@Composable
private fun LocationField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    isFocused: Boolean,
    onFocused: () -> Unit,
) {
    Column(modifier = Modifier.clickable { onFocused() }) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Blue600 else MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(Blue600),
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                },
            )
            if (value.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onClear, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ─── Suggestions dropdown ────────────────────────────────────────────────────

@Composable
private fun SuggestionsDropdown(
    suggestions: List<PlaceResult>,
    maxHeight: Dp,
    onSelect: (PlaceResult) -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = maxHeight)) {
            itemsIndexed(suggestions, key = { index, _ -> index }) { index, place ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(place) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, tint = Blue600, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            place.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (place.address.isNotBlank()) {
                            Text(
                                place.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (index < suggestions.lastIndex)
                    HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(start = 50.dp))
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

// ─── Transit mode helpers ────────────────────────────────────────────────

/** Maps an OTP mode string to the appropriate Material icon for use in trip cards. */
private fun legTransitIcon(mode: String) = when (mode.uppercase()) {
    "TRAM"     -> Icons.Default.Tram
    "RAIL"     -> Icons.Default.Train
    "SUBWAY"   -> Icons.Default.DirectionsSubway
    "FERRY"    -> Icons.Default.DirectionsBoat
    "MONORAIL" -> Icons.Default.Train
    else       -> Icons.Default.DirectionsBus
}

/** Maps an OTP mode string to a brand color consistent with [TransitType.mapColor]. */
private fun legTransitColor(mode: String): Color = when (mode.uppercase()) {
    "BUS"      -> Color(0xFFDC2626)
    "TRAM"     -> Color(0xFF9B2335)
    "RAIL"     -> Color(0xFFE35205)
    "SUBWAY"   -> Color(0xFF007C3E)
    "FERRY"    -> Color(0xFF0369A1)
    "MONORAIL" -> Color(0xFF5CB85C)
    else       -> Color(0xFFDC2626)
}

// ─── Itinerary card ───────────────────────────────────────────────────────────

@Composable
private fun ItineraryCard(itinerary: OtpItinerary, onClick: () -> Unit) {
    val durationMin = itinerary.duration / 60
    val transitLegs = itinerary.legs.filter { it.transitLeg }
    val walkSec = itinerary.legs.filter { !it.transitLeg }.sumOf { it.endTime - it.startTime }
    val walkMin = (walkSec / 1000 / 60).toInt()
    val transfers = (transitLegs.size - 1).coerceAtLeast(0)
    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    val startTime = fmt.format(Date(itinerary.startTime))
    val endTime = fmt.format(Date(itinerary.endTime))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Time row ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        startTime,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "  —  ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        endTime,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    "$durationMin min",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (transitLegs.isEmpty()) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Leg visualizer: walk icon — connector — transit chip — connector — walk ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Start walk
                val hasStartWalk = itinerary.legs.firstOrNull()?.transitLeg == false
                if (hasStartWalk) {
                    Icon(
                        Icons.Default.DirectionsWalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }

                // Transit chips
                transitLegs.forEachIndexed { i, leg ->
                    if (i > 0) {
                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                    val chipColor = legTransitColor(leg.mode)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = chipColor.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, chipColor.copy(alpha = 0.40f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                legTransitIcon(leg.mode),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = chipColor,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                leg.routeShortName?.takeIf { it.isNotBlank() } ?: leg.mode,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = chipColor,
                            )
                        }
                    }
                }

                // End walk connector + icon
                val hasEndWalk = itinerary.legs.lastOrNull()?.transitLeg == false
                if (hasEndWalk) {
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Icon(
                        Icons.Default.DirectionsWalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // If no transit legs at all (pure walk), show walk distance
                if (transitLegs.isEmpty()) {
                    Icon(
                        Icons.Default.DirectionsWalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Walk only",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Walk + transfers metadata row ─────────────────────────────
            if (transitLegs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (walkMin > 0) {
                        Icon(
                            Icons.Default.DirectionsWalk,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "$walkMin min walk",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (transfers == 0) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "Direct",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "$transfers transfer${if (transfers > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
