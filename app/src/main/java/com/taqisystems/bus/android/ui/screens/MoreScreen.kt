// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.screens

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
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

@Composable
fun MoreScreen(navController: NavController) {
    val reminderCount by ServiceLocator.preferences.activeReminders.collectAsState(initial = emptyList())
    val uriHandler = LocalUriHandler.current

    Scaffold(
        bottomBar = {
            BottomNavBar(selected = 3, onSelect = { idx ->
                when (idx) {
                    0 -> navController.popBackStack(Routes.HOME, inclusive = false)
                    1 -> navController.navigate(Routes.PLAN_PLAIN)  { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                    2 -> navController.navigate(Routes.SAVED)       { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
                }
            })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Branded header ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.logo),
                        contentDescription = stringResource(R.string.onboarding_logo_cd),
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            stringResource(R.string.more_tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Section: Manage ──────────────────────────────────────────────
            SectionLabel(stringResource(R.string.more_section_manage))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                tonalElevation = 0.dp,
            ) {
                Column {
                    MenuRow(
                        icon          = Icons.Default.Alarm,
                        tileColor     = Color(0xFFE65100),
                        title         = stringResource(R.string.nav_reminders),
                        subtitle      = if (reminderCount.isNotEmpty()) stringResource(R.string.more_reminders_active, reminderCount.size) else stringResource(R.string.more_reminders_no_active),
                        badgeCount    = reminderCount.size,
                        showDivider   = false,
                    ) { navController.navigate(Routes.REMINDERS) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Section: App ─────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.more_section_app))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                tonalElevation = 0.dp,
            ) {
                Column {
                    MenuRow(
                        icon        = Icons.Default.Settings,
                        tileColor   = Color(0xFF37474F),
                        title       = stringResource(R.string.settings_title),
                        subtitle    = stringResource(R.string.more_settings_subtitle),
                        showDivider = true,
                    ) { navController.navigate(Routes.SETTINGS) }

                    MenuRow(
                        icon        = Icons.Default.Info,
                        tileColor   = Color(0xFF2E7D32),
                        title       = stringResource(R.string.about_title),
                        subtitle    = stringResource(R.string.more_about_subtitle),
                        showDivider = true,
                    ) { navController.navigate(Routes.ABOUT) }

                    MenuRow(
                        icon        = Icons.Default.Speed,
                        tileColor   = Color(0xFF00695C),
                        title       = stringResource(R.string.more_status_title),
                        subtitle    = stringResource(R.string.more_status_subtitle),
                        showDivider = true,
                    ) { uriHandler.openUri(com.taqisystems.bus.android.BuildConfig.STATUS_PAGE_URL) }

                    MenuRow(
                        icon        = Icons.Default.Feedback,
                        tileColor   = Color(0xFF6A1B9A),
                        title       = stringResource(R.string.more_feedback_title),
                        subtitle    = stringResource(R.string.more_feedback_subtitle),
                        showDivider = false,
                    ) { navController.navigate(Routes.FEEDBACK) }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label.uppercase(),
        style     = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color      = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.8.sp,
        modifier  = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    tileColor: Color,
    title: String,
    subtitle: String,
    badgeCount: Int = 0,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Colored rounded-square icon tile
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tileColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint     = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color      = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (badgeCount > 0) {
                Badge(containerColor = MaterialTheme.colorScheme.error) {
                    Text(
                        if (badgeCount > 99) "99+" else badgeCount.toString(),
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }

        if (showDivider) {
            HorizontalDivider(
                modifier  = Modifier.padding(start = 70.dp),
                color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}
