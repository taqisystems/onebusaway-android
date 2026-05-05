package com.taqisystems.bus.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DirectionsTransit
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taqisystems.bus.android.ui.theme.OnSurfaceVariant
import com.taqisystems.bus.android.ui.theme.OutlineVariant
import com.taqisystems.bus.android.ui.theme.Primary
import com.taqisystems.bus.android.ui.theme.SurfaceContainerLow

/**
 * Bottom navigation bar — white bg, subtle top divider, navy active, grey inactive.
 * Matches the original design: bg-white, border-t border-slate-100, text-blue-900 active.
 *
 * [selected] = 0:Map, 1:Plan, 2:Saved, 3:More
 */
@Composable
fun BottomNavBar(
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = modifier) {
        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.6f), thickness = 1.dp)
        NavigationBar(
            containerColor = Color.White,
            tonalElevation  = 0.dp,
        ) {
            val itemColors = NavigationBarItemDefaults.colors(
                selectedIconColor   = Primary,
                selectedTextColor   = Primary,
                indicatorColor      = SurfaceContainerLow,
                unselectedIconColor = OnSurfaceVariant,
                unselectedTextColor = OnSurfaceVariant,
            )
            NavigationBarItem(
                selected = selected == 0,
                onClick  = { onSelect(0) },
                icon     = {
                    Icon(if (selected == 0) Icons.Filled.Map else Icons.Outlined.Map, "Map")
                },
                label  = { Text("Map", fontSize = 10.sp) },
                colors = itemColors,
            )
            NavigationBarItem(
                selected = selected == 1,
                onClick  = { onSelect(1) },
                icon     = {
                    Icon(
                        if (selected == 1) Icons.Filled.DirectionsTransit else Icons.Outlined.DirectionsTransit,
                        "Plan",
                    )
                },
                label  = { Text("Plan", fontSize = 10.sp) },
                colors = itemColors,
            )
            NavigationBarItem(
                selected = selected == 2,
                onClick  = { onSelect(2) },
                icon     = {
                    Icon(
                        if (selected == 2) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        "Saved",
                    )
                },
                label  = { Text("Saved", fontSize = 10.sp) },
                colors = itemColors,
            )
            NavigationBarItem(
                selected = selected == 3,
                onClick  = { onSelect(3) },
                icon     = { Icon(Icons.Filled.MoreHoriz, "More") },
                label  = { Text("More", fontSize = 10.sp) },
                colors = itemColors,
            )
        }
    }
}
