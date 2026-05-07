// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.taqisystems.bus.android.data.model.SavedRoute
import com.taqisystems.bus.android.data.model.SavedStop
import com.taqisystems.bus.android.ui.navigation.Routes
import com.taqisystems.bus.android.ui.theme.Blue600
import com.taqisystems.bus.android.ui.theme.Primary
import com.taqisystems.bus.android.ui.viewmodel.StopDetailsViewModel
import com.taqisystems.bus.android.ui.viewmodel.StopDetailsViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    navController: NavController,
    viewModel: StopDetailsViewModel = viewModel(factory = StopDetailsViewModelFactory()),
) {
    val savedStops  by viewModel.savedStops.collectAsState()
    val savedRoutes by viewModel.savedRoutes.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Saved", fontWeight = FontWeight.Bold)
                            val subtitle = when (selectedTab) {
                                0 -> if (savedStops.isEmpty()) "No saved stops" else "${savedStops.size} stop${if (savedStops.size == 1) "" else "s"}"
                                else -> if (savedRoutes.isEmpty()) "No saved routes" else "${savedRoutes.size} route${if (savedRoutes.size == 1) "" else "s"}"
                            }
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick  = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Stops")
                            }
                        },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick  = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.DirectionsBus, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Routes")
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            BottomNavBar(selected = 2, onSelect = { idx ->
                when (idx) {
                    0 -> navController.popBackStack(Routes.HOME, inclusive = false)
                    1 -> navController.navigate(Routes.PLAN_PLAIN) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                    3 -> navController.navigate(Routes.MORE) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                }
            })
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> SavedStopsList(
                stops       = savedStops,
                innerPadding = innerPadding,
                onClickStop = { stop -> navController.navigate(Routes.stopDetails(stop.id, stop.name, stop.code)) },
                onPlanTrip  = { stop ->
                    navController.navigate(
                        if (stop.lat != 0.0 && stop.lon != 0.0)
                            Routes.planWithDest(stop.name, stop.lat, stop.lon)
                        else Routes.PLAN_PLAIN,
                    )
                },
                onRemove    = { stop -> viewModel.toggleSaved(stop.id, stop.name, stop.code) },
            )
            1 -> SavedRoutesList(
                routes       = savedRoutes,
                innerPadding = innerPadding,
                onClickRoute = { route ->
                    navController.navigate(
                        Routes.routeDetails(
                            tripId     = route.tripId,
                            routeId    = route.routeId,
                            routeShort = route.shortName,
                            routeLong  = route.longName,
                            headsign   = route.headsign,
                            stopId     = "",
                        )
                    )
                },
                onRemove = { route -> viewModel.toggleSavedRoute(route) },
            )
        }
    }
}

// ─── Stops list ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedStopsList(
    stops: List<SavedStop>,
    innerPadding: PaddingValues,
    onClickStop: (SavedStop) -> Unit,
    onPlanTrip: (SavedStop) -> Unit,
    onRemove: (SavedStop) -> Unit,
) {
    if (stops.isEmpty()) {
        SavedEmptyState(
            icon     = Icons.Default.LocationOff,
            title    = "No saved stops",
            subtitle = "Tap the bookmark icon on any stop to save it here.",
            padding  = innerPadding,
        )
    } else {
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(stops, key = { it.id }) { stop ->
                SwipeToDismissItem(onDismiss = { onRemove(stop) }) {
                    SavedStopRow(
                        stop       = stop,
                        onClick    = { onClickStop(stop) },
                        onPlanTrip = { onPlanTrip(stop) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}

// ─── Routes list ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedRoutesList(
    routes: List<SavedRoute>,
    innerPadding: PaddingValues,
    onClickRoute: (SavedRoute) -> Unit,
    onRemove: (SavedRoute) -> Unit,
) {
    if (routes.isEmpty()) {
        SavedEmptyState(
            icon     = Icons.Default.BookmarkBorder,
            title    = "No saved routes",
            subtitle = "Tap the bookmark icon on any route to save it here.",
            padding  = innerPadding,
        )
    } else {
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(routes, key = { it.routeId }) { route ->
                SwipeToDismissItem(onDismiss = { onRemove(route) }) {
                    SavedRouteRow(
                        route   = route,
                        onClick = { onClickRoute(route) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun SavedEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    padding: PaddingValues,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style     = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color     = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style  = MaterialTheme.typography.bodySmall,
                color  = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Swipe-to-dismiss wrapper ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissItem(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) { onDismiss(); true } else false
        },
        positionalThreshold = { it * 0.4f },
    )
    SwipeToDismissBox(
        state                  = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val bg by animateColorAsState(
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surface,
                label = "swipe_bg",
            )
            Box(
                modifier           = Modifier.fillMaxSize().background(bg).padding(end = 24.dp),
                contentAlignment   = Alignment.CenterEnd,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.BookmarkRemove,
                        contentDescription = "Remove",
                        tint     = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Remove",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

// ─── Stop row ─────────────────────────────────────────────────────────────────

@Composable
private fun SavedStopRow(stop: SavedStop, onClick: () -> Unit, onPlanTrip: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon tile
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                stop.name,
                fontWeight  = FontWeight.SemiBold,
                style       = MaterialTheme.typography.bodyMedium,
                maxLines    = 2,
                overflow    = TextOverflow.Ellipsis,
            )
            if (stop.code.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Stop #${stop.code}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            // Plan trip chip
            Surface(
                onClick = onPlanTrip,
                shape   = RoundedCornerShape(50),
                color   = Primary.copy(alpha = 0.10f),
                modifier = Modifier.height(26.dp),
            ) {
                Row(
                    modifier  = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null, tint = Primary, modifier = Modifier.size(12.dp))
                    Text("Plan Trip", style = MaterialTheme.typography.labelSmall, color = Primary)
                }
            }
        }

        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ─── Route row ────────────────────────────────────────────────────────────────

@Composable
private fun SavedRouteRow(route: SavedRoute, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Route number badge tile
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (route.shortName.isNotBlank()) {
                Text(
                    route.shortName,
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines   = 1,
                    fontSize   = if (route.shortName.length <= 3) 14.sp else 11.sp,
                )
            } else {
                Icon(
                    Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            val headsign = route.headsign.ifBlank { route.longName }
            if (headsign.isNotBlank()) {
                Text(
                    headsign,
                    fontWeight  = FontWeight.SemiBold,
                    style       = MaterialTheme.typography.bodyMedium,
                    maxLines    = 1,
                    overflow    = TextOverflow.Ellipsis,
                )
            }
            if (route.longName.isNotBlank() && route.longName != route.headsign) {
                Spacer(Modifier.height(2.dp))
                Text(
                    route.longName,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
