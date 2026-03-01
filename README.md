# RouteMe

An Android navigation/routing app built with Kotlin.

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Minimum Android API level 24 (Android 7.0)

## Getting Started

1. Clone this repository
2. Open the project in Android Studio
3. Sync Gradle files
4. Run the app on an emulator or physical device

## Project Structure

```
route-me/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/routeme/   # Kotlin source files
│   │   │   ├── res/                        # Resources (layouts, strings, etc.)
│   │   │   └── AndroidManifest.xml
│   │   ├── test/                           # Unit tests
│   │   └── androidTest/                    # Instrumented tests
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

## Permissions

- `INTERNET` – for fetching route data
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` – for determining user location