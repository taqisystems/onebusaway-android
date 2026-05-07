package com.taqisystems.bus.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.taqisystems.bus.android.BuildConfig
import com.taqisystems.bus.android.R

private val openSourceLibraries = listOf(
    Triple("Kotlin", "JetBrains", "Apache License 2.0"),
    Triple("Jetpack Compose", "Google / AndroidX", "Apache License 2.0"),
    Triple("Compose Material 3", "Google / AndroidX", "Apache License 2.0"),
    Triple("Compose Navigation", "Google / AndroidX", "Apache License 2.0"),
    Triple("Lifecycle ViewModel Compose", "Google / AndroidX", "Apache License 2.0"),
    Triple("AndroidX DataStore", "Google / AndroidX", "Apache License 2.0"),
    Triple("OkHttp", "Square, Inc.", "Apache License 2.0"),
    Triple("Retrofit", "Square, Inc.", "Apache License 2.0"),
    Triple("Gson", "Google", "Apache License 2.0"),
    Triple("Kotlinx Coroutines", "JetBrains", "Apache License 2.0"),
    Triple("Maps Compose", "Google Maps Platform", "Apache License 2.0"),
    Triple("Google Play Services — Maps", "Google", "Google APIs Terms of Service"),
    Triple("Google Play Services — Location", "Google", "Google APIs Terms of Service"),
    Triple("OneBusAway SDK (Kotlin)", "OneBusAway", "Apache License 2.0"),
    Triple("OneSignal Android SDK", "OneSignal, Inc.", "MIT License"),
    Triple("Material Components for Android", "Google", "Apache License 2.0"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About ${BuildConfig.APP_NAME}") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── App identity card ─────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = "${BuildConfig.APP_NAME} logo",
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    BuildConfig.APP_NAME,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Real-time transit information for Malaysia,\npowered by OneBusAway & OpenTripPlanner.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            HorizontalDivider()

            // ── Developer credit ──────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SectionHeader("Developer")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Taqi Systems",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Kota Bharu, Kelantan, Malaysia",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} Taqi Systems. " +
                            "All rights reserved. This application and its source code are proprietary " +
                            "and confidential. Unauthorised copying, distribution, or modification is " +
                            "strictly prohibited.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                        lineHeight = 18.sp,
                    )
                }
            }

            HorizontalDivider()

            // ── Open-source licences ──────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SectionHeader("Open-Source Libraries")
                Spacer(Modifier.height(4.dp))
                Text(
                    "${BuildConfig.APP_NAME} is built with the following open-source libraries:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                openSourceLibraries.forEachIndexed { index, (name, author, license) ->
                    if (index > 0) HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                license,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.sp,
    )
}
