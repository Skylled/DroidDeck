Title: QL-839: Run Half-Life: Alyx's native ARM64 build; keep the fixed VR preset on Proton only

**Untested on hardware.** No device, headset or Steam login was used: nothing here has been run against a real Alyx install, a real `appmanifest_546560.acf`, or the Steam Frame `linuxarm64` build. Every result below comes from unit tests with synthetic manifests and ELF headers.

## What changes

### 1. Prefer Alyx's Linux depot so the native ARM64 build runs
Valve's Steam Frame update put `game/bin/linuxarm64/hlvr` inside the regular Linux depot `546564` (app `546560`). Until now `steam-compatibility` defaulted Alyx to Proton ARM64, so Steam fetched the Windows depot `546563` and ran it through Proton/FEX.

- **`steam-compatibility`**: `546560` joins `FEX_DEFAULT_APPS`, the same mechanism `4385780` already uses. Alyx now defaults to **Linux x86 (FEX)**, so Steam installs the Linux depot.
- **Fallback to Proton** (`ARM64_LINUX_BUILDS`, `arm64_fallbacks`): Alyx stays on, or returns to, Proton ARM64 when:
  - the installed depot is the Windows one (`546563` under `InstalledDepots`). An existing Proton install is **not** switched, so nothing is re-downloaded. The player can pick Linux x86 (FEX) by hand.
  - a fully installed Linux depot has no arm64 `game/bin/linuxarm64/hlvr`.
  - While Steam is still downloading or updating (`StateFlags` not fully installed, or update required), the binary may not exist yet, so the current side is kept. This stops the mapping flipping between depots mid-download.
  - Explicit player picks are kept, as before.
  - In x64-client mode (`linux_tool` = `droiddeck-direct`), Alyx stays on Proton because only `droiddeck-fex` starts a bundled arm64 build.
- **`droiddeck-fex`**:
  - `launch` now runs a bundled arm64 sibling natively. When Steam starts an x86 ELF in `bin/linuxsteamrt64/` (or `bin/linux64/`) and `bin/linuxarm64/<same name>` is an executable arm64 ELF, that binary is exec'd directly instead of emulating the x86 one. `DROIDDECK_FEX=on` (or `run --mode on`) still forces FEX.
  - **`SCAN_LIMIT` fix**: when Steam's command is a script, `scan()` decides native vs FEX by walking the game folder, and it stopped after 4000 files in `os.walk` order. In a Source 2 tree the `game/hlvr` and `game/core` VPKs and data could use up that budget before `game/bin/linuxarm64` was reached. The scan then saw only x86 binaries and picked FEX. The scan now visits binary-looking folders (`bin`, `lib`, `linux*`, `*arm*`, `x86`/`x64`, …) and everything under them first, and stops at the first arm64 ELF. The depth limit (4) and the file limit (4000) are unchanged.

### 2. Fixed VR preset only on the Proton/FEX path
`droiddeck-game-env`'s `half_life_alyx_vr` (`-novid -vulkan +vr_fidelity_level_auto 0 +vr_fidelity_level 1 +vr_render_scale 0.65`) now returns nothing for the native build: an exe in `linuxarm64/`, or any arm64 ELF. Valve recommends keeping dynamic resolution on there, so native runs at Valve's defaults. `hlvr.exe` under Proton gets the preset as before. (On today's code the native launch through `droiddeck-fex` never reached the arg-appending path anyway. The guard keeps it that way if a native launch ever goes through `droiddeck-game-env`.)

`PcvrStreaming.HALF_LIFE_ALYX_PRESET.standaloneLaunchOptions` still holds the fixed string, but nothing reads it, so I left it alone.

Docs (`docs/galaxy-xr.md`) and the XR hub note (`xr_standalone_alyx_note`) are updated to match.

## Validation (local, macOS host)
- New tests:
  - `test_game_environment.ProtonDefaultTest`: Linux-depot preference; Proton fallback for a Windows depot or a missing arm64 binary; side kept mid-download.
  - `test_droiddeck_fex`: binary folders scanned before a large data tree with a small `SCAN_LIMIT`; scan still stops at its limit; bundled arm64 build exec'd instead of FEX, `mode=on` still uses FEX, non-executable or non-Source-2 layouts are ignored.
  - `test_alyx_game_env`: native `linuxarm64/hlvr` gets no preset; x86 Linux and `hlvr.exe` still do.
  All pass.
- I ran all 24 `tools/tests` modules that load `steam-compatibility`, `droiddeck-fex` or `droiddeck-game-env` (Python 3.13) against both this branch and unmodified `main`. The branch's failures are a strict subset of `main`'s. They are host-only failures on macOS: no Linux `cc`/uruntime, signal timing.
- `./gradlew -PskipRust=true :app:lintVitalRelease` passes (for the changed string). `:app:testDebugUnitTest --tests '*PcvrStreamingTest'` passes.
- `:app:assembleDebug` stops at `verifyRuntimeInputs` ("Runtime inputs have not been prepared"). That step needs `tools/build_local.sh`'s prepared runtime.
- `tools/build_local.sh` could not run here: it needs Docker for the glibc ARM64 preloads, and this host has none. CI covers the full build.

## Needs hardware to confirm
- **Steam's actual Linux launch command for Alyx.** If Steam starts `game/bin/linuxsteamrt64/hlvr` directly, the sibling redirect handles it. If it starts a wrapper script, the improved scan sends the script to native, and the native path then depends on that script choosing `linuxarm64` on aarch64. Kyle's hardware logs (`droiddeck-fex: running the bundled arm64 build …`) will show which path was taken.
- **Whether the native `hlvr` finds every library it needs** outside Steam's arm64 runtime container on DroidDeck's rootfs, and whether it talks to the standalone SteamVR/OpenXR bridge.
- **Real `InstalledDepots`/`StateFlags` during a depot switch.** Confirm Steam reports them the way the fallback expects.
