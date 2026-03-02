# RouteMe - Copilot Workspace Instructions

## Project Overview
- Android Kotlin app for lawn treatment route planning and service tracking
- Gradle KTS build system, minSdk 24, targetSdk 35
- Package: `com.routeme.app`

## Build & Run
- JDK: Eclipse Adoptium Temurin 17 at `C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot`
- Android SDK: `C:\Users\andre\AppData\Local\Android\Sdk`
- Android Studio: `D:\Program Files\android studio`
- AVD storage: `D:\AndroidAVD`
- Build debug APK: `.\gradlew.bat assembleDebug`
- Install on emulator: `adb install -r .\app\build\outputs\apk\debug\app-debug.apk`

## Key Source Files
- `app/src/main/java/com/routeme/app/MainActivity.kt` - Main routing workflow
- `app/src/main/java/com/routeme/app/RouteModels.kt` - Client/service data models
- `app/src/main/java/com/routeme/app/ClientImportParser.kt` - CSV/HTML import
- `app/src/main/java/com/routeme/app/SampleData.kt` - Seed data for testing
- `app/src/main/res/layout/activity_main.xml` - UI layout

## Coding Conventions
- Kotlin with AndroidX and Material 3
- Follow Android best practices
- Keep business logic in separate files from UI code
