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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
                    Text(
                        "Plan a Trip",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            )
        },
        bottomBar = {
            BottomNavBar(selected = 1, onSelect = { idx ->
                when (idx) {
                    0 -> navController.navigate(Routes.HOME) { popUpTo(Routes.TRIP_FLOW) { inclusive = true; saveState = true }; launchSingleTop = true; restoreState = true }
                    2 -> navController.navigate(Routes.SAVED) { popUpTo(Routes.TRIP_FLOW) { inclusive = true; saveState = true }; launchSingleTop = true; restoreState = true }
                    3 -> navController.navigate(Routes.MORE) { popUpTo(Routes.TRIP_FLOW) { inclusive = true; saveState = true }; launchSingleTop = true; restoreState = true }
                }
            })
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {

            // ── Input card ──────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            LocationField(
                                label = "From",
                                value = uiState.originText,
                                onValueChange = { viewModel.setOriginText(it); activeField = "origin" },
                                onClear = { viewModel.clearOrigin(); activeField = null },
                                isFocused = activeField == "origin",
                                onFocused = { activeField = "origin" },
                                leadingIcon = {
                                    Icon(Icons.Default.TripOrigin, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                                },
                            )
                        }
                        if (gpsLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp, color = Blue600)
                        } else {
                            IconButton(onClick = { onGpsClick() }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.MyLocation, contentDescription = "Use my location", tint = Blue600, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    // Suggestions under "From" field
                    if (activeField == "origin" && uiState.suggestions.isNotEmpty()) {
                        SuggestionsDropdown(
                            suggestions = uiState.suggestions,
                            onSelect = { viewModel.selectOrigin(it); activeField = null },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    LocationField(
                        label = "To",
                        value = uiState.destinationText,
                        onValueChange = { viewModel.setDestinationText(it); activeField = "destination" },
                        onClear = { viewModel.clearDestination(); activeField = null },
                        isFocused = activeField == "destination",
                        onFocused = { activeField = "destination" },
                        leadingIcon = {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        },
                    )
                    // Suggestions under "To" field
                    if (activeField == "destination" && uiState.suggestions.isNotEmpty()) {
                        SuggestionsDropdown(
                            suggestions = uiState.suggestions,
                            onSelect = { viewModel.selectDestination(it); activeField = null },
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                    // Swap + time picker button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { viewModel.swapOriginDestination() }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Swap", tint = Blue600)
                        }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                timeBtnLabel(uiState.departMode, uiState.selectedDate, uiState.selectedTime),
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Transport mode chips
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip("All", "TRANSIT,WALK", uiState.selectedMode, viewModel::setMode)
                        ModeChip("Bus", "BUS,WALK", uiState.selectedMode, viewModel::setMode)
                        ModeChip("Train", "RAIL,WALK", uiState.selectedMode, viewModel::setMode)
                    }

                    Spacer(Modifier.height(8.dp))

                    // Plan button
                    Button(
                        onClick = { viewModel.planTrip(); activeField = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.origin != null && uiState.destination != null && !uiState.loading,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        if (uiState.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Finding Routes…")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Find Routes")
                        }
                    }
                }
            }

            // ── Error ────────────────────────────────────────────────────────
            uiState.error?.let { error ->
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

            // ── Results ──────────────────────────────────────────────────────
            if (uiState.itineraries.isNotEmpty()) {
                Text(
                    "Route Options",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.itineraries.take(5)) { itinerary ->
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
            Text(
                "Departure Options",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

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
                    "Date",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(dateList) { (label, date) ->
                        val isSel = date == selectedDate
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSel) Blue600
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable { selectedDate = date }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) Color.White
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Time grid — 4 columns, all cells equal tap target ────────────
                Text(
                    "Time",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSel) Blue600
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable { selectedTime = value }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) Color.White
                                        else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
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
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Confirm", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ─── Mode chip ────────────────────────────────────────────────────────────────

@Composable
private fun ModeChip(label: String, modeValue: String, selected: String, onSelect: (String) -> Unit) {
    val isSelected = selected == modeValue
    val icon = when {
        label == "Bus"   -> Icons.Default.DirectionsBus
        label == "Train" -> Icons.Default.Train
        else             -> Icons.Default.DirectionsTransit
    }
    Surface(
        onClick = { onSelect(modeValue) },
        shape = RoundedCornerShape(50),
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Location field ───────────────────────────────────────────────────────────

@Composable
private fun LocationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    isFocused: Boolean,
    onFocused: () -> Unit,
    leadingIcon: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leadingIcon()
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.weight(1f).clickable { onFocused() },
            singleLine = true,
            trailingIcon = {
                if (value.isNotBlank()) {
                    IconButton(onClick = onClear, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isFocused) Blue600 else Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}

// ─── Suggestions dropdown (inline, inside card) ─────────────────────────────

@Composable
private fun SuggestionsDropdown(suggestions: List<PlaceResult>, onSelect: (PlaceResult) -> Unit) {
    HorizontalDivider(thickness = 0.5.dp)
    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
        items(suggestions) { place ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(place) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Place, contentDescription = null, tint = Blue600, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(place.name, style = MaterialTheme.typography.bodyMedium)
                    if (place.address.isNotBlank()) {
                        Text(place.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

// ─── Itinerary card ───────────────────────────────────────────────────────────

@Composable
private fun ItineraryCard(itinerary: OtpItinerary, onClick: () -> Unit) {
    val durationMin = itinerary.duration / 60
    val transitLegs = itinerary.legs.filter { it.transitLeg }
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
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (leg.mode.contains("RAIL", ignoreCase = true)) Icons.Default.Train
                                else Icons.Default.DirectionsBus,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                leg.routeShortName?.takeIf { it.isNotBlank() } ?: leg.mode,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
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
        }
    }
}
