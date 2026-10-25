# Sailfish OS Launcher for Unrooted Android (Self-Healing GitHub Actions Build)

A native Kotlin + Jetpack Compose Android launcher bringing Sailfish OS's Silica UI, Active Covers, Pulley Menu, Events View, and Ambience theming to standard unrooted Android devices (Android 8.0 / API 26+).

## Building in the Cloud with GitHub Actions

Even if you upload `SailfishLauncher.kt` directly to the root of your GitHub repository from a mobile browser, `.github/workflows/android-build.yml` automatically scaffolds `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, and copies `SailfishLauncher.kt` into `app/src/main/java/org/sailfish/launcher/SailfishLauncher.kt` before compiling `app-debug.apk`.
