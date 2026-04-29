package com.taqisystems.bus.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.taqisystems.bus.android.ServiceLocator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.taqisystems.bus.android.R
import com.taqisystems.bus.android.ui.navigation.Routes
import com.taqisystems.bus.android.ui.theme.Blue600

@Composable
fun MoreScreen(navController: NavController) {
    val unreadCount by ServiceLocator.preferences.unreadNotificationCount.collectAsState(initial = 0)
    Scaffold(
        bottomBar = {
            BottomNavBar(selected = 3, onSelect = { idx ->
                when (idx) {
                    0 -> navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                    1 -> navController.navigate(Routes.PLAN_PLAIN) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                    2 -> navController.navigate(Routes.SAVED) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                }
            })
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // ── Branded header ────────────────────────────────────────────────
            // Bold transit-red banner with logo + app name.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.logo),
                        contentDescription = "Kelantan Bus logo",
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Kelantan Bus",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            text = "Real-time transit for Malaysia",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.80f),
                            letterSpacing = 0.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            MenuRow(
                icon = Icons.Default.Notifications,
                title = "Notifications",
                subtitle = if (unreadCount > 0) "$unreadCount unread" else "All caught up",
                tint = Blue600,
                badgeCount = unreadCount,
            ) {
                navController.navigate(Routes.NOTIFICATIONS)
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            MenuRow(icon = Icons.Default.Settings, title = "Settings", subtitle = "Region, preferences", tint = Blue600) {
                navController.navigate(Routes.SETTINGS)
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            MenuRow(icon = Icons.Default.Info, title = "About Kelantan Bus", subtitle = "App version & info", tint = Blue600) {
                navController.navigate(Routes.ABOUT)
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            MenuRow(icon = Icons.Default.Feedback, title = "Feedback", subtitle = "Report an issue", tint = Blue600) {
                navController.navigate(Routes.FEEDBACK)
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, tint: Color, badgeCount: Int = 0, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (badgeCount > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.error) {
                Text(if (badgeCount > 99) "99+" else badgeCount.toString(), fontSize = 10.sp)
            }
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
