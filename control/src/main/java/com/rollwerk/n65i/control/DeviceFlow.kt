package com.rollwerk.n65i.control

/** Scan filtering, switch state and the outbound command allowlist. Switch state always comes from a readback. */
object DeviceFlow {
    fun isNavee(name: String) = name.contains("NAVEE", true) || name.contains("N65i", true)

    /** PID from the manufacturer AD record (FF 04 04 <pid-high> …), e.g. 25092. */
    fun advertisedPid(record: ByteArray): Int? {
        var offset = 0
        while (offset < record.size) {
            val length = record[offset].toInt() and 255
            if (length == 0 || offset + length >= record.size) return null
            if (length >= 4 && record[offset + 1] == 0xFF.toByte() &&
                record[offset + 2] == 4.toByte() && record[offset + 3] == 4.toByte()) {
                return 4 or ((record[offset + 4].toInt() and 255) shl 8)
            }
            offset += length + 1
        }
        return null
    }

    fun ready(connected: Boolean, authenticated: Boolean, maximum: Int?) =
        connected && authenticated && maximum != null

    /** Session frames that are always allowed: owner auth, 0x6F/06 clock sync, 0x70 read. */
    fun sessionTx(bytes: ByteArray, ownerAuth: ByteArray): Boolean =
        bytes.contentEquals(ownerAuth) || bytes.contentEquals(Protocol.command(0x70)) ||
            (bytes.size == 13 && bytes[3] == 0x6F.toByte() && bytes[4] == 5.toByte() && bytes[5] == 6.toByte() &&
                runCatching { Protocol.response(bytes) }.isSuccess)

    /** Read-only requests for identity, battery and speed; allowed without auth so recovery still works. */
    val readOnly = listOf(0x72, 0x73, 0x74, 0x92)

    /** 0x30/0x31 session authentication frames, sent during connection bring-up. */
    fun isAuthFrame(bytes: ByteArray): Boolean =
        bytes.size > 3 && ((bytes[3].toInt() and 0xFF) == 0x30 || (bytes[3].toInt() and 0xFF) == 0x31)

    fun allowedTx(bytes: ByteArray, ownerAuth: ByteArray, ready: Boolean, readOnlyOk: Boolean = ready): Boolean =
        sessionTx(bytes, ownerAuth) ||
            (readOnlyOk && isAuthFrame(bytes)) ||
            (readOnlyOk && readOnly.any { bytes.contentEquals(Protocol.command(it)) }) ||
            (ready && ScooterControls.isControlFrame(bytes))
}
