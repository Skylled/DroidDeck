package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build

enum class XrHeadsetKind {
    NONE,
    META_QUEST,
    ANDROID_XR,
    GENERIC_OPENXR,
}

data class XrHeadsetInfo(
    val kind: XrHeadsetKind,
    val displayName: String,
    val isGalaxyXr: Boolean,
    val isImmersiveSupported: Boolean,
    val supportsHandTrackingFeature: Boolean,
)

object XrHeadset {
    const val FEATURE_OPENXR = "android.software.xr.api.openxr"
    const val FEATURE_SPATIAL = "android.software.xr.api.spatial"
    const val FEATURE_HEADTRACKING = "android.hardware.vr.headtracking"
    const val FEATURE_HAND_TRACKING = "android.hardware.xr.input.hand_tracking"
    const val PERMISSION_HAND_TRACKING = "android.permission.HAND_TRACKING"

    fun detect(context: Context): XrHeadsetInfo {
        val pm = context.packageManager
        fun hasFeature(name: String): Boolean =
            runCatching { pm.hasSystemFeature(name) }.getOrDefault(false)
        return detect(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            hasOpenXr = hasFeature(FEATURE_OPENXR),
            hasSpatial = hasFeature(FEATURE_SPATIAL),
            hasHeadtracking = hasFeature(FEATURE_HEADTRACKING),
            hasHandTracking = hasFeature(FEATURE_HAND_TRACKING),
        )
    }

    fun detect(
        manufacturer: String,
        model: String,
        device: String,
        hasOpenXr: Boolean,
        hasSpatial: Boolean,
        hasHeadtracking: Boolean,
        hasHandTracking: Boolean,
    ): XrHeadsetInfo {
        if (QuestExperimental.supported(model, device, manufacturer)) {
            val name = when {
                QuestExperimental.quest2(model, device, manufacturer) -> "Meta Quest 2"
                model.equals("Quest 3S", ignoreCase = true) || device == "panther" -> "Meta Quest 3S"
                model.equals("Quest 3", ignoreCase = true) || device == "eureka" -> "Meta Quest 3"
                else -> "Meta Quest"
            }
            return XrHeadsetInfo(
                kind = XrHeadsetKind.META_QUEST,
                displayName = name,
                isGalaxyXr = false,
                isImmersiveSupported = true,
                supportsHandTrackingFeature = hasHandTracking,
            )
        }

        if (hasOpenXr || hasSpatial) {
            val galaxyXr = manufacturer.equals("Samsung", ignoreCase = true) ||
                model.contains("SM-I610", ignoreCase = true) ||
                model.contains("galaxy xr", ignoreCase = true) ||
                device.contains("moohan", ignoreCase = true)
            return XrHeadsetInfo(
                kind = XrHeadsetKind.ANDROID_XR,
                displayName = if (galaxyXr) "Samsung Galaxy XR" else "Android XR Headset",
                isGalaxyXr = galaxyXr,
                isImmersiveSupported = true,
                supportsHandTrackingFeature = hasHandTracking,
            )
        }

        if (hasHeadtracking) {
            return XrHeadsetInfo(
                kind = XrHeadsetKind.GENERIC_OPENXR,
                displayName = model.ifBlank { "OpenXR Headset" },
                isGalaxyXr = false,
                isImmersiveSupported = true,
                supportsHandTrackingFeature = hasHandTracking,
            )
        }

        return XrHeadsetInfo(
            kind = XrHeadsetKind.NONE,
            displayName = "No XR Headset",
            isGalaxyXr = false,
            isImmersiveSupported = false,
            supportsHandTrackingFeature = false,
        )
    }

    fun isImmersiveSupported(context: Context): Boolean = detect(context).isImmersiveSupported

    fun isAndroidXr(context: Context): Boolean = detect(context).kind == XrHeadsetKind.ANDROID_XR

    fun isGalaxyXr(context: Context): Boolean = detect(context).isGalaxyXr
}
