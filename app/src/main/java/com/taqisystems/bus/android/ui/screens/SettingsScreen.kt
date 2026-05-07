// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.taqisystems.bus.android.R
import com.taqisystems.bus.android.data.model.ObaRegion
import com.taqisystems.bus.android.data.model.PlaceResult
import com.taqisystems.bus.android.data.model.SavedPlace
import com.taqisystems.bus.android.ui.util.resolve
import com.taqisystems.bus.android.ui.viewmodel.SettingsViewModel
import com.taqisystems.bus.android.ui.viewmodel.SettingsViewModelFactory
import com.taqisystems.bus.android.ui.viewmodel.TripPlannerViewModel
import com.taqisystems.bus.android.ui.viewmodel.TripPlannerViewModelFactory
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory()),
    searchViewModel: TripPlannerViewModel = viewModel(factory = TripPlannerViewModelFactory()),
) {
    val uiState by viewModel.uiState.collectAsState()
    val homePlace by viewModel.homePlace.collectAsState()
    val workPlace by viewModel.workPlace.collectAsState()
    val context = LocalContext.current
    val sortedRegions = remember(uiState.regions, uiState.userLat, uiState.userLon) {
        viewModel.sortedRegions()
    }
    var editingPlace: Boolean? by remember { mutableStateOf(null) } // true = home, false = work
    var currentLang by remember {
        mutableStateOf(
            androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
                .get(0)?.language ?: java.util.Locale.getDefault().language
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshRegions() }) {
                        if (uiState.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh), tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Region section header
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_section_region),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ── Auto-detect toggle
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setAutoDetect(!uiState.autoDetect) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = if (uiState.autoDetect) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_auto_detect),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        val detectedName = uiState.regions.find { it.id == uiState.detectedRegionId }?.regionName
                        Text(
                            if (uiState.autoDetect && detectedName != null)
                                String.format(stringResource(R.string.settings_auto_detect_currently), detectedName)
                            else if (uiState.autoDetect)
                                stringResource(R.string.settings_detecting_location)
                            else
                                stringResource(R.string.settings_manual_region),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.autoDetect,
                        onCheckedChange = { viewModel.setAutoDetect(it) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }

            // ── Regions list subheader
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (uiState.autoDetect) stringResource(R.string.settings_section_available_regions) else stringResource(R.string.settings_section_select_region),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ── Error banner
            if (uiState.error != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            uiState.error?.resolve(context) ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // ── Loading skeleton
            if (uiState.loading && uiState.regions.isEmpty()) {
                items(6) { RegionItemSkeleton() }
            }

            // ── Region entries
            items(sortedRegions, key = { it.id }) { region ->
                val isSelected = region.id == uiState.selectedRegionId
                val isDetected = region.id == uiState.detectedRegionId
                val distKm = distanceKm(
                    uiState.userLat, uiState.userLon,
                    region.centerLat, region.centerLon,
                )
                RegionItem(
                    region = region,
                    isSelected = isSelected,
                    isDetected = isDetected,
                    distanceKm = distKm,
                    autoDetectOn = uiState.autoDetect,
                    onClick = { viewModel.selectRegion(region) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }

            // ── Saved Places section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_section_saved_places),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                SavedPlaceSettingsRow(
                    icon = Icons.Default.Home,
                    label = stringResource(R.string.settings_home_label),
                    place = homePlace,
                    showDivider = true,
                    onEdit = { editingPlace = true },
                    onClear = { viewModel.saveHomePlace(null) },
                )
            }
            item {
                SavedPlaceSettingsRow(
                    icon = Icons.Default.Work,
                    label = stringResource(R.string.settings_work_label),
                    place = workPlace,
                    showDivider = false,
                    onEdit = { editingPlace = false },
                    onClear = { viewModel.saveWorkPlace(null) },
                )
            }

            // ── Display / Theme section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_section_theme),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                ThemeOptionRow(
                    mode = "system",
                    label = stringResource(R.string.settings_theme_system),
                    current = uiState.themeMode,
                    showDivider = true,
                    onClick = { viewModel.setThemeMode("system") },
                )
            }
            item {
                ThemeOptionRow(
                    mode = "light",
                    label = stringResource(R.string.settings_theme_light),
                    current = uiState.themeMode,
                    showDivider = true,
                    onClick = { viewModel.setThemeMode("light") },
                )
            }
            item {
                ThemeOptionRow(
                    mode = "dark",
                    label = stringResource(R.string.settings_theme_dark),
                    current = uiState.themeMode,
                    showDivider = false,
                    onClick = { viewModel.setThemeMode("dark") },
                )
            }

            // ── Language section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_section_language),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                LanguageOptionRow(
                    label = stringResource(R.string.settings_lang_english),
                    selected = currentLang != "ms",
                    showDivider = true,
                    onClick = {
                        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                            androidx.core.os.LocaleListCompat.forLanguageTags("en")
                        )
                        currentLang = "en"
                    },
                )
            }
            item {
                LanguageOptionRow(
                    label = stringResource(R.string.settings_lang_malay),
                    selected = currentLang == "ms",
                    showDivider = false,
                    onClick = {
                        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                            androidx.core.os.LocaleListCompat.forLanguageTags("ms")
                        )
                        currentLang = "ms"
                    },
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // ── Saved place edit sheet
    editingPlace?.let { isHome ->
        SettingsSavedPlaceSheet(
            title = stringResource(if (isHome) R.string.settings_home_label else R.string.settings_work_label),
            icon = if (isHome) Icons.Default.Home else Icons.Default.Work,
            existingPlace = if (isHome) homePlace else workPlace,
            searchViewModel = searchViewModel,
            onSave = { place ->
                if (isHome) viewModel.saveHomePlace(place) else viewModel.saveWorkPlace(place)
                editingPlace = null
            },
            onDismiss = { editingPlace = null },
        )
    }
}

@Composable
private fun RegionItem(
    region: ObaRegion,
    isSelected: Boolean,
    isDetected: Boolean,
    distanceKm: Double?,
    autoDetectOn: Boolean,
    onClick: () -> Unit,
) {
    val dimmed = autoDetectOn && !isDetected && !isSelected
    val alpha = if (dimmed) 0.45f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading circled icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        isDetected -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    isSelected -> Icons.Default.CheckCircle
                    isDetected -> Icons.Default.LocationOn
                    else -> Icons.Default.Map
                },
                contentDescription = null,
                tint = when {
                    isSelected -> Color.White
                    isDetected -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                },
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    region.regionName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                if (isDetected) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            stringResource(R.string.settings_your_area),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (distanceKm != null) {
                    val distLabel = if (distanceKm < 1.0)
                        stringResource(R.string.settings_distance_m, (distanceKm * 1000).roundToInt())
                    else if (distanceKm < 100.0)
                        stringResource(R.string.settings_distance_km, "%.1f".format(distanceKm))
                    else
                        stringResource(R.string.settings_distance_km, distanceKm.roundToInt().toString())
                    Text(
                        distLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // Trailing radio / check
        if (!autoDetectOn) {
            Icon(
                if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) stringResource(R.string.settings_region_selected) else null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RegionItemSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
}

private fun distanceKm(lat1: Double?, lon1: Double?, lat2: Double, lon2: Double): Double? {
    if (lat1 == null || lon1 == null) return null
    val dlat = lat2 - lat1
    val dlon = lon2 - lon1
    return sqrt(dlat * dlat + dlon * dlon) * 111.0
}


@Composable
private fun LanguageOptionRow(
    label: String,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(40.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Icon(
            imageVector = if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

@Composable
private fun ThemeOptionRow(
    mode: String,
    label: String,
    current: String,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val selected = mode == current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(40.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Icon(
            imageVector = if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

@Composable
private fun SavedPlaceSettingsRow(
    icon: ImageVector,
    label: String,
    place: SavedPlace?,
    showDivider: Boolean,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (place != null) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                place?.label ?: stringResource(R.string.settings_saved_place_not_set),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (place != null) {
            TextButton(onClick = onClear) {
                Text(
                    stringResource(R.string.settings_saved_place_clear),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
    if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSavedPlaceSheet(
    title: String,
    icon: ImageVector,
    existingPlace: SavedPlace?,
    searchViewModel: TripPlannerViewModel,
    onSave: (SavedPlace) -> Unit,
    onDismiss: () -> Unit,
) {
    val searchUiState by searchViewModel.uiState.collectAsState()
    var query by remember { mutableStateOf(existingPlace?.label ?: "") }
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    searchViewModel.searchPlaces(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.plan_saved_place_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )
            Spacer(Modifier.height(8.dp))
            if (searchUiState.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                searchUiState.suggestions.forEach { place ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val sp = SavedPlace(label = place.label, lat = place.lat, lon = place.lon)
                                onSave(sp)
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(place.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
