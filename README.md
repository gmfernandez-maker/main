# Jewel Grade (Fully Native Android)

This Android app now runs fully native and does not require `studio-main` to run login, signup, upload, and grading flows.

## Run

1. Open `kotlin-webwrapper` in Android Studio.
2. Sync Gradle.
3. Run on an emulator or physical device.

## Current Native Behavior

- Authentication is local (stored on-device in `SharedPreferences`).
- Grading result is generated on-device using native Kotlin logic.
- No local web server or backend is required for core app flow.

## Notes

- Existing local users are stored only on the device where signup happened.
- Current grading is an on-device estimate. For production-quality scoring, integrate an on-device ML model.

## ML Planning Docs

- Week 1 execution plan: `docs/week1_yolo_execution_plan_2026-04-05_to_2026-04-11.md`
- Android on-device guide: `docs/android_pytorch_ondevice.md`
