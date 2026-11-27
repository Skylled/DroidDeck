# Samsung Galaxy XR & Android XR support

DroidDeck ships single-APK support for **Samsung Galaxy XR** and **Android XR** headsets alongside standard Android phones, handhelds, and Meta Quest. All XR manifest features are declared `android:required="false"`, so the same APK installs and runs normally on non-XR devices.

## How Galaxy XR runs DroidDeck

- **Hardware target:** Snapdragon XR2+ Gen 2 (Adreno 740-class GPU, Vulkan 1.3), 16 GB RAM, dual **3,552 × 3,840** Micro-OLED panels (**60 / 72 / 90 Hz**), Wi-Fi 7, hardware AV1 / HEVC decode, built-in hand & eye tracking, and optional 6DoF motion controllers.
- **Home Space → Full Space transition:** DroidDeck's main launcher and 2D Steam session run as a standard Android XR **Home Space** panel. Entering SteamVR launches DroidDeck's immersive OpenXR activity (`PROPERTY_XR_ACTIVITY_START_MODE = XR_ACTIVITY_START_MODE_FULL_SPACE_UNMANAGED`), switching cleanly into exclusive **Full Space** stereo rendering and returning to the 2D panel on exit.
- **No Quest-only hacks on Galaxy XR:** Meta Quest KGSL/Turnip workarounds remain strictly scoped to Quest 2/3/3S and are not applied on Galaxy XR.

## Playing Half-Life: Alyx via PCVR Streaming (Recommended)

For full-fidelity **Half-Life: Alyx** on Galaxy XR's 4K Micro-OLED panels, run Half-Life: Alyx on a Windows or Linux gaming PC and stream to Galaxy XR. Open **Settings › XR headset** (or the in-session **VR** tab) to manage PCVR streaming from DroidDeck:

1. **Install a PCVR streaming client on Galaxy XR:**
   - **Virtual Desktop** (Windows PC — HEVC 10-bit / AV1, Wi-Fi 7)
   - **Steam Link** (Windows & Linux PC — direct SteamVR streaming)
   - **WiVRn** (Linux PC — open-source Monado PCVR, auto-discovered over mDNS `_wivrn._tcp`)
   - **ALVR** (Windows & Linux PC — open-source SteamVR over LAN / USB, auto-discovered on UDP `9943`)
   DroidDeck detects installed clients automatically and provides one-tap **Launch** buttons.
2. **Host PC SteamVR launch options for Half-Life: Alyx:**
   Copy directly from **Settings › XR headset** and set in Steam's Properties → Launch Options on your host PC:
   ```text
   -vr -novid +vr_fidelity_level_auto 0 +vr_fidelity_level 3
   ```
3. **Recommended 4K Micro-OLED streaming targets:**
   - **Render resolution:** `2600 px/eye` (scales cleanly without saturating Wi-Fi encode/decode)
   - **Refresh rate:** `72 Hz` (default) or `90 Hz`
   - **Codec & bitrate:** `HEVC 10-bit` or `AV1` at `150 Mbps`
   - **Network:** Connect the host PC via Gigabit/2.5G Ethernet and Galaxy XR to a dedicated **Wi-Fi 7 / 6 GHz** access point in the same room. For wired ALVR over USB-C, forward the port from your PC (`adb forward tcp:9943 tcp:9943` and `adb forward tcp:9944 tcp:9944`).
   - **LAN Host Discovery:** Toggle **Scan local network** in **Settings › XR headset** to discover live WiVRn and ALVR hosts, or enter a **Manual host IP** as a fallback.
   - **Controllers:** Dedicated **6DoF motion controllers** are strongly recommended for gravity gloves, smooth locomotion, and weapon handling.

## Standalone SteamVR & VR Cinema on Galaxy XR

DroidDeck also runs Valve's ARM64 SteamVR directly on Galaxy XR with no host PC required:

1. Start a Steam session in DroidDeck, open the in-session menu (**VR** tab), install SteamVR once, and select **Enter SteamVR**.
2. **Standalone eye resolution cap (`Settings › XR headset` or in-session `VR` tab):**
   Galaxy XR's runtime advertises `3,552 × 3,840` per eye (~104 MiB per stereo RGBA8 frame), which exceeds the 64 MiB stereo transport limit and overwhelms standalone GPU fill rate. DroidDeck automatically clamps standalone swapchains while preserving aspect ratio:
   - **Performance (`1440p`)** — lowest GPU load, best for demanding 3D VR titles
   - **Balanced (`1800p`, default)** — sharp text and stable standalone frame pacing
   - **High (`2160p`)** — crisp panels for VR Cinema and lightweight VR games
   - **Ultra (`2880p`)** — maximum safe cap within the 64 MiB stereo frame budget
3. **Target refresh rate:** Choose **Auto**, **60 Hz**, **72 Hz**, or **90 Hz** (requested via `XR_FB_display_refresh_rate` when reported by the runtime).
4. **Bare-hand tracking fallback:** When 6DoF motion controllers are not paired, DroidDeck uses `XR_EXT_hand_tracking` (with runtime `android.permission.HAND_TRACKING`) to synthesize 6DoF hand poses, pinch-to-trigger, and grab-to-grip so you can navigate SteamVR menus and simple interactions.
5. **Best standalone experiences vs experimental standalone Half-Life: Alyx:**
   - **Recommended standalone:** **VR Cinema / flat Steam games** (non-VR games launched while SteamVR is running automatically wrap into Gamescope's OpenVR theater panel) and lightweight VR titles such as **SUPERHOT VR**, **Half-Life 2: VR Mod**, and **Beat Saber**.
   - **Experimental standalone Half-Life: Alyx:** Alyx's Linux depot (`546564`) now bundles a native ARM64 build (`game/bin/linuxarm64/hlvr`). On ARM devices `steam-compatibility` maps Alyx (`AppID 546560`) to **Linux x86 (FEX)** so Steam installs the Linux depot, and `droiddeck-fex` starts the bundled `linuxarm64` binary natively instead of emulating `linuxsteamrt64/hlvr`. The native build runs with Valve's defaults, including dynamic resolution.
     - **Fallback:** if Alyx is installed from the Windows depot (`546563`), or a fully installed Linux depot has no `linuxarm64/hlvr`, Alyx stays on (or returns to) Proton ARM64. An existing Windows-depot install is not switched, so nothing is re-downloaded; pick **Linux x86 (FEX)** in the game's compatibility settings to move it to the native build. Set `DROIDDECK_FEX=on` to force the x86 Linux build under FEX.
     - **Proton path only:** when `hlvr.exe` runs through Proton, `droiddeck-game-env` applies a low-resolution Vulkan preset (respecting any explicit user flags):
       ```text
       -vr -novid -vulkan +vr_fidelity_level_auto 0 +vr_fidelity_level 1 +vr_render_scale 0.65
       ```
     Galaxy XR's 16 GB RAM avoids 8 GB OOM crashes, but sustained standalone performance on XR2+ Gen 2 remains experimental. Use **PCVR Streaming** for full-speed 4K gameplay.

## Troubleshooting

- **Stuck in 2D Home Space instead of Full Space:** Confirm the headset reports `android.software.xr.api.openxr` (visible under **Settings › XR headset** as `OpenXR ready`). Launch SteamVR via **Enter SteamVR** in the session drawer's **VR** tab.
- **No hands or controllers inside SteamVR:** Pair 6DoF motion controllers in Android XR settings, or enable **Bare-hand tracking fallback** and grant the **Hand Tracking** permission when prompted on entering SteamVR.
- **Stutter or high latency in standalone SteamVR:** Lower **Eye render resolution cap** from `Balanced (1800p)` to `Performance (1440p)` and set **Target refresh rate** to `72 Hz` (changes apply next time you enter SteamVR).
- **LAN discovery finds no PCVR host:** Ensure the headset and PC are on the same subnet with multicast/mDNS enabled, allow UDP `9943` (ALVR) or mDNS `5353` (WiVRn) through the host firewall, or save the host's IPv4 address under **Manual host IP**.
