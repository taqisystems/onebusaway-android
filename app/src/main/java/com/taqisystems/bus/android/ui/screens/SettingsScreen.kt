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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.taqisystems.bus.android.data.model.ObaRegion
import com.taqisystems.bus.android.ui.viewmodel.SettingsViewModel
import com.taqisystems.bus.android.ui.viewmodel.SettingsViewModelFactory
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory()),
) {
    val uiState by viewModel.uiState.collectAsState()
    val sortedRegions = remember(uiState.regions, uiState.userLat, uiState.userLon) {
        viewModel.sortedRegions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
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
                    "REGION",
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
                            "Auto-detect region",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        val detectedName = uiState.regions.find { it.id == uiState.detectedRegionId }?.regionName
                        Text(
                            if (uiState.autoDetect && detectedName != null)
                                "Currently in: $detectedName"
                            else if (uiState.autoDetect)
                                "Detecting your location…"
                            else
                                "Manually selecting a region",
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
                    if (uiState.autoDetect) "AVAILABLE REGIONS" else "SELECT A REGION",
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
                            uiState.error ?: "",
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

            item { Spacer(Modifier.height(32.dp)) }
        }
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
                            "Your area",
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
                    Text(
                        formatDistance(distanceKm),
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
                contentDescription = if (isSelected) "Selected" else null,
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

private fun formatDistance(km: Double): String = when {
    km < 1.0 -> "${(km * 1000).roundToInt()} m away"
    km < 100.0 -> "${"%.1f".format(km)} km away"
    else -> "${km.roundToInt()} km away"
}
