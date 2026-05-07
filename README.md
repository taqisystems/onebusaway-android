# KelantanBus Android

A real-time transit app for the Kelantan public bus network — native Android, built with Jetpack Compose.

> **Package:** `com.taqisystems.bus.android`  
> **Min SDK:** 24 (Android 7.0) · **Target SDK:** 36  
> **Build status:** ✅ `assembleKelantanDebug` passing

---

## Features

| Feature | Description |
|---|---|
| Live map | Google Map centred on user location; zoom-adaptive stop markers |
| Stop arrivals | Real-time + scheduled arrivals from OneBusAway API with 30 s auto-refresh |
| Arrival reminders | Push reminder N minutes before your ride arrives (OneSignal + sidecar) |
| Destination alerts | GPS foreground service: TTS voice + vibration as bus approaches your stop |
| Trip planner | Origin → Destination routing via OpenTripPlanner |
| Itinerary view | Leg-by-leg breakdown with a Destination Alert FAB |
| Route timeline | Live vehicle position overlaid on stop timeline |
| Saved stops | Persist favourite stops across sessions |
| Multi-region | 11 Malaysian regions selectable at runtime |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.2.0 |
| UI | Jetpack Compose (BOM 2025.04.01), Material 3 |
| Navigation | `androidx.navigation:navigation-compose` 2.9.0 |
| Maps | `maps-compose` 6.4.4 · `play-services-maps` 19.2.0 |
| Networking | OkHttp 4.12.0 · Retrofit 2.11.0 · Gson 2.13.1 |
| Transit data | OneBusAway Kotlin SDK 0.1.0-alpha.77 |
| Push notifications | OneSignal SDK 5.1.21 |
| Persistence | DataStore Preferences 1.1.4 |
| Build | AGP 9.0.1 · Gradle 9.1.0 |

---

## Getting Started

### Prerequisites

- Android Studio Meerkat or later
- JDK 11+
- A Google Maps API key (Maps SDK for Android enabled)
- A OneSignal account and App ID

### 1. Clone the repository

```bash
git clone <repo-url>
cd KelantanBus
```

### 2. Create `local.properties`

`local.properties` is gitignored. Create it in the project root with the following content:

```properties
# ── Android SDK ───────────────────────────────────────────────────────────────
sdk.dir=/path/to/Android/Sdk

# ── Release signing ───────────────────────────────────────────────────────────
STORE_FILE=/absolute/path/to/keystore.jks
STORE_PASSWORD=<keystore password>
KEY_ALIAS=<key alias>
KEY_PASSWORD=<key password>

# ── API keys ──────────────────────────────────────────────────────────────────
ONESIGNAL_APP_ID=<your OneSignal App ID>
GOOGLE_MAPS_API_KEY=<your Google Maps API key>

# ── OBA user-agent ────────────────────────────────────────────────────────────
OBA_USER_AGENT=KelantanBus

# ── Branding (optional — defaults shown) ──────────────────────────────────────
APP_NAME=Kelantan Bus
APP_PRIMARY_COLOR=#C62828
APP_SECONDARY_COLOR=#E53935
APP_TERTIARY_COLOR=#37474F
```

> **Security:** `ONESIGNAL_APP_ID` and `GOOGLE_MAPS_API_KEY` are validated at
> Gradle sync time and injected via `BuildConfig` / `manifestPlaceholders`. They
> are never present in any committed file. If either key was ever accidentally
> committed, revoke and regenerate it immediately.

### 3. Build

```bash
# Debug APK
./gradlew assembleKelantanDebug

# Signed release APK
./gradlew assembleKelantanRelease

# Install on connected device
./gradlew installKelantanDebug
```

---

## White-Labelling

The project is structured for multiple transit agency brands using Android
[product flavors](https://developer.android.com/build/build-variants). No Kotlin
source files need to be touched to produce a new brand.

### What you can change in `local.properties`

| Key | Default | Effect |
|---|---|---|
| `APP_NAME` | `Kelantan Bus` | Launcher label, About screen, onboarding, notification titles |
| `APP_PRIMARY_COLOR` | `#C62828` | Buttons, toolbar, FAB, active nav icon |
| `APP_SECONDARY_COLOR` | `#E53935` | Accent chips, live-status badges, active tab |
| `APP_TERTIARY_COLOR` | `#37474F` | Supporting text, icon tints |

### What you override with flavor resource files

Place files under `app/src/<flavourName>/res/` — the flavor resource always
shadows the equivalent file in `app/src/main/res/`:

| Asset | Path within `res/` |
|---|---|
| In-app / onboarding logo | `drawable/logo.png` |
| Launcher icon | `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher*.png` |
| Adaptive icon foreground | `mipmap-{density}/ic_launcher_foreground.png` |
| Notification alert sound | `raw/alert.wav` (WAV / OGG / MP3, under 1 MB) |

### Adding a new brand

1. Add a flavor block to `app/build.gradle.kts`:

   ```kotlin
   create("johor") {
       dimension = "brand"
       applicationId = "com.yourcompany.johorbus"
   }
   ```

2. Create `app/src/johor/res/drawable/logo.png` and mipmap launcher icons.

3. Optionally add `app/src/johor/res/raw/alert.wav` for a custom notification
   sound.

4. Set your branding and API keys in `local.properties`:

   ```properties
   APP_NAME=Johor Bus
   APP_PRIMARY_COLOR=#0057B8
   APP_SECONDARY_COLOR=#003F84
   APP_TERTIARY_COLOR=#1A1A2E
   ONESIGNAL_APP_ID=<johor onesignal app id>
   GOOGLE_MAPS_API_KEY=<johor maps api key>
   ```

5. Build:

   ```bash
   ./gradlew assembleJohorRelease
   ```

> **Notification sound note:** Channel IDs are scoped to `packageName` (e.g.
> `com.yourcompany.johorbus_reminders`). This prevents Android from locking in
> a previous brand's sound, since the OS caches a channel's sound on first
> creation and it cannot be changed programmatically afterward.

---

## Project Structure

```
KelantanBus/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── kelantan/res/          ← Kelantan brand resources
│       ├── generic/res/           ← White-label template
│       └── main/
│           ├── AndroidManifest.xml
│           ├── res/
│           │   ├── drawable/      ← default logo + icons
│           │   ├── mipmap-*/      ← adaptive launcher icons
│           │   ├── raw/alert.wav  ← default notification sound
│           │   └── values/
│           └── java/com/taqisystems/bus/android/
│               ├── KelantanBusApplication.kt
│               ├── MainActivity.kt
│               ├── ServiceLocator.kt
│               ├── data/
│               │   ├── AppPreferences.kt
│               │   ├── model/Models.kt
│               │   └── repository/
│               │       ├── ObaRepository.kt
│               │       ├── OtpRepository.kt
│               │       ├── GeocodingRepository.kt
│               │       └── RegionsRepository.kt
│               ├── service/
│               │   └── DestinationAlertService.kt
│               └── ui/
│                   ├── map/VehicleMarkerFactory.kt
│                   ├── navigation/AppNavigation.kt
│                   ├── screens/
│                   ├── theme/
│                   │   ├── BrandConfig.kt  ← runtime brand color parser
│                   │   ├── Color.kt
│                   │   ├── Theme.kt
│                   │   └── Type.kt
│                   └── viewmodel/
├── gradle/libs.versions.toml
├── local.properties               ← gitignored, never committed
├── DOCUMENTATION.md               ← full technical reference
└── README.md                      ← this file
```

---

## Architecture

The app follows a single-Activity, MVVM + Repository pattern:

```
Compose UI  ──▶  ViewModel  ──▶  Repository  ──▶  OBA SDK / OkHttp / DataStore
                                                         │
                                                    ServiceLocator (singleton DI)
```

- **ServiceLocator** initialises all repositories once in `Application.onCreate`
  and exposes them as singletons.
- **ViewModels** hold `StateFlow`s consumed by Composables.
- **Repositories** wrap all I/O: OneBusAway SDK, OpenTripPlanner REST, Pelias
  geocoding, DataStore preferences, and the OBA sidecar.

---

## API Services

| Service | Base URL | Auth |
|---|---|---|
| OneBusAway | `https://api.kelantanbus.com` | `key=TEST` query param |
| OpenTripPlanner | `https://otp.kelantanbus.com/otp/routers/default/` | None |
| Pelias Geocoding | `https://geocode.kelantanbus.com/v1/` | None |
| Regions JSON | `https://cdn.unrealasia.net/onebusaway/regions.json` | None |
| OBA Sidecar | `https://sidecar.kelantanbus.com` | None |

The OBA and OTP base URLs can be overridden at runtime via the **Settings**
screen and are persisted to DataStore.

---

## Permissions

| Permission | Reason |
|---|---|
| `INTERNET` | API calls |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Live map, destination alerts |
| `RECORD_AUDIO` | Voice search on trip planner |
| `POST_NOTIFICATIONS` | Arrival reminders, destination alert notifications |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | GPS tracking while on the bus |
| `VIBRATE` | Haptic feedback on destination alert |

---

## Notifications

Two notification channels are registered on app start, both scoped to
`packageName` to support white-label isolation:

| Channel | Importance | Sound |
|---|---|---|
| `{pkg}_reminders` | HIGH | `res/raw/alert.wav` |
| `{pkg}_destination` | LOW | Silent (ongoing foreground service) |

Arrival reminders are registered through the sidecar service and delivered via
OneSignal. The `KelantanBusNotificationExtension` intercepts each push, suppresses
OneSignal's default display, and re-posts via `NotificationCompat` to ensure the
custom sound plays.

---

## Full Documentation

See [DOCUMENTATION.md](DOCUMENTATION.md) for the complete technical reference,
including:

- All domain models and repository method signatures
- AppPreferences DataStore schema
- Screen-by-screen UI breakdown and ViewModel state descriptions
- Map marker drawing specifications
- Known SDK quirks and workarounds
- Unit test reference
