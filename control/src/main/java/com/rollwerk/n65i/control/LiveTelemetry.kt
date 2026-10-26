package com.rollwerk.n65i.control

/** Live telemetry pushed by the scooter: 0x90 (active mode) and 0x92 (status and speed). */
object LiveTelemetry {
    /** 0x90 DATA[1]: active riding mode number. */
    fun activeMode(raw: ByteArray): Int? {
        val r = Protocol.response(raw)
        return if (r.command == 0x90 && r.status == 0 && r.data.size >= 2) r.data[1].toInt() and 255 else null
    }

    /** 0x92 DATA[1]: driving status, 0 while parked. */
    fun drivingStatus(raw: ByteArray): Int? {
        val r = Protocol.response(raw)
        return if (r.command == 0x92 && r.status == 0 && r.data.size >= 2) r.data[1].toInt() and 255 else null
    }

    /** 0x92 DATA[2..3]: speed, unsigned little-endian, tenths of km/h. */
    fun speedKmh(raw: ByteArray): Double? {
        val r = Protocol.response(raw)
        return if (r.command == 0x92 && r.status == 0 && r.data.size >= 4)
            ((r.data[2].toInt() and 255) or ((r.data[3].toInt() and 255) shl 8)) / 10.0 else null
    }
}
