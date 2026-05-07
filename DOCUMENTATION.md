# KelantanBus Android — Project Documentation

> Native Android port of the KelantanBus React Native commuter transit app.  
> Package: `com.taqisystems.bus.android`  
> Build status: ✅ `assembleDebug` passing

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Tech Stack & Dependencies](#2-tech-stack--dependencies)
3. [Project Structure](#3-project-structure)
4. [Architecture](#4-architecture)
5. [Data Layer](#5-data-layer)
   - [Domain Models](#51-domain-models)
   - [Repositories](#52-repositories)
   - [AppPreferences](#53-apppreferences)
   - [ServiceLocator](#54-servicelocator)
6. [UI Layer](#6-ui-layer)
   - [Theme](#61-theme)
   - [Navigation](#62-navigation)
   - [Screens](#63-screens)
   - [ViewModels](#64-viewmodels)
7. [Build Configuration](#7-build-configuration)
   - [Credentials & local.properties](#71-credentials--localproperties)
   - [White-Label / Product Flavors](#72-white-label--product-flavors)
   - [BrandConfig](#73-brandconfig)
   - [Packaging exclusions](#74-packaging-exclusions)
8. [API Endpoints](#8-api-endpoints)
9. [Notification Infrastructure](#9-notification-infrastructure)
10. [Map Marker Specifications](#10-map-marker-specifications)
11. [Known Quirks & SDK Notes](#11-known-quirks--sdk-notes)

---

## 1. Project Overview

KelantanBus is a real-time transit app for the Kelantan public bus network. It shows live bus arrivals at nearby stops, allows trip planning using OpenTripPlanner, and lets users save favourite stops.

### Feature Summary

| Feature | Description |
|---|---|
| Live map | Google Map centred on user location; zoom-adaptive stop markers |
| Stop arrivals | Real-time + scheduled arrivals from OneBusAway API with 30 s auto-refresh |
| Arrival reminders | Push reminder N minutes before your ride arrives (OneSignal + sidecar) |
| Destination alerts | GPS foreground service: TTS voice + vibration as bus approaches stop |
| Trip planner | Origin → Destination routing via OpenTripPlanner (OTP) |
| Itinerary view | Leg-by-leg breakdown with **Destination Alert** FAB |
| Route timeline | Live vehicle position overlaid on stop timeline |
| Overview polylines | Semi-transparent route shapes + label pills loaded from sidecar GeoJSON |
| Saved stops | Persist favourite stops in DataStore |
| Multi-region | 11 Malaysian regions selectable; per-region sidecar base URL |
| Settings | Edit OBA/OTP base URLs at runtime |
| Localisation | Full English + Bahasa Malaysia UI strings |

---

## 2. Tech Stack & Dependencies

### Build toolchain

| Tool | Version |
|---|---|
| Android Gradle Plugin (AGP) | 9.0.1 |
| Kotlin | 2.2.0 |
| Gradle | 9.1.0 |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 24 (Android 7.0) |

> **Important:** AGP 9.0.1 bundles its own Kotlin toolchain. The `kotlin.android` plugin must **not** be declared in `app/build.gradle.kts` to avoid a duplicate-extension error. Only `kotlin.plugin.compose` is needed at the app module level.

### Localisation

The app ships with two locale directories:

| Locale qualifier | File | Status |
|---|---|---|
| _(default)_ | `app/src/main/res/values/strings.xml` | English — complete |
| `ms` | `app/src/main/res/values-ms/strings.xml` | Bahasa Malaysia — complete |

All user-visible strings in Kotlin source use `stringResource(R.string.*)` or `pluralStringResource(R.plurals.*)` — no hardcoded string literals remain in UI code. Adding a new locale requires only a new `values-<qualifier>/strings.xml`; no Kotlin changes are needed.

### Library versions (`gradle/libs.versions.toml`)

| Alias | Artifact | Version |
|---|---|---|
| `composeBom` | `androidx.compose:compose-bom` | 2025.04.01 |
| `navigationCompose` | `androidx.navigation:navigation-compose` | 2.9.0 |
| `lifecycleViewmodelCompose` | `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.9.0 |
| `mapsCompose` | `com.google.maps.android:maps-compose` | 6.4.4 |
| `playServicesMaps` | `com.google.android.gms:play-services-maps` | 19.2.0 |
| `playServicesLocation` | `com.google.android.gms:play-services-location` | 21.3.0 |
| `retrofit` | `com.squareup.retrofit2:retrofit` | 2.11.0 |
| `okhttp` | `com.squareup.okhttp3:okhttp` | 4.12.0 |
| `gson` | `com.google.code.gson:gson` | 2.13.1 |
| `coroutines` | `kotlinx-coroutines-android` | 1.9.0 |
| `coroutines` | `kotlinx-coroutines-play-services` | 1.9.0 |
| `datastorePrefs` | `androidx.datastore:datastore-preferences` | 1.1.4 |
| `onebusawaySdk` | `org.onebusaway:onebusaway-sdk-kotlin` | 0.1.0-alpha.77 |

---

## 3. Project Structure

```
KelantanBus/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/
│       │   ├── drawable/            # launcher icons
│       │   ├── mipmap-*/            # adaptive icons
│       │   ├── values/
│       │   │   ├── colors.xml
│       │   │   ├── strings.xml      # English (default)
│       │   │   └── themes.xml       # Material3 no-action-bar base theme
│       │   ├── values-ms/
│       │   │   └── strings.xml      # Bahasa Malaysia translations
│       │   └── xml/
│       │       ├── backup_rules.xml
│       │       └── data_extraction_rules.xml
│       └── java/com/taqisystems/bus/android/
│           ├── KelantanBusApplication.kt   # Application subclass
│           ├── MainActivity.kt             # single Activity; hosts Compose + location permission
│           ├── ServiceLocator.kt           # singleton DI
│           ├── data/
│           │   ├── AppPreferences.kt       # DataStore wrapper
│           │   ├── model/
│           │   │   └── Models.kt           # all domain data classes & enums
│           │   └── repository/
│           │       ├── ObaRepository.kt    # OneBusAway SDK wrapper
│           │       ├── OtpRepository.kt    # OpenTripPlanner REST wrapper
│           │       ├── GeocodingRepository.kt  # Pelias geocoding
│           │       └── RegionsRepository.kt    # regions JSON fetch
│           ├── service/
│           │   └── DestinationAlertService.kt  # GPS foreground service
│           └── ui/
│               ├── map/
│               │   └── VehicleMarkerFactory.kt # vehicle marker bitmap factory
│               ├── navigation/
│               │   └── AppNavigation.kt    # NavHost + Routes
│               ├── screens/
│               │   ├── CommonComponents.kt # shared BottomNavBar
│               │   ├── HomeMapScreen.kt
│               │   ├── StopDetailsScreen.kt
│               │   ├── TripPlannerScreen.kt
│               │   ├── TripItineraryScreen.kt
│               │   ├── RouteDetailsScreen.kt
│               │   ├── SavedScreen.kt
│               │   ├── MoreScreen.kt
│               │   └── SettingsScreen.kt
│               ├── theme/
│               │   ├── Color.kt
│               │   ├── Theme.kt
│               │   └── Type.kt
│               └── viewmodel/
│                   ├── HomeViewModel.kt
│                   ├── StopDetailsViewModel.kt
│                   ├── TripPlannerViewModel.kt
│                   ├── RouteDetailsViewModel.kt
│                   └── SavedViewModel.kt
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts        # root: repos + plugin declarations
└── settings.gradle.kts
```

---

## 4. Architecture

The app follows **MVVM** with a manual service-locator for dependency injection (no Hilt).

```
Activity / Composable
       │  collectAsStateWithLifecycle()
       ▼
  ViewModel  (androidx.lifecycle.ViewModel)
       │  suspend fun / StateFlow<UiState>
       ▼
  Repository  (plain Kotlin class)
       │  SDK / Retrofit / OkHttp
       ▼
  Remote API  (OBA SDK · OTP REST · Pelias · Regions JSON)
       │
  AppPreferences  (DataStore — persisted config & saved stops)
```

- **`ServiceLocator`** initialises all repositories once in `KelantanBusApplication.onCreate()`.
- Each ViewModel has a companion `*Factory` that reads from `ServiceLocator` so `viewModel(factory = …)` works inside Compose.
- UI state is modelled as a single immutable `data class` exposed via `StateFlow<UiState>`.

---

## 5. Data Layer

### 5.1 Domain Models

All models live in `data/model/Models.kt`.

#### `ObaStop`
Represents a physical bus stop returned by the OBA API.

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | e.g. `"1_75403"` |
| `name` | `String` | Human-readable stop name |
| `lat` / `lon` | `Double` | WGS-84 coordinates |
| `code` | `String` | Short stop code displayed on signage |
| `direction` | `String` | Cardinal direction buses travel (`"N"`, `"SW"` …) |
| `routeIds` | `List<String>` | Routes serving this stop |

#### `ObaArrival`
One arrival/departure prediction for a stop.

| Field | Type | Notes |
|---|---|---|
| `routeId` | `String` | |
| `routeShortName` | `String` | e.g. `"30A"` |
| `routeLongName` | `String` | Full route name |
| `tripId` | `String` | |
| `tripHeadsign` | `String` | Destination sign text |
| `predictedArrivalTime` | `Long` | Unix ms; `0` when not predicted |
| `scheduledArrivalTime` | `Long` | Unix ms |
| `predicted` | `Boolean` | `true` = real-time data |
| `status` | `ArrivalStatus` | `ON_TIME \| DELAYED \| EARLY \| SCHEDULED \| UNKNOWN` |
| `minutesUntilArrival` | `Int` | Baked in at fetch time — use `liveMinutesUntilArrival()` in UI |
| `deviationMinutes` | `Int` | `(predicted − scheduled) / 60 000` |
| `vehicleId` | `String?` | |
| `vehicleLat` / `vehicleLon` | `Double?` | Live vehicle position |
| `vehicleOrientation` | `Double?` | Degrees, 0 = north |
| `vehicleLastUpdateTime` | `Long?` | Unix ms |
| `shapeId` | `String?` | Resolved separately via shape endpoint |
| `isHeadway` | `Boolean` | `true` for frequency/headway-based trips (`frequencyType == 1`) |
| `headwaySecs` | `Int?` | Seconds between vehicles; `null` for timetabled trips |
| `headwayEndTime` | `Long?` | Epoch ms when the headway window ends |
| `serviceDate` | `Long` | Epoch ms of midnight of the operating day |
| `stopSequence` | `Int` | Position of this stop in the trip's stop sequence |

**`liveMinutesUntilArrival(): Int`** — recalculates minutes-to-arrival from the stored timestamps at the moment of calling, so displayed times stay accurate after the phone wakes from sleep without requiring a fresh API fetch. Always use this method in the UI instead of reading `minutesUntilArrival` directly.

#### `ObaRoute`

| Field | Type |
|---|---|
| `id`, `shortName`, `longName`, `description` | `String` |
| `agencyId` | `String` |
| `color`, `textColor`, `url` | `String?` |
| `type` | `Int` (GTFS route type) |

#### `TripStop`
A stop within a trip's schedule, used in `TripDetails` and `RouteDetailsViewModel`.

| Field | Type | Notes |
|---|---|---|
| `stopId` | `String` | |
| `stopName` | `String` | Also exposed as `.name` property |
| `arrivalTime` | `Int` | Seconds since midnight |
| `departureTime` | `Int` | Seconds since midnight |
| `lat` / `lon` | `Double` | Default `0.0`; enriched from references when available |

#### `TripDetails`

| Field | Type |
|---|---|
| `tripId`, `headsign` | `String` |
| `stops` | `List<TripStop>` |
| `closestStopId`, `nextStopId` | `String?` |
| `distanceAlongTrip`, `totalDistance`, `scheduleDeviation` | `Double` |
| `predicted` | `Boolean` |
| `vehicleId` | `String?` |

#### `ObaRegion` / `RegionBound`
Used for multi-region support; fetched from the regions JSON feed.

#### `PlaceResult`
Geocoding result from Pelias.

| Field | Type | Notes |
|---|---|---|
| `label` | `String` | Full formatted address |
| `name` | `String` | Short place name (defaults to `label`) |
| `address` | `String` | Secondary address line |
| `lat` / `lon` | `Double` | |

#### OTP Trip Planning models

| Class | Purpose |
|---|---|
| `OtpPlace` | Named coordinate used as origin/destination |
| `OtpIntermediateStop` | A stop mid-leg |
| `OtpLeg` | One segment (walk or transit) of an itinerary |
| `OtpItinerary` | Full planned trip; contains `List<OtpLeg>` |

#### `OverviewRoute`
A route shape entry returned by the sidecar for the map overview layer.

| Field | Type | Notes |
|---|---|---|
| `shortName` | `String` | Route short name, e.g. `"30A"` — used as the pill label |
| `points` | `List<RoutePoint>` | Decoded polyline for the route's shape |

---

#### `RoutePoint`
Thin `(lat: Double, lon: Double)` wrapper used for decoded polyline shape points.

---

### 5.2 Repositories

#### `ObaRepository`

Wraps `OnebusawaySdkOkHttpClientAsync` — the official OneBusAway **Kotlin** SDK client. All methods are `suspend` functions. One client instance is created per `ObaRepository` instance (ServiceLocator holds a singleton).

```kotlin
class ObaRepository(
    baseUrl: String = "https://api.kelantanbus.com",
    apiKey: String  = "TEST",
)
```

| Method | SDK call | Returns |
|---|---|---|
| `getStopsForLocation(lat, lon, radius)` | `client.stopsForLocation().list(params)` | `List<ObaStop>` |
| `getArrivalsForStop(stopId)` | `client.arrivalAndDeparture().list(params)` | `List<ObaArrival>` |
| `getRoute(routeId)` | `client.route().retrieve(params)` | `ObaRoute?` |
| `getTripDetails(tripId)` | `client.tripDetails().retrieve(params)` | `TripDetails?` |
| `getRouteShape(shapeId)` | `client.shape().retrieve(shapeId)` | `List<RoutePoint>` |

**SDK package note:** The arrivals list params class lives at  
`org.onebusaway.models.arrivalanddeparture.ArrivalAndDepartureListParams`  
(singular `arrivalanddeparture`, **not** `arrivalsanddepartures`).

**SDK response chain for arrivals:**
```
response
  .data()            // ArrivalAndDepartureListResponse.Data
  .entry()           // …Data.Entry
  .arrivalsAndDepartures()   // List<ArrivalsAndDeparture>
```

Each `ArrivalsAndDeparture` exposes:

| Method | Return type |
|---|---|
| `routeId()` | `String` |
| `routeShortName()` | `String?` |
| `routeLongName()` | `String?` |
| `tripId()` | `String` |
| `tripHeadsign()` | `String` |
| `predictedArrivalTime()` | `Long` (primitive — never null) |
| `scheduledArrivalTime()` | `Long` (primitive — never null) |
| `predicted()` | `Boolean?` (nullable) |
| `vehicleId()` | `String?` |
| `tripStatus()` | `TripStatus` (non-null wrapper) |

`TripStatus` then has `.position()?.lat()`, `.position()?.lon()`, `.orientation()`, `.vehicleId()`, `.lastLocationUpdateTime()`.

**Shape decoding:** `decodePolyline(encoded: String)` is a Google encoded-polyline decoder implemented as a static companion function. `getRouteShape` calls `client.shape().retrieve(shapeId).data().entry().points()` to obtain the encoded polyline string, then passes it to `decodePolyline`.

---

#### `OtpRepository`

Uses Retrofit + Gson to call the OpenTripPlanner v1 REST API.

```
Base URL: https://otp.kelantanbus.com/otp/routers/default/
```

| Method | Endpoint | Returns |
|---|---|---|
| `planTrip(from, to, time, date, arriveBy)` | `plan` | `List<OtpItinerary>` |

---

#### `GeocodingRepository`

Calls the Pelias geocoding service.

```
Base URL: https://geocode.kelantanbus.com/v1/
```

| Method | Endpoint | Returns |
|---|---|---|
| `autocomplete(text, focusLat, focusLon)` | `autocomplete` | `List<PlaceResult>` |
| `reverse(lat, lon)` | `reverse` | `PlaceResult?` |

---

#### `RegionsRepository`

Fetches and parses the regional OBA server list.

```
Feed URL: https://cdn.unrealasia.net/onebusaway/regions.json
```

| Method | Returns |
|---|---|
| `fetchRegions(forceRefresh: Boolean)` | `List<ObaRegion>` |

Has an in-memory cache; pass `forceRefresh = true` to bypass it.

---

#### `ReminderRepository`

Registers and cancels arrival reminders via the OBA-compatible sidecar.

```
Base URL: {sidecarBaseUrl} (per-region, stored in AppPreferences)
```

| Method | HTTP | Endpoint | Returns |
|---|---|---|---|
| `registerAlarm(sidecarBaseUrl, regionId, stopId, arrival, secondsBefore)` | POST | `/{regionId}/alarms` | `Result<String>` (deleteUrl) |
| `cancelAlarm(sidecarBaseUrl, deleteUrl)` | DELETE | `{deleteUrl}` | `Result<Unit>` |

Request body fields (form-encoded): `seconds_before`, `stop_id`, `trip_id`, `service_date`, `stop_sequence`, `vehicle_id`, `user_push_id` (OneSignal subscription ID), `operating_system=android`.

The `deleteUrl` returned on success is stored in `AppPreferences.activeReminders` as part of an `ActiveReminder` object for later cancellation.

---

### 5.3 AppPreferences

`data/AppPreferences.kt` — DataStore wrapper for persisted app settings and saved stops.

**DataStore file name:** `kelantanbus_prefs`

| Key | Type | Purpose |
|---|---|---|
| `region_id` | `String` | Currently selected region ID |
| `oba_base_url` | `String` | Override OBA server URL |
| `otp_base_url` | `String` | Override OTP server URL |
| `sidecar_base_url` | `String` | Arrival reminder sidecar URL (nullable) |
| `saved_stops_json` | `String` | JSON-serialised `List<SavedStop>` |
| `inbox_notifications_json` | `String` | JSON-serialised `List<InboxNotification>` |
| `active_reminders_json` | `String` | JSON-serialised `List<ActiveReminder>` |
| `last_reminder_minutes` | `Int` | Last reminder time selected by user (default: 5) |

Exposed as `Flow<T>` properties + `suspend fun set*()` mutators:

```kotlin
val regionId: Flow<String?>
val obaBaseUrl: Flow<String?>
val otpBaseUrl: Flow<String?>
val sidecarBaseUrl: Flow<String?>
val savedStops: Flow<List<SavedStop>>
val notifications: Flow<List<InboxNotification>>
val unreadNotificationCount: Flow<Int>
val activeReminders: Flow<List<ActiveReminder>>
val lastReminderMinutes: Flow<Int>          // defaults to 5

suspend fun setRegionId(id: String)
suspend fun setObaBaseUrl(url: String)
suspend fun setOtpBaseUrl(url: String)
suspend fun setSidecarBaseUrl(url: String?)
suspend fun setRegion(id, obaUrl, otpUrl?)            // atomic 3-field write
suspend fun toggleSavedStop(stop, currentList)        // add or remove
suspend fun addActiveReminder(reminder: ActiveReminder)
suspend fun removeActiveReminder(tripId: String)
suspend fun setLastReminderMinutes(minutes: Int)      // persist reminder time selection
```

---

### 5.4 ServiceLocator

`ServiceLocator.kt` — Kotlin `object` singleton acting as a manual DI container.

```kotlin
object ServiceLocator {
    lateinit var application: Application
    lateinit var httpClient: OkHttpClient   // shared client with User-Agent interceptor
    lateinit var obaRepository: ObaRepository
    lateinit var otpRepository: OtpRepository
    lateinit var geocodingRepository: GeocodingRepository
    lateinit var regionsRepository: RegionsRepository
    lateinit var reminderRepository: ReminderRepository
    lateinit var preferences: AppPreferences
    val appPreferences get() = preferences  // alias

    fun init(context: Context) { … }
    fun applyRegionUrls(obaBaseUrl: String) { … }
}
```

`init()` is called from `KelantanBusApplication.onCreate()`. Every ViewModel factory reads from `ServiceLocator` to obtain its dependencies.

**Shared `OkHttpClient` with `User-Agent`:** All repositories share a single `OkHttpClient` instance built in `init()`. A network interceptor attaches a `User-Agent` header to every request:

```
KelantanBus/<versionName> (build <versionCode>; Android <sdk>; <manufacturer> <model>)
Example: KelantanBus/1.4.2 (build 42; Android 33; samsung SM-A715F)
```

This lets server-side logs identify the build number and device model.

> Note: The OBA SDK's internal HTTP client (`OnebusawaySdkOkHttpClientAsync`) is separate and not injectable — it handles OBA API calls only.

---

## 6. UI Layer

### 6.1 Theme

Located in `ui/theme/`.

| File | Contents |
|---|---|
| `Color.kt` | Material3 colour tokens + semantic aliases (`Primary`, `Blue600`, `StatusOnTimeColor`, `StatusDelayedColor`, `StatusEarlyColor`) |
| `Theme.kt` | `KelantanBusTheme` composable; dynamic colour on Android 12+, static fallback otherwise |
| `Type.kt` | `Typography` object |

**Key colour aliases:**

| Alias | Usage |
|---|---|
| `Primary` | Brand colour (blue) |
| `Blue600` | Identical to `Primary` — used in itinerary route chips |
| `StatusOnTimeColor` | Green — on-time arrivals |
| `StatusDelayedColor` | Red — late arrivals |
| `StatusEarlyColor` | Orange — early arrivals |

---

### 6.2 Navigation

`ui/navigation/AppNavigation.kt`

The entire app lives within a single `NavHost`. Bottom-tab destinations are peer composables; detail screens are pushed on the back stack.

#### Route definitions (`Routes` object)

| Constant | Pattern | Description |
|---|---|---|
| `HOME` | `"home"` | Map + nearby arrivals |
| `PLAN` | `"plan"` | Trip planner |
| `SAVED` | `"saved"` | Saved stops list |
| `MORE` | `"more"` | More menu |
| `SETTINGS` | `"settings"` | Settings (URL editing) |
| `STOP_DETAILS` | `"stop/{stopId}?name={stopName}&code={stopCode}"` | Stop arrivals detail |
| `ROUTE_DETAILS` | `"route/{tripId}?routeId=…&routeShort=…&routeLong=…&headsign=…&stopId=…"` | Live route timeline |
| `TRIP_ITINERARY` | `"itinerary"` | OTP itinerary leg breakdown |

Route arguments are URL-encoded with `URLEncoder.encode(value, "UTF-8")` at the call site and decoded with `URLDecoder.decode(…)` in the NavHost `backStack.arguments`.

#### Bottom navigation

`ui/screens/CommonComponents.kt` exports `BottomNavBar(navController)` — a `NavigationBar` with four items (Home, Plan, Saved, More) used by all four tab screens.

---

### 6.3 Screens

#### `HomeMapScreen`
- Shows a full-screen `GoogleMap` (`maps-compose`).
- Camera idle detection: `LaunchedEffect(cameraPositionState.isMoving)` — triggers `loadStopsForLocation(lat, lon, zoom)` when the camera stops moving.
- **Zoom-adaptive stop display:** stop markers are hidden entirely below zoom 13; the API search radius shrinks as the user zooms in (see `HomeViewModel.loadStopsForLocation`).
- Tapping a stop marker opens a `BottomSheetScaffold` showing live arrivals.
- **Arrivals sheet header:** stop name + `MyLocation` icon button (centres camera on the stop), bookmark button, minimise button.
- "My Location" FAB recentres the camera on the user's GPS position.
- Each arrival row has a **"Bus"** icon button that pushes `RouteDetails`.
- Bus vehicle markers use `VehicleMarkerFactory` — teardrop circle (28dp diameter) colour-coded by arrival status, with an optional label pill showing route short name and data freshness.

#### `StopDetailsScreen`
- Receives `stopId`, `stopName`, `stopCode` via nav arguments.
- Pull-to-refresh list of `ObaArrival` items.
- Save/unsave button in the top app bar via `viewModel.toggleSaved(stopId, stopName, stopCode)`.

#### `TripPlannerScreen`
- Two `LocationField` inputs (origin / destination) with clear buttons and focus management.
- Suggestion dropdown powered by `GeocodingRepository.autocomplete()`.
- Results shown as `ItineraryCard` rows; tapping one navigates to `TripItinerary` after calling `viewModel.selectItinerary(itinerary)`.

#### `TripItineraryScreen`
- Reads `selectedItinerary` from `TripPlannerViewModel` (shared ViewModel scoped to the nav graph).
- Displays each `OtpLeg` with walk / transit icons, route chips, and duration.
- **Destination Alert FAB** (`ExtendedFloatingActionButton`) appears when the itinerary contains at least one transit leg. Tapping it starts/stops `DestinationAlertService` for the last transit leg's destination stop. The FAB turns red with "Stop Alert" when active.
- The `startDestinationAlert()` helper derives the "before stop" from the last intermediate stop of the leg (falls back to `leg.from` when there are no intermediate stops).

#### `RouteDetailsScreen`
- Receives `tripId`, `routeId`, `routeShort`, `routeLong`, `headsign`, `stopId` via nav arguments.
- Calls `viewModel.load(tripId, stopId)`.
- Vertical timeline of `List<TripStop>` with the current stop highlighted.
- Live vehicle position overlaid if `uiState.currentArrival?.vehicleLat != null`.
- **Refresh** button triggers `viewModel.refreshNow()`.

#### `SavedScreen`
- Lists `List<SavedStop>` from `AppPreferences.savedStops`.
- Tapping an item navigates to `StopDetails`.
- Swipe-to-dismiss or delete icon removes the stop.

#### `MoreScreen`
- Static menu: Settings, About, etc.

#### `SettingsScreen`
- Editable fields for OBA base URL and OTP base URL, backed by `AppPreferences`.
- Changes persisted immediately via `viewModel.saveUrls()`.

---

### 6.4 ViewModels

All ViewModels extend `ViewModel()` and each has a companion `*Factory` that implements `ViewModelProvider.Factory`.

#### `HomeViewModel`

```kotlin
data class HomeUiState(
    val userLocation: Location? = null,
    val locationPermissionGranted: Boolean = false,
    val loading: Boolean = false,
    val arrivalsLoading: Boolean = false,
    val stops: List<ObaStop> = emptyList(),
    val selectedStop: ObaStop? = null,
    val arrivals: List<ObaArrival> = emptyList(),
    val arrivalsLastUpdated: Long? = null,
    val routeShape: List<RoutePoint> = emptyList(),
    val selectedArrivalStatus: ArrivalStatus? = null,
    val activeRegion: ObaRegion? = null,
    val overviewRoutes: List<OverviewRoute> = emptyList(),
    val overviewRoutesLoaded: Boolean = false,
    val focusedVehicle: ObaArrival? = null,
    val pinnedTripVehicleId: String? = null,
    val routeHighlight: ObaRoute? = null,
    val routeHighlightShape: List<RoutePoint> = emptyList(),
    val routeHighlightVehicles: List<ObaArrival> = emptyList(),
    val lastCameraLat: Double = 0.0,
    val lastCameraLon: Double = 0.0,
    val lastCameraZoom: Float = 0f,
    val searchQuery: String = "",
    val searchActive: Boolean = false,
    val searchResults: List<HomeSearchResult> = emptyList(),
    val searchLoading: Boolean = false,
    val pendingCameraCenter: Pair<Double, Double>? = null,
    val autoDetectRegion: Boolean = true,
    val regionSwitchMessage: String? = null,
    val error: String? = null,
)
```

Key methods:

| Method | Purpose |
|---|---|
| `onLocationPermissionGranted()` | Starts GPS and initial stop load |
| `loadStopsForLocation(lat, lon, zoom)` | Zoom-adaptive stop fetch (see table below) |
| `selectStop(stop)` | Sets selected stop, clears arrivals, cancels old poll, starts new poll loop |
| `clearSelectedStop()` | Alias for `selectStop(null)` |
| `loadArrivals(stopId, silent)` | Public one-shot fetch (e.g. pull-to-refresh); wraps `fetchArrivals()` |
| `refreshArrivalsIfStale()` | Called on `ON_RESUME`; restarts poll immediately if ≥ 30 s elapsed since last fetch |
| `loadOverviewRoutes()` | Fetches/caches GeoJSON route shapes from sidecar |
| `focusVehicle(arrival)` | Pins a vehicle marker label and highlights its route shape |
| `selectRegion(region)` | Switches active region; updates prefs, resets stops/arrivals/overview |
| `saveCameraPosition(lat, lon, zoom)` | Persists last camera position to DataStore |

**Zoom-adaptive stop loading** (`loadStopsForLocation`):

| Zoom | Behaviour |
|---|---|
| < 13 | Clears stops list; no API call |
| 13–14 | Radius = 2200 m |
| 14–15 | Radius = 1400 m |
| 15–16 | Radius = 900 m |
| ≥ 16 | Radius = 600 m |

**Arrivals polling design** (mirrors OBA Android's `ArrivalsListLoader`):

`selectStop()` cancels any existing `arrivalsPollJob` and starts a new coroutine that calls `fetchArrivals()` inline (no child launch), then loops with a 30 s `delay`. Running inline means only one request is ever in-flight — the 30 s clock resets after each response, eliminating races between a slow in-flight request and the next poll tick.

`fetchArrivals(stopId, silent)` implements the `mLastGoodResponse` pattern from OBA:
- On **success with data**: updates `arrivals` and `arrivalsLastUpdated`.
- On **success with empty list + silent=true + existing data**: keeps the existing arrivals unchanged (treats server-empty as a transient glitch, not a real "no services" result).
- On **network/timeout error**: keeps existing arrivals visible, waits 5 s, retries once. If the retry also fails, the existing list remains on screen without any blank flash.

**`sidecarBaseUrl` resolution order** in `loadOverviewRoutes()`:
1. `prefs.sidecarBaseUrl.first()` (DataStore — written when user picks a region in Settings)
2. `_uiState.value.activeRegion?.sidecarBaseUrl` (freshly fetched from regions.json on each launch)

This ensures users who were on a region before `regions.json` was updated still get overview polylines on the next launch without having to re-select their region.

**Region switch behaviour:** `observeRegionCenter()` watches for manual region changes in Settings. When a switch occurs it calls `selectStop(null)` (cancels arrival poll, clears arrivals, collapses the bottom sheet) before clearing stops and resetting the overview — preventing the old region's arrival sheet from appearing briefly in the new region.

---

#### `StopDetailsViewModel`

```kotlin
data class StopDetailsUiState(
    val arrivals: List<ObaArrival> = emptyList(),
    val loading: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null,
)
```

Key methods: `load(stopId)`, `refresh()`, `toggleSaved(stopId, stopName, stopCode)`.

---

#### `TripPlannerViewModel`

```kotlin
data class TripPlannerUiState(
    val originText: String = "",
    val destinationText: String = "",
    val origin: PlaceResult? = null,
    val destination: PlaceResult? = null,
    val suggestions: List<PlaceResult> = emptyList(),
    val itineraries: List<OtpItinerary> = emptyList(),
    val selectedItinerary: OtpItinerary? = null,
    val loading: Boolean = false,
    val error: String? = null,
)
```

Key methods: `setOriginText(text)`, `clearOrigin()`, `setDestinationText(text)`, `clearDestination()`, `selectOrigin(place)`, `selectDestination(place)`, `planTrip()`, `selectItinerary(itinerary)`.

---

#### `RouteDetailsViewModel`

```kotlin
data class RouteDetailsUiState(
    val stops: List<TripStop> = emptyList(),
    val currentArrival: ObaArrival? = null,
    val currentStopIndex: Int = -1,
    val loading: Boolean = false,
    val error: String? = null,
)
```

Key methods: `load(tripId: String, stopId: String)`, `refreshNow()`.

---

#### `SavedViewModel`

Observes `AppPreferences.savedStops` as a `StateFlow` and exposes `removeSaved(stop)`.

---

## 7. Build Configuration

### 7.1 Credentials & `local.properties`

`local.properties` is **gitignored** and is never committed. All secrets and
machine-specific paths live here. Create it by copying the template below:

```properties
# ── Android SDK (auto-generated by Android Studio) ────────────────────────────
sdk.dir=/path/to/Android/Sdk

# ── Signing ───────────────────────────────────────────────────────────────────
STORE_FILE=/absolute/path/to/keystore.jks
STORE_PASSWORD=<keystore password>
KEY_ALIAS=<key alias>
KEY_PASSWORD=<key password>

# ── API keys ──────────────────────────────────────────────────────────────────
ONESIGNAL_APP_ID=<your OneSignal App ID>
GOOGLE_MAPS_API_KEY=<your Google Maps API key>

# ── OBA user-agent ────────────────────────────────────────────────────────────
OBA_USER_AGENT=KelantanBus

# ── White-label branding (see §7.2) ───────────────────────────────────────────
APP_NAME=Kelantan Bus
APP_PRIMARY_COLOR=#C62828
APP_SECONDARY_COLOR=#E53935
APP_TERTIARY_COLOR=#37474F

# ── Service URLs (required — see §7.2) ───────────────────────────────────────────────────
OBA_BASE_URL=https://api.kelantanbus.com
GEOCODING_BASE_URL=https://geocode.kelantanbus.com/v1
REGIONS_URL=https://cdn.unrealasia.net/onebusaway/regions.json

# ── Social / support URLs (required — see §7.2) ──────────────────────────────────────────
FACEBOOK_PAGE_URL=https://www.facebook.com/kelantanbus
WHATSAPP_PHONE=60109141767
STATUS_PAGE_URL=https://status.kelantanbus.com
```

All credential properties are validated at Gradle sync time:

```kotlin
// build.gradle.kts
val oneSignalAppId = localProps.getProperty("ONESIGNAL_APP_ID")
    ?: error("ONESIGNAL_APP_ID not set in local.properties")
val googleMapsApiKey = localProps.getProperty("GOOGLE_MAPS_API_KEY")
    ?: error("GOOGLE_MAPS_API_KEY not set in local.properties")
```

The Google Maps API key is injected into `AndroidManifest.xml` via
`manifestPlaceholders["GOOGLE_MAPS_API_KEY"]` so the key is never present in any
committed file.

> **Security note:** If either key has ever been committed to git, revoke and
> regenerate it immediately — git history is public even after the line is
> removed. Use `git-filter-repo --replace-text` to scrub history before
> force-pushing.

---

### 7.2 White-Label / Product Flavors

The build system supports multiple white-label brands without touching any Kotlin
source code. A single `brand` flavor dimension is defined:

| Flavor | `applicationId` | Purpose |
|---|---|---|
| `kelantanbus` | `com.taqisystems.bus.android` | Kelantan Bus (default) |
| `generic` | `com.taqisystems.bus.android.generic` | Blank white-label template |

Build commands:

```bash
./gradlew assembleKelantanbusDebug     # Kelantan brand, debug
./gradlew assembleKelantanbusRelease   # Kelantan brand, signed release
./gradlew assembleGenericDebug      # Generic white-label, debug
```

#### What can be overridden in `local.properties` (text / color)

| Key | Default | Controls |
|---|---|---|
| `APP_NAME` | `Kelantan Bus` | Launcher label, About screen title, onboarding welcome, notification fallback title |
| `APP_PRIMARY_COLOR` | `#C62828` | Primary Material 3 role: buttons, toolbar, FAB, active nav icon |
| `APP_SECONDARY_COLOR` | `#E53935` | Secondary role: accent chips, live-status badges, active tab indicator |
| `APP_TERTIARY_COLOR` | `#37474F` | Tertiary role: supporting UI, secondary text, icon tints |

All four values are compiled into `BuildConfig` fields at build time:

```kotlin
buildConfigField("String", "APP_NAME",         "\"$appName\"")
buildConfigField("String", "BRAND_PRIMARY",    "\"$brandPrimary\"")
buildConfigField("String", "BRAND_SECONDARY",  "\"$brandSecondary\"")
buildConfigField("String", "BRAND_TERTIARY",   "\"$brandTertiary\"")
```

#### Required `local.properties` entries — service URLs

All six URL entries are **required**. Gradle sync fails with an explicit error if
any are missing:

| Key | Controls |
|---|---|
| `OBA_BASE_URL` | Base URL for all OneBusAway API calls (`ObaRepository`) |
| `GEOCODING_BASE_URL` | Base URL for the Photon geocoding API (`GeocodingRepository`) |
| `REGIONS_URL` | URL for the OBA regions manifest (`RegionsRepository`) |

#### Required `local.properties` entries — social / support URLs

| Key | Controls |
|---|---|
| `FACEBOOK_PAGE_URL` | Facebook link in the Feedback screen |
| `WHATSAPP_PHONE` | WhatsApp number (E.164 without `+`) in the Feedback screen |
| `STATUS_PAGE_URL` | Service-status link in the More screen |

All six URL values are compiled into `BuildConfig` fields at build time:

```kotlin
buildConfigField("String", "OBA_BASE_URL",       "\"$obaBaseUrl\"")
buildConfigField("String", "GEOCODING_BASE_URL", "\"$geocodingBaseUrl\"")
buildConfigField("String", "REGIONS_URL",        "\"$regionsUrl\"")
buildConfigField("String", "FACEBOOK_PAGE_URL",  "\"$facebookPageUrl\"")
buildConfigField("String", "WHATSAPP_PHONE",     "\"$whatsappPhone\"")
buildConfigField("String", "STATUS_PAGE_URL",    "\"$statusPageUrl\"")
```

#### What is overridden via flavor resource directories (binary assets)

Place files in `app/src/<flavourName>/res/` to shadow the equivalent file in
`app/src/main/res/`. The Gradle merge rules mean the flavor file always wins.

| Asset | Path | Notes |
|---|---|---|
| In-app / onboarding logo | `drawable/logo.png` | Displayed at ~140 dp in onboarding and About screen |
| Launcher icon (normal) | `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` | Standard square icon |
| Launcher icon (round) | `mipmap-{densities}/ic_launcher_round.png` | Circular variant |
| Launcher adaptive foreground | `mipmap-{densities}/ic_launcher_foreground.png` | Adaptive foreground layer |
| Notification sound | `raw/alert.wav` | See §9 for format requirements |

#### Adding a new brand (step-by-step)

1. Add a new flavor block in `app/build.gradle.kts`:
   ```kotlin
   create("johor") {
       dimension = "brand"
       applicationId = "com.yourcompany.johorbus"
   }
   ```
2. Create `app/src/johor/res/drawable/logo.png` and mipmap icons.
3. Add `app/src/johor/res/raw/alert.wav` if a custom sound is needed.
4. Set branding and service URLs in your `local.properties`:
   ```properties
   APP_NAME=Johor Bus
   APP_PRIMARY_COLOR=#0057B8
   APP_SECONDARY_COLOR=#003F84
   APP_TERTIARY_COLOR=#1A1A2E
   OBA_BASE_URL=https://api.johorbus.com
   GEOCODING_BASE_URL=https://geocode.johorbus.com/v1
   REGIONS_URL=https://cdn.example.com/onebusaway/regions.json
   FACEBOOK_PAGE_URL=https://www.facebook.com/johorbus
   WHATSAPP_PHONE=601XXXXXXXXX
   STATUS_PAGE_URL=https://status.johorbus.com
   ```
5. Add your API keys to `local.properties`.
6. Build with `./gradlew assembleJohorRelease`.

---

### 7.3 BrandConfig

`ui/theme/BrandConfig.kt` is a Kotlin `object` that parses the
`BuildConfig.BRAND_*` hex strings into `androidx.compose.ui.graphics.Color`
instances at first access (`by lazy`).

```kotlin
object BrandConfig {
    val appName: String    get() = BuildConfig.APP_NAME
    val primary: Color     by lazy { hex(BuildConfig.BRAND_PRIMARY) }
    val secondary: Color   by lazy { hex(BuildConfig.BRAND_SECONDARY) }
    val tertiary: Color    by lazy { hex(BuildConfig.BRAND_TERTIARY) }

    // Auto-derived 40% lighter variants for dark-theme surfaces
    val primaryDark: Color   by lazy { primary.blend(Color.White, 0.40f) }
    val secondaryDark: Color by lazy { secondary.blend(Color.White, 0.28f) }
}
```

`Theme.kt` uses `BrandConfig.primary/secondary/tertiary` in both
`LightColorScheme` and `DarkColorScheme` instead of the hardcoded
`Primary`/`Secondary`/`Tertiary` constants. All other colour roles (containers,
surfaces, error, outlines) remain hardcoded in `Color.kt` because they are
derived from the Transit Red palette and would need a full design-system rework
to make dynamic.

---

### 7.4 Packaging exclusions

```kotlin
packaging {
    resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        excludes += "META-INF/INDEX.LIST"
        excludes += "META-INF/io.netty.versions.properties"
        // Required: Apache HttpComponents (transitive from OBA SDK) ships these
        excludes += "META-INF/DEPENDENCIES"
        excludes += "META-INF/LICENSE"
        excludes += "META-INF/LICENSE.txt"
        excludes += "META-INF/NOTICE"
        excludes += "META-INF/NOTICE.txt"
    }
}
```

Without the `META-INF/DEPENDENCIES` exclusion the build fails at the `mergeDebugJavaResource` task with a _"3 files found with path 'META-INF/DEPENDENCIES'"_ error from `httpclient5`, `httpcore5-h2`, and `httpcore5`.

### `AndroidManifest.xml` permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.VIBRATE" />
```

Google Maps API key is injected into the manifest via a `manifestPlaceholder`
(`GOOGLE_MAPS_API_KEY`). The key value lives only in `local.properties` and is
never committed to version control (see §7.1).

`DestinationAlertService` is declared with `android:foregroundServiceType="location"` (required on Android 14+).

---

## 8. API Endpoints

| Service | Base URL | Auth |
|---|---|---|
| OneBusAway | `https://api.kelantanbus.com` | `key=TEST` query param |
| OpenTripPlanner | `https://otp.kelantanbus.com/otp/routers/default/` | None |
| Pelias Geocoding | `https://geocode.kelantanbus.com/v1/` | None |
| Regions JSON | `https://cdn.unrealasia.net/onebusaway/regions.json` | None |
| OBA Sidecar | `https://sidecar.kelantanbus.com` | None |

The OBA base URL and OTP base URL can be overridden at runtime via **Settings** screen and are persisted to DataStore.

### ObaRepository — SDK vs raw HTTP

All OBA calls go through `ObaRepository`. Methods are implemented either via the official OBA Kotlin SDK (`OnebusawaySdkOkHttpClientAsync`) or raw OkHttp where the SDK model does not expose the required fields:

| Method | Implementation | Reason |
|---|---|---|
| `getStopsForLocation` | ✅ SDK | `stopsForLocation().list()` — `References.Route.type()` available |
| `getStopLocation` | ✅ SDK | `stop().retrieve()` |
| `getRoutesForLocation` | ✅ SDK | `routesForLocation().list()` |
| `getRoute` | ✅ SDK | `route().retrieve()` |
| `getRouteShape` | ✅ SDK | `shape().retrieve()` |
| `getShapeForTrip` | ✅ SDK | `trip().retrieve()` → `shapeId()` |
| `getVehiclesForRoute` | ✅ SDK | `tripsForRoute().list(includeStatus=true)` |
| `getArrivalsForStop` | ⚠️ Raw HTTP | SDK models `frequency` as `String?` but server returns a JSON object — raw OkHttp used so `frequency` is parsed cleanly as `JSONObject` |
| `getTripDetails` | ✅ SDK | `tripDetails().retrieve(params)` — `entry.schedule()?.stopTimes()` for stop times; `entry.status()` for live position; headsign from `references().trips()` |
| `getShapeForRoute` | ✅ SDK | `stopsForRoute().list(StopsForRouteListParams…includePolylines(true))` — `entry().polylines()` returns typed `List<Polyline>`, each with `points()` encoded string |
| `downloadText` | ⚠️ Raw HTTP | Intentional — fetches sidecar GeoJSON from arbitrary URL |

### Sidecar endpoints

The sidecar (`oba-sidecar`) is a self-hosted Node.js service that supplements the OBA server with endpoints the SDK does not cover.

| Endpoint | Method | Purpose |
|---|---|---|
| `/route-shapes/{regionId}` | GET | GeoJSON of all route shapes for the region (cached, built lazily) |
| `/{regionId}/alarms` | POST | Register an arrival push reminder |
| `{deleteUrl}` | DELETE | Cancel a registered alarm |

`sidecarBaseUrl` is per-region and comes from `regions.json` (`sidecarBaseUrl` field on each `ObaRegion`). It is stored in `AppPreferences.sidecarBaseUrl` when the user selects a region. On app startup, `loadOverviewRoutes()` also reads it from `activeRegion.sidecarBaseUrl` as a fallback in case the DataStore value is stale.

---

## 9. Notification Infrastructure

### Notification channels

Registered in `KelantanBusApplication.registerNotificationChannels()` (called
before OneSignal init). Channel IDs are **scoped to `packageName`** so that
different white-label builds never share a channel — this is critical because
Android locks a channel's sound on first creation and it cannot be changed
programmatically afterward.

```kotlin
fun channelIdReminders(pkg: String)   = "${pkg}_reminders"
fun channelIdDestination(pkg: String) = "${pkg}_destination"
// e.g. "com.taqisystems.bus.android_reminders"
//      "com.yourcompany.johorbus_reminders"
```

| Channel (suffix) | Name | Importance | Sound |
|---|---|---|---|
| `{pkg}_reminders` | Reminders | HIGH | `res/raw/alert.wav` |
| `{pkg}_destination` | Destination Alerts | LOW | None (silent ongoing) |

#### Custom notification sound (white-label override)

To replace `alert.wav` for a white-label brand:

1. Place your audio file at `app/src/<flavourName>/res/raw/alert.wav`.
   The flavor resource shadows `app/src/main/res/raw/alert.wav` automatically.
2. **Supported formats:** WAV, OGG (recommended), MP3.
3. **Keep under 1 MB and 30 seconds** for best OS compatibility.
4. The package-scoped channel ID ensures Android registers a fresh channel for
   the new brand — the old sound is never "stuck" from a previous install.

> ⚠️ If you update the sound file during development without changing the
> `applicationId`, Android will still play the old sound (channel already
> cached on device). Uninstall the app and reinstall to force channel
> recreation.

### OneSignal push (arrival reminders)

- SDK version: **5.1.21**
- `KelantanBusNotificationExtension` (declared in manifest via `com.onesignal.NotificationServiceExtension` meta-data) intercepts every incoming push.
- For reminder pushes it calls `event.preventDefault()` to suppress OneSignal's
  default display and re-posts via `NotificationCompat.Builder` on
  `{packageName}_reminders` so `alert.wav` plays reliably.
- Notification heading: **"Arriving Soon"**; body: **"Your ride is arriving at [stop] in ~N minutes."** — wording is intentionally transport-mode agnostic to cover bus, rail, and other transit types.
- Push subscriptions are registered through the **sidecar** service (`POST {sidecarBaseUrl}/{regionId}/alarms`) with `seconds_before`, `stop_id`, `trip_id`, `service_date`, `stop_sequence`, `vehicle_id`, `user_push_id` (OneSignal subscription ID), `operating_system=android`.
- The sidecar returns a `deleteUrl` stored in `ActiveReminder` for later cancellation.

### Destination Alert Service (`service/DestinationAlertService.kt`)

A `START_STICKY` foreground `Service` that monitors the user's GPS position while they are on the bus.

**Start intent extras:**

| Extra | Type | Meaning |
|---|---|---|
| `dest_name` | `String` | Destination stop display name |
| `dest_lat` / `dest_lon` | `Double` | Destination stop coordinates |
| `before_lat` / `before_lon` | `Double` | Second-to-last stop coordinates |

**Alert stages:**

| Stage | Trigger | Effect |
|---|---|---|
| 1 — Get Ready | GPS within 300 m of before-stop | TTS: *"Get ready. Your stop is coming up soon."* + short vibration `[0, 600ms]` |
| 2 — Pull Cord | GPS within 100 m of destination | TTS: *"Your stop is here. Please exit the vehicle now."* + triple vibration `[0, 1s, 0.4s, 1s, 0.4s, 1s]` + high-priority `alert.wav` notification; service self-stops after 9 s |

- Uses `FusedLocationProviderClient` (`PRIORITY_HIGH_ACCURACY`, 2–3 s interval).
- Persistent foreground notification (channel: `{packageName}_destination`)
  updates live distance, e.g. *"250 m to Kota Bharu Sentral"*.
- Final arrival notification posts on `{packageName}_reminders` channel so
  `alert.wav` fires.
- Both TTS and vibration use API-26-safe code paths (`VibrationEffect.createWaveform`, `VibratorManager`).
- Send `ACTION_STOP` intent to cancel monitoring early.

---

## 10. Map Marker Specifications

### Stop markers (`createStopMarkerBitmap`)

Drawn as a **22 dp rounded-square badge** with a downward anchor notch (6 dp).

| State | Fill | Border | Symbol colour |
|---|---|---|---|
| Normal | White | Brand red (1.8 dp) | Brand red |
| Selected | Brand red (solid) | Brand red (2.5 dp) | White |
| Selected | + glow ring (α 60, 3 dp) | | |

Symbol: bus-stop canopy bar + pole drawn in fill-paint.

### Vehicle markers (`VehicleMarkerFactory`)

Drawn as a **14 dp radius coloured circle** (28 dp diameter) with an optional directional fin (7 dp).

| Status | Circle colour |
|---|---|
| On time | `#16A34A` (green) |
| Delayed | `#1A73E8` (blue) |
| Early | `#DC2626` (red) |
| Scheduled | `#6B7280` (grey) |

When a vehicle is focused (`showLabel = true`), a **label pill** is drawn above the circle:
- **Line 1:** Route short name (9 sp, bold, status colour)
- **Line 2:** Status word + elapsed time (7 sp, same status colour, ~80% alpha)
  - Live bus: `"On time · 2m 30s ago"` / `"Delayed · 45s ago"` / `"Early · 1m 10s ago"`
  - Schedule-only: `"Scheduled"`

Both lines share the same status colour so the pill reads as a single coherent unit. `statusLabel` is passed into `VehicleMarkerFactory.get()` by the call site.

`formatUpdateLabel(statusLabel, lastUpdateMs, isPredicted)` produces the second-line string. Bitmaps are cached in a `LruCache<String, Bitmap>(15)` keyed by `"$halfWind $color"`. The anchor is set so the circle **centre** maps to the vehicle's geographic coordinate (`anchorV` calculated at runtime).

### Overview route polylines

When the sidecar has built shapes for a region, `HomeViewModel.loadOverviewRoutes()` fetches a GeoJSON file from:

```
GET {sidecarBaseUrl}/route-shapes/{regionId}
```

The response is cached to `filesDir/route_shapes_{regionId}.geojson` and refreshed after 7 days. Each feature's `properties.route_short_name` field becomes `OverviewRoute.shortName`.

On the map, each route is drawn as a semi-transparent polyline (width 5 dp, 55% opacity, brand colour) with a **route label pill** at the polyline midpoint, rendered by `createRouteLabelBitmap()`. Pills use the same rounded-rect style as vehicle labels but are static (not status-coloured).

---

## 11. Known Quirks & SDK Notes

### AGP 9.0.1 + Kotlin plugin conflict

AGP 9.0.1 embeds Kotlin and registers the `kotlin` extension internally. Explicitly applying `alias(libs.plugins.kotlin.android)` in `app/build.gradle.kts` causes:

```
The 'kotlin.android' extension is already registered by AGP
```

**Fix:** remove `kotlin.android` from the app module's plugin block and remove the `kotlinOptions { jvmTarget }` block (set `compileOptions` Java version instead).

---

### `onCameraIdle` does not exist in Maps Compose v6

`GoogleMap()` in `maps-compose` 6.x does not expose an `onCameraIdle` callback parameter. Use:

```kotlin
val cameraPositionState = rememberCameraPositionState()

LaunchedEffect(cameraPositionState.isMoving) {
    if (!cameraPositionState.isMoving) {
        // camera has just stopped
        val target = cameraPositionState.position.target
        viewModel.loadStopsForArea(target.latitude, target.longitude)
    }
}
```

---

### `kotlinx-coroutines-play-services` required for `Task.await()`

`com.google.android.gms.tasks.Task.await()` is a suspend extension from `kotlinx-coroutines-play-services`. Without this dependency the call resolves as `Unresolved reference: await`.

---

### OBA SDK package is singular

The arrival-list params class is in package `arrivalanddeparture` (**singular**):

```kotlin
// ✅ correct
import org.onebusaway.models.arrivalanddeparture.ArrivalAndDepartureListParams

// ❌ wrong (package does not exist)
import org.onebusaway.models.arrivalsanddepartures.ArrivalAndDepartureListParams
```

---

### `predictedArrivalTime()` / `scheduledArrivalTime()` return primitive `long`

These methods return Java primitive `long` (never null). The check for real-time data should be:

```kotlin
val isPredicted = predictedMs > 0   // not: entry.predicted() == true
```

`entry.predicted()` returns `Boolean?` (nullable) and may be `null` even when prediction data is present.

---

### `TripDetailRetrieveResponse.Data.Entry` has no `trip()` method

The entry has `tripId()`, `schedule()`, `status()`, `serviceDate()`, and `situationIds()` — but **no** `trip()` method. The trip headsign is not directly available on the `TripDetails` entry; resolve it from `response.data().references().trips()` if needed, or leave `headsign = ""`.

---

### `schedule()` is nullable at runtime despite non-null Java signature

Although `javap` shows `schedule()` returning a non-null type, the Kotlin compiler treats it as platform type. Use safe-call `?.` or `runCatching {}` when accessing it:

```kotlin
val stopTimes = entry.schedule()?.stopTimes()?.map { … } ?: emptyList()
```

---

### `ArrivalsAndDeparture.frequency()` is `String?`, not an object

Despite the raw JSON having `frequency` as an object with `headway` and `endTime` sub-fields, the SDK model exposes:

```kotlin
fun frequency(): String?   // returns null — Jackson cannot coerce an object to String
```

Because this field cannot be recovered through the SDK's typed API, `getArrivalsForStop` bypasses the SDK entirely and uses a raw OkHttp call. The `frequency` key is then read as a plain `JSONObject`, giving direct access to `headway` (int, seconds) and `endTime` (long, epoch ms) without any coercion issues.

---

### `TripsForRouteListResponse.Data.List` has no `routeId()` method

The list item only exposes `tripId()`. The `routeId` for each active trip must be resolved from `references().trips()` (i.e., `References.Trip.routeId()`). `getVehiclesForRoute` uses `tripRouteMap[tripId] ?: routeId` as a fallback.

---

### Google Maps Platform billing

The app only generates **Dynamic Maps (mobile)** charges — map tile rendering when `GoogleMap()` composable initialises. All search, geocoding, and directions calls go to self-hosted services (Pelias, OpenTripPlanner) and are **$0 to Google**.

| SKU | Trigger | Price |
|---|---|---|
| Dynamic Maps (mobile) | Each `GoogleMap` composable load | $7 / 1,000 loads |

Google provides a **$200 free credit/month**, covering ~28,500 map loads. Risk factors:
- `HomeMapScreen` and `TripItineraryScreen` each contain one `GoogleMap` — opening the itinerary screen counts as a second billable load per session.
- Never add the Google Places SDK for autocomplete; all place search uses the self-hosted Pelias geocoder at `geocode.kelantanbus.com`.

---

## 12. Unit Tests

All unit tests are pure-JVM (no Android runtime required) and live in:

```
app/src/test/java/com/taqisystems/bus/android/
├── model/
│   ├── TransitTypeTest.kt
│   ├── ObaArrivalTest.kt
│   └── ObaRegionExtensionsTest.kt
└── repository/
    ├── DecodePolylineTest.kt
    └── RegionsRepositoryTest.kt
```

Run with:
```bash
./gradlew testDebugUnitTest
```

### 12.1 Test dependencies

Added to `gradle/libs.versions.toml` and `app/build.gradle.kts`:

| Dependency | Purpose |
|---|---|
| `junit:4.13.2` | Test runner (pre-existing) |
| `kotlinx-coroutines-test:1.9.0` | `runTest` for suspend functions |
| `okhttp-mockwebserver:4.12.0` | Local HTTP server for `RegionsRepository` response tests |

### 12.2 Test file reference

#### `TransitTypeTest` — 14 tests
Covers `TransitType.fromGtfsType(Int)` and `TransitType.fromGtfsTypes(Set<Int>)`.

- All GTFS type integers (0 = LRT_TRAM, 1 = MRT_METRO, 2 = COMMUTER_RAIL, 3 = BUS, 4 = FERRY, 11 = MONORAIL).
- Unknown positive / negative integers → `BUS`.
- Empty set, bus-only, duplicate-bus, single non-bus, bus + non-bus, two non-bus → `MIXED`, three non-bus → `MIXED`, bus + two non-bus → `MIXED`.
- Sanity check: all enum values have unique `color` values.

#### `ObaArrivalTest` — 9 tests
Covers `ObaArrival.liveMinutesUntilArrival()`.

- Uses `predictedArrivalTime` when it is `> 0`; falls back to `scheduledArrivalTime` when predicted is `0`.
- Returns negative value for a past arrival.
- Returns `0` for an imminent arrival (< 60 s away).
- Result is independent of the stale `minutesUntilArrival` stored field.
- Data-class equality and inequality.
- Default `transitType` is `BUS`; headway fields default to `false`/`null`.

#### `ObaRegionExtensionsTest` — 5 tests
Covers `ObaRegion.outerBoundingBox(): DoubleArray?`.

- Empty bounds → `null`.
- Single bound → correct `[minLat, maxLat, minLon, maxLon]` from `lat ± latSpan/2`, `lon ± lonSpan/2`.
- Two non-overlapping bounds → outer union.
- Two overlapping bounds → outer envelope.
- Result array index order verified (`[0]` = minLat, `[1]` = maxLat, `[2]` = minLon, `[3]` = maxLon).
- Negative (southern/western hemisphere) coordinates.

#### `DecodePolylineTest` — 7 tests
Covers `ObaRepository.decodePolyline(String)` (companion object static function).

- Empty string → empty list.
- `"_ibE_seK"` → `[(1.0, 2.0)]`.
- `"~hbE~reK"` → `[(-1.0, -2.0)]`.
- Google's canonical sample `"_p~iF~ps|U_ulLnnqC_mqNvxq\`@"` → 3 points, first ≈ `(38.5, -120.2)`, second ≈ `(40.7, -120.95)`, third ≈ `(43.252, -126.453)`.
- All decoded points have finite lat/lon.

#### `RegionsRepositoryTest` — 15 tests

**`fetchRegions` (using `MockWebServer`):**
- Inactive regions are filtered out.
- `id`, `obaBaseUrl`, `sidecarBaseUrl` parsed correctly.
- `centerLat` = average of bound lats; `latSpan` = max of bound latSpans.
- Active regions have `active = true`.
- Cache: second call with default `forceRefresh = false` makes no additional HTTP request.
- `forceRefresh = true` always makes a fresh HTTP request.
- Empty list in response body → empty result (no crash).

**`findRegionForLocation` (pure, synchronous):**
- Empty list → `null`.
- Location inside a bound → matching region returned.
- Location outside all bounds → `null`.
- Location inside second region only → second region returned.
- Points on exact boundary edges (min/max) are included.
- Points just outside boundary are excluded.
- When multiple regions overlap the same coordinate the **first** region in the list wins.

**`REGIONS_URL` constant:**
- Value is `"https://cdn.unrealasia.net/onebusaway/regions.json"`.

### 12.3 Testability note — `RegionsRepository` URL injection

To allow `MockWebServer` to intercept HTTP calls, `RegionsRepository` accepts an optional `regionsUrl` constructor parameter:

```kotlin
class RegionsRepository(
    private val http: OkHttpClient = OkHttpClient(),
    private val regionsUrl: String = REGIONS_URL,
)
```

All production call sites pass no argument and therefore use `REGIONS_URL` as before. Tests pass the `MockWebServer` base URL.

---

## 11. License

**SPDX-License-Identifier:** `Apache-2.0`

Copyright 2026 Taqi Systems

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
any file in this project except in compliance with the License. You may obtain a
copy of the License at:

```
http://www.apache.org/licenses/LICENSE-2.0
```

The full license text is in the [`LICENSE`](../LICENSE) file at the repository
root. Every Kotlin source file carries an SPDX header:

```kotlin
// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0
```
