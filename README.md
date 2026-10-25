# Sailfish OS Launcher for Unrooted Android (GitHub Actions Ready)

A native Kotlin + Jetpack Compose Android launcher bringing Sailfish OS's Silica UI, Active Covers, Pulley Menu, Events View, and Ambience theming to standard unrooted Android devices (Android 8.0 / API 26+).

## Building in the Cloud with GitHub Actions (No PC / Android Studio Required)

This project includes `.github/workflows/android-build.yml` and `gradle.properties` pre-configured for wrapper-free cloud builds (`gradle/actions/setup-gradle@v4` with Gradle 8.7 and JDK 17):

1. Create a new repository on GitHub (`https://github.com/new`).
2. Extract this zip archive and push or upload all files (including the `.github/workflows/android-build.yml` folder) to the `main` branch.
3. Open the **Actions** tab in your GitHub repository.
4. Click the **Build Sailfish Android Launcher APK** workflow run (or trigger it manually via **Run workflow**).
5. Once the run completes (around 2–3 minutes), scroll down to the **Artifacts** section at the bottom of the summary page and download **`sailfish-launcher-debug-apk`**, which contains `app-debug.apk`.
