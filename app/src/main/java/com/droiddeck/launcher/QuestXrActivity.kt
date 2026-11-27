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
    @Volatile private var activeFailure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        sessionId = SessionState.sessionId
        Log.i(
            TAG,
            "onCreate: sessionRunning=${SessionState.running} compositorStarted=${CompositorHost.isStarted} " +
                "androidXr=${XrHeadset.isAndroidXr(this)}",
        )
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
        val started = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - started
        try {
            if (active) {
                check(CompositorHost.isStarted) { "DroidDeck compositor is not running" }
                SessionService.resume(this)
                WaylandCompositor.nativeSetCardboard(false, 1, 0.064f, 3f, 4f, 0f, 0f, 90f, 0f, 0f, false, 0f)
                WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
                WaylandCompositor.nativeSetZeroCopy(false)
                Log.i(TAG, "onXrActive: attaching compositor at ${elapsed()} ms")
                CompositorHost.attachXr()
                WaylandCompositor.nativeVsync(System.nanoTime())
                Log.i(TAG, "onXrActive: waiting for the compositor surface at ${elapsed()} ms")
                check(WaylandCompositor.nativeWaitSurface(3000)) {
                    "the compositor did not release its screen surface within 3 s"
                }
            }
            SessionService.setXrActivityVisible(this, active)
            if (active) startSteamVr()
            Log.i(TAG, "onXrActive($active) done in ${elapsed()} ms")
        } catch (failure: Throwable) {
            activeFailure = failure.message ?: failure.javaClass.simpleName
            Log.e(TAG, "onXrActive($active) failed after ${elapsed()} ms", failure)
            throw failure
        }
    }

    @Keep
    fun onXrFatal(message: String) {
        val reason = activeFailure?.let { "$message: $it" } ?: message
        Log.e(TAG, "XR session failed: $reason")
        closing = true
        runOnUiThread { Toast.makeText(applicationContext, "DroidDeck XR: $reason", Toast.LENGTH_LONG).show() }
    }

    private fun startSteamVr() {
        if (!steamVrStarted) {
            steamVrStarted = true
            Thread({
                try {
                    val requested = SystemClock.elapsedRealtime()
                    val started = synchronized(steamVrLock) {
                        if (closing || !sessionAlive()) false else {
                            steamVrOwner = this
                            SteamVr.run(this, "start")
                        }
                    }
                    Log.i(
                        TAG,
                        "SteamVR start returned $started after ${SystemClock.elapsedRealtime() - requested} ms " +
                            "(closing=$closing sessionAlive=${sessionAlive()})",
                    )
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
                        val status = SteamVr.status(this)
                        if (status != steamVrStatus) Log.i(TAG, "SteamVR status $steamVrStatus -> $status")
                        steamVrStatus = status
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
        Log.i(TAG, "onDestroy")
        closing = true
        SessionService.setXrActivityVisible(this, false)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (!closing) SessionService.setXrActivityVisible(this, true)
    }

    override fun onStop() {
        Log.i(TAG, "onStop: closing=$closing finishing=$isFinishing")
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
