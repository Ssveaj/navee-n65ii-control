package com.rollwerk.n65i.control

import org.junit.Assert.*
import org.junit.Test

class DeviceFlowTest {
    @Test fun advertisedIdentityRequiresTheKnownManufacturerLayout() {
        val real = unhex("02 01 06 0A FF 04 04 62 11 22 33 44 55 66 06 09 4E 41 56 45 45")
        assertEquals(25092, DeviceFlow.advertisedPid(real))
        assertNull(DeviceFlow.advertisedPid(real.copyOf(8)))
        assertNull(DeviceFlow.advertisedPid(byteArrayOf()))
        assertTrue(DeviceFlow.isNavee("N65i II"))
        assertFalse(DeviceFlow.isNavee("Headphones"))
    }
    @Test fun controlsRequireConnectionAuthenticationAndReadback() {
        assertFalse(DeviceFlow.ready(false, true, 34))
        assertFalse(DeviceFlow.ready(true, false, 34))
        assertFalse(DeviceFlow.ready(true, true, null))
        assertTrue(DeviceFlow.ready(true, true, 20))
    }
    @Test fun sessionFramesAreAlwaysAllowed() {
        val owner = Protocol.ownerFrame(1)
        assertTrue(DeviceFlow.sessionTx(owner, owner))
        assertTrue(DeviceFlow.sessionTx(Protocol.timeInitialization(123), owner))
        assertTrue(DeviceFlow.sessionTx(Protocol.command(0x70), owner))
        assertFalse(DeviceFlow.sessionTx(Protocol.command(0x72), owner))
    }
    @Test fun transportGatesReadsOnReadiness() {
        val owner = Protocol.ownerFrame(1)
        // an arbitrary non-allowlisted frame is never allowed, even when ready
        assertFalse(DeviceFlow.allowedTx(Protocol.command(0x6F, byteArrayOf(8, 1)), owner, true))
        for (command in listOf(0x73, 0x74)) {
            assertFalse(DeviceFlow.allowedTx(Protocol.command(command), owner, false))
            assertTrue(DeviceFlow.allowedTx(Protocol.command(command), owner, true))
        }
    }
    @Test fun normalControlTogglesAreAllowedOnlyWhenReady() {
        val owner = Protocol.ownerFrame(1)
        for (toggle in ScooterControls.toggles) {
            for (on in listOf(true, false)) {
                assertTrue("${toggle.key} $on ready", DeviceFlow.allowedTx(toggle.frame(on), owner, true))
                assertFalse("${toggle.key} $on not ready", DeviceFlow.allowedTx(toggle.frame(on), owner, false))
            }
        }
    }
    @Test fun valueControlsAreAllowedOnlyWhenReady() {
        val owner = Protocol.ownerFrame(1)
        for (frame in ScooterControls.ENERGY_VALUES.map { ScooterControls.energyFrame(it) } +
            ScooterControls.START_SPEED_RANGE.map { ScooterControls.startSpeedFrame(it) }) {
            assertTrue(DeviceFlow.allowedTx(frame, owner, true))
            assertFalse(DeviceFlow.allowedTx(frame, owner, false))
        }
        // start speed region clamp is enforced in the model, not the allowlist
        assertEquals(3, ScooterControls.minStartSpeed("SE"))
        assertEquals(0, ScooterControls.minStartSpeed("US"))
    }
    @Test fun readOnlyIdentityReadsWorkWithoutAuthButSettingsDoNot() {
        val owner = Protocol.ownerFrame(1)
        for (command in listOf(0x72, 0x73, 0x74, 0x92)) {
            assertFalse(DeviceFlow.allowedTx(Protocol.command(command), owner, false, false))
            assertTrue(DeviceFlow.allowedTx(Protocol.command(command), owner, false, true))
        }
        // A read-only session must never be able to send a control write.
        assertFalse(DeviceFlow.allowedTx(ScooterControls.byKey.getValue("tailLight").frame(true), owner, false, true))
    }
    private fun reply(command: Int, data: String): ByteArray {
        val payload = data.toByteArray(Charsets.US_ASCII)
        val body = byteArrayOf(0x55, 0xAA.toByte(), 0, command.toByte(), (payload.size + 1).toByte(), 0) + payload
        return body + byteArrayOf((body.sumOf { it.toInt() and 255 } and 255).toByte(), 0xFE.toByte(), 0xFD.toByte())
    }
    @Test fun capturedFirmwareAndSyntheticSerialDecode() {
        val real = unhex("55 AA 00 73 0D 00 32 30 35 38 30 30 30 35 32 30 30 31 D6 FE FD")
        assertEquals(listOf("2.0.5.8", "0.0.0.5", "2.0.0.1"), Protocol.versions(real))
        assertEquals(listOf("2.0.5.8", null, null), Protocol.versions(reply(0x73, "2058")))
        val serial = Protocol.serial(reply(0x74, "12345678SE1234567EXTRA"))
        assertEquals(17, serial.length)
        assertEquals("SE", serial.substring(8, 10))
    }
    @Test fun disconnectedScreenContainsNoOldDeviceData() {
        val screen = Screen()
        assertNull(screen.device); assertNull(screen.settings); assertNull(screen.serial)
        assertNull(screen.bldc); assertNull(screen.lastResponse); assertNull(screen.backup)
        assertFalse(screen.authenticated); assertFalse(screen.connected)
        assertTrue(screen.devices.isEmpty())
    }
}
