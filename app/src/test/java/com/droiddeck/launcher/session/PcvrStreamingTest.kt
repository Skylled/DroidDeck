package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PcvrStreamingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun halfLifeAlyxPresetContainsVerifiedLaunchFlagsAndTargets() {
        val preset = PcvrStreaming.HALF_LIFE_ALYX_PRESET
        assertEquals("546560", preset.appId)
        assertEquals("Half-Life: Alyx", preset.title)
        assertTrue(preset.pcLaunchOptions.contains("-vr"))
        assertTrue(preset.pcLaunchOptions.contains("+vr_fidelity_level 3"))
        assertTrue(preset.standaloneLaunchOptions.contains("-vulkan"))
        assertTrue(preset.standaloneLaunchOptions.contains("+vr_render_scale 0.65"))
        assertEquals(150, preset.recommendedBitrateMbps)
        assertEquals("HEVC 10-bit / AV1", preset.recommendedCodec)
        assertEquals(2600, preset.recommendedEyePx)
        assertEquals(72, preset.recommendedHz)
        assertTrue(preset.controllerNote.contains("6DoF"))
    }

    @Test fun streamerDetectionMatchesPrimaryAndAlternatePackages() {
        val none = PcvrStreaming.detectInstalledStreamers(emptySet())
        assertEquals(4, none.size)
        assertTrue(none.all { !it.isInstalled && it.installedPackage == null })

        val installed = PcvrStreaming.detectInstalledStreamers(
            setOf("com.virtualdesktop.android", "io.github.wivrn.wivrn", "alvr.client.nightly"),
        )
        val byId = installed.associateBy { it.id }
        assertEquals("com.virtualdesktop.android", byId["virtual_desktop"]?.installedPackage)
        assertNull(byId["steam_link"]?.installedPackage)
        assertEquals("io.github.wivrn.wivrn", byId["wivrn"]?.installedPackage)
        assertEquals("alvr.client.nightly", byId["alvr"]?.installedPackage)
        assertTrue(byId["virtual_desktop"]!!.supportsWindows)
        assertFalse(byId["virtual_desktop"]!!.supportsLinux)
        assertTrue(byId["wivrn"]!!.supportsLinux)
        assertFalse(byId["wivrn"]!!.supportsWindows)
    }

    @Test fun launchStreamerReturnsFalseWhenNotInstalled() {
        val missing = PcvrStreaming.detectInstalledStreamers(emptySet()).first()
        assertFalse(PcvrStreaming.launchStreamer(context, missing))
    }

    @Test fun parseAlvrHandshakePacketExtractsHostnameAndRejectsInvalidPackets() {
        val packet = ByteArray(56)
        "ALVR".toByteArray(Charsets.US_ASCII).copyInto(packet, 0)
        "Desktop-RTX4090".toByteArray(Charsets.UTF_8).copyInto(packet, 24)

        val host = PcvrStreaming.parseAlvrHandshakePacket(packet, packet.size, "192.168.1.25")
        assertNotNull(host)
        assertEquals("Desktop-RTX4090", host!!.name)
        assertEquals("192.168.1.25", host.host)
        assertEquals(9943, host.port)
        assertEquals("ALVR", host.protocol)

        val emptyNamePacket = ByteArray(56)
        "ALVR".toByteArray(Charsets.US_ASCII).copyInto(emptyNamePacket, 0)
        val fallback = PcvrStreaming.parseAlvrHandshakePacket(emptyNamePacket, emptyNamePacket.size, "10.0.0.8")
        assertEquals("ALVR (10.0.0.8)", fallback?.name)

        val shortMagic = "ALVR".toByteArray(Charsets.US_ASCII)
        val shortHost = PcvrStreaming.parseAlvrHandshakePacket(shortMagic, shortMagic.size, "10.0.0.9")
        assertEquals("ALVR (10.0.0.9)", shortHost?.name)

        val dirtyPacket = ByteArray(56)
        "ALVR".toByteArray(Charsets.US_ASCII).copyInto(dirtyPacket, 0)
        "PC\u0007\nRig".toByteArray(Charsets.UTF_8).copyInto(dirtyPacket, 24)
        assertEquals("PCRig", PcvrStreaming.parseAlvrHandshakePacket(dirtyPacket, dirtyPacket.size, "10.0.0.5")?.name)

        assertNull(PcvrStreaming.parseAlvrHandshakePacket("NOPE1234".toByteArray(), 8, "192.168.1.25"))
        assertNull(PcvrStreaming.parseAlvrHandshakePacket("ALV".toByteArray(), 3, "192.168.1.25"))
        assertNull(PcvrStreaming.parseAlvrHandshakePacket(packet, 100, "192.168.1.25"))
        assertNull(PcvrStreaming.parseAlvrHandshakePacket(packet, packet.size, "   "))
    }

    @Test fun discoveryStartsAndClosesCleanly() {
        val handle = PcvrStreaming.startDiscovery(context) { }
        handle.close()
        handle.close()
    }
}
