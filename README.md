# Jarvis build test

A copy of the Jarvis Hands Android app, used to test that Jarvis can push an Android project to GitHub and have GitHub build its debug APK automatically. Not meant for everyday use; the real app lives in the Jarvis-Hands repo.

## Build

GitHub builds it on every push (see .github/workflows). The APK appears under the run's Artifacts and as a release. Locally: open in Android Studio, or run gradle assembleDebug with JDK 17 and the Android SDK (platform 34).

## License

Same as Jarvis Hands.
