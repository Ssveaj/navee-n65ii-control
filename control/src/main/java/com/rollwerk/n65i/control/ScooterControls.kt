package com.rollwerk.n65i.control

/**
 * Normal NAVEE controls (not speed-cap related). Each sends exactly one command; the shown state
 * reflects the last command the scooter acknowledged this session, not a live readback.
 */
data class ScooterToggle(val key: String, val label: String, val detail: String, val cmd: Int, val sub: Int? = null) {
    fun frame(on: Boolean): ByteArray {
        val value = (if (on) 1 else 0).toByte()
        val payload = if (sub != null) byteArrayOf(sub.toByte(), value) else byteArrayOf(value)
        return Protocol.command(cmd, payload)
    }
}

object ScooterControls {
    val toggles = listOf(
        ScooterToggle("lock", "Lock scooter", "Immobilises the wheel (0x51)", 0x51),
        ScooterToggle("tailLight", "Tail light", "Rear light (0x54)", 0x54),
        ScooterToggle("autoLight", "Auto headlight", "Automatic headlight (0x57)", 0x57),
        ScooterToggle("drl", "Daytime running light", "DRL (0x6D/3)", 0x6D, 3),
        ScooterToggle("cruise", "Cruise control", "Hold speed (0x52)", 0x52),
        ScooterToggle("tcs", "Traction control", "TCS (0x5F)", 0x5F),
        ScooterToggle("eco", "Eco / low power", "Low-power mode (0x6F/5)", 0x6F, 5),
        ScooterToggle("turnSound", "Turn-signal sound", "Blinker beep (0x60)", 0x60),
        ScooterToggle("proximityKey", "Proximity key", "Auto-unlock near phone (0x61)", 0x61),
        ScooterToggle("mph", "Show mph", "Display unit km/h ↔ mph (0x55)", 0x55),
    )
    val byKey = toggles.associateBy { it.key }

    // ── Value controls (not simple on/off) ──
    const val ENERGY_CMD = 0x53
    val ENERGY_VALUES = listOf(30, 60)          // low / high regen
    const val START_SPEED_CMD = 0x6A
    val START_SPEED_RANGE = 0..5

    fun energyFrame(value: Int): ByteArray { require(value in ENERGY_VALUES); return Protocol.command(ENERGY_CMD, byteArrayOf(value.toByte())) }
    fun startSpeedFrame(value: Int): ByteArray { require(value in START_SPEED_RANGE); return Protocol.command(START_SPEED_CMD, byteArrayOf(value.toByte())) }

    /** Stock meter clamps start speed 0–2 on non-USA variants, so the minimum allowed is region-dependent. */
    fun isUsaRegion(region: String?): Boolean = region in listOf("US", "CN", "RU")
    fun minStartSpeed(region: String?): Int = if (isUsaRegion(region)) 0 else 3

    /** A frame is a normal control frame when it equals one of the toggle or value-control frames. */
    fun isControlFrame(bytes: ByteArray): Boolean =
        toggles.any { bytes.contentEquals(it.frame(true)) || bytes.contentEquals(it.frame(false)) } ||
            ENERGY_VALUES.any { bytes.contentEquals(energyFrame(it)) } ||
            START_SPEED_RANGE.any { bytes.contentEquals(startSpeedFrame(it)) }
}
