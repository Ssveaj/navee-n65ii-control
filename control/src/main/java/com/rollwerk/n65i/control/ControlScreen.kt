@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rollwerk.n65i.control

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rollwerk.n65i.control.firmware.FlashKind
import com.rollwerk.n65i.control.firmware.FlashPreflight
import com.rollwerk.n65i.control.firmware.TurboSpeedFirmware
import java.time.Instant
import java.util.Locale

const val APP_NAME = "Rocket Scooter"

private data class TabItem(val title: String, val icon: ImageVector)
private val TABS = listOf(
    TabItem("Settings", Icons.Outlined.Tune),
    TabItem("Firmware", Icons.Outlined.Memory),
    TabItem("Logs", Icons.Outlined.Article),
)

/**
 * The whole app screen, driven only by state. A connect screen, then a connected screen with a
 * bottom navigation bar (Settings / Firmware / Diag). The screenshot tests feed it fixed states.
 */
@Composable
fun ControlScreen(
    s: Screen,
    fw: FirmwareUi,
    now: Long,
    actions: ControlActions,
    initialInstallAck: Boolean = false,
    initialRecoveryAck: Boolean = false,
    initialTab: Int = 0,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (s.device == null) {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectPhase(s, actions)
            }
        } else {
            ConnectedPhase(s, fw, now, actions, initialInstallAck, initialRecoveryAck, initialTab)
        }
    }
}

// ── Connect / splash ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.ConnectPhase(s: Screen, actions: ControlActions) {
    Spacer(Modifier.height(56.dp))
    Box(Modifier.size(104.dp).align(Alignment.CenterHorizontally)
        .clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center) {
        Text("🚀", style = MaterialTheme.typography.displayMedium)
    }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text("Rocket ", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Scooter", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    Text("Connect to your scooter", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(28.dp))
    Button(onClick = { actions.scan() }, enabled = !s.scanning && !s.busy, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(58.dp)) {
        Icon(Icons.Outlined.Bluetooth, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        Text(if (s.scanning) "Scanning…" else "Scan & connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    if (s.scanning || s.busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
        Text(s.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else Text(s.status, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    s.remembered?.let { Text("Last scooter: ${it.name}", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) }
    if (s.authResult.startsWith("Failed")) Text(s.authResult, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    s.devices.forEach { device ->
        Card(onClick = { actions.connect(device) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ElectricScooter, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text("${device.rssi} dBm · ${device.address}" + (device.pid?.let { " · PID $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!s.scanning && !s.busy && s.devices.isEmpty() && s.status != "Disconnected")
        Text("No scooter found. Turn it on and scan again.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.weight(1f, fill = false))
    Text("$APP_NAME ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
}

// ── Connected: header + tab content + bottom nav ──────────────────────────────────────────────

@Composable
private fun ConnectedPhase(
    s: Screen, fw: FirmwareUi, now: Long, actions: ControlActions,
    initialInstallAck: Boolean, initialRecoveryAck: Boolean, initialTab: Int,
) {
    var tab by remember { mutableIntStateOf(initialTab) }
    val ready = DeviceFlow.ready(s.connected, s.authenticated, s.settings?.maximum)
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        ConnectedHeader(s, now, actions, ready)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(2.dp))
            if (ready) when (tab) {
                0 -> { ControlsCard(s, actions); RideSettingsCard(s, actions) }
                1 -> FirmwareCard(s, fw, actions, initialInstallAck, initialRecoveryAck)
                else -> DiagnosticsCard(s)
            } else if (s.limitedSession) {
                LimitedSessionCard(s)
                FirmwareCard(s, fw, actions, initialInstallAck, initialRecoveryAck)
            } else {
                SectionCard("Connecting", Icons.Outlined.Bluetooth) { Text("Connecting and authenticating…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            TABS.forEachIndexed { i, item ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(item.icon, null) }, label = { Text(item.title) })
            }
        }
    }
}

@Composable
private fun ConnectedHeader(s: Screen, now: Long, actions: ControlActions, ready: Boolean) {
    val fresh = s.speedAt?.let { now - it <= 5000 } == true
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bluetooth, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(if (s.pid == 25092) "NAVEE N65i II" else s.device?.name ?: "NAVEE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(s.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = { actions.disconnect() }, enabled = true, shape = RoundedCornerShape(12.dp)) { Text("Disconnect") }
        }
        if (ready) {
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    StatTile("Battery", s.battery?.toString() ?: "–", "%")
                    StatTile("Speed", if (fresh) s.speed?.let { String.format(Locale.US, "%.1f", it) } ?: "–" else "–", "km/h")
                    StatTile("Mode", s.activeMode?.toString() ?: "–")
                    StatTile("Stored", s.settings?.maximum?.toString() ?: "–", "km/h")
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatTile(label: String, value: String, unit: String = "") {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Cards ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlsCard(s: Screen, actions: ControlActions) {
    // Identity
    SectionCard("Scooter", Icons.Outlined.ElectricScooter) {
        InfoRow(Icons.Outlined.Tag, "PID", (s.pid?.toString() ?: "–") + (if (s.pid == 25092) " · model 6001" else ""))
        InfoRow(Icons.Outlined.Badge, "Serial", s.serial ?: "–")
        InfoRow(Icons.Outlined.Public, "Region", s.region?.let { if (it == "SE") "SE / EUR" else it } ?: "–")
        InfoRow(Icons.Outlined.Memory, "Firmware", "dash ${s.dashboard ?: "–"} · BLDC ${s.bldc ?: "–"} · BMS ${s.bms ?: "–"}")
        InfoRow(Icons.Outlined.Speed, "Stored maximum", s.settings?.maximum?.let { "$it km/h" } ?: "–")
    }
    val icons = controlIcons()
    SectionCard("Controls", Icons.Outlined.Tune) {
        Text("Switches reflect your last command this session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ScooterControls.toggles.forEach { t ->
            ToggleRow(icons[t.key] ?: Icons.Outlined.ToggleOn, t.label, t.detail,
                checked = s.toggles[t.key] == true, enabled = !s.busy && s.togglePending == null,
                onChange = { actions.setToggle(t.key, it) })
        }
    }
}

@Composable
private fun RideSettingsCard(s: Screen, actions: ControlActions) = SectionCard("Ride settings", Icons.Outlined.Settings) {
    val enabled = !s.busy && DeviceFlow.ready(s.connected, s.authenticated, s.settings?.maximum)
    Text("Energy recovery (regen braking)", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val values = listOf(30 to "Low", 60 to "High")
        values.forEachIndexed { i, (value, label) ->
            SegmentedButton(selected = s.energyRecovery == value, onClick = { actions.setEnergyRecovery(value) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, values.size)) { Text(label) }
        }
    }
    Spacer(Modifier.height(6.dp))
    val min = ScooterControls.minStartSpeed(s.region)
    var value by remember(s.startSpeed, min) { mutableFloatStateOf((s.startSpeed ?: min).toFloat().coerceIn(min.toFloat(), 5f)) }
    Text("Start speed (kickstart level): ${value.toInt()}", style = MaterialTheme.typography.bodyLarge)
    Slider(value = value, onValueChange = { value = it }, valueRange = min.toFloat()..5f,
        steps = (5 - min - 1).coerceAtLeast(0), enabled = enabled, onValueChangeFinished = { actions.setStartSpeed(value.toInt()) })
    if (min > 0) Text("Range $min–5 on this region (meter clamps 0–2 on non-USA variants).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LimitedSessionCard(s: Screen) = SectionCard("Read-only session", Icons.Outlined.Lock) {
    InfoRow(Icons.Outlined.Tag, "PID", s.serialPid ?: s.pid?.toString() ?: "–")
    InfoRow(Icons.Outlined.Memory, "Firmware", "dash ${s.dashboard ?: "–"} · BLDC ${s.bldc ?: "–"}")
    InfoRow(Icons.Outlined.Info, "Status", s.authResult)
    Text("Authentication did not complete. Recovery stock, and the firmware install, still work.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var showLog by remember { mutableStateOf(false) }
    TextButton(onClick = { showLog = !showLog }, contentPadding = PaddingValues(0.dp)) { Text(if (showLog) "Hide session log" else "Show session log") }
    if (showLog) s.logs.takeLast(40).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun FirmwareCard(s: Screen, fw: FirmwareUi, actions: ControlActions, initialInstallAck: Boolean, initialRecoveryAck: Boolean) {
    val busy = s.busy || fw.dfuActive
    val turbo = "TurboSpeed ${fw.targetKmh}"

    SectionCard("Controller firmware (BLDC)", Icons.Outlined.Bolt) {
        Text("Sets the Turbo cap and removes the meter-limit clamp. ECO and DRIVE stay stock. Flash only while parked, wheel off the ground.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val range = TurboSpeedFirmware.SPEED_RANGE
        var speed by remember(fw.targetKmh) { mutableFloatStateOf(fw.targetKmh.toFloat()) }
        Text("Turbo cap: ${speed.toInt()} km/h", style = MaterialTheme.typography.titleSmall)
        Slider(value = speed, onValueChange = { speed = it }, valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0), enabled = !busy && fw.armedKind == null,
            onValueChangeFinished = { actions.setTargetSpeed(speed.toInt()) })
        Text("25 = EU · 40 = US. Higher means longer braking distance and more component stress.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        InfoRow(Icons.Outlined.BatteryStd, "Battery / MTU", "${s.battery?.let { "$it%" } ?: "–"} (min ${FlashPreflight.MIN_BATTERY}%) · MTU ${s.mtu} (min ${FlashPreflight.MIN_MTU})")
        fw.lastFlash?.let { InfoRow(Icons.Outlined.History, "Last flash", "${it.kind} · ${if (it.success) "OK" else "FAILED"}") }

        if (fw.dfuActive || fw.phase == "DONE" || fw.phase == "FAILED") {
            InstallChecklist(fw)
        } else {
            Text(fw.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedButton(onClick = { actions.dryRun() }, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Science, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Build + dry run (offline)")
        }
        if (fw.dryRunOk != null && !fw.dfuActive) InfoRow(if (fw.dryRunOk) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, "Dry run", if (fw.dryRunOk) "OK — $turbo verified" else "FAILED")

        var installAck by remember { mutableStateOf(initialInstallAck) }
        AckRow(installAck, !busy, "I understand: this permanently rewrites the controller and can brick it.") { installAck = it }
        val installArmed = fw.armedKind == FlashKind.TURBOSPEED
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.armInstall() }, enabled = installAck && !busy && fw.armedKind == null && fw.dryRunOk == true, shape = RoundedCornerShape(12.dp)) { Text("Arm install") }
            OutlinedButton(onClick = { actions.cancelArm() }, enabled = fw.armedKind != null && !fw.dfuActive, shape = RoundedCornerShape(12.dp)) { Text("Cancel") }
        }
        Button(onClick = { actions.startInstall() }, enabled = installAck && installArmed && !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text(if (installArmed) "START $turbo INSTALL (${fw.armSecondsLeft}s)" else "START $turbo INSTALL", fontWeight = FontWeight.SemiBold)
        }
    }

    SectionCard("Recovery stock", Icons.Outlined.Restore) {
        Text("Re-flashes the original BLDC image. Works on meter identity alone, so it is reachable even if the controller app is broken.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WarningBox("BLE recovery is not proven on every failure.")
        var recoveryAck by remember { mutableStateOf(initialRecoveryAck) }
        AckRow(recoveryAck, !busy, "I understand: BLE recovery is not proven on every failure.") { recoveryAck = it }
        val recoveryArmed = fw.armedKind == FlashKind.RECOVERY
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.armRecovery() }, enabled = recoveryAck && !busy && fw.armedKind == null, shape = RoundedCornerShape(12.dp)) { Text("Arm recovery") }
            OutlinedButton(onClick = { actions.cancelArm() }, enabled = fw.armedKind != null && !fw.dfuActive, shape = RoundedCornerShape(12.dp)) { Text("Cancel") }
        }
        Button(onClick = { actions.startRecovery() }, enabled = recoveryAck && recoveryArmed && !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text(if (recoveryArmed) "START RECOVERY STOCK (${fw.armSecondsLeft}s)" else "START RECOVERY STOCK", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun InstallChecklist(fw: FirmwareUi) {
    val done = fw.phase == "DONE"
    val failed = fw.phase == "FAILED"
    val flashing = fw.phase == "FLASH" || (fw.dfuActive && fw.progress in 1..99)
    val preparing = fw.dfuActive && !flashing && !done
    val steps = listOf(
        Triple("Building firmware (offline)", true, null),
        Triple("Verifying firmware", true, null),
        Triple("Preparing controller", done || flashing || !preparing, if (preparing) "" else null),
        Triple("Writing firmware", done || (flashing && fw.progress >= 100), if (flashing) "${fw.progress}%" else null),
        Triple("Verifying installation", done, if (fw.phase == "FINISH") "" else null),
        Triple("Finalizing", done, null),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { (label, complete, active) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                when {
                    complete -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    active != null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(label, Modifier.weight(1f), color = if (complete || active != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when { complete -> "Done"; active?.isNotEmpty() == true -> active; active != null -> ""; else -> "Waiting…" },
                    color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (failed) WarningBox(fw.status) else if (fw.dfuActive) WarningBox("Do NOT power off the scooter, close the app or move away.")
}

@Composable
private fun DiagnosticsCard(s: Screen) {
    SectionCard("Diagnostics", Icons.Outlined.MonitorHeart) {
        Text("Live data from the scooter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val fresh = s.speedAt?.let { System.currentTimeMillis() - it <= 5000 } == true
        InfoRow(Icons.Outlined.BatteryStd, "Battery level", s.battery?.let { "$it %" } ?: "–")
        InfoRow(Icons.Outlined.Speed, "Speed", if (fresh) s.speed?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "–" else "no fresh telemetry")
        InfoRow(Icons.Outlined.Tune, "Active mode", s.activeMode?.toString() ?: "–")
        InfoRow(Icons.Outlined.Flag, "Stored maximum", s.settings?.maximum?.let { "$it km/h" } ?: "–")
        InfoRow(Icons.Outlined.TrendingUp, "Max observed", s.sessionMaximum?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "–")
        InfoRow(Icons.Outlined.Block, "Limiter", s.settings?.limiter?.let { if (it == 0) "disabled (0)" else it.toString() } ?: "–")
    }
    SectionCard("Raw frames & log", Icons.Outlined.DataObject) {
        InfoRow(Icons.Outlined.Memory, "Current 0x70", s.settings?.rawHex?.take(32)?.plus("…") ?: "–")
        for (command in listOf(0x73, 0x74, 0x90, 0x92)) InfoRow(Icons.Outlined.DataObject, "0x%02X".format(command), s.rawResponses[command]?.take(28)?.plus("…") ?: "–")
        Text("Session log · owner identity redacted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.logs.takeLast(30).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ── Building blocks ───────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, modifier = Modifier.semantics { contentDescription = title }, onCheckedChange = onChange)
    }
}

@Composable
private fun WarningBox(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AckRow(checked: Boolean, enabled: Boolean, text: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun controlIcons(): Map<String, ImageVector> = mapOf(
    "lock" to Icons.Outlined.Lock,
    "tailLight" to Icons.Outlined.Lightbulb,
    "autoLight" to Icons.Outlined.LightMode,
    "drl" to Icons.Outlined.WbSunny,
    "cruise" to Icons.Outlined.Speed,
    "tcs" to Icons.Outlined.Tune,
    "eco" to Icons.Outlined.Eco,
    "turnSound" to Icons.Outlined.VolumeUp,
    "proximityKey" to Icons.Outlined.Key,
    "mph" to Icons.Outlined.Straighten,
)
