Title: QL-841: Stop the immersive SteamVR launch hanging the app on Galaxy XR

**Untested on hardware.** No headset was used. The hang mechanism is confirmed by reading the NDK glue source, but the reason the XR session failed on the Galaxy XR is a ranked guess from the code and Google's Android XR docs, not a reproduction. Nothing here has run on a Galaxy XR or a Quest.

## The report (QL-841)
Tapping "Launch SteamXR" on a Galaxy XR starts an immersive view that shows only the DroidDeck logo and the system's four-dot animation. Android then reports the app as not responding, and "Wait" never helps.

## Why it hangs
`QuestXrActivity` is a `NativeActivity`. `android_native_app_glue` makes the UI thread wait, with no timeout, for the native thread to acknowledge every lifecycle, window and input callback. `android_main` returned as soon as the XR session failed (and on a normal native-side exit too). After that the UI thread waits forever. Nothing has been drawn, so the system splash stays up, and the ANR cannot clear. Every underlying failure looked the same on the headset.

## What changes
- **Hang fix** (`quest_xr.cpp`): the native thread no longer returns early. It finishes the activity and keeps handling glue commands until `APP_CMD_DESTROY`. A failure is reported to the activity through a new `onXrFatal` callback, logged, and shown as a toast, and the user returns to the 2D panel.
- **Manifest**: declares `<uses-native-library android:name="libopenxr.google.so" android:required="false" />`. Google's Android XR docs say that without this declaration "the runtime fails to load". This is the likeliest reason the session failed in the first place. `required="false"` leaves other devices unaffected.
- **UI thread held less during start-up**: pending glue commands are handled between OpenXR init stages.
- **Blend mode**: uses one the runtime offers (OPAQUE when available) instead of assuming OPAQUE at `xrEndFrame`.
- **Visible state**: `QuestXrActivity.onStart` reports the activity visible again after a restart.
- **Logging under tag `DroidDeckXR`**:
  - each init stage, the runtime name and version, and its extension list
  - OpenXR errors by name (`XR_ERROR_…`) as well as number
  - session state names, the first guest frames, compositor handoff timing, and SteamVR start and status transitions
  - a watchdog warning when the XR thread sits in one call for more than 2 s (`xr_stall.h`)
  - the session and compositor state when the 2D panel launches the immersive activity

No user-facing strings or resources are added. The toast uses the failure text as is.

## Validation (local, macOS host)
- Built with **JDK 21**, because JDK 17 (which the repo expects) is not installed on this host.
- `:app:compileDebugKotlin`, `:app:externalNativeBuildDebug` and `:app:processDebugMainManifest` succeed. The merged debug manifest contains the new `uses-native-library` entry.
- `:app:testDebugUnitTest` for `GlassesMenuControllerTest`, `XrHeadsetTest`, `XrPreferencesTest` and `PcvrStreamingTest`: all pass.
- `python3 -m unittest discover -s tools/tests -p 'test_android_xr*.py'`: 5 tests pass. This includes the new `test_android_xr_launch.py`:
  - a host-compiled test of the watchdog schedule
  - `android_main` never returns before the activity is destroyed
  - init stages exist before `xrCreateInstance` and `xrCreateSession`
  - the manifest entry is present
- `tools/pre_pr.sh` passes (authorship, compile, lint).

Not done:
- **No APK was assembled or installed.**
- **The Quest path is untested.** It shares `quest_xr.cpp`, so the exit handling, staged init and blend-mode choice change there too.
- `tools/tests/test_steamvr_helper.py` cannot run on macOS (it needs Linux headers). This change does not touch what it covers.
- The new tests check the structure of the exit path and the watchdog logic. They do not run an OpenXR session.

## Needs hardware to confirm
- Whether the missing `uses-native-library` entry was the cause. With this build, `adb logcat -s DroidDeckXR OpenXR-Loader` should show the init stages, then the session reaching VISIBLE and FOCUSED.
- If the session still fails, the log now names the stage and the OpenXR error, and the app shows a toast and returns to the panel.
- Whether the system splash clears before SteamVR's first frame (see proposal 1).

## Deferred proposals (not in this PR)
1. Draw a "Starting SteamVR" placeholder layer until the first guest frame. The app submits no layer for up to 30 s while SteamVR starts.
2. Make the compositor handoff in `onXrActive` non-blocking and non-fatal, and bound the unbounded fence wait in `vk_present.c`.
3. Run OpenXR init from the event loop after resume, so no single slow runtime call can hold the UI thread.
4. Ask for hand-tracking permission from the 2D panel before launching, not from the immersive activity's `onCreate`.
5. Review the manifest for Android XR: `QuestXrActivity` shares a task with home-space activities, its `DUMP` permission blocks any system relaunch, and `screenOrientation="landscape"` and the unused `EYE_TRACKING_COARSE` permission are worth a second look.
6. Log guest-side frame transport failures in `wsi_capture.cpp`, which are silent today.
7. Refuse the launch with a visible message when no session or compositor is running (needs a new translated string).
8. Add a Vulkan OpenXR session path, only if headset logs point at the GLES binding.
