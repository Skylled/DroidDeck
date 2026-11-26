package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class StreamerApp(
    val id: String,
    val name: String,
    val packageNames: List<String>,
    val installedPackage: String?,
    val supportsWindows: Boolean,
    val supportsLinux: Boolean,
    val notes: String,
) {
    val isInstalled: Boolean get() = installedPackage != null
}

data class DiscoveredHost(
    val name: String,
    val host: String,
    val port: Int,
    val protocol: String,
)

data class GamePreset(
    val appId: String,
    val title: String,
    val pcLaunchOptions: String,
    val standaloneLaunchOptions: String,
    val recommendedBitrateMbps: Int,
    val recommendedCodec: String,
    val recommendedEyePx: Int,
    val recommendedHz: Int,
    val controllerNote: String,
)

object PcvrStreaming {
    private const val TAG = "PcvrStreaming"
    const val ALVR_DISCOVERY_PORT = 9943
    const val WIVRN_MDNS_TYPE = "_wivrn._tcp."
    private const val ALVR_HOSTNAME_OFFSET = 24
    private const val ALVR_PACKET_SIZE = 56

    val HALF_LIFE_ALYX_PRESET = GamePreset(
        appId = "546560",
        title = "Half-Life: Alyx",
        pcLaunchOptions = "-vr -novid +vr_fidelity_level_auto 0 +vr_fidelity_level 3",
        standaloneLaunchOptions = "-vr -novid -vulkan +vr_fidelity_level_auto 0 +vr_fidelity_level 1 +vr_render_scale 0.65",
        recommendedBitrateMbps = 150,
        recommendedCodec = "HEVC 10-bit / AV1",
        recommendedEyePx = 2600,
        recommendedHz = 72,
        controllerNote = "6DoF motion controllers strongly recommended for gravity gloves, locomotion, and weapon handling.",
    )

    private val STREAMER_SPECS = listOf(
        StreamerApp(
            id = "virtual_desktop",
            name = "Virtual Desktop",
            packageNames = listOf("VirtualDesktop.Android", "com.virtualdesktop.android"),
            installedPackage = null,
            supportsWindows = true,
            supportsLinux = false,
            notes = "Wireless PCVR for Windows hosts with HEVC 10-bit / AV1 over Wi-Fi 7.",
        ),
        StreamerApp(
            id = "steam_link",
            name = "Steam Link",
            packageNames = listOf("com.valvesoftware.steamlink"),
            installedPackage = null,
            supportsWindows = true,
            supportsLinux = true,
            notes = "Valve's SteamVR streaming client with foveated encoding.",
        ),
        StreamerApp(
            id = "wivrn",
            name = "WiVRn",
            packageNames = listOf("org.meumeu.wivrn", "io.github.wivrn.wivrn"),
            installedPackage = null,
            supportsWindows = false,
            supportsLinux = true,
            notes = "Open-source OpenXR streamer for Linux PCVR hosts.",
        ),
        StreamerApp(
            id = "alvr",
            name = "ALVR",
            packageNames = listOf("alvr.client.stable", "alvr.client.nightly", "alvr.client"),
            installedPackage = null,
            supportsWindows = true,
            supportsLinux = true,
            notes = "Open-source SteamVR streamer for Windows and Linux over Wi-Fi or USB-C.",
        ),
    )

    fun detectInstalledStreamers(context: Context): List<StreamerApp> {
        val pm = context.packageManager
        val installed = buildSet {
            for (spec in STREAMER_SPECS) {
                for (pkg in spec.packageNames) {
                    val found = runCatching {
                        pm.getLaunchIntentForPackage(pkg) != null || pm.getPackageInfo(pkg, 0) != null
                    }.getOrDefault(false)
                    if (found) add(pkg)
                }
            }
        }
        return detectInstalledStreamers(installed)
    }

    fun detectInstalledStreamers(installedPackages: Set<String>): List<StreamerApp> =
        STREAMER_SPECS.map { spec ->
            spec.copy(installedPackage = spec.packageNames.firstOrNull { it in installedPackages })
        }

    fun launchStreamer(context: Context, app: StreamerApp): Boolean {
        val pkg = app.installedPackage ?: return false
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.w(TAG, "Could not launch streamer $pkg: ${e.message}")
            false
        }
    }

    fun parseAlvrHandshakePacket(bytes: ByteArray, length: Int, senderIp: String): DiscoveredHost? {
        val ip = senderIp.trim()
        if (ip.isEmpty() || length < 4 || length > bytes.size) return null
        if (bytes[0] != 'A'.code.toByte() || bytes[1] != 'L'.code.toByte() ||
            bytes[2] != 'V'.code.toByte() || bytes[3] != 'R'.code.toByte()
        ) {
            return null
        }

        // Upstream ALVR HandshakePacket is 56 bytes: 16B magic/pad, 8B protocol_id, 32B hostname.
        val hostName = if (length > ALVR_HOSTNAME_OFFSET) {
            val end = minOf(length, ALVR_PACKET_SIZE)
            var nul = end
            for (i in ALVR_HOSTNAME_OFFSET until end) {
                if (bytes[i] == 0.toByte()) {
                    nul = i
                    break
                }
            }
            val raw = String(bytes, ALVR_HOSTNAME_OFFSET, nul - ALVR_HOSTNAME_OFFSET, Charsets.UTF_8).trim()
            raw.filter { it.code in 0x20..0x7E }.trim()
        } else {
            ""
        }

        return DiscoveredHost(
            name = hostName.ifEmpty { "ALVR ($ip)" },
            host = ip,
            port = ALVR_DISCOVERY_PORT,
            protocol = "ALVR",
        )
    }

    fun startDiscovery(context: Context, onHostFound: (DiscoveredHost) -> Unit): AutoCloseable {
        val running = AtomicBoolean(true)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val emit: (DiscoveredHost) -> Unit = { host ->
            val key = "${host.protocol}:${host.host}:${host.port}"
            if (running.get() && seen.add(key)) {
                runCatching { onHostFound(host) }
            }
        }

        val nsdManager = runCatching {
            context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull()

        var discoveryListener: NsdManager.DiscoveryListener? = null
        if (nsdManager != null) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "NSD start failed ($serviceType): $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "NSD stop failed ($serviceType): $errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!running.get()) return
                    resolveWivrnService(nsdManager, serviceInfo, emit)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            }
            val started = runCatching {
                nsdManager.discoverServices(WIVRN_MDNS_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                true
            }.getOrDefault(false)
            if (started) discoveryListener = listener
        }

        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 500
                bind(InetSocketAddress(ALVR_DISCOVERY_PORT))
            }
        }.getOrNull()

        val udpThread = if (socket != null) {
            Thread({
                val buf = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)
                while (running.get() && !socket.isClosed) {
                    try {
                        packet.length = buf.size
                        socket.receive(packet)
                        val senderIp = packet.address?.hostAddress.orEmpty()
                        val discovered = parseAlvrHandshakePacket(packet.data, packet.length, senderIp)
                        if (discovered != null) emit(discovered)
                    } catch (_: SocketTimeoutException) {
                        // Poll shutdown flag periodically.
                    } catch (_: Throwable) {
                        if (!running.get()) break
                    }
                }
            }, "pcvr-alvr-discovery").apply {
                isDaemon = true
                start()
            }
        } else {
            null
        }

        return AutoCloseable {
            if (!running.compareAndSet(true, false)) return@AutoCloseable
            if (nsdManager != null && discoveryListener != null) {
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }
            runCatching { socket?.close() }
            runCatching { udpThread?.interrupt() }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveWivrnService(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
        emit: (DiscoveredHost) -> Unit,
    ) {
        runCatching {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "WiVRn resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val hostAddress = info.host?.hostAddress ?: return
                    val port = info.port.takeIf { it > 0 } ?: 9757
                    emit(
                        DiscoveredHost(
                            name = info.serviceName.ifBlank { "WiVRn ($hostAddress)" },
                            host = hostAddress,
                            port = port,
                            protocol = "WiVRn",
                        )
                    )
                }
            })
        }
    }
}
