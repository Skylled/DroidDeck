package com.droiddeck.launcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.session.DiscoveredHost
import com.droiddeck.launcher.session.StreamerApp
import com.droiddeck.launcher.session.XrHeadsetInfo
import com.droiddeck.launcher.session.XrHeadsetKind
import com.droiddeck.launcher.session.XrQualityPreset
import com.droiddeck.launcher.session.XrRefreshRate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every preview case with Robolectric's native graphics and saves it as a PNG under app/build/preview-renders/,
 * so UI changes can be looked at without a device. Fails on a preview that throws or draws a single flat colour.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PreviewRenderTest(private val case: PreviewCase) {
    @get:Rule val compose = createComposeRule()

    @Test fun handheld() = render("handheld")

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun portrait() = render("portrait")

    private fun render(size: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent { case.content() }
        // The clock is paused, so step it while waiting: navigation and the loads that make a case ready run in frames.
        compose.waitUntil(timeoutMillis = 15_000) { compose.mainClock.advanceTimeByFrame(); case.ready() }
        // Entrance animations settle well within this.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.runOnIdle { save(size) }
    }

    private fun save(size: String) {
        // A dialog has its own window; the last one is on top.
        val view = WindowInspector.getGlobalWindowViews().last()
        assertTrue("${case.name}: empty bounds", view.width > 0 && view.height > 0)
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        assertTrue("${case.name}: drew one flat colour", pixels.any { it != pixels.first() })
        val out = File("build/preview-renders/$size/${case.name}.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    companion object {
        private val xrPreviewCases: List<PreviewCase> = listOf(
            PreviewCase("xr-headset-galaxy") {
                PreviewHost {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                    ) {
                        XrHeadsetPanelContent(
                            headset = XrHeadsetInfo(
                                kind = XrHeadsetKind.ANDROID_XR,
                                displayName = "Samsung Galaxy XR",
                                isGalaxyXr = true,
                                isImmersiveSupported = true,
                                supportsHandTrackingFeature = true,
                            ),
                            streamers = listOf(
                                StreamerApp(
                                    id = "virtual_desktop",
                                    name = "Virtual Desktop",
                                    packageNames = listOf("VirtualDesktop.Android"),
                                    installedPackage = "VirtualDesktop.Android",
                                    supportsWindows = true,
                                    supportsLinux = false,
                                    notes = "HEVC 10-bit / AV1, Wi-Fi 7",
                                ),
                                StreamerApp(
                                    id = "steam_link",
                                    name = "Steam Link",
                                    packageNames = listOf("com.valvesoftware.steamlink"),
                                    installedPackage = "com.valvesoftware.steamlink",
                                    supportsWindows = true,
                                    supportsLinux = true,
                                    notes = "Direct SteamVR streaming",
                                ),
                                StreamerApp(
                                    id = "wivrn",
                                    name = "WiVRn",
                                    packageNames = listOf("org.meumeu.wivrn"),
                                    installedPackage = null,
                                    supportsWindows = false,
                                    supportsLinux = true,
                                    notes = "Open-source Monado PCVR",
                                ),
                                StreamerApp(
                                    id = "alvr",
                                    name = "ALVR",
                                    packageNames = listOf("alvr.client.stable"),
                                    installedPackage = "alvr.client.stable",
                                    supportsWindows = true,
                                    supportsLinux = true,
                                    notes = "Open-source SteamVR over LAN / USB",
                                ),
                            ),
                            discoveredHosts = listOf(
                                DiscoveredHost("Gaming-PC", "192.168.1.42", 9757, "WiVRn"),
                                DiscoveredHost("Alyx-Rig", "192.168.1.42", 9943, "ALVR"),
                            ),
                            scanning = true,
                            manualHost = "192.168.1.42",
                            qualityPreset = XrQualityPreset.BALANCED,
                            refreshRate = XrRefreshRate.HZ_72,
                            handTrackingEnabled = true,
                        )
                    }
                }
            },
        )

        @JvmStatic @Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = (previewCases + xrPreviewCases).map { arrayOf(it) }
    }
}
