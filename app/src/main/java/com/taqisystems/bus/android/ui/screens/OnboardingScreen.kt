// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.screens

import androidx.annotation.StringRes
import com.taqisystems.bus.android.BuildConfig
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.taqisystems.bus.android.R
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.ui.theme.Primary
import com.taqisystems.bus.android.ui.theme.PrimaryContainer
import com.taqisystems.bus.android.ui.theme.OnPrimaryContainer
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    @StringRes val navLabelId: Int,  // 0 = welcome page (no nav label)
    @StringRes val titleId: Int,
    @StringRes val bodyId: Int,
)

private val pages = listOf(
    OnboardingPage(
        icon = Icons.Default.DirectionsBus,
        navLabelId = 0,
        titleId = R.string.onboarding_welcome_title,
        bodyId = R.string.onboarding_welcome_body,
    ),
    OnboardingPage(
        icon = Icons.Filled.Map,
        navLabelId = R.string.nav_map,
        titleId = R.string.onboarding_map_title,
        bodyId = R.string.onboarding_map_body,
    ),
    OnboardingPage(
        icon = Icons.Filled.DirectionsTransit,
        navLabelId = R.string.nav_plan,
        titleId = R.string.onboarding_plan_title,
        bodyId = R.string.onboarding_plan_body,
    ),
    OnboardingPage(
        icon = Icons.Filled.Bookmark,
        navLabelId = R.string.nav_saved,
        titleId = R.string.onboarding_saved_title,
        bodyId = R.string.onboarding_saved_body,
    ),
    OnboardingPage(
        icon = Icons.Filled.NotificationsActive,
        navLabelId = R.string.nav_reminders,
        titleId = R.string.onboarding_reminder_title,
        bodyId = R.string.onboarding_reminder_body,
    ),
    OnboardingPage(
        icon = Icons.Filled.MoreHoriz,
        navLabelId = R.string.nav_more,
        titleId = R.string.onboarding_more_title,
        bodyId = R.string.onboarding_more_body,
    ),
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val prefs = ServiceLocator.preferences

    fun advance() {
        if (pagerState.currentPage < pages.lastIndex) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        } else {
            scope.launch {
                prefs.setOnboardingComplete()
                onFinished()
            }
        }
    }

    fun skip() {
        scope.launch {
            prefs.setOnboardingComplete()
            onFinished()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            // ── Pager ─────────────────────────────────────────────────────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                OnboardingPage(page = pages[page])
            }

            // ── Bottom controls ────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Dot indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(pages.size) { idx ->
                        val isSelected = idx == pagerState.currentPage
                        val width by animateDpAsState(
                            targetValue = if (isSelected) 24.dp else 8.dp,
                            label = "dot_width",
                        )
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(width)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) Primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                    }
                }

                // Buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Skip — hidden on last page
                    if (pagerState.currentPage < pages.lastIndex) {
                        TextButton(onClick = ::skip) {
                            Text(
                                stringResource(R.string.onboarding_skip),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        Spacer(Modifier.width(64.dp))
                    }

                    Button(
                        onClick = ::advance,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            text = if (pagerState.currentPage == pages.lastIndex) stringResource(R.string.onboarding_get_started) else stringResource(R.string.onboarding_next),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val navLabel = if (page.navLabelId != 0) stringResource(page.navLabelId) else ""
        if (navLabel.isEmpty()) {
            // Welcome page — logo inside the same circle shape as icon pages
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(PrimaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = "${BuildConfig.APP_NAME} logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(140.dp),
                )
            }
        } else {
            // Feature pages — icon in circle matching the nav bar selected state
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(PrimaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = Primary,
                )
            }
        }

        // Nav-tab label chip — shown for pages that correspond to a bottom nav tab
        if (navLabel.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = PrimaryContainer,
            ) {
                Text(
                    text = navLabel,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnPrimaryContainer,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = stringResource(page.titleId),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(page.bodyId),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )
    }
}
