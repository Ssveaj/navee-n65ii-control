package com.rollwerk.n65i.control

import org.junit.Assert.*
import org.junit.Test

class LiveTelemetryTest {
    private fun response(cmd: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf(0x55, 0xAA.toByte(), 0, cmd.toByte(), (data.size + 1).toByte(), 0) + data
        return body + byteArrayOf((body.sumOf { it.toInt() and 255 } and 255).toByte(), 0xFE.toByte(), 0xFD.toByte())
    }
    @Test fun modeSpeedAndStatusAreDecodedIndependently() {
        assertEquals(3, LiveTelemetry.activeMode(response(0x90, byteArrayOf(0, 3))))
        assertEquals(5, LiveTelemetry.activeMode(response(0x90, byteArrayOf(0, 5))))
        assertEquals(20.0, LiveTelemetry.speedKmh(response(0x92, byteArrayOf(90, 0, 200.toByte(), 0)))!!, 0.0)
        assertEquals(32.1, LiveTelemetry.speedKmh(response(0x92, byteArrayOf(90, 0, 65, 1)))!!, 0.0)
        assertEquals(0, LiveTelemetry.drivingStatus(response(0x92, byteArrayOf(90, 0, 0, 0))))
        assertEquals(1, LiveTelemetry.drivingStatus(response(0x92, byteArrayOf(90, 1, 0, 0))))
        assertNull(LiveTelemetry.speedKmh(response(0x90, byteArrayOf(0, 3))))
        assertNull(LiveTelemetry.activeMode(response(0x90, byteArrayOf(0))))
    }
}
