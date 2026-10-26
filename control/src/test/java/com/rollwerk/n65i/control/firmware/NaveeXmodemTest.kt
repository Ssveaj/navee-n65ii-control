package com.rollwerk.n65i.control.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** XMODEM-128 framing shared by the DFU engine and the dry run. */
class NaveeXmodemTest {

    @Test fun crc16KnownVector() {
        // CRC-16/XMODEM of "123456789" = 0x31C3.
        assertEquals(0x31C3, NaveeXmodem.crc16Xmodem("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test fun blockCountAndSeqWrapSkipsZero() {
        assertEquals(369, NaveeXmodem.blockCount(47232))      // exact, no padding
        assertEquals(1, NaveeXmodem.seqForIndex(0))
        assertEquals(255, NaveeXmodem.seqForIndex(254))
        assertEquals(1, NaveeXmodem.seqForIndex(255))         // 255 -> wrap to 1 (skips 0)
        assertEquals(2, NaveeXmodem.seqForIndex(256))
        assertEquals(114, NaveeXmodem.seqForIndex(368))       // last block of the real image
    }

    @Test fun buildBlockShapeAndCrc() {
        val img = ByteArray(300) { (it and 0xFF).toByte() }
        val f = NaveeXmodem.buildBlock(img, 0, NaveeXmodem.seqForIndex(0))
        assertEquals(NaveeXmodem.FRAME_SIZE, f.size)
        assertEquals(NaveeXmodem.SOH, f[0].toInt() and 0xFF)
        assertEquals(1, f[1].toInt() and 0xFF)
        assertEquals(0xFE, f[2].toInt() and 0xFF)             // ~1
        val data = f.copyOfRange(3, 3 + NaveeXmodem.BLOCK)
        val crc = ((f[3 + NaveeXmodem.BLOCK].toInt() and 0xFF) shl 8) or (f[4 + NaveeXmodem.BLOCK].toInt() and 0xFF)
        assertEquals(NaveeXmodem.crc16Xmodem(data), crc)
    }

    @Test fun lastPartialBlockIsPaddedWith0x1A() {
        val img = ByteArray(130) { 0x55 }                     // 2 blocks, 2nd has 2 real bytes
        val f = NaveeXmodem.buildBlock(img, 1, NaveeXmodem.seqForIndex(1))
        assertEquals(0x55, f[3].toInt() and 0xFF)
        assertEquals(0x55, f[4].toInt() and 0xFF)
        assertEquals(NaveeXmodem.PAD, f[5].toInt() and 0xFF)  // padding begins
        assertTrue((5 until 3 + NaveeXmodem.BLOCK).all { (f[it].toInt() and 0xFF) == NaveeXmodem.PAD })
    }

    @Test fun imageSizeIsExactlyDivisibleBy128() {
        assertEquals(0, TurboSpeedFirmware.IMAGE_SIZE % NaveeXmodem.BLOCK)
        assertEquals(369, NaveeXmodem.blockCount(TurboSpeedFirmware.IMAGE_SIZE))
    }

    // ── Simulated receiver (from TurboSpeedDryRun) enforces the NAVEE transport rules ──
    @Test fun simulatedReceiverAcceptsGoodBlocksAndRejectsFaults() {
        val rx = SimulatedDfuReceiver(expectedTarget = 2)
        assertTrue(rx.dfuStart(2))
        val img = ByteArray(256) { (it and 0xFF).toByte() }
        assertTrue(rx.onBlock(NaveeXmodem.buildBlock(img, 0, 1)))
        // flip a data byte -> CRC mismatch -> NAK
        val broken = NaveeXmodem.buildBlock(img, 1, 2).copyOf()
        broken[10] = (broken[10].toInt() xor 0xFF).toByte()
        assertFalse(rx.onBlock(broken))
        assertTrue(rx.message.contains("CRC mismatch"))
    }

    @Test fun wrongComponentIsRefused() {
        val rx = SimulatedDfuReceiver(expectedTarget = 2)
        assertFalse(rx.dfuStart(1))
        assertTrue(rx.message.contains("wrong component"))
    }

    @Test fun outOfOrderSeqIsRejected() {
        val rx = SimulatedDfuReceiver(expectedTarget = 2)
        rx.dfuStart(2)
        val img = ByteArray(384) { 0x22 }
        assertTrue(rx.onBlock(NaveeXmodem.buildBlock(img, 0, 1)))
        // skip block 1, send block 2 (seq 3) -> unexpected seq
        assertFalse(rx.onBlock(NaveeXmodem.buildBlock(img, 2, NaveeXmodem.seqForIndex(2))))
        assertTrue(rx.message.contains("unexpected seq"))
    }
}
