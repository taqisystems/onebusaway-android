package com.taqisystems.bus.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.ui.screens.AboutScreen
import com.taqisystems.bus.android.ui.screens.FeedbackScreen
import com.taqisystems.bus.android.ui.screens.NotificationsScreen
import com.taqisystems.bus.android.ui.screens.RemindersScreen
import com.taqisystems.bus.android.ui.screens.HomeMapScreen
import com.taqisystems.bus.android.ui.screens.MoreScreen
import com.taqisystems.bus.android.ui.screens.OnboardingScreen
import com.taqisystems.bus.android.ui.screens.RouteDetailsScreen
import com.taqisystems.bus.android.ui.screens.SavedScreen
import com.taqisystems.bus.android.ui.screens.SettingsScreen
import com.taqisystems.bus.android.ui.screens.StopDetailsScreen
import com.taqisystems.bus.android.ui.screens.TripItineraryScreen
import com.taqisystems.bus.android.ui.screens.TripPlannerScreen
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val HOME = "home"
    const val PLAN = "plan?dest={dest}&destLat={destLat}&destLon={destLon}"
    const val SAVED = "saved"
    const val MORE = "more"
    const val SETTINGS = "settings"
    const val STOP_DETAILS = "stop/{stopId}?name={stopName}&code={stopCode}"
    const val ROUTE_DETAILS = "route/{tripId}?routeId={routeId}&routeShort={routeShort}&routeLong={routeLong}&headsign={headsign}&stopId={stopId}"
    const val TRIP_FLOW = "trip_flow"
    const val TRIP_ITINERARY = "itinerary"
    const val ONBOARDING = "onboarding"
    const val ABOUT = "about"
    const val FEEDBACK = "feedback"
    const val NOTIFICATIONS = "notifications"
    const val REMINDERS = "reminders"

    /** Navigate to plan screen without pre-fill */
    const val PLAN_PLAIN = "plan"

    fun planWithDest(destName: String, destLat: Double, destLon: Double) =
        "plan?dest=${URLEncoder.encode(destName, "UTF-8")}&destLat=$destLat&destLon=$destLon"

    fun stopDetails(stopId: String, stopName: String, stopCode: String) =
        "stop/${stopId}?name=${URLEncoder.encode(stopName, "UTF-8")}&code=${URLEncoder.encode(stopCode, "UTF-8")}"

    fun routeDetails(
        tripId: String,
        routeId: String,
        routeShort: String,
        routeLong: String,
        headsign: String,
        stopId: String,
    ) = "route/${tripId}?routeId=${URLEncoder.encode(routeId, "UTF-8")}" +
        "&routeShort=${URLEncoder.encode(routeShort, "UTF-8")}" +
        "&routeLong=${URLEncoder.encode(routeLong, "UTF-8")}" +
        "&headsign=${URLEncoder.encode(headsign, "UTF-8")}" +
        "&stopId=${URLEncoder.encode(stopId, "UTF-8")}"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val prefs = ServiceLocator.preferences
    // null = still loading from DataStore, Boolean = resolved
    val onboardingComplete by prefs.onboardingComplete.collectAsState(initial = null)

    // Show blank while DataStore emits the first value (avoids wrong-start-destination flash)
    if (onboardingComplete == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    // Freeze the start destination — evaluated once when DataStore first resolves.
    // Using remember without a key means NavHost never re-creates itself when
    // onboardingComplete later flips to true (which would fight the navigate() call).
    val startDestination = remember { if (onboardingComplete == true) Routes.HOME else Routes.ONBOARDING }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeMapScreen(navController = navController)
        }
        // ── Trip planner flow — nested graph so plan + itinerary share a scoped ViewModel ──
        navigation(
            startDestination = "plan?dest={dest}&destLat={destLat}&destLon={destLon}",
            route = Routes.TRIP_FLOW,
        ) {
        composable(
            route = "plan?dest={dest}&destLat={destLat}&destLon={destLon}",
            arguments = listOf(
                navArgument("dest") { type = NavType.StringType; defaultValue = "" },
                navArgument("destLat") { type = NavType.StringType; defaultValue = "" },
                navArgument("destLon") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStack ->
            val destName = URLDecoder.decode(backStack.arguments?.getString("dest") ?: "", "UTF-8")
            val destLat = backStack.arguments?.getString("destLat")?.toDoubleOrNull()
            val destLon = backStack.arguments?.getString("destLon")?.toDoubleOrNull()
            TripPlannerScreen(
                navController = navController,
                prefilledDestName = destName.ifBlank { null },
                prefilledDestLat = destLat,
                prefilledDestLon = destLon,
            )
        }
        composable(Routes.TRIP_ITINERARY) {
            TripItineraryScreen(navController = navController)
        }
        }
        composable(Routes.SAVED) {
            SavedScreen(navController = navController)
        }
        composable(Routes.MORE) {
            MoreScreen(navController = navController)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(navController = navController)
        }
        composable(Routes.ABOUT) {
            AboutScreen(navController = navController)
        }
        composable(Routes.FEEDBACK) {
            FeedbackScreen(navController = navController)
        }
        composable(Routes.NOTIFICATIONS) {
            NotificationsScreen(navController = navController)
        }
        composable(Routes.REMINDERS) {
            RemindersScreen(navController = navController)
        }
        composable(
            route = "stop/{stopId}?name={stopName}&code={stopCode}",
            arguments = listOf(
                navArgument("stopId") { type = NavType.StringType },
                navArgument("stopName") { type = NavType.StringType; defaultValue = "Bus Stop" },
                navArgument("stopCode") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStack ->
            val stopId = backStack.arguments?.getString("stopId") ?: ""
            val stopName = URLDecoder.decode(backStack.arguments?.getString("stopName") ?: "", "UTF-8")
            val stopCode = URLDecoder.decode(backStack.arguments?.getString("stopCode") ?: "", "UTF-8")
            StopDetailsScreen(
                navController = navController,
                stopId = stopId,
                stopName = stopName,
                stopCode = stopCode,
            )
        }
        composable(
            route = "route/{tripId}?routeId={routeId}&routeShort={routeShort}&routeLong={routeLong}&headsign={headsign}&stopId={stopId}",
            arguments = listOf(
                navArgument("tripId") { type = NavType.StringType },
                navArgument("routeId") { type = NavType.StringType; defaultValue = "" },
                navArgument("routeShort") { type = NavType.StringType; defaultValue = "" },
                navArgument("routeLong") { type = NavType.StringType; defaultValue = "" },
                navArgument("headsign") { type = NavType.StringType; defaultValue = "" },
                navArgument("stopId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStack ->
            fun decode(key: String) = URLDecoder.decode(backStack.arguments?.getString(key) ?: "", "UTF-8")
            RouteDetailsScreen(
                navController = navController,
                tripId = decode("tripId"),
                routeId = decode("routeId"),
                routeShortName = decode("routeShort"),
                routeLongName = decode("routeLong"),
                tripHeadsign = decode("headsign"),
                stopId = decode("stopId"),
            )
        }
    }
}
