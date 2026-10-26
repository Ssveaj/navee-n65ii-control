package com.rollwerk.n65i.control

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.rollwerk.n65i.control.firmware.FlashKind
import com.rollwerk.n65i.control.firmware.FlashRecord
import org.junit.Rule
import org.junit.Test

/**
 * Renders every ControlScreen state to a PNG (control/src/test/snapshots/images/). Pure JVM, no
 * device. Run: `gradlew :control:recordPaparazziDebug`. These are for documentation, not asserts.
 */
class ControlScreenshotTest {
    // Device-height render so the bottom navigation bar sits at the bottom like on a phone.
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    private val noop = object : ControlActions {
        override fun scan() {}
        override fun connect(found: Found) {}
        override fun disconnect() {}
        override fun setToggle(key: String, on: Boolean) {}
        override fun setEnergyRecovery(value: Int) {}
        override fun setStartSpeed(value: Int) {}
        override fun setTargetSpeed(kmh: Int) {}
        override fun dryRun() {}
        override fun armInstall() {}
        override fun armRecovery() {}
        override fun cancelArm() {}
        override fun startInstall() {}
        override fun startRecovery() {}
    }

    private val owner = Found("AA:BB:CC:DD:EE:FF", "NAVEE N65i II", -54, 25092)
    private fun settings(max: Int) = Protocol.Settings("55 AA 00 70 25 00 01 05 … $max", 5, 0, max)
    private fun connected(max: Int) = Screen(
        status = "Connected / Auth OK", connected = true, subscribed = true, authenticated = true,
        device = owner, settings = settings(max), serial = "N2509 2SE000000", region = "SE",
        dashboard = "2.0.5.8", bldc = "0.0.0.5", bms = "2.0.0.1", mtu = 148, battery = 82,
        drivingStatus = 0, speed = 0.0, speedAt = 10_000, activeMode = 5, modeAt = 10_000, authResult = "Auth OK",
        toggles = mapOf("tailLight" to true, "autoLight" to true, "tcs" to true),
        energyRecovery = 60, startSpeed = 3,
        backup = Backup(DeviceIdentity(owner.address, "25092", "2.0.5.8", "0.0.0.5", "SE"), 0L, settings(max)))

    private fun shot(name: String, screen: Screen, fw: FirmwareUi, installAck: Boolean = false, tab: Int = 0) =
        paparazzi.snapshot(name) { N65iControlTheme { ControlScreen(screen, fw, now = 12_000, actions = noop, initialInstallAck = installAck, initialTab = tab) } }

    @Test fun a_startScanning() = shot("1-start-scanning", Screen(status = "Scanning…", scanning = true, remembered = owner), FirmwareUi())

    @Test fun b_scooterFound() = shot("2-scooter-found", Screen(status = "Scan complete", devices = listOf(owner)), FirmwareUi())

    @Test fun c_settingsTab() = shot("3-connected-settings", connected(32), FirmwareUi(), tab = 0)

    @Test fun d_armed() = shot("4-dry-run-armed", connected(32), tab = 1, fw = FirmwareUi(
        dryRunOk = true, dryRunMessage = "369 blocks accepted, EOT accepted, image reassembled byte-exact",
        candidateSha256 = "e586f95bf767ec71b1b9303852c56100d9b386d304a43a8d419f28d433c5c32d",
        primaryCrc = "0x1A2B", secondaryCrc = "0x3C4D",
        changedOffsets = "0x13 0x14 0xB0 0xB1 0x36D0 0x36D1 0x36FE 0x36FF 0x3964 0x3965 0x3966 0x3967",
        blockCount = 369, armedKind = FlashKind.TURBOSPEED, armSecondsLeft = 47,
        status = "TurboSpeed 25 ARMED 60 s — press START to flash the controller"), installAck = true)

    @Test fun e_flashing() = shot("5-flashing", connected(32), tab = 1, installAck = true, fw = FirmwareUi(
        dfuActive = true, dfuKind = "TurboSpeed 25", progress = 58, block = 214, blocks = 369, phase = "FLASH",
        status = "TurboSpeed 25 — flash 58% (214/369)",
        log = listOf("16:40:02  Controller DFU (target 2) — TurboSpeed 25", "16:40:03  C received", "16:40:41  flash 58% (214/369)")))

    @Test fun f_success() = shot("6-success-patched", connected(32), tab = 1, fw = FirmwareUi(
        phase = "DONE", progress = 100,
        status = "SUCCESS — TurboSpeed 25 dfu_ok received (transfer complete; verify speed on a test ride)",
        lastFlash = FlashRecord(FlashKind.TURBOSPEED, "e586f95b…", 1_700_000_000_000, true, "SUCCESS")))

    @Test fun g_recoveryOnly() = shot("7-recovery-only", Screen(
        status = "Not authenticated — read-only session. Recovery flash only.",
        connected = true, subscribed = true, device = owner, authResult = "Incomplete: Authentication rejected",
        serial = "N2509 2SE000000", dashboard = "2.0.5.8", bldc = "0.0.0.5", mtu = 148, battery = 82), FirmwareUi())
}
