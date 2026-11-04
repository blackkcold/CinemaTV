# CinemaTV

Native Android TV client inspired by cinematic streaming interfaces. Built with Kotlin, Jetpack Compose for TV and AndroidX Media3.

## Architecture

- `app/`: TV-native UI, focus interactions, LibreTV adapter and Media3 player.
- `backend/tv-bff/`: optional device-friendly metadata/search adapter that talks to your own LibreTV server.
- `.github/workflows/`: reproducible unsigned/debug APK build.

## Safety and deployment

Bring your own **legally accessible** LibreTV instance and streaming sources. The app ships with no third-party content streams or copyrighted posters. The LibreTV server address is configured at runtime. For production, use HTTPS and keep admin credentials out of the APK.

## Current state

Active implementation. Never interpret a successful source push as successful APK build or production release: consult the latest GitHub Actions run and release artifacts.

## Requirements

Android TV / Google TV, Android 8.0+; Android Studio with JDK 17 and Android SDK 35, or the repository's GitHub Actions pipeline.

## Design

Apple TV-inspired full-bleed hero, frosted top navigation, cinema content rails, cover-flow exploration, remote-first focus styling. All images are loaded from sources supplied by the operator.

## License

See LICENSE. Third-party dependencies have their own respective licenses. No source from DpadTV or LibreTV is copied into the client.
