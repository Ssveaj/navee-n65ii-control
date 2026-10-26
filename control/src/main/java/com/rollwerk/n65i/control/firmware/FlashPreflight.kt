package com.rollwerk.n65i.control.firmware

/**
 * Everything the flash gates look at, captured right after fresh 0x72/0x73/0x74/0x92 reads.
 * Pure data so the gates can be unit-tested without Android or BLE.
 */
data class FlashSnapshot(
    val connected: Boolean,
    val subscribed: Boolean,
    val authenticated: Boolean,
    val dfuActive: Boolean,
    val serialPid: String?,
    val advertisedPid: Int?,
    val dashboard: String?,
    val bldc: String?,
    val mtu: Int,
    val battery: Int?,
    val speedKmh: Double?,
    val drivingStatus: Int?,
    val stock: ByteArray?,
)

/** Flash gates. Each returns null when every check passes, otherwise the first reason it is blocked. */
object FlashPreflight {
    const val MIN_BATTERY = 50
    const val MIN_MTU = 136
    const val ARM_WINDOW_MS = 60_000L

    /** PID 2509 from the serial or the advertised/owner PID 25092. */
    fun pidOk(s: FlashSnapshot) = s.serialPid == ScooterIdentity.PID || s.advertisedPid == 25092

    fun fullIdentity(s: FlashSnapshot) =
        pidOk(s) && ScooterIdentity.fullMatch(ScooterIdentity.PID, s.dashboard, s.bldc)

    fun meterIdentity(s: FlashSnapshot) =
        pidOk(s) && ScooterIdentity.meterSideMatch(ScooterIdentity.PID, s.dashboard)

    fun stockOk(stock: ByteArray?) = stock != null && stock.size == TurboSpeedFirmware.IMAGE_SIZE &&
        TurboSpeedFirmware.sha256Hex(stock) == TurboSpeedFirmware.STOCK_SHA256

    /**
     * TurboSpeed install: exact PID + Meter 2058 + BLDC 0005, stationary, enough battery and MTU.
     * Does NOT require the normal session auth — the DFU engine authenticates itself, exactly like
     * recovery. The flash safety comes from the exact identity, the pinned-SHA image and the two-tap arm.
     */
    fun install(s: FlashSnapshot): String? = when {
        !s.connected || !s.subscribed -> "BLE / b003 not ready"
        s.dfuActive -> "DFU already active"
        !fullIdentity(s) -> "Not exact PID 2509 / Meter 2058 / BLDC 0005 (dashboard ${s.dashboard ?: "-"}, BLDC ${s.bldc ?: "-"})"
        s.mtu < MIN_MTU -> "MTU ${s.mtu} < $MIN_MTU"
        s.battery == null -> "Battery level unknown"
        s.battery < MIN_BATTERY -> "Battery ${s.battery}% < $MIN_BATTERY%"
        // Idle/just-booted scooters don't push 0x92, so treat unknown speed as parked (the wheel
        // must be off the ground anyway, and DFU puts the controller in bootloader mode = motor off).
        (s.speedKmh ?: 0.0) > 0.5 -> "Scooter is moving"
        (s.drivingStatus ?: 0) != 0 -> "Driving status ${s.drivingStatus}"
        !stockOk(s.stock) -> "Stock image missing or hash/size changed"
        else -> null
    }

    /**
     * Recovery stock: gated on meter-side identity only (PID + Meter), so it still works when the
     * BLDC application is corrupt and no longer reports its version. Authentication not required.
     */
    fun recovery(s: FlashSnapshot): String? = when {
        !s.connected || !s.subscribed -> "BLE / b003 not ready"
        s.dfuActive -> "DFU already active"
        !meterIdentity(s) -> "Meter identity is not 2509 / 2058 (dashboard ${s.dashboard ?: "-"})"
        s.mtu < MIN_MTU -> "MTU ${s.mtu} < $MIN_MTU"
        s.battery == null -> "Battery level unknown"
        s.battery < MIN_BATTERY -> "Battery ${s.battery}% < $MIN_BATTERY%"
        (s.speedKmh ?: 0.0) > 0.5 -> "Scooter is moving"
        !stockOk(s.stock) -> "Stock image missing or hash/size changed"
        else -> null
    }
}

/** Last controller flash, persisted so the app knows whether the TurboSpeed image is installed. */
data class FlashRecord(val kind: String, val sha256: String, val timestamp: Long, val success: Boolean, val message: String) {
    val turboSpeedInstalled get() = success && kind == FlashKind.TURBOSPEED
}

object FlashKind {
    const val TURBOSPEED = "TurboSpeed 25"
    const val RECOVERY = "RECOVERY STOCK"
}
