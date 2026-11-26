package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class XrHeadsetTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        ShadowBuild.setManufacturer("Generic")
        ShadowBuild.setModel("Phone")
        ShadowBuild.setDevice("phone")
        val pm = Shadows.shadowOf(context.packageManager)
        pm.setSystemFeature(XrHeadset.FEATURE_OPENXR, false)
        pm.setSystemFeature(XrHeadset.FEATURE_SPATIAL, false)
        pm.setSystemFeature(XrHeadset.FEATURE_HEADTRACKING, false)
        pm.setSystemFeature(XrHeadset.FEATURE_HAND_TRACKING, false)
    }

    @Test fun regularPhoneAndSamsungPhoneAreNotXrHeadsets() {
        val phone = XrHeadset.detect(
            manufacturer = "Samsung",
            model = "SM-S938U",
            device = "pa3q",
            hasOpenXr = false,
            hasSpatial = false,
            hasHeadtracking = false,
            hasHandTracking = false,
        )
        assertEquals(XrHeadsetKind.NONE, phone.kind)
        assertFalse(phone.isGalaxyXr)
        assertFalse(phone.isImmersiveSupported)
        assertFalse(phone.supportsHandTrackingFeature)
        assertFalse(XrHeadset.isImmersiveSupported(context))
        assertFalse(XrHeadset.isAndroidXr(context))
        assertFalse(XrHeadset.isGalaxyXr(context))
    }

    @Test fun galaxyXrDetectedViaAndroidXrFeaturesWithoutTriggeringQuestHacks() {
        val info = XrHeadset.detect(
            manufacturer = "Samsung",
            model = "SM-I610",
            device = "moohan",
            hasOpenXr = true,
            hasSpatial = true,
            hasHeadtracking = true,
            hasHandTracking = true,
        )
        assertEquals(XrHeadsetKind.ANDROID_XR, info.kind)
        assertEquals("Samsung Galaxy XR", info.displayName)
        assertTrue(info.isGalaxyXr)
        assertTrue(info.isImmersiveSupported)
        assertTrue(info.supportsHandTrackingFeature)
        assertFalse(QuestExperimental.supported("SM-I610", "moohan", "Samsung"))

        val byDeviceOnly = XrHeadset.detect(
            manufacturer = "Unknown",
            model = "Prototype",
            device = "moohan",
            hasOpenXr = false,
            hasSpatial = true,
            hasHeadtracking = false,
            hasHandTracking = false,
        )
        assertEquals(XrHeadsetKind.ANDROID_XR, byDeviceOnly.kind)
        assertTrue(byDeviceOnly.isGalaxyXr)
        assertFalse(byDeviceOnly.supportsHandTrackingFeature)
    }

    @Test fun galaxyXrDetectedThroughAndroidPackageManager() {
        ShadowBuild.setManufacturer("Samsung")
        ShadowBuild.setModel("SM-I610")
        ShadowBuild.setDevice("moohan")
        val pm = Shadows.shadowOf(context.packageManager)
        pm.setSystemFeature(XrHeadset.FEATURE_OPENXR, true)
        pm.setSystemFeature(XrHeadset.FEATURE_HAND_TRACKING, true)

        assertTrue(XrHeadset.isImmersiveSupported(context))
        assertTrue(XrHeadset.isAndroidXr(context))
        assertTrue(XrHeadset.isGalaxyXr(context))
        val info = XrHeadset.detect(context)
        assertEquals("Samsung Galaxy XR", info.displayName)
        assertTrue(info.supportsHandTrackingFeature)
    }

    @Test fun nonSamsungAndroidXrHeadsetDetectedAsGenericAndroidXr() {
        val info = XrHeadset.detect(
            manufacturer = "Google",
            model = "XR Reference",
            device = "xr_ref",
            hasOpenXr = true,
            hasSpatial = false,
            hasHeadtracking = true,
            hasHandTracking = false,
        )
        assertEquals(XrHeadsetKind.ANDROID_XR, info.kind)
        assertEquals("Android XR Headset", info.displayName)
        assertFalse(info.isGalaxyXr)
        assertTrue(info.isImmersiveSupported)
    }

    @Test fun metaQuestHeadsetsRemainMetaQuestAndNotGalaxyXr() {
        for ((model, device, expectedName) in listOf(
            Triple("Quest 3", "eureka", "Meta Quest 3"),
            Triple("Quest 3S", "panther", "Meta Quest 3S"),
            Triple("Quest 2", "hollywood", "Meta Quest 2"),
        )) {
            val info = XrHeadset.detect(
                manufacturer = "Meta",
                model = model,
                device = device,
                hasOpenXr = false,
                hasSpatial = false,
                hasHeadtracking = true,
                hasHandTracking = true,
            )
            assertEquals(XrHeadsetKind.META_QUEST, info.kind)
            assertEquals(expectedName, info.displayName)
            assertFalse(info.isGalaxyXr)
            assertTrue(info.isImmersiveSupported)
        }
    }

    @Test fun genericHeadtrackingDeviceDetectedAsGenericOpenXr() {
        val info = XrHeadset.detect(
            manufacturer = "Pico",
            model = "PICO 4 Ultra",
            device = "pico4u",
            hasOpenXr = false,
            hasSpatial = false,
            hasHeadtracking = true,
            hasHandTracking = false,
        )
        assertEquals(XrHeadsetKind.GENERIC_OPENXR, info.kind)
        assertEquals("PICO 4 Ultra", info.displayName)
        assertFalse(info.isGalaxyXr)
        assertTrue(info.isImmersiveSupported)
    }
}
