# RouteMe v2.0 — Property Stats & Weather-Aware Routing

> Produced 2026-03-18 · Builds on completed refactor (all 7 phases) and neumorphic UI redesign

## Overview

Add per-client property characteristics (lawn size, sun/shade, wind exposure, slopes,
irrigation) collected during on-site visits, then use that data combined with live
weather forecasts to automatically adjust route suggestions — deprioritizing unsafe or
inefficient stops and prioritizing favorable conditions.

## Maintainability Guardrails

- Keep `MainViewModel` orchestration-only: no new parsing, scoring, or SQL logic there.
- Put persistence in DAOs/repositories, decision logic in use cases/engine functions, and UI in dialog/controller classes.
- Keep each phase shippable in small slices (schema first, then UI wiring, then scoring), with tests added per slice.
- Reuse shared weather/property threshold constants from `AppConfig` (no duplicated magic numbers).
- Prefer small files with single responsibility over adding more feature logic to existing large classes.

## Prerequisites

- Neumorphic UI redesign (see `UI_REDESIGN_PLAN.md`) ships first
- Google Sheet created: **"RouteMe Property Data"** (separate permanent spreadsheet)
  - Columns: Name | Address | Lawn Size | Sun/Shade | Wind Exposure | Steep Slopes | Irrigation | Notes | Last Updated
  - Dropdowns on D–G; numeric validation on C (5,000–100,000 sqft)
  - Key = client Name (matches seasonal sheet name-matching logic)

---

## Phase 1 — Schema & Data Layer

> Blocks all other phases. Creates persistent property storage that survives seasonal reimports.

### Why a separate table?

Client imports use `OnConflictStrategy.REPLACE`, which wipes all columns on the
`clients` table when a client is reimported. Property data collected on-site during the
season would be lost. A separate `client_properties` table with no cascade delete keeps
that data safe across reimports, Google Sheets syncs, and season-to-season reuse.

### Steps

- [x] **1.1** DB migration 12→13: CREATE `client_properties` table
  ```sql
  CREATE TABLE client_properties (
      clientId    TEXT PRIMARY KEY,
      lawnSizeSqFt INTEGER NOT NULL DEFAULT 0,
      sunShade    TEXT NOT NULL DEFAULT 'UNKNOWN',
      windExposure TEXT NOT NULL DEFAULT 'UNKNOWN',
      hasSteepSlopes INTEGER NOT NULL DEFAULT 0,
      hasIrrigation INTEGER NOT NULL DEFAULT 0,
      propertyNotes TEXT NOT NULL DEFAULT '',
      updatedAtMillis INTEGER NOT NULL DEFAULT 0,
      FOREIGN KEY (clientId) REFERENCES clients(id)
  );
  ```
  - **No `ON DELETE CASCADE`** — properties persist even when client row is replaced
  - No terrain column (redundant with slopes — decided during planning)

- [x] **1.2** Migrate existing data in migration 12→13: copy non-default `lawnSizeSqFt`,
  `sunShade`, `windExposure`, and `terrain` from `clients` into `client_properties`.
  Map `terrain` → `hasSteepSlopes` (non-empty terrain string = true). Deprecate those
  four columns on `clients` (leave in schema since SQLite <3.35 can't DROP COLUMN, but
  stop reading them).

- [x] **1.3** Create `PropertyEnums.kt` (root package) — typed enums stored as TEXT in DB:
  ```
  SunShade:      FULL_SUN | PARTIAL_SHADE | FULL_SHADE | UNKNOWN
  WindExposure:  EXPOSED  | SHELTERED     | MIXED      | UNKNOWN
  ```

- [x] **1.4** Create `ClientPropertyEntity.kt` (root package, alongside `ClientEntity.kt`) — Room `@Entity` for the new table

- [x] **1.5** Create `ClientProperty.kt` (root package) — domain model with typed enums

- [x] **1.6** Create `ClientPropertyDao.kt` (root package, alongside `ClientDao.kt`):
  - `getPropertyForClient(clientId): ClientPropertyEntity?`
  - `getAllProperties(): List<ClientPropertyEntity>` (for batch scoring)
  - `upsertProperty(entity)` (`OnConflictStrategy.REPLACE` on clientId PK)

- [x] **1.7** Update `Client.kt` (root package) — add `property: ClientProperty?` (nullable = not yet collected)

- [x] **1.8** Update `DbQueryModels.kt` (root package) — add `@Relation` on `ClientWithRecords`
  to include `ClientPropertyEntity`

- [x] **1.9** Update `Mappers.kt` (root package) — `ClientPropertyEntity` ↔ `ClientProperty`

- [x] **1.10** Update `AppDatabase.kt` (root package) — bump version to 13, register entity + DAO

- [x] **1.11** Update `di/AppModule.kt` — provide `ClientPropertyDao`

- [x] **1.12** Update import pipeline (`CsvParsingUtils.kt`, root package):
  - Add `steepSlopeKeys` and `irrigationKeys` to `ClientBuildConfig`
  - Imported property data → `client_properties` via upsert
  - Missing/blank import columns do NOT overwrite existing manually-entered data

### Files Modified
- `AppDatabaseMigrations.kt`, `AppDatabase.kt`, `DbQueryModels.kt` (root package)
- `Mappers.kt`, `Client.kt`, `di/AppModule.kt` 
- `CsvParsingUtils.kt`, `ClientImportParser.kt` (root package)

### Files Created
- `PropertyEnums.kt`, `ClientProperty.kt` (root package)
- `ClientPropertyEntity.kt`, `ClientPropertyDao.kt` (root package)

---

## Phase 2 — Property Edit Dialog

> On-site data collection. Can be developed in parallel with Phase 3 design.
> **Note:** Partial backend already exists — `PropertyInput` in `ConsumableRates.kt` has
> `sunShade`/`windExposure`/`steepSlopes`/`irrigation` with dropdown options;
> `MainViewModel.writePropertyStats()` and `SheetsWriteBack.postPropertyRaw()` already
> write to the property Google Sheet. This phase wires the new Room-backed
> `ClientProperty` model into the existing write path and adds a dedicated edit dialog.

- [x] **2.1** Create `dialog_property_edit.xml` layout:
  - Lawn Size: `TextInputEditText` (number input, 5000–100000)
  - Sun/Shade: `ChipGroup` single-select (Full Sun / Partial Shade / Full Shade)
  - Wind Exposure: `ChipGroup` single-select (Exposed / Sheltered / Mixed)
  - Steep Slopes: `MaterialSwitch` (Yes/No)
  - Irrigation: `MaterialSwitch` (Yes/No)
  - Property Notes: `TextInputEditText` (multi-line, free-form)
  - Pre-populate with existing values; highlight UNKNOWN fields

- [x] **2.2** Create dialog class (via `DialogFactory` or `MaterialAlertDialogBuilder`)
  - Shown when user taps "Property Info" button during/after arrival

- [x] **2.3** Wire trigger — "Property Info" action button visible when client is
  selected/arrived. Subtle badge if client has UNKNOWN fields.

- [x] **2.4** Persist via ViewModel: `MainViewModel.updateClientProperties()` →
  `ClientPropertyDao.upsertProperty()`. Refactor existing `writePropertyStats()` to
  save to Room first, then trigger the sheet write-back (currently writes only to Sheets).

- [x] **2.5** Queue write-backs to property Google Sheet — extend existing
  `SheetsWriteBack.postPropertyRaw()` / `addPropertyClientRow()` and
  `ServiceCompletionUseCase` write-back logic to also persist locally via Room.

### Files Modified
- `ui/MainViewModel.kt`, `ui/DialogFactory.kt` or new dialog class
- `data/db/PendingWriteBackEntity.kt` (if adding sheet-target discriminator)
- `network/GoogleSheetsSync.kt` (route property writes to correct sheet)

### Files Created
- `res/layout/dialog_property_edit.xml`

---

## Phase 3 — Property Sheet Sync

> Bidirectional sync with the permanent Google Sheet.
> **Note:** Much of the export pipeline already exists: `PreferencesRepository.propertySheetWriteUrl`,
> `SheetsWriteBack.postPropertyRaw()` / `addPropertyClientRow()`, `ServiceCompletionUseCase`
> write-back on completion, `SyncSettingsUseCase` wiring of the URL, and the deployed
> `apps-script-property-sheet.js`. Steps 3.1–3.3 are marked done; only 3.4 (import) is new.

- [x] **3.1** ~~Add `PROPERTY_SHEET_URL` to `PreferencesRepository`~~ — **DONE.**
  Already exists as `propertySheetWriteUrl` in `PreferencesRepository`; wired via
  `SyncSettingsUseCase` → `SheetsWriteBack.propertyWebAppUrl`.

- [x] **3.2** ~~Export: property edits → property sheet~~ — **DONE.**
  `ServiceCompletionUseCase` already writes Sun/Shade, Wind Exposure, Steep Slopes,
  Irrigation, and Last Updated to the property sheet via `postPropertyRaw()`.
  `MainViewModel.writePropertyStats()` also covers manual edits.

- [x] **3.3** ~~Apps Script for property sheet~~ — **DONE.**
  `apps-script-property-sheet.js` is committed in repo root; deployed as web app.

- [x] **3.4** Import on season start: after importing seasonal client list, read Property
  Data sheet as CSV → match by Name → upsert into `client_properties` for returning
  clients. Pre-populates property data before first visit of the season.

### Sheet Column ↔ App Field Mapping

| Sheet Header    | App Import Key                              | Domain Field            |
|-----------------|---------------------------------------------|-------------------------|
| Name            | `name`                                      | Match key (not stored)  |
| Address         | `address`                                   | Display only            |
| Lawn Size       | `lawnsize`, `lawn_size`, `lawn size`        | `lawnSizeSqFt: Int`     |
| Sun/Shade       | `sunshade`, `sun/shade`, `sun_shade`        | `SunShade` enum         |
| Wind Exposure   | `windexposure`, `wind_exposure`, `wind exposure` | `WindExposure` enum |
| Steep Slopes    | `steepslopes`, `steep_slopes`, `steep slopes` | `hasSteepSlopes: Boolean` |
| Irrigation      | `irrigation`                                | `hasIrrigation: Boolean` |
| Notes           | `notes`                                     | `propertyNotes: String`  |
| Last Updated    | `lastupdated`, `last_updated`, `last updated` | `updatedAtMillis` (display) |

### Files Modified
- `data/PreferencesRepository.kt`, `network/GoogleSheetsSync.kt`
- `domain/SyncSettingsUseCase.kt`, `ui/MainViewModel.kt`

---

## Phase 4 — Weather-Aware Scoring Engine

> Core intelligence: combine property data + weather forecasts to adjust route rankings.

- [x] **4.1** Fetch today's weather when "Suggest Next" is tapped — ensure
  `WeatherRepository.getWeather(today)` supplies `DailyWeather?`

- [x] **4.2** Fetch 2-day lookback (yesterday + day-before) from `WeatherRepository`.
  Compute `recentPrecipInches` = sum of precipitation over past 2 days + today's forecast.
  Slopes stay slippery for 24–48h after rain.

- [x] **4.3** Add `getRecentPrecip(lookbackDays: Int): Double?` helper to
  `WeatherRepository`

- [x] **4.4** Extend `RoutingEngine.rankClients()` signature:
  ```kotlin
  fun rankClients(
      clients: List<Client>,
      serviceTypes: Set<ServiceType>,
      minDays: Int,
      lastLocation: Location?,
      cuOverrideEnabled: Boolean,
      routeDirection: RouteDirection,
      skippedClientIds: Set<String>,
      destination: SavedDestination?,
      weather: DailyWeather?,            // NEW
      recentPrecipInches: Double?,       // NEW
      propertyMap: Map<String, ClientProperty>  // NEW
  ): List<ClientSuggestion>
  ```

- [x] **4.5** Add weather-property scoring rules in `calculateRouteScore()`:

  | Condition | Client Property | Weather Trigger | Score Adj |
  |-----------|----------------|-----------------|-----------|
  | Wind-exposed + windy day | `windExposure = EXPOSED` | `windSpeedMph ≥ 20` or `windGustMph ≥ 30` | **-80** |
  | Large exposed + calm day | `windExposure = EXPOSED`, `lawnSizeSqFt ≥ 15000` | `windSpeedMph ≤ 8` | **+40** |
  | Shaded yard + hot day | `sunShade = FULL_SHADE` or `PARTIAL_SHADE` | `highTempF ≥ 90` | **+25** |
  | Steep slopes + recent rain | `hasSteepSlopes = true` | `recentPrecipInches ≥ 0.25` | **-70** |
  | Rain + sensitive service | (any) | `today precip ≥ 0.10"` | Per-`ServiceType` penalty |
  | Irrigated + dry/hot | `hasIrrigation = true` | `highTempF ≥ 85`, `precip = 0` | **+10** |

- [x] **4.6** Add weather scoring constants to `AppConfig.kt`:
  ```
  WEATHER_WIND_EXPOSED_PENALTY        = -80.0
  WEATHER_WIND_THRESHOLD_MPH          = 20
  WEATHER_WIND_GUST_THRESHOLD_MPH     = 30
  WEATHER_CALM_EXPOSED_BONUS          = 40.0
  WEATHER_CALM_THRESHOLD_MPH          = 8
  WEATHER_CALM_LARGE_LAWN_SQFT        = 15000
  WEATHER_SHADE_HOT_BONUS             = 25.0
  WEATHER_HOT_THRESHOLD_F             = 90
  WEATHER_SLOPE_RAIN_PENALTY          = -70.0
  WEATHER_RAIN_LOOKBACK_DAYS          = 2
  WEATHER_SLOPE_RAIN_THRESHOLD_INCHES = 0.25
  WEATHER_RAIN_SERVICE_PENALTY        = -50.0
  WEATHER_RAIN_LIGHT_THRESHOLD        = 0.10
  WEATHER_IRRIGATED_DRY_BONUS         = 10.0
  WEATHER_DRY_HOT_THRESHOLD_F         = 85
  ```

- [x] **4.7** UNKNOWN / null property = **neutral** — no weather adjustment applied.
  Data-poor clients are not penalized while property data is being collected.

### Files Modified
- `domain/RoutingEngine.kt` (scoring rules)
- `util/AppConfig.kt` (constants)
- `ui/MainViewModel.kt` (pass weather + properties to ranking)
- `data/WeatherRepository.kt` (lookback helper)
- `domain/SuggestionUseCase.kt` (fetch weather before ranking)

---

## Phase 5 — Scoring Debug Log

> For tuning scoring weights from real-route feedback.

- [x] **5.1** Add `DEBUG_SCORING` flag to `AppConfig.kt` (default false)

- [x] **5.2** When enabled, log per-client score breakdown via `Log.d("RoutingScore", ...)`:
  ```
  Wagner, Mary: dist=+160 mow=+5 overdue=+12 dir=+28 wind=-80 slope=0 shade=0 total=+125
  ```

- [x] **5.3** Toggle via menu — implemented as build-time constant instead of runtime toggle — "Debug Scoring" checkable item (hidden behind long-press
  on existing menu item, or dev-only build flag)

### Files Modified
- `domain/RoutingEngine.kt`, `util/AppConfig.kt`
- `MainActivity.kt` (menu item)

---

## Phase 6 — Progressive Data Collection Prompts

> Encourage filling in property data organically during the season.
> **Note:** `ServiceCompletionUseCase` already checks `property.hasAnyData` and writes to
> the property sheet on completion. The nudge builds on this existing check — after the
> write-back, inspect the new Room-backed `ClientProperty` for UNKNOWN fields.

- [x] **6.1** Post-completion nudge: after service is confirmed, if the client has no
  `ClientProperty` row or ≥3 UNKNOWN fields, show snackbar:
  *"Add property details for [Client]?"* — tapping opens the property edit dialog.
  Hook into existing `ServiceCompletionUseCase` property check (currently only writes
  to Sheets; extend to also evaluate completeness for the nudge).

- [x] **6.2** Property completeness badge on suggestion cards — small icon indicating
  whether the client has full property data vs. incomplete (subtle, not intrusive)

### Files Modified
- `domain/ServiceCompletionUseCase.kt` (extend existing property check → nudge event)
- `ui/SuggestionUiController.kt` (completeness badge)
- `ui/MainViewModel.kt` (snackbar event)

---

## Verification Checklist

| # | Test | Phase |
|---|------|-------|
| 1 | Room migration test (12→13): `client_properties` created, existing data migrated, defaults correct | 1 |
| 2 | Reimport survival: import → edit properties → reimport same clients → properties intact | 1 |
| 3 | CSV import with slope/irrigation columns populates `client_properties`; missing columns don't overwrite | 1 |
| 4 | Property edit round-trip: dialog → save → reopen → values persisted + write-back queued | 2 |
| 5 | Property sheet export: edit in app → row updated in Google Sheet | 3 |
| 6 | Property sheet import: data in sheet → season reimport → `client_properties` pre-populated | 3 |
| 7 | Scoring: EXPOSED + 25mph wind → penalty applied | 4 |
| 8 | Scoring: EXPOSED + large yard + calm → bonus applied | 4 |
| 9 | Scoring: hasSteepSlopes + recentPrecip 0.30" (2-day sum) → penalty even if today = 0" | 4 |
| 10 | Scoring: null property / UNKNOWN fields → no weather adjustment (neutral) | 4 |
| 11 | Debug log: enable flag → Logcat shows per-client score breakdown | 5 |
| 12 | Integration: on device, "Suggest Next" works; wind-exposed clients drop on windy forecast | 4 |

---

## Decisions Log

| Decision | Rationale |
|----------|-----------|
| Property data in separate `client_properties` table | Survives `OnConflictStrategy.REPLACE` on seasonal client reimport. No CASCADE DELETE. |
| Steep slopes = binary (Yes/No) | User preference — simpler than gradient scale, sufficient for safety decision |
| Irrigation = binary (Yes/No) | Present or not; type/details not needed for routing |
| No terrain enum (dropped) | Redundant with steep slopes for routing purposes |
| Typed enums for sun/shade + wind exposure | Replaces free-text strings; prevents inconsistent data |
| UNKNOWN = neutral scoring | Data-poor clients aren't unfairly penalized during collection phase |
| 2-day precipitation lookback | Slopes stay slippery 24–48h; sum today + yesterday + day-before |
| Client Name as sheet key (not clientId) | Seasonal sheet has no ID column; name matching already works in Apps Script |
| Separate permanent Google Sheet for properties | Survives season transitions (new seasonal spreadsheet each year) |
| Score debug logging via AppConfig flag | Enables real-route weight tuning over first few weeks |
| Weather scoring is additive | Doesn't replace existing distance/mow/overdue factors — supplements them |
| Rain-service restrictions per-ServiceType | Some steps (e.g., weed control) are rain-sensitive, others aren't; mapping TBD |
| Treatment efficacy tracking deferred | Out of scope for v2.0; data model supports future correlation analysis |
