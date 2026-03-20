# RouteMe – Android Auto Implementation Plan

## Overview

Add an Android Auto experience that gives the technician hands-free access to the
core daily loop: **see next suggested clients → navigate to one → mark service
complete**. The car UI is a separate presentation layer built with
`androidx.car.app` templates; it shares the existing data/domain layer (Room DB,
`RoutingEngine`, `ClientRepository`, `SuggestionUseCase`, etc.) with the phone UI.

Since the app is personal-use only, no Play Store upload or Google review is
required. Android Auto Developer Mode ("Unknown sources") is enabled on the
phone, and the debug APK is sideloaded.

---

## Car UX Flow

```
┌─────────────────────────────────────────────────────────────┐
│  ANDROID AUTO HEAD UNIT                                      │
│                                                              │
│  1. Launch RouteMe from Auto app drawer                      │
│         │                                                    │
│         ▼                                                    │
│  ┌──────────────┐    tap     ┌────────────────────────┐      │
│  │ StepSelect   │ ────────→  │ SuggestionListScreen   │      │
│  │ Screen       │            │ (top 5 ranked clients) │      │
│  │              │            │                        │      │
│  │ Step 1       │            │ John Smith  – 0.8 mi   │      │
│  │ Step 2       │            │ Jane Doe    – 1.2 mi   │      │
│  │ Step 3       │            │ Bob Jones   – 1.7 mi   │      │
│  │  ...         │            │ ...                    │      │
│  └──────────────┘            └───────┬────────────────┘      │
│                                      │ tap client            │
│                                      ▼                       │
│                              ┌────────────────────────┐      │
│                              │ ClientDetailScreen     │      │
│                              │                        │      │
│                              │ John Smith              │      │
│                              │ 123 Oak St, Portage    │      │
│                              │ 0.8 mi · Step 3 due   │      │
│                              │                        │      │
│                              │ [Navigate]  [Complete] │      │
│                              │ [Skip]                 │      │
│                              └───────┬────────────────┘      │
│                                      │                       │
│                        Navigate      │      Complete         │
│                     (open G-Maps)    │   (write record,      │
│                                      │    return to list)    │
└──────────────────────────────────────────────────────────────┘
```

**Screens (4 total):**

| # | Screen class              | Template used          | Purpose |
|---|---------------------------|------------------------|---------|
| 1 | `StepSelectScreen`        | `ListTemplate`         | Pick service step(s) for today's route |
| 2 | `SuggestionListScreen`    | `ListTemplate`         | Show top-N ranked suggestions with address + distance |
| 3 | `ClientDetailScreen`      | `PaneTemplate`         | Single client info + action buttons (Navigate / Complete / Skip) |
| 4 | `ServiceCompleteScreen`   | `MessageTemplate`      | Confirmation after marking a service complete |

---

## Implementation Phases

### Phase 0 — Dependency & Manifest Setup

**Files changed:**
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/automotive_app_desc.xml` *(new)*

**build.gradle.kts** — add Car App dependency:
```kotlin
dependencies {
    // existing deps …
    implementation("androidx.car.app:app:1.4.0")
}
```

Min SDK 24 is fine — `car-app:1.4.0` requires minSdk 23.

**AndroidManifest.xml** — register `CarAppService` and metadata:
```xml
<application …>
    <!-- existing activities/services … -->

    <service
        android:name=".auto.RouteMeCarAppService"
        android:exported="true">
        <intent-filter>
            <action android:name="androidx.car.app.CarAppService" />
            <category android:name="androidx.car.app.category.NAVIGATION" />
        </intent-filter>
    </service>

    <meta-data
        android:name="com.google.android.gms.car.application"
        android:resource="@xml/automotive_app_desc" />
</application>
```

**res/xml/automotive_app_desc.xml** *(new)*:
```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="navigation" />
</automotiveApp>
```

---

### Phase 1 — CarAppService + Session Skeleton

**New files** (all under `app/src/main/java/com/routeme/app/auto/`):

| File | Purpose |
|------|---------|
| `RouteMeCarAppService.kt` | Entry point; creates a `Session` |
| `RouteMeSession.kt` | Lifecycle owner; holds references to use-cases |
| `StepSelectScreen.kt` | First screen shown on connection |

#### `RouteMeCarAppService.kt`
```kotlin
package com.routeme.app.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class RouteMeCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR   // personal-use — no host restriction

    override fun onCreateSession(): Session = RouteMeSession()
}
```

#### `RouteMeSession.kt`
```kotlin
package com.routeme.app.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.routeme.app.domain.SuggestionUseCase
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RouteMeSession : Session(), KoinComponent {

    internal val suggestionUseCase: SuggestionUseCase by inject()

    override fun onCreateScreen(intent: Intent): Screen =
        StepSelectScreen(carContext)
}
```

> **Koin note:** `Session` is not an Android `Context` subclass, but
> `KoinComponent` gives it access to the global Koin graph that
> `RouteMeApplication.onCreate()` already starts. No DI changes needed.

---

### Phase 2 — Step Selection Screen

**File:** `auto/StepSelectScreen.kt`

Presents a `ListTemplate` with one row per step (Step 1–6, Grub, Incidental).
Tapping a row pushes `SuggestionListScreen` with the selected `ServiceType` set.

```kotlin
package com.routeme.app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.routeme.app.RouteModels.ServiceType

class StepSelectScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        ServiceType.entries.forEach { step ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(step.label)
                    .setOnClickListener {
                        screenManager.push(
                            SuggestionListScreen(carContext, setOf(step))
                        )
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Select Step")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
```

**Enhancements to consider later:**
- Multi-step selection (tap to toggle, then a "Go" action button) similar to
  how the phone UI allows multiple steps. This would use `Toggle` rows or a
  two-screen flow: select → confirm.
- Remember the last phone-selected steps via `PreferencesRepository` so the
  car screen pre-selects them.

---

### Phase 3 — Suggestion List Screen

**File:** `auto/SuggestionListScreen.kt`

Calls `SuggestionUseCase.suggestNextClients()` to get ranked suggestions,
then displays the top 5 as a `ListTemplate`.

```kotlin
package com.routeme.app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.lifecycleScope
import com.routeme.app.RouteModels.ClientSuggestion
import com.routeme.app.RouteModels.ServiceType
import com.routeme.app.domain.SuggestionUseCase
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SuggestionListScreen(
    carContext: CarContext,
    private val selectedSteps: Set<ServiceType>
) : Screen(carContext), KoinComponent {

    private val suggestionUseCase: SuggestionUseCase by inject()
    private var suggestions: List<ClientSuggestion> = emptyList()
    private var loading = true

    init {
        lifecycleScope.launch {
            suggestions = suggestionUseCase.suggestNextClients(
                selectedSteps = selectedSteps,
                /* pass current location, direction, etc. */
            ).take(5)
            loading = false
            invalidate()   // re-render with data
        }
    }

    override fun onGetTemplate(): Template {
        if (loading) {
            return MessageTemplate.Builder("Loading suggestions…")
                .setTitle("Suggestions")
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
        suggestions.forEach { s ->
            val distText = s.drivingDistance ?: s.distanceMiles?.let { "%.1f mi".format(it) } ?: ""
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(s.client.name)
                    .addText("${s.client.address}  ·  $distText")
                    .setOnClickListener {
                        screenManager.push(
                            ClientDetailScreen(carContext, s, selectedSteps)
                        )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Next Clients")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
```

**Data flow:**
1. Screen gets current location from `LocationTrackingService` (already running
   as a foreground service; expose last-known location via `TrackingEventBus` or
   a simple `LiveData`/`StateFlow` on the service).
2. Passes location + selected steps to `SuggestionUseCase`.
3. `RoutingEngine` scores & orders candidates.
4. Top 5 rendered.

---

### Phase 4 — Client Detail Screen

**File:** `auto/ClientDetailScreen.kt`

Shows a single client with action buttons. Uses `PaneTemplate` (supports up to
4 action buttons).

```kotlin
package com.routeme.app.auto

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.lifecycleScope
import com.routeme.app.RouteModels.ClientSuggestion
import com.routeme.app.RouteModels.ServiceType
import com.routeme.app.domain.ServiceCompletionUseCase
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ClientDetailScreen(
    carContext: CarContext,
    private val suggestion: ClientSuggestion,
    private val selectedSteps: Set<ServiceType>
) : Screen(carContext), KoinComponent {

    private val completionUseCase: ServiceCompletionUseCase by inject()

    override fun onGetTemplate(): Template {
        val client = suggestion.client

        val navigateAction = Action.Builder()
            .setTitle("Navigate")
            .setOnClickListener {
                val lat = client.latitude
                val lng = client.longitude
                if (lat != null && lng != null) {
                    val uri = Uri.parse("google.navigation:q=$lat,$lng")
                    carContext.startCarApp(Intent(Intent.ACTION_VIEW, uri))
                }
            }
            .build()

        val completeAction = Action.Builder()
            .setTitle("Complete")
            .setOnClickListener {
                lifecycleScope.launch {
                    completionUseCase.completeService(client, selectedSteps)
                    screenManager.push(ServiceCompleteScreen(carContext, client.name))
                }
            }
            .build()

        val skipAction = Action.Builder()
            .setTitle("Skip")
            .setOnClickListener { screenManager.pop() }
            .build()

        val distText = suggestion.drivingDistance
            ?: suggestion.distanceMiles?.let { "%.1f mi".format(it) }
            ?: ""

        val stepsText = suggestion.eligibleSteps.joinToString { it.label }

        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(client.name).build())
            .addRow(Row.Builder().setTitle(client.address).build())
            .addRow(Row.Builder().setTitle("$distText  ·  $stepsText").build())
            .addAction(navigateAction)
            .addAction(completeAction)
            .addAction(skipAction)
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(client.name)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
```

**Navigate action:** Opens Google Maps on the car display via
`carContext.startCarApp(intent)`. Google Maps for Auto handles the turn-by-turn.
This avoids the need to draw a custom map surface.

**Complete action:** Calls `ServiceCompletionUseCase` to write the
`ServiceRecordEntity` + `ClientStopEventEntity`, then shows a confirmation
screen and pops back to the suggestion list.

---

### Phase 5 — Service-Complete Confirmation Screen

**File:** `auto/ServiceCompleteScreen.kt`

Simple `MessageTemplate` confirming the service was logged.

```kotlin
package com.routeme.app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

class ServiceCompleteScreen(
    carContext: CarContext,
    private val clientName: String
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder("$clientName marked complete.")
            .setTitle("Service Logged")
            .addAction(
                Action.Builder()
                    .setTitle("Next Client")
                    .setOnClickListener {
                        // Pop back past the detail screen to the suggestion list
                        screenManager.popToRoot()
                    }
                    .build()
            )
            .build()
    }
}
```

---

### Phase 6 — Expose Current Location to Car Screens

The car screens need the technician's current location for suggestion ranking.
`LocationTrackingService` already tracks GPS; we need to surface the latest fix.

**Option A (preferred): StateFlow in TrackingEventBus**

`TrackingEventBus` already exists for event dispatch. Add a `StateFlow`:

```kotlin
// In TrackingEventBus.kt (existing file)
val lastLocation = MutableStateFlow<Location?>(null)
```

`LocationTrackingService` writes to it on every GPS fix (it already dispatches
events — add one line). Car screens collect from it.

**Option B: SharedPreferences last-known-location**

Write lat/lng to `PreferencesRepository` on each fix. Car screens read it.
Simpler but less reactive.

---

### Phase 7 — Auto-Refresh Suggestion List

After completing a service and popping back to `SuggestionListScreen`, the list
should re-rank. Two approaches:

1. **`onStart()` refresh:** Override `onStart()` in `SuggestionListScreen` to
   re-query `SuggestionUseCase` and `invalidate()`. This runs each time the
   screen becomes visible again.

2. **Observe `TrackingEventBus`:** Listen for service-completion events and
   re-query automatically.

Option 1 is simpler and sufficient.

---

## File Summary

### New files

| Path | Description |
|------|-------------|
| `app/src/main/java/com/routeme/app/auto/RouteMeCarAppService.kt` | CarAppService entry point |
| `app/src/main/java/com/routeme/app/auto/RouteMeSession.kt` | Session lifecycle + Koin bridge |
| `app/src/main/java/com/routeme/app/auto/StepSelectScreen.kt` | Step picker (ListTemplate) |
| `app/src/main/java/com/routeme/app/auto/SuggestionListScreen.kt` | Ranked suggestion list |
| `app/src/main/java/com/routeme/app/auto/ClientDetailScreen.kt` | Client info + Navigate/Complete/Skip |
| `app/src/main/java/com/routeme/app/auto/ServiceCompleteScreen.kt` | Completion confirmation |
| `app/src/main/res/xml/automotive_app_desc.xml` | Required Auto descriptor |

### Modified files

| Path | Change |
|------|--------|
| `app/build.gradle.kts` | Add `androidx.car.app:app:1.4.0` dependency |
| `app/src/main/AndroidManifest.xml` | Register `RouteMeCarAppService` + `automotive_app_desc` metadata |
| `app/src/main/java/com/routeme/app/TrackingEventBus.kt` | Add `lastLocation` StateFlow |
| `app/src/main/java/com/routeme/app/LocationTrackingService.kt` | Emit to `lastLocation` on GPS fix |

### No changes needed

| Component | Reason |
|-----------|--------|
| `RouteMeApplication.kt` / Koin modules | Car screens use `KoinComponent`; no new modules required |
| `ClientRepository` | Already provides all needed data |
| `SuggestionUseCase` / `RoutingEngine` | Already provides ranked suggestions — car screens call them directly |
| `ServiceCompletionUseCase` | Already handles writing service records |
| Room DB / DAOs | Shared as-is |
| `GeocodingHelper` / `DistanceMatrixHelper` | Used indirectly through existing use-cases |

---

## Testing & Development Environment

There are two ways to test the Android Auto UI on your PC without a real car.
The **DHU** is the recommended approach for this project.

### Option A: Desktop Head Unit (DHU) — Recommended

The DHU is a lightweight Windows app that simulates the car head unit display.
It connects to your phone or emulator via ADB and renders the projected Android
Auto UI — exactly how it works in a real car.

**Why DHU is the right choice for RouteMe:**
- Matches the real deployment model (phone app projecting to car display)
- `LocationTrackingService` runs on the phone/emulator alongside the car UI
- Room DB, Koin DI, and all phone-side services are live
- Lightweight — small desktop window, not a full second emulator

#### Install the DHU

1. Open **SDK Manager** (Android Studio → Settings → Appearance & Behavior →
   System Settings → Android SDK → SDK Tools tab)
2. Check **"Android Auto Desktop Head Unit Emulator"**
3. Click Apply / OK to install
4. Installs to: `C:\Users\andre\AppData\Local\Android\Sdk\extras\google\auto\`

#### Prerequisites on the emulator

The emulator must have **Google Play Services** with Android Auto support:
- Use a system image with **"Google APIs"** or **"Google Play"** (not plain AOSP)
- Android Auto app must be present — it's included in Google Play images

#### Enable Android Auto Developer Mode (one-time setup)

On the emulator (or physical phone):

1. Open **Settings → Connected devices → Connection preferences → Android Auto**
   (path varies by Android version; may also be a standalone "Android Auto" app)
2. Tap the **version number** at the bottom **10 times** to unlock Developer Settings
3. Tap the overflow menu (⋮) → **Developer settings**
4. Enable **"Unknown sources"** — allows sideloaded apps on Android Auto
5. Enable **"Start head unit server"** — required for DHU connections

#### Start the DHU

```powershell
# 1. Forward the head unit server port from the emulator to your PC
$adb = "C:\Users\andre\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 forward tcp:5277 tcp:5277

# 2. Launch the DHU
& "C:\Users\andre\AppData\Local\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

A window opens showing the car display. Your `RouteMeCarAppService` appears in
the Auto app launcher (the grid icon). Click it to launch the car experience.

#### DHU interaction

- **Click** = tap on touchscreen head unit
- **Scroll wheel** = rotary controller input
- **Keyboard shortcuts**: `N` = navigation, `M` = media, `H` = home
- The DHU toolbar has buttons for day/night mode toggle, mic input, etc.
- Resize the window to test different head unit resolutions

#### Typical dev workflow

```powershell
# Build and install the app on the emulator
.\gradlew.bat assembleDebug
& $adb -s emulator-5554 install -r .\app\build\outputs\apk\debug\app-debug.apk

# Forward port and launch DHU (if not already running)
& $adb -s emulator-5554 forward tcp:5277 tcp:5277
& "C:\Users\andre\AppData\Local\Android\Sdk\extras\google\auto\desktop-head-unit.exe"

# After code changes: rebuild, reinstall, reconnect DHU
# The DHU auto-reconnects when the app is reinstalled
```

### Option B: Android Automotive OS Emulator (alternative)

This is a full Android system image that *is* the car — no phone in the loop.
Use this only if you want to test against embedded Automotive OS (not needed for
RouteMe's phone-projected use case).

**Setup:**

1. SDK Manager → SDK Platforms → check **"Android Automotive with Google APIs"**
   for your target API level (e.g., API 34)
2. AVD Manager → Create Virtual Device → **Automotive** category → pick a
   profile → select the Automotive system image
3. Launch the AVD — it boots into a car dashboard UI
4. Install your APK onto the Automotive AVD directly via `adb install`

**Tradeoffs vs DHU:**

| | DHU | Automotive Emulator |
|---|---|---|
| **Tests the real flow** | Yes — phone runs full app + services, DHU shows car UI | Partial — no phone, so `LocationTrackingService` doesn't run alongside |
| **Setup effort** | Low — one SDK tool + one ADB forward | Medium — new AVD + large system image download |
| **Resource usage** | Light — small window process | Heavy — full second emulator instance |
| **Matches deployment** | Yes — phone → car projection (Android Auto) | No — this is embedded Android in the car (Automotive OS) |
| **Google Maps handoff** | Works — Maps runs on the phone and projects | May not work — Maps may not be available on Automotive image |

**Recommendation:** Use the DHU for all development and testing. The Automotive
emulator is only relevant if you later want to publish a standalone car app.

### Test Matrix

| Scenario | Verify |
|----------|--------|
| Launch from Auto drawer | `StepSelectScreen` shows all steps |
| Select a step | `SuggestionListScreen` loads top 5 ranked clients |
| Tap a client | `ClientDetailScreen` shows correct info + distance |
| Tap Navigate | Google Maps opens on car display with correct lat/lng |
| Tap Complete | `ServiceRecordEntity` written to DB; confirmation shown; list refreshes on pop-back |
| Tap Skip | Returns to suggestion list; skipped client deprioritized |
| No location available | Suggestions fall back to non-distance-ranked order |
| No clients for selected step | Empty state / message shown |
| Day/night toggle (DHU toolbar) | Templates render correctly in both themes |
| Rotary controller (scroll wheel) | Lists are navigable; buttons are focusable |
| Phone screen off | Car UI continues to work (service keeps running) |
| Disconnect/reconnect USB | Session reestablishes cleanly |

---

## Sideloading & Developer Mode Setup (Summary)

Since this is personal-use only, no Play Store or Google review is needed.

1. **Enable Android Auto Developer Mode** (see detailed steps above under
   "Enable Android Auto Developer Mode")
2. **Build & install:**
   ```powershell
   .\gradlew.bat assembleDebug
   adb install -r .\app\build\outputs\apk\debug\app-debug.apk
   ```
3. **Test with DHU** (see "Start the DHU" above) or connect phone to car via
   USB/wireless Android Auto.

---

## Future Enhancements (Not in scope for initial build)

- **Voice commands:** "Hey Google, open RouteMe" / "Mark complete" via
  `VoiceInteractionSession` or Auto's built-in assistant hooks.
- **Multi-step selection:** Toggle multiple steps before viewing suggestions.
- **Map surface drawing:** Replace Google Maps handoff with an in-app
  `NavigationTemplate` + `SurfaceCallback` showing client pins. Adds
  significant complexity — only worthwhile if Google Maps handoff proves
  insufficient.
- **Arrival auto-detection on car display:** When `LocationTrackingService`
  detects on-site arrival, push a notification-style prompt on the car screen
  to mark complete. Requires `Screen` push from a background observer.
- **Route queue:** Port the `DestinationsActivity` queue to a car list,
  allowing the technician to see and reorder upcoming stops.
