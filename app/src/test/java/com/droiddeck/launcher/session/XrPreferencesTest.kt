package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class XrPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("xr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultsAreSensibleForGalaxyXr() {
        assertEquals(XrQualityPreset.BALANCED, XrPreferences.getQualityPreset(context))
        assertEquals(1800, XrPreferences.getQualityPreset(context).maxEyePixels)
        assertEquals(XrRefreshRate.AUTO, XrPreferences.getRefreshRate(context))
        assertEquals(0f, XrPreferences.getRefreshRate(context).hz, 0.001f)
        assertTrue(XrPreferences.isHandTrackingEnabled(context))
        assertEquals("", XrPreferences.getManualPcvrHost(context))
    }

    @Test fun qualityPresetsRoundTripAndFallbackOnUnknownId() {
        for (preset in XrQualityPreset.entries) {
            XrPreferences.setQualityPreset(context, preset)
            assertEquals(preset, XrPreferences.getQualityPreset(context))
        }
        assertEquals(XrQualityPreset.BALANCED, XrQualityPreset.fromId(null))
        assertEquals(XrQualityPreset.BALANCED, XrQualityPreset.fromId("unknown"))
        assertEquals(1440, XrQualityPreset.PERFORMANCE.maxEyePixels)
        assertEquals(2160, XrQualityPreset.HIGH.maxEyePixels)
        assertEquals(2880, XrQualityPreset.MAX_SAFE.maxEyePixels)
    }

    @Test fun refreshRatesRoundTripAndFallbackOnUnknownId() {
        for (rate in XrRefreshRate.entries) {
            XrPreferences.setRefreshRate(context, rate)
            assertEquals(rate, XrPreferences.getRefreshRate(context))
        }
        assertEquals(XrRefreshRate.AUTO, XrRefreshRate.fromId(null))
        assertEquals(XrRefreshRate.AUTO, XrRefreshRate.fromId("144"))
        assertEquals(60f, XrRefreshRate.HZ_60.hz, 0.001f)
        assertEquals(72f, XrRefreshRate.HZ_72.hz, 0.001f)
        assertEquals(90f, XrRefreshRate.HZ_90.hz, 0.001f)
    }

    @Test fun handTrackingAndManualHostPersistCleanly() {
        XrPreferences.setHandTrackingEnabled(context, false)
        assertFalse(XrPreferences.isHandTrackingEnabled(context))
        XrPreferences.setHandTrackingEnabled(context, true)
        assertTrue(XrPreferences.isHandTrackingEnabled(context))

        XrPreferences.setManualPcvrHost(context, "  192.168.1.50  ")
        assertEquals("192.168.1.50", XrPreferences.getManualPcvrHost(context))
    }
}
