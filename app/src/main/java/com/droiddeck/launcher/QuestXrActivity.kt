package com.droiddeck.launcher

import android.app.NativeActivity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep
import com.droiddeck.launcher.gpu.SteamVr
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.XrHeadset
import com.droiddeck.launcher.session.XrPreferences
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor

class QuestXrActivity : NativeActivity() {
    data class XrRuntimeInfo(
        val systemName: String,
        val eyeWidth: Int,
        val eyeHeight: Int,
        val refreshHz: Float,
        val handTrackingActive: Boolean,
    )

    private var steamVrStarted = false
    @Volatile private var closing = false
    @Volatile private var steamVrStatus = SteamVr.Status.UNAVAILABLE
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        sessionId = SessionState.sessionId
        SessionService.setXrActivityVisible(this, true)
        if (XrHeadset.isAndroidXr(this) &&
            XrPreferences.isHandTrackingEnabled(this) &&
            checkSelfPermission(XrHeadset.PERMISSION_HAND_TRACKING) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { requestPermissions(arrayOf(XrHeadset.PERMISSION_HAND_TRACKING), REQ_HAND_TRACKING) }
                .onFailure { Log.w(TAG, "Hand tracking permission request skipped: ${it.message}") }
        }
        super.onCreate(savedInstanceState)
    }

    @Keep
    fun getXrMaxEyePixels(): Int = XrPreferences.getQualityPreset(this).maxEyePixels

    @Keep
    fun getXrRenderScalePercent(): Int = 0

    @Keep
    fun getXrTargetRefreshHz(): Float = XrPreferences.getRefreshRate(this).hz

    @Keep
    fun isXrHandTrackingEnabled(): Boolean {
        if (!XrPreferences.isHandTrackingEnabled(this)) return false
        if (XrHeadset.isAndroidXr(this)) {
            return checkSelfPermission(XrHeadset.PERMISSION_HAND_TRACKING) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    @Keep
    fun onXrRuntimeInfo(
        systemName: String,
        eyeWidth: Int,
        eyeHeight: Int,
        refreshHz: Float,
        handTrackingActive: Boolean,
    ) {
        val info = XrRuntimeInfo(systemName, eyeWidth, eyeHeight, refreshHz, handTrackingActive)
        lastRuntimeInfo = info
        Log.i(
            TAG,
            "XR runtime: system=$systemName eye=${eyeWidth}x${eyeHeight} refresh=${refreshHz}Hz handTracking=$handTrackingActive",
        )
    }

    @Keep
    fun onXrActive(active: Boolean) {
        if (active) {
            check(CompositorHost.isStarted) { "DroidDeck compositor is not running" }
            SessionService.resume(this)
            WaylandCompositor.nativeSetCardboard(false, 1, 0.064f, 3f, 4f, 0f, 0f, 90f, 0f, 0f, false, 0f)
            WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
            WaylandCompositor.nativeSetZeroCopy(false)
            CompositorHost.attachXr()
            WaylandCompositor.nativeVsync(System.nanoTime())
            check(WaylandCompositor.nativeWaitSurface(3000)) { "XR frame GPU handshake timed out" }
        }
        SessionService.setXrActivityVisible(this, active)
        if (active) startSteamVr()
    }

    private fun startSteamVr() {
        if (!steamVrStarted) {
            steamVrStarted = true
            Thread({
                try {
                    val started = synchronized(steamVrLock) {
                        if (closing || !sessionAlive()) false else {
                            steamVrOwner = this
                            SteamVr.run(this, "start")
                        }
                    }
                    if (!started) {
                        if (!closing) leaveXr("SteamVR start failed", sessionAlive())
                        return@Thread
                    }
                    val startupDeadline = SystemClock.elapsedRealtime() + 30_000
                    var lastRunning: Long? = null
                    while (!closing) {
                        if (!sessionAlive()) {
                            leaveXr("Steam session ended", false)
                            break
                        }
                        steamVrStatus = SteamVr.status(this)
                        val now = SystemClock.elapsedRealtime()
                        if (steamVrStatus == SteamVr.Status.RUNNING) lastRunning = now
                        val deadline = lastRunning?.plus(5_000) ?: startupDeadline
                        if (steamVrStatus != SteamVr.Status.RUNNING && now >= deadline) {
                            leaveXr("SteamVR stopped or did not finish starting", true)
                            break
                        }
                        Thread.sleep(1000)
                    }
                } finally {
                    synchronized(steamVrLock) {
                        if (steamVrOwner === this) {
                            if (sessionAlive()) SteamVr.run(this, "stop")
                            steamVrOwner = null
                        }
                    }
                }
            }, "quest-steamvr-start").start()
        }
    }

    private fun sessionAlive() = SessionState.running && !SessionState.stopRequested &&
        sessionId != null && SessionState.sessionId == sessionId

    private fun leaveXr(reason: String, failed: Boolean) {
        Log.w("DroidDeckXR", reason)
        closing = true
        runOnUiThread {
            if (failed) Toast.makeText(this, R.string.cardboard_steamvr_failed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Keep
    fun onXrFrame(frameTimeNanos: Long) {
        WaylandCompositor.nativeVsync(frameTimeNanos)
    }

    @Keep
    fun onXrStatus(): Int = steamVrStatus.ordinal

    override fun onDestroy() {
        closing = true
        SessionService.setXrActivityVisible(this, false)
        super.onDestroy()
    }

    override fun onStop() {
        SessionService.setXrActivityVisible(this, false)
        super.onStop()
    }

    companion object {
        private const val TAG = "DroidDeckXR"
        private const val REQ_HAND_TRACKING = 4102
        private val steamVrLock = Any()
        private var steamVrOwner: QuestXrActivity? = null
        @Volatile var lastRuntimeInfo: XrRuntimeInfo? = null
            private set
    }
}
