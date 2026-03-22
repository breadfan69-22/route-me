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
┌──────────────────────────────────────────────────────────────────┐
│  ANDROID AUTO HEAD UNIT                                          │
│                                                                  │
│  1. Launch RouteMe from Auto app drawer                          │
│         │                                                        │
│         ▼                                                        │
│  ┌──────────────────┐                                            │
│  │ SuggestionList   │  ← uses phone-selected steps from prefs   │
│  │ Screen           │                                            │
│  │                  │  ┌── if Errands Mode ON ──────────────┐    │
│  │ John Smith  0.8mi│  │  Shows destination queue instead   │    │
│  │ Jane Doe   1.2mi │  │  "Stop 1: Tractor Supply"          │    │
│  │ Bob Jones  1.7mi │  │  "Stop 2: Meijer"                  │    │
│  │ ...              │  │  [Navigate] only                    │    │
│  │ [⚙ Change Steps] │  └────────────────────────────────────┘    │
│  └───────┬──────────┘                                            │
│          │ tap client                                            │
│          ▼                                                       │
│  ┌────────────────────────┐                                      │
│  │ ClientDetailScreen     │                                      │
│  │                        │                                      │
│  │ John Smith              │                                      │
│  │ 123 Oak St, Portage    │                                      │
│  │ 0.8 mi · Step 3 due   │                                      │
│  │                        │                                      │
│  │ [Navigate]  [Complete] │                                      │
│  │ [Skip]                 │                                      │
│  └───────┬────────────────┘                                      │
│          │                                                       │
│          ├── Navigate → open Google Maps                         │
│          ├── Complete → write record → confirmation              │
│          └── Skip → back to list                                 │
└──────────────────────────────────────────────────────────────────┘
```

**Screens (4 total):**

| # | Screen class              | Template used          | Purpose |
|---|---------------------------|------------------------|---------|
| 1 | `SuggestionListScreen`    | `ListTemplate`         | Landing screen — ranked suggestions (or destination queue in Errands Mode). Defaults to phone-selected steps. |
| 2 | `StepSelectScreen`        | `ListTemplate`         | Override step selection (accessed via action button on suggestion list) |
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
import org.koin.core.component.KoinComponent

class RouteMeSession : Session(), KoinComponent {

    override fun onCreateScreen(intent: Intent): Screen =
        SuggestionListScreen(carContext)
}
```

> **Koin note:** `Session` is not an Android `Context` subclass, but
> `KoinComponent` gives it access to the global Koin graph that
> `RouteMeApplication.onCreate()` already starts. No DI changes needed.
> Car screens inject their own use-cases via `KoinComponent`.

---

### Phase 2 — Step Selection Screen (secondary)

**File:** `auto/StepSelectScreen.kt`

This screen is **not** the landing screen — the car launches directly into
`SuggestionListScreen` using whatever steps are already selected on the phone
(stored in `PreferencesRepository.selectedSteps`). The step selector is accessible
via an action button on the suggestion list for cases where the driver wants to
override the phone's selection without pulling it out.

Tapping a step writes it to `PreferencesRepository` (so phone and car stay
in sync) and pops back to the suggestion list, which re-queries on `onStart()`.

```kotlin
package com.routeme.app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.routeme.app.ServiceType
import com.routeme.app.data.PreferencesRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class StepSelectScreen(carContext: CarContext) : Screen(carContext), KoinComponent {

    private val prefs: PreferencesRepository by inject()

    override fun onGetTemplate(): Template {
        val currentSteps = prefs.selectedSteps
            .split(",")
            .mapNotNull { runCatching { ServiceType.valueOf(it.trim()) }.getOrNull() }
            .toSet()

        val listBuilder = ItemList.Builder()
        ServiceType.entries.forEach { step ->
            val checked = step in currentSteps
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("${if (checked) "✓ " else ""}${step.label}")
                    .setOnClickListener {
                        // Toggle this step and persist
                        val updated = if (checked) currentSteps - step else currentSteps + step
                        prefs.selectedSteps = updated.joinToString(",") { it.name }
                        invalidate()  // re-render with updated checkmarks
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Select Steps")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Done")
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
    }
}
```

> **Sync note:** Writing to `PreferencesRepository.selectedSteps` keeps the
> phone and car in sync. When the driver pops back, `SuggestionListScreen`
> re-reads the steps in `onStart()` and re-ranks automatically.

---

### Phase 3 — Suggestion List Screen (landing screen)

**File:** `auto/SuggestionListScreen.kt`

This is the **first screen** the driver sees. It reads the phone-selected steps
from `PreferencesRepository`, checks whether Errands Mode is active, and renders
either ranked client suggestions or the destination queue.

```kotlin
package com.routeme.app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.lifecycleScope
import com.routeme.app.ClientSuggestion
import com.routeme.app.SavedDestination
import com.routeme.app.ServiceType
import com.routeme.app.TrackingEventBus
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.domain.DestinationQueueUseCase
import com.routeme.app.domain.SuggestionUseCase
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SuggestionListScreen(
    carContext: CarContext
) : Screen(carContext), KoinComponent {

    private val suggestionUseCase: SuggestionUseCase by inject()
    private val clientRepository: ClientRepository by inject()
    private val prefs: PreferencesRepository by inject()
    private val trackingEventBus: TrackingEventBus by inject()
    private val destinationQueueUseCase: DestinationQueueUseCase by inject()

    private var suggestions: List<ClientSuggestion> = emptyList()
    private var destinations: List<SavedDestination> = emptyList()
    private var errandsMode = false
    private var loading = true

    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
        super.onStart(owner)
        loadData()
    }

    private fun loadData() {
        loading = true
        invalidate()
        lifecycleScope.launch {
            errandsMode = prefs.errandsModeEnabled

            if (errandsMode) {
                destinations = destinationQueueUseCase.loadSavedDestinations()
            } else {
                val selectedSteps = prefs.selectedSteps
                    .split(",")
                    .mapNotNull { runCatching { ServiceType.valueOf(it.trim()) }.getOrNull() }
                    .toSet()
                    .ifEmpty { setOf(ServiceType.ROUND_1) }

                val clients = clientRepository.loadClients()
                val location = trackingEventBus.latestLocation.value
                val activeDestIndex = prefs.activeDestinationIndex
                val queue = destinationQueueUseCase.loadSavedDestinations()
                val activeDest = queue.getOrNull(activeDestIndex)

                val result = suggestionUseCase.suggestNextClients(
                    clients = clients,
                    selectedServiceTypes = selectedSteps,
                    minDays = prefs.minDays,
                    cuOverrideEnabled = prefs.cuOverrideEnabled,
                    routeDirection = prefs.routeDirection,
                    activeDestination = activeDest,
                    currentLocation = location
                )
                suggestions = result.suggestions.take(5)
            }

            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        if (loading) {
            return MessageTemplate.Builder(
                if (errandsMode) "Loading destinations…" else "Loading suggestions…"
            )
                .setTitle(if (errandsMode) "Errands" else "Next Clients")
                .setLoading(true)
                .build()
        }

        return if (errandsMode) buildErrandsTemplate() else buildSuggestionsTemplate()
    }

    private fun buildSuggestionsTemplate(): Template {
        val listBuilder = ItemList.Builder()
        suggestions.forEach { s ->
            val distText = s.distanceToShopMiles?.let { "%.1f mi".format(it) } ?: ""
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(s.client.name)
                    .addText("${s.client.address}  ·  $distText")
                    .setOnClickListener {
                        screenManager.push(
                            ClientDetailScreen(carContext, s)
                        )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Next Clients")
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle("Steps")
                    .setOnClickListener {
                        screenManager.push(StepSelectScreen(carContext))
                    }
                    .build()
            )
            .build()
    }

    private fun buildErrandsTemplate(): Template {
        val listBuilder = ItemList.Builder()
        destinations.forEachIndexed { index, dest ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(dest.name)
                    .addText(dest.address)
                    .setOnClickListener {
                        // Launch Google Maps navigation to this destination
                        val uri = android.net.Uri.parse(
                            "google.navigation:q=${dest.lat},${dest.lng}"
                        )
                        carContext.startCarApp(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        )
                    }
                    .build()
            )
        }

        if (destinations.isEmpty()) {
            return MessageTemplate.Builder("No destinations queued.\nAdd stops on your phone.")
                .setTitle("Errands Mode")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Errands")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
```

**Data flow:**
1. On every `onStart()`, reads `PreferencesRepository` for steps, errands mode,
   route direction, and active destination.
2. Gets current location from `TrackingEventBus.latestLocation` (already
   populated by `LocationTrackingService` on every GPS fix).
3. In normal mode: calls `SuggestionUseCase.suggestNextClients()` → top 5.
4. In errands mode: loads destination queue → renders navigate-only items.
5. "Steps" action button pushes `StepSelectScreen` for in-car override.

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
import com.routeme.app.ClientSuggestion
import com.routeme.app.GeoPoint
import com.routeme.app.ServiceType
import com.routeme.app.TrackingEventBus
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.domain.ServiceCompletionUseCase
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ClientDetailScreen(
    carContext: CarContext,
    private val suggestion: ClientSuggestion
) : Screen(carContext), KoinComponent {

    private val completionUseCase: ServiceCompletionUseCase by inject()
    private val clientRepository: ClientRepository by inject()
    private val prefs: PreferencesRepository by inject()
    private val trackingEventBus: TrackingEventBus by inject()

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
                    val clients = clientRepository.loadClients()
                    val selectedSteps = prefs.selectedSteps
                        .split(",")
                        .mapNotNull { runCatching { ServiceType.valueOf(it.trim()) }.getOrNull() }
                        .toSet()
                    val loc = trackingEventBus.latestLocation.value
                    val geoPoint = loc?.let { GeoPoint(it.latitude, it.longitude) }

                    completionUseCase.confirmSelectedClientService(
                        ServiceCompletionUseCase.ConfirmSelectedRequest(
                            clients = clients,
                            selectedClient = client,
                            arrivalStartedAtMillis = null,
                            arrivalLat = loc?.latitude,
                            arrivalLng = loc?.longitude,
                            selectedSuggestionEligibleSteps = suggestion.eligibleSteps,
                            selectedServiceTypes = selectedSteps,
                            currentLocation = geoPoint,
                            visitNotes = ""
                        )
                    )
                    screenManager.push(ServiceCompleteScreen(carContext, client.name))
                }
            }
            .build()

        val skipAction = Action.Builder()
            .setTitle("Skip")
            .setOnClickListener { screenManager.pop() }
            .build()

        val distText = suggestion.distanceToShopMiles?.let { "%.1f mi".format(it) } ?: ""
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

**Complete action:** Calls `ServiceCompletionUseCase.confirmSelectedClientService()`
with a proper `ConfirmSelectedRequest`, writing the `ServiceRecordEntity` +
`ClientStopEventEntity`, then shows a confirmation screen and pops back to the
suggestion list.

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

### Phase 6 — Auto-Refresh Suggestion List

After completing a service and popping back to `SuggestionListScreen`, the list
should re-rank. The `onStart()` lifecycle hook (already implemented in Phase 3's
`SuggestionListScreen`) handles this: each time the screen becomes visible, it
re-reads `PreferencesRepository` and re-queries `SuggestionUseCase`.

> **Note:** `TrackingEventBus.latestLocation` already exists as a `StateFlow`
> populated by `LocationTrackingService` on every GPS fix. No new code is needed
> to expose the current location — car screens read it directly.

---

### Phase 7 — Cluster Completion (future)

When `LocationTrackingService` detects departure from a cluster of 2+ adjacent
clients, the phone receives a `ClusterComplete` event and shows a multi-client
completion dialog. The current plan does **not** implement a car-side equivalent.

For now, cluster completions are handled via the phone notification (which
already works even when Android Auto is projected). A future enhancement could
add a car-side `ClusterCompleteScreen` using `ListTemplate` with checkable rows,
triggered by observing `TrackingEventBus.events` for `ClusterComplete`.

---

## File Summary

### New files

| Path | Description |
|------|-------------|
| `app/src/main/java/com/routeme/app/auto/RouteMeCarAppService.kt` | CarAppService entry point |
| `app/src/main/java/com/routeme/app/auto/RouteMeSession.kt` | Session lifecycle — pushes `SuggestionListScreen` as landing |
| `app/src/main/java/com/routeme/app/auto/SuggestionListScreen.kt` | Landing screen — ranked suggestions (or destination queue in errands mode) |
| `app/src/main/java/com/routeme/app/auto/StepSelectScreen.kt` | Secondary step-override picker (reads/writes `PreferencesRepository`) |
| `app/src/main/java/com/routeme/app/auto/ClientDetailScreen.kt` | Client info + Navigate / Complete / Skip |
| `app/src/main/java/com/routeme/app/auto/ServiceCompleteScreen.kt` | Completion confirmation |
| `app/src/main/res/xml/automotive_app_desc.xml` | Required Auto descriptor |

### Modified files

| Path | Change |
|------|--------|
| `app/build.gradle.kts` | Add `androidx.car.app:app:1.4.0` dependency |
| `app/src/main/AndroidManifest.xml` | Register `RouteMeCarAppService` + `automotive_app_desc` metadata |

### No changes needed

| Component | Reason |
|-----------|--------|
| `RouteMeApplication.kt` / Koin modules | Car screens use `KoinComponent`; no new modules required |
| `ClientRepository` | Already provides all needed data |
| `SuggestionUseCase` / `RoutingEngine` | Already provides ranked suggestions — car screens call them directly |
| `ServiceCompletionUseCase` | Already handles writing service records |
| `TrackingEventBus.kt` | `latestLocation` StateFlow already exists — car screens observe it directly |
| `LocationTrackingService.kt` | Already emits to `TrackingEventBus.latestLocation` on GPS fix |
| `PreferencesRepository` | Already stores `selectedSteps`, `errandsModeEnabled`, etc. — car screens read it |
| `DestinationQueueUseCase` | Already manages saved destinations — car errands mode reads it |
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
