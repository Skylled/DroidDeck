package com.droiddeck.launcher.session

import android.content.Context

enum class XrQualityPreset(val id: String, val maxEyePixels: Int, val label: String) {
    PERFORMANCE("performance", 1440, "Performance (1440p)"),
    BALANCED("balanced", 1800, "Balanced (1800p)"),
    HIGH("high", 2160, "High (2160p)"),
    MAX_SAFE("max_safe", 2880, "Ultra (2880p)");

    companion object {
        fun fromId(id: String?): XrQualityPreset =
            entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

enum class XrRefreshRate(val id: String, val hz: Float, val label: String) {
    AUTO("auto", 0f, "Auto"),
    HZ_60("60", 60f, "60 Hz"),
    HZ_72("72", 72f, "72 Hz"),
    HZ_90("90", 90f, "90 Hz");

    companion object {
        fun fromId(id: String?): XrRefreshRate =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

object XrPreferences {
    private const val PREFS_NAME = "xr"
    private const val KEY_QUALITY_PRESET = "quality_preset"
    private const val KEY_REFRESH_RATE = "refresh_rate"
    private const val KEY_HAND_TRACKING = "hand_tracking_enabled"
    private const val KEY_MANUAL_PCVR_HOST = "manual_pcvr_host"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getQualityPreset(context: Context): XrQualityPreset =
        XrQualityPreset.fromId(prefs(context).getString(KEY_QUALITY_PRESET, null))

    fun setQualityPreset(context: Context, preset: XrQualityPreset) {
        prefs(context).edit().putString(KEY_QUALITY_PRESET, preset.id).apply()
    }

    fun getRefreshRate(context: Context): XrRefreshRate =
        XrRefreshRate.fromId(prefs(context).getString(KEY_REFRESH_RATE, null))

    fun setRefreshRate(context: Context, rate: XrRefreshRate) {
        prefs(context).edit().putString(KEY_REFRESH_RATE, rate.id).apply()
    }

    fun isHandTrackingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAND_TRACKING, true)

    fun setHandTrackingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAND_TRACKING, enabled).apply()
    }

    fun getManualPcvrHost(context: Context): String =
        prefs(context).getString(KEY_MANUAL_PCVR_HOST, "") ?: ""

    fun setManualPcvrHost(context: Context, host: String) {
        prefs(context).edit().putString(KEY_MANUAL_PCVR_HOST, host.trim()).apply()
    }
}
