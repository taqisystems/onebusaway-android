package com.taqisystems.bus.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.taqisystems.bus.android.data.model.SavedRoute
import com.taqisystems.bus.android.data.model.SavedStop
import com.taqisystems.bus.android.ui.navigation.Routes
import com.taqisystems.bus.android.ui.theme.Blue600
import com.taqisystems.bus.android.ui.viewmodel.StopDetailsViewModel
import com.taqisystems.bus.android.ui.viewmodel.StopDetailsViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    navController: NavController,
    viewModel: StopDetailsViewModel = viewModel(factory = StopDetailsViewModelFactory()),
) {
    val savedStops by viewModel.savedStops.collectAsState()
    val savedRoutes by viewModel.savedRoutes.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Stops", "Routes")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Saved") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = Color.White,
                    ),
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                        )
                    }
                }
            }
        },
        bottomBar = {
            BottomNavBar(selected = 2, onSelect = { idx ->
                when (idx) {
                    0 -> navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                    1 -> navController.navigate(Routes.PLAN_PLAIN) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                    3 -> navController.navigate(Routes.MORE) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                }
            })
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> {
                if (savedStops.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text("No saved stops yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("Tap the bookmark icon on a stop to save it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        items(savedStops, key = { it.id }) { stop ->
                            SavedStopRow(
                                stop = stop,
                                onClick = { navController.navigate(Routes.stopDetails(stop.id, stop.name, stop.code)) },
                                onPlanTrip = {
                                    navController.navigate(
                                        if (stop.lat != 0.0 && stop.lon != 0.0)
                                            Routes.planWithDest(stop.name, stop.lat, stop.lon)
                                        else
                                            Routes.PLAN_PLAIN,
                                    )
                                },
                                onRemove = { viewModel.toggleSaved(stop.id, stop.name, stop.code) },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }
            1 -> {
                if (savedRoutes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text("No saved routes yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("Tap the bookmark icon on a route to save it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        items(savedRoutes, key = { it.routeId }) { route ->
                            SavedRouteRow(
                                route = route,
                                onClick = {
                                    navController.navigate(
                                        Routes.routeDetails(
                                            tripId = route.tripId,
                                            routeId = route.routeId,
                                            routeShort = route.shortName,
                                            routeLong = route.longName,
                                            headsign = route.headsign,
                                            stopId = "",
                                        )
                                    )
                                },
                                onRemove = { viewModel.toggleSavedRoute(route) },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedStopRow(stop: SavedStop, onClick: () -> Unit, onPlanTrip: () -> Unit, onRemove: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Blue600, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stop.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                if (stop.code.isNotBlank()) {
                    Text("Stop #${stop.code}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.BookmarkRemove, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPlanTrip, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Plan Trip", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SavedRouteRow(route: SavedRoute, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Blue600, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (route.shortName.isNotBlank()) {
                    Text(
                        route.shortName,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Blue600,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    route.headsign.ifBlank { route.longName },
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            if (route.longName.isNotBlank() && route.longName != route.headsign) {
                Text(route.longName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.BookmarkRemove, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
        }
    }
}
