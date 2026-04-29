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
8. [API Endpoints](#8-api-endpoints)
9. [Known Quirks & SDK Notes](#9-known-quirks--sdk-notes)

---

## 1. Project Overview

KelantanBus is a real-time transit app for the Kelantan public bus network. It shows live bus arrivals at nearby stops, allows trip planning using OpenTripPlanner, and lets users save favourite stops.

### Feature Summary

| Feature | Description |
|---|---|
| Live map | Google Map centred on user location; nearby stops as markers |
| Stop arrivals | Real-time + scheduled arrivals from OneBusAway API |
| Trip planner | Origin → Destination routing via OpenTripPlanner (OTP) |
| Itinerary view | Leg-by-leg breakdown of a planned trip |
| Route timeline | Live vehicle position overlaid on stop timeline |
| Saved stops | Persist favourite stops in DataStore |
| Settings | Edit OBA/OTP base URLs at runtime |

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
│       │   │   ├── strings.xml
│       │   │   └── themes.xml       # Material3 no-action-bar base theme
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
│           └── ui/
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
| `minutesUntilArrival` | `Int` | Derived from effective time |
| `deviationMinutes` | `Int` | `(predicted − scheduled) / 60 000` |
| `vehicleId` | `String?` | |
| `vehicleLat` / `vehicleLon` | `Double?` | Live vehicle position |
| `vehicleOrientation` | `Double?` | Degrees, 0 = north |
| `vehicleLastUpdateTime` | `Long?` | Unix ms |
| `shapeId` | `String?` | Resolved separately via shape endpoint |

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

#### `SavedStop`
Persisted favourite stop (id, name, code).

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
| `getRouteShape(shapeId)` | Raw OkHttp `GET /api/where/shape/{id}.json` | `List<RoutePoint>` |

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

**Shape decoding:** `decodePolyline(encoded: String)` is a Google encoded-polyline decoder implemented as a static companion function.

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
| `getRegions()` | `List<ObaRegion>` |

---

### 5.3 AppPreferences

`data/AppPreferences.kt` — DataStore wrapper for persisted app settings and saved stops.

**DataStore file name:** `kelantanbus_prefs`

| Key | Type | Purpose |
|---|---|---|
| `region_id` | `String` | Currently selected region ID |
| `oba_base_url` | `String` | Override OBA server URL |
| `otp_base_url` | `String` | Override OTP server URL |
| `saved_stops_json` | `String` | JSON-serialised `List<SavedStop>` |

Exposed as `Flow<T>` properties + `suspend fun set*()` mutators:

```kotlin
val regionId: Flow<String?>
val obaBaseUrl: Flow<String?>
val otpBaseUrl: Flow<String?>
val savedStops: Flow<List<SavedStop>>

suspend fun setRegionId(id: String)
suspend fun setObaBaseUrl(url: String)
suspend fun setOtpBaseUrl(url: String)
suspend fun setRegion(id, obaUrl, otpUrl?)       // atomic 3-field write
suspend fun toggleSavedStop(stop, currentList)   // add or remove
```

---

### 5.4 ServiceLocator

`ServiceLocator.kt` — Kotlin `object` singleton acting as a manual DI container.

```kotlin
object ServiceLocator {
    lateinit var application: Application
    lateinit var obaRepository: ObaRepository
    lateinit var otpRepository: OtpRepository
    lateinit var geocodingRepository: GeocodingRepository
    lateinit var regionsRepository: RegionsRepository
    lateinit var preferences: AppPreferences
    val appPreferences get() = preferences  // alias

    fun init(context: Context) { … }
}
```

`init()` is called from `KelantanBusApplication.onCreate()`. Every ViewModel factory reads from `ServiceLocator` to obtain its dependencies.

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
- Camera idle detection: `LaunchedEffect(cameraPositionState.isMoving)` — triggers `loadStopsForArea()` when the camera stops moving.
- Tapping a stop marker opens a bottom sheet (`ModalBottomSheet`) showing live arrivals.
- "My Location" FAB recentres the camera on `uiState.userLocation`.
- Each arrival row has a **"Bus"** icon button that pushes `RouteDetails`.

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
    val nearbyStops: List<ObaStop> = emptyList(),
    val selectedStop: ObaStop? = null,
    val arrivals: List<ObaArrival> = emptyList(),
    val routeShape: List<RoutePoint> = emptyList(),
    val error: String? = null,
)
```

Key methods: `onLocationPermissionGranted()`, `updateUserLocation(location)`, `loadStopsForArea(lat, lon)`, `selectStop(stop)`, `clearSelectedStop()`, `loadArrivalsForStop(stopId)`.

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

### `app/build.gradle.kts` highlights

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // ⚠️ DO NOT add kotlin.android — AGP 9.0.1 registers it internally
}
```

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
```

Google Maps API key is declared in the manifest under `com.google.android.geo.API_KEY`.

---

## 8. API Endpoints

| Service | Base URL | Auth |
|---|---|---|
| OneBusAway | `https://api.kelantanbus.com` | `key=TEST` query param |
| OpenTripPlanner | `https://otp.kelantanbus.com/otp/routers/default/` | None |
| Pelias Geocoding | `https://geocode.kelantanbus.com/v1/` | None |
| Regions JSON | `https://cdn.unrealasia.net/onebusaway/regions.json` | None |

The OBA base URL and OTP base URL can be overridden at runtime via **Settings** screen and are persisted to DataStore.

---

## 9. Known Quirks & SDK Notes

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
