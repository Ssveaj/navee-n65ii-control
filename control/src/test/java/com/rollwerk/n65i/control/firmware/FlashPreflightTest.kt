package com.rollwerk.n65i.control.firmware

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flash gates must match the reference sportPreflight()/recoveryPreflight() behaviour. */
class FlashPreflightTest {
    private val stock = File("src/main/assets/firmware/bldc_0005_stock.bin").readBytes()

    private fun ready() = FlashSnapshot(
        connected = true, subscribed = true, authenticated = true, dfuActive = false,
        serialPid = "2509", advertisedPid = 25092, dashboard = "2.0.5.8", bldc = "0.0.0.5",
        mtu = 148, battery = 80, speedKmh = 0.0, drivingStatus = 0, stock = stock,
    )

    @Test fun bundledStockIsTheExactReferenceImage() {
        assertEquals(TurboSpeedFirmware.IMAGE_SIZE, stock.size)
        assertEquals(TurboSpeedFirmware.STOCK_SHA256, TurboSpeedFirmware.sha256Hex(stock))
    }

    @Test fun pinnedSport25ShaIsReproducedFromStock() {
        val built = TurboSpeedFirmware.build(stock, TurboSpeedFirmware.INSTALL_KMH)
        assertEquals(TurboSpeedFirmware.TURBOSPEED25_SHA256, built.sha256)
    }

    @Test fun readyScooterPassesBothGates() {
        assertNull(FlashPreflight.install(ready()))
        assertNull(FlashPreflight.recovery(ready()))
    }

    @Test fun installDoesNotRequireSessionAuth() {
        // The DFU engine authenticates itself; install gates on identity, not the normal session auth.
        assertNull(FlashPreflight.install(ready().copy(authenticated = false)))
    }

    @Test fun installAllowsUnknownSpeedButBlocksMovement() {
        // An idle/just-booted scooter doesn't push 0x92; unknown speed must not block the flash.
        assertNull(FlashPreflight.install(ready().copy(speedKmh = null, drivingStatus = null)))
        assertNotNull(FlashPreflight.install(ready().copy(speedKmh = 3.2)))
    }

    @Test fun installBlocksEveryUnsafeCondition() {
        val blocked = listOf(
            ready().copy(connected = false),
            ready().copy(subscribed = false),
            ready().copy(dfuActive = true),
            ready().copy(serialPid = "2345", advertisedPid = 23451),
            ready().copy(dashboard = "2.0.5.9"),
            ready().copy(bldc = "0.0.0.6"),
            ready().copy(bldc = null),
            ready().copy(mtu = 23),
            ready().copy(battery = null),
            ready().copy(battery = 49),
            ready().copy(speedKmh = 3.2),
            ready().copy(drivingStatus = 1),
            ready().copy(stock = null),
            ready().copy(stock = stock.copyOf().also { it[0x36FE] = 0 }),
        )
        blocked.forEach { assertNotNull("should block: $it", FlashPreflight.install(it)) }
    }

    @Test fun recoveryOnlyNeedsMeterIdentityAndSurvivesDeadBldc() {
        // After a bad flash: no BLDC version, no auth, no fresh speed — recovery must still be allowed.
        assertNull(FlashPreflight.recovery(ready().copy(bldc = null, authenticated = false, speedKmh = null)))
        assertNotNull(FlashPreflight.recovery(ready().copy(dashboard = "2.0.5.9")))
        assertNotNull(FlashPreflight.recovery(ready().copy(battery = 10)))
        assertNotNull(FlashPreflight.recovery(ready().copy(mtu = 100)))
        assertNotNull(FlashPreflight.recovery(ready().copy(speedKmh = 5.0)))
    }

    @Test fun pidAcceptsSerialOrAdvertisedIdentity() {
        assertTrue(FlashPreflight.pidOk(ready().copy(advertisedPid = null)))
        assertTrue(FlashPreflight.pidOk(ready().copy(serialPid = null)))
        assertEquals("2509", ScooterIdentity.pidFromSerial("N2509 2SE0000000"))
        assertNull(ScooterIdentity.pidFromSerial(null))
    }

    @Test fun lastFlashRecordKnowsWhenSport25IsInstalled() {
        assertTrue(FlashRecord(FlashKind.TURBOSPEED, "x", 1, true, "").turboSpeedInstalled)
        assertTrue(!FlashRecord(FlashKind.TURBOSPEED, "x", 1, false, "").turboSpeedInstalled)
        assertTrue(!FlashRecord(FlashKind.RECOVERY, "x", 1, true, "").turboSpeedInstalled)
    }
}
