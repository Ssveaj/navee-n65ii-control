package com.rollwerk.n65i.control

import com.rollwerk.n65i.control.firmware.FlashRecord
import com.rollwerk.n65i.control.firmware.ScooterIdentity

data class Found(val address: String, val name: String, val rssi: Int, val pid: Int? = null)

/** Connection, session and telemetry state shown on screen. Reset on every disconnect. */
data class Screen(val status: String = "Disconnected", val connected: Boolean = false, val busy: Boolean = false,
    val scanning: Boolean = false, val devices: List<Found> = emptyList(), val device: Found? = null,
    val remembered: Found? = null, val sessionMaximum: Double? = null,
    val rawResponses: Map<Int, String> = emptyMap(), val settingsAt: Long? = null,
    val settings: Protocol.Settings? = null, val backup: Backup? = null,
    val serial: String? = null, val region: String? = null, val dashboard: String? = null,
    val bldc: String? = null, val bms: String? = null, val lastResponse: String? = null,
    val subscribed: Boolean = false, val authenticated: Boolean = false, val logs: List<String> = emptyList(),
    val authResult: String = "Not attempted",
    val activeMode: Int? = null, val modeAt: Long? = null, val speed: Double? = null, val speedAt: Long? = null,
    val mtu: Int = 23, val battery: Int? = null, val drivingStatus: Int? = null,
    val toggles: Map<String, Boolean> = emptyMap(), val togglePending: String? = null,
    val energyRecovery: Int? = null, val startSpeed: Int? = null) {
    val pid: Int? get() = device?.pid ?: if (serialPid == ScooterIdentity.PID) 25092 else null
    val serialPid: String? get() = ScooterIdentity.pidFromSerial(serial)
    val limitedSession get() = connected && subscribed && !busy && authResult.startsWith("Incomplete")
}

/** Controller firmware flash state. Kept outside [Screen] so a disconnect never wipes the DFU result. */
data class FirmwareUi(
    val targetKmh: Int = 25, val dryRunSpeed: Int? = null,
    val candidateSha256: String? = null, val primaryCrc: String? = null, val secondaryCrc: String? = null,
    val changedOffsets: String? = null, val blockCount: Int? = null,
    val dryRunOk: Boolean? = null, val dryRunMessage: String? = null,
    val armedKind: String? = null, val armSecondsLeft: Int = 0,
    val dfuActive: Boolean = false, val dfuKind: String? = null, val progress: Int = 0,
    val block: Int = 0, val blocks: Int = 0, val phase: String = "IDLE",
    val status: String = "Not prepared", val log: List<String> = emptyList(),
    val lastFlash: FlashRecord? = null)

/** Everything the screen can ask for. Implemented by [ControlModel]; screenshot tests pass a no-op. */
interface ControlActions {
    fun scan()
    fun connect(found: Found)
    fun disconnect()
    fun setToggle(key: String, on: Boolean)
    fun setEnergyRecovery(value: Int)
    fun setStartSpeed(value: Int)
    fun setTargetSpeed(kmh: Int)
    fun dryRun()
    fun armInstall()
    fun armRecovery()
    fun cancelArm()
    fun startInstall()
    fun startRecovery()
}
