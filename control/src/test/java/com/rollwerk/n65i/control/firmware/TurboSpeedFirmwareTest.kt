package com.rollwerk.n65i.control.firmware

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline build + dry run of the TurboSpeed image against the bundled stock (6001 / 2058 / 0005).
 * Proves the patch is correctly constructed and frames cleanly. It cannot prove the controller
 * accepts it or that the road speed actually rises — that needs a test ride.
 */
class TurboSpeedFirmwareTest {
    private fun stock(): ByteArray = File("src/main/assets/firmware/bldc_0005_stock.bin").readBytes()
    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    @Test fun capZAnchorsHold() {
        assertEquals(397, TurboSpeedFirmware.capZ(20))
        assertEquals(497, TurboSpeedFirmware.capZ(25))
        assertEquals(795, TurboSpeedFirmware.capZ(40))
    }

    @Test fun buildMakesExactlyTheThreeEditsAndReseals() {
        val stock = stock()
        val r = TurboSpeedFirmware.build(stock, 25)
        // (1) Turbo LDRH r0,[r3] -> MOV r0,r3
        assertEquals(0x18, r.bytes[0x36D0].toInt() and 0xFF)
        assertEquals(0x46, r.bytes[0x36D1].toInt() and 0xFF)
        // (2) pool word becomes capZ(25) = 497
        assertEquals(497, le32(r.bytes, 0x3964))
        // (3) meter clamp STRH r2,[r4,#0x16] -> NOP
        assertEquals(0x00, r.bytes[0x36FE].toInt() and 0xFF)
        assertEquals(0xBF, r.bytes[0x36FF].toInt() and 0xFF)
        assertEquals(TurboSpeedFirmware.IMAGE_SIZE, r.bytes.size)
        assertEquals(TurboSpeedFirmware.TURBOSPEED25_SHA256, r.sha256)

        val allow = (setOf(0x13, 0x14, 0xB0, 0xB1, 0x36D0, 0x36D1, 0x36FE, 0x36FF) + (0x3964..0x3967)).toSet()
        assertTrue("changed ${r.changedOffsets.map { it.toString(16) }}", r.changedOffsets.all { it in allow })
    }

    @Test fun ecoDriveAndMode4LeftExactlyStock() {
        val stock = stock()
        val r = TurboSpeedFirmware.build(stock, 25)
        for (o in intArrayOf(0x36C6, 0x36C7, 0x36CA, 0x36CB, 0x36CC, 0x36CD, 0x36D4, 0x36D5)) {
            assertEquals("off 0x${o.toString(16)}", stock[o], r.bytes[o])
        }
        for (o in 0x3968..0x396F) assertEquals("off 0x${o.toString(16)}", stock[o], r.bytes[o])
        for (o in 0x3978..0x397B) assertEquals("off 0x${o.toString(16)}", stock[o], r.bytes[o])
    }

    @Test fun differentTargetChangesHash() {
        val stock = stock()
        assertNotEquals(TurboSpeedFirmware.build(stock, 25).sha256, TurboSpeedFirmware.build(stock, 30).sha256)
    }

    @Test fun corruptedStockIsRejected() {
        val bad = stock().copyOf()
        bad[0x36FE] = 0x00
        var threw = false
        try { TurboSpeedFirmware.build(bad, 25) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("corrupted stock must be rejected", threw)
    }

    @Test fun dryRunPassesForTwentyFive() {
        val r = TurboSpeedDryRun.run(stock(), 25)
        assertTrue(r.message, r.ok)
        assertEquals(2, r.targetComponent)
        assertEquals(47232, r.imageSize)
        assertEquals(369, r.blockCount)
        assertTrue(r.reconstructedMatches)
        assertEquals(TurboSpeedFirmware.TURBOSPEED25_SHA256, r.candidateSha256)
    }

    @Test fun dryRunRefusesWrongComponent() {
        val r = TurboSpeedDryRun.run(stock(), 25, targetComponent = 1)
        assertTrue(!r.ok)
        assertTrue(r.message.contains("wrong component"))
    }

    @Test fun dryRunLeavesStockArrayUntouched() {
        val stock = stock()
        val before = TurboSpeedFirmware.sha256Hex(stock)
        TurboSpeedDryRun.run(stock, 25)
        assertEquals(before, TurboSpeedFirmware.sha256Hex(stock))
        assertEquals(TurboSpeedFirmware.STOCK_SHA256, before)
    }
}
