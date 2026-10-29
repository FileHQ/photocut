# PhotoCut Offline — standard Gradle project (ready to push, NOT pushed)

Clean-room, fully offline photo editor. Own branding only: PhotoCut Offline,
package `com.technoon.photocut`. No original Photoroom code, assets, logo, or
copy is used. No AI, no ML model, no network code, and NO INTERNET permission.

## What is in this project

- `app/src/main/` — AndroidManifest.xml, MainActivity.kt, and res/ copied
  unchanged from `../app-src/` (same package, same version 1.0 / code 1).
- `app/build.gradle` — applicationId `com.technoon.photocut`, compileSdk 34,
  minSdk 21, targetSdk 34, versionCode 1, versionName 1.0. No dependencies
  beyond what the Kotlin plugin supplies, because MainActivity imports only
  android.* framework classes, java.util, and kotlin.math (no AndroidX).
- `build.gradle` / `settings.gradle` — Android Gradle Plugin 8.5.2, Kotlin
  1.9.24, Gradle 8.7 (see `gradle/wrapper/gradle-wrapper.properties`).
- `.github/workflows/build-apk.yml` — on push to `main` (or manual run):
  JDK 17, Android SDK, `./gradlew assembleDebug --stacktrace`, uploads
  `app/build/outputs/apk/debug/app-debug.apk` as artifact `PhotoCut-Offline-apk`.

## To use (parent agent / user step, not done here)

1. Connect GitHub, create a repository, and push the contents of this
   folder to its `main` branch.
2. The workflow runs automatically. Download the `PhotoCut-Offline-apk`
   artifact from the workflow run and install it on a phone.
3. On-device checks still owed after install: pick a real photo, Auto Remove,
   brush an edge, drag text/logo, choose an export size, export PNG and JPG,
   and confirm exports appear in Pictures/PhotoCut at the selected size.

## Not done here

- Local Gradle was NOT run (it is blocked in this sandbox).
- Nothing was pushed to GitHub and no password or token was requested or used.
