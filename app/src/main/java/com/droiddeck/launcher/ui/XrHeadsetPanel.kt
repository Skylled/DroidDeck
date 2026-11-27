package com.droiddeck.launcher.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.DiscoveredHost
import com.droiddeck.launcher.session.GamePreset
import com.droiddeck.launcher.session.PcvrStreaming
import com.droiddeck.launcher.session.StreamerApp
import com.droiddeck.launcher.session.XrHeadset
import com.droiddeck.launcher.session.XrHeadsetInfo
import com.droiddeck.launcher.session.XrHeadsetKind
import com.droiddeck.launcher.session.XrPreferences
import com.droiddeck.launcher.session.XrQualityPreset
import com.droiddeck.launcher.session.XrRefreshRate

@Composable
internal fun XrHeadsetPanel() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val headset = remember(context) { XrHeadset.detect(context) }
    val streamers = remember(context) { PcvrStreaming.detectInstalledStreamers(context) }
    var qualityPreset by remember(context) { mutableStateOf(XrPreferences.getQualityPreset(context)) }
    var refreshRate by remember(context) { mutableStateOf(XrPreferences.getRefreshRate(context)) }
    var handTrackingEnabled by remember(context) { mutableStateOf(XrPreferences.isHandTrackingEnabled(context)) }
    var manualHost by remember(context) { mutableStateOf(XrPreferences.getManualPcvrHost(context)) }
    var editingManualHost by remember { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var flagsCopied by remember { mutableStateOf(false) }
    val discoveredHosts = remember { mutableStateListOf<DiscoveredHost>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(scanning) {
        if (!scanning) {
            onDispose { }
        } else {
            discoveredHosts.clear()
            var active = true
            val handle = runCatching {
                PcvrStreaming.startDiscovery(context) { found ->
                    mainHandler.post {
                        if (!active) return@post
                        val idx = discoveredHosts.indexOfFirst {
                            it.host == found.host && it.port == found.port && it.protocol == found.protocol
                        }
                        if (idx >= 0) discoveredHosts[idx] = found else discoveredHosts.add(found)
                    }
                }
            }.getOrNull()
            onDispose {
                active = false
                runCatching { handle?.close() }
            }
        }
    }

    if (editingManualHost) {
        TextEntryDialog(
            title = stringResource(R.string.xr_pcvr_manual_host_dialog),
            initial = manualHost,
            onDismiss = { editingManualHost = false },
            onSave = { value ->
                val cleaned = value.trim()
                XrPreferences.setManualPcvrHost(context, cleaned)
                manualHost = cleaned
                editingManualHost = false
            },
        )
    }

    XrHeadsetPanelContent(
        headset = headset,
        streamers = streamers,
        discoveredHosts = discoveredHosts,
        scanning = scanning,
        manualHost = manualHost,
        qualityPreset = qualityPreset,
        refreshRate = refreshRate,
        handTrackingEnabled = handTrackingEnabled,
        flagsCopied = flagsCopied,
        onCopyFlags = { flags ->
            clipboard.setText(AnnotatedString(flags))
            flagsCopied = true
        },
        onLaunchStreamer = { app ->
            if (!PcvrStreaming.launchStreamer(context, app)) {
                Toast.makeText(
                    context,
                    context.getString(R.string.xr_pcvr_launch_failed, app.name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onToggleScan = { scanning = it },
        onEditManualHost = { editingManualHost = true },
        onQualityPreset = {
            XrPreferences.setQualityPreset(context, it)
            qualityPreset = it
        },
        onRefreshRate = {
            XrPreferences.setRefreshRate(context, it)
            refreshRate = it
        },
        onHandTrackingEnabled = {
            XrPreferences.setHandTrackingEnabled(context, it)
            handTrackingEnabled = it
        },
    )
}

@Composable
internal fun XrHeadsetPanelContent(
    headset: XrHeadsetInfo,
    streamers: List<StreamerApp>,
    discoveredHosts: List<DiscoveredHost>,
    scanning: Boolean,
    manualHost: String,
    qualityPreset: XrQualityPreset,
    refreshRate: XrRefreshRate,
    handTrackingEnabled: Boolean,
    alyxPreset: GamePreset = PcvrStreaming.HALF_LIFE_ALYX_PRESET,
    flagsCopied: Boolean = false,
    onCopyFlags: (String) -> Unit = {},
    onLaunchStreamer: (StreamerApp) -> Unit = {},
    onToggleScan: (Boolean) -> Unit = {},
    onEditManualHost: () -> Unit = {},
    onQualityPreset: (XrQualityPreset) -> Unit = {},
    onRefreshRate: (XrRefreshRate) -> Unit = {},
    onHandTrackingEnabled: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val host = rememberMenuHost()

    val leftColumn: @Composable () -> Unit = {
        XrHeadsetStatusBanner(headset)
        SectionTitle(stringResource(R.string.xr_pcvr_group), null)
        HalfLifeAlyxSpotlightCard(
            preset = alyxPreset,
            flagsCopied = flagsCopied,
            onCopyFlags = onCopyFlags,
        )
        PcvrStreamersGroup(
            streamers = streamers,
            onLaunchStreamer = onLaunchStreamer,
        )
    }

    val rightColumn: @Composable () -> Unit = {
        StandaloneSteamVrGroup(
            host = host,
            qualityPreset = qualityPreset,
            refreshRate = refreshRate,
            handTrackingEnabled = handTrackingEnabled,
            onQualityPreset = onQualityPreset,
            onRefreshRate = onRefreshRate,
            onHandTrackingEnabled = onHandTrackingEnabled,
        )
        PcvrDiscoveryGroup(
            host = host,
            scanning = scanning,
            discoveredHosts = discoveredHosts,
            manualHost = manualHost,
            onToggleScan = onToggleScan,
            onEditManualHost = onEditManualHost,
        )
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= 760.dp && !LocalNarrowPane.current) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1.08f)) { leftColumn() }
                Column(Modifier.weight(0.92f)) { rightColumn() }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                leftColumn()
                rightColumn()
            }
        }
    }
}

@Composable
internal fun xrHeadsetBadgeLabel(headset: XrHeadsetInfo): String = when {
    headset.isGalaxyXr -> stringResource(R.string.xr_badge_galaxy_xr)
    headset.kind == XrHeadsetKind.ANDROID_XR -> stringResource(R.string.xr_badge_android_xr)
    headset.kind == XrHeadsetKind.META_QUEST -> stringResource(R.string.xr_badge_quest)
    headset.kind == XrHeadsetKind.GENERIC_OPENXR -> stringResource(R.string.xr_badge_openxr)
    else -> stringResource(R.string.xr_headset_none)
}

@Composable
private fun XrHeadsetStatusBanner(headset: XrHeadsetInfo) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ready = headset.isImmersiveSupported
    val borderColor = if (ready) pal.signal.copy(alpha = 0.45f) else pal.line
    val iconTint = if (ready) pal.signal else colors.onSurfaceVariant
    val title = if (ready) {
        headset.displayName.ifBlank { xrHeadsetBadgeLabel(headset) }
    } else {
        stringResource(R.string.xr_headset_none)
    }
    val detail = when {
        headset.isGalaxyXr -> stringResource(R.string.xr_headset_galaxy_xr_detail)
        headset.kind == XrHeadsetKind.ANDROID_XR -> stringResource(R.string.xr_headset_android_xr_detail)
        headset.kind == XrHeadsetKind.META_QUEST -> stringResource(R.string.xr_headset_quest_detail)
        headset.kind == XrHeadsetKind.GENERIC_OPENXR -> stringResource(R.string.xr_headset_generic_detail)
        else -> stringResource(R.string.xr_headset_none_detail)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(Shape14)
            .background(colors.surface)
            .border(1.dp, borderColor, Shape14)
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.14f)),
        ) {
            Icon(
                VrHeadsetIcon,
                contentDescription = stringResource(R.string.xr_headset_status_title),
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Chip(
                    t = stringResource(if (ready) R.string.xr_badge_ready else R.string.xr_badge_not_detected),
                    ok = ready,
                )
            }
            Text(
                detail,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HalfLifeAlyxSpotlightCard(
    preset: GamePreset,
    flagsCopied: Boolean,
    onCopyFlags: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(Shape14)
            .background(pal.signal.copy(alpha = 0.08f))
            .border(1.dp, pal.signal.copy(alpha = 0.35f), Shape14)
            .padding(14.dp),
    ) {
        Text(
            stringResource(R.string.xr_pcvr_spotlight_badge).uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            color = pal.signal,
        )
        Text(
            preset.title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
        )
        Text(
            stringResource(
                R.string.xr_pcvr_alyx_specs,
                preset.recommendedEyePx,
                preset.recommendedHz,
                preset.recommendedCodec,
                preset.recommendedBitrateMbps,
            ),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onBackground,
        )
        Text(
            preset.controllerNote,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = colors.onSurfaceVariant,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(Shape12)
                .background(colors.surface)
                .border(1.dp, pal.line, Shape12)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.xr_pcvr_pc_flags_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant,
            )
            Text(
                preset.pcLaunchOptions,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onBackground,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = stringResource(if (flagsCopied) R.string.xr_pcvr_copied_flags else R.string.xr_pcvr_copy_flags),
                compact = true,
                onClick = { onCopyFlags(preset.pcLaunchOptions) },
            )
        }
    }
}

@Composable
private fun PcvrStreamersGroup(
    streamers: List<StreamerApp>,
    onLaunchStreamer: (StreamerApp) -> Unit,
) {
    SettingsGroup(stringResource(R.string.xr_pcvr_streamers_group)) {
        streamers.forEach { app ->
            val installed = app.installedPackage != null
            val platformText = when {
                app.supportsWindows && app.supportsLinux -> stringResource(R.string.xr_pcvr_platforms_both)
                app.supportsWindows -> stringResource(R.string.xr_pcvr_platforms_windows)
                else -> stringResource(R.string.xr_pcvr_platforms_linux)
            }
            val hint = listOf(platformText, app.notes).filter { it.isNotBlank() }.joinToString(" · ")
            SettingsRow(app.name, hint) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip(
                        t = stringResource(if (installed) R.string.xr_pcvr_installed else R.string.xr_pcvr_not_installed),
                        ok = installed,
                    )
                    if (installed) {
                        SecondaryButton(
                            text = stringResource(R.string.xr_pcvr_launch),
                            compact = true,
                            onClick = { onLaunchStreamer(app) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PcvrDiscoveryGroup(
    host: MenuHost,
    scanning: Boolean,
    discoveredHosts: List<DiscoveredHost>,
    manualHost: String,
    onToggleScan: (Boolean) -> Unit,
    onEditManualHost: () -> Unit,
) {
    SettingsGroup(stringResource(R.string.xr_pcvr_discovery_group)) {
        ToggleRow(
            host = host,
            key = "xr-pcvr-scan",
            label = stringResource(R.string.xr_pcvr_scan_toggle),
            hint = stringResource(R.string.xr_pcvr_scan_hint),
            checked = scanning,
            onChange = onToggleScan,
        )
        if (discoveredHosts.isEmpty()) {
            SettingsRow(
                label = stringResource(if (scanning) R.string.xr_pcvr_scanning else R.string.xr_pcvr_no_hosts),
                hint = null,
            ) {}
        } else {
            discoveredHosts.forEach { entry ->
                SettingsRow(
                    label = entry.name,
                    hint = "${entry.host}:${entry.port}",
                ) {
                    Chip(entry.protocol, ok = true)
                }
            }
        }
        ActionRow(
            label = stringResource(R.string.xr_pcvr_manual_host),
            hint = manualHost.ifBlank { stringResource(R.string.xr_pcvr_manual_host_empty) },
            button = stringResource(R.string.xr_pcvr_manual_host_edit),
            onClick = onEditManualHost,
        )
    }
}

@Composable
private fun StandaloneSteamVrGroup(
    host: MenuHost,
    qualityPreset: XrQualityPreset,
    refreshRate: XrRefreshRate,
    handTrackingEnabled: Boolean,
    onQualityPreset: (XrQualityPreset) -> Unit,
    onRefreshRate: (XrRefreshRate) -> Unit,
    onHandTrackingEnabled: (Boolean) -> Unit,
) {
    SettingsGroup(stringResource(R.string.xr_standalone_group)) {
        ChoiceRow(
            host = host,
            key = "xr-eye-resolution",
            label = stringResource(R.string.xr_standalone_resolution),
            hint = stringResource(R.string.xr_standalone_resolution_hint),
            options = XrQualityPreset.entries.map { it to it.label },
            selected = qualityPreset,
            onPick = onQualityPreset,
        )
        ChoiceRow(
            host = host,
            key = "xr-refresh-rate",
            label = stringResource(R.string.xr_standalone_refresh),
            hint = stringResource(R.string.xr_standalone_refresh_hint),
            options = XrRefreshRate.entries.map { it to it.label },
            selected = refreshRate,
            onPick = onRefreshRate,
        )
        ToggleRow(
            host = host,
            key = "xr-hand-tracking",
            label = stringResource(R.string.xr_standalone_hand_tracking),
            hint = stringResource(R.string.xr_standalone_hand_tracking_hint),
            checked = handTrackingEnabled,
            onChange = onHandTrackingEnabled,
        )
        Box(Modifier.padding(12.dp)) {
            Note(stringResource(R.string.xr_standalone_alyx_note))
        }
    }
}
