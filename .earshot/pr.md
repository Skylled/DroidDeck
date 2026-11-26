Title: Add Samsung Galaxy XR / Android XR VR support and Half-Life: Alyx PCVR hub

## Scope

- **Native OpenXR Android XR engine (`app/src/main/cpp/questxr/`)**: Added Android XR support for Samsung Galaxy XR alongside Meta Quest, including 4K micro-OLED swapchain clamping, refresh-rate negotiation, hand-tracking support, multi-profile controller bindings, and host C++17 unit tests (`tools/tests/test_android_xr_native.py`).
- **Android XR manifest, headset detection, preferences & PCVR streaming (`app/src/main/`)**: Added Android XR manifest metadata for Home Space to Full Space transitions with `required="false"` to preserve phone/handheld compatibility, `XrHeadset` device detection, `XrPreferences` persistence, `PcvrStreaming` LAN host discovery and streamer launching, and JVM unit tests (`XrHeadsetTest`, `XrPreferencesTest`, `PcvrStreamingTest`).
- **Galaxy XR VR Hub UI, in-session controls & documentation**: Added `XrHeadsetPanel`, integrated VR Hub setup and in-session overlay controls (`FrontEndSetup`, `SessionOverlay`, `SessionActivity`, `SettingsIndex`, `strings.xml`), added Compose preview coverage (`PreviewRenderTest`), regenerated `docs/settings-map.md`, and added `docs/galaxy-xr.md`.
- **Half-Life: Alyx standalone VR preset**: Added a standalone Half-Life: Alyx VR environment preset in `tools/linuxfs/overlay/usr/local/bin/droiddeck-game-env` with unit tests in `tools/tests/test_alyx_game_env.py`.

## Verification

- `./gradlew :app:externalNativeBuildDebug :app:assembleDebug` (NDK 30 / `libdroiddeckxr.so`): passed
- `./gradlew :app:testDebugUnitTest` and `:app:testReleaseUnitTest`: 25/25 passed
- `./gradlew :app:testDebugUnitTest --tests '*PreviewRenderTest'`: 32/32 passed (handheld and portrait visual inspection verified)
- `./gradlew :app:lintVitalRelease`: passed
- `SettingsMapTest` and regenerated `docs/settings-map.md`: passed
- Host Python/C++17 tests: `test_android_xr_native.py` (1/1), `test_alyx_game_env.py` (7/7), `test_quest_*`, and `test_steamvr_*`: passed
- `python3 tools/release/check_authorship.py origin/main..HEAD`: passed

## Hardware Testing Note

**None of these changes have been tested on physical Samsung Galaxy XR or Quest hardware yet.** All validation so far is build, lint, preview-render, and host/JVM unit-test coverage.
