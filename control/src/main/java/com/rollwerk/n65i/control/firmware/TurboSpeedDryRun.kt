package com.rollwerk.n65i.control.firmware

/**
 * Offline dry run: builds the TurboSpeed image, frames it through the same [NaveeXmodem] logic the
 * real engine uses and feeds it to a [SimulatedDfuReceiver]. Never touches BLE.
 */
internal object TurboSpeedDryRun {

    data class Report(
        val ok: Boolean,
        val targetComponent: Int,
        val imageSize: Int,
        val blockCount: Int,
        val reconstructedMatches: Boolean,
        val candidateSha256: String,
        val message: String
    )

    /** Target component is fixed at 2 (BLDC); anything else is refused like the real receiver would. */
    fun run(stock: ByteArray, speedKmh: Int, targetComponent: Int = 2): Report {
        val built = TurboSpeedFirmware.build(stock, speedKmh) // throws on bad/altered stock
        val image = built.bytes
        val n = NaveeXmodem.blockCount(image.size)
        val rx = SimulatedDfuReceiver(expectedTarget = 2)
        fun fail(message: String) = Report(false, targetComponent, image.size, n, false, built.sha256, message)

        if (!rx.dfuStart(targetComponent)) return fail(rx.message)
        for (i in 0 until n) {
            if (!rx.onBlock(NaveeXmodem.buildBlock(image, i, NaveeXmodem.seqForIndex(i)))) return fail("block $i rejected: ${rx.message}")
        }
        val eot = rx.onEot()
        val matches = rx.reconstructed()?.contentEquals(image) == true
        return Report(eot && matches, targetComponent, image.size, n, matches, built.sha256,
            if (eot && matches) "$n blocks accepted, EOT accepted, image reassembled byte-exact" else rx.message)
    }
}

/**
 * Minimal model of the scooter's XMODEM-128 receive side: component, frame length, SOH, seq/~seq,
 * sequence order, per-block CRC-16 and EOT. It cannot model the bootloader's own acceptance checks.
 */
internal class SimulatedDfuReceiver(private val expectedTarget: Int) {
    var message: String = ""; private set
    private var started = false
    private var aborted = false
    private var expectedSeq = 1
    private val out = ArrayList<Byte>(48 * 1024)

    fun dfuStart(target: Int): Boolean {
        if (target != expectedTarget) { message = "wrong component $target (expected $expectedTarget)"; aborted = true; return false }
        started = true
        message = "dfu_start $target"
        return true
    }

    /** @return true = ACK (block accepted), false = NAK/reject (bad CRC/seq/frame). */
    fun onBlock(frame: ByteArray): Boolean {
        if (!started || aborted) { message = "block before dfu_start or after abort"; return false }
        if (frame.size != NaveeXmodem.FRAME_SIZE) { message = "bad frame length ${frame.size}"; aborted = true; return false }
        if ((frame[0].toInt() and 0xFF) != NaveeXmodem.SOH) { message = "bad SOH"; aborted = true; return false }
        val seq = frame[1].toInt() and 0xFF
        if ((seq xor (frame[2].toInt() and 0xFF)) != 0xFF) { message = "seq/~seq mismatch"; return false }
        if (seq != expectedSeq) { message = "unexpected seq $seq (expected $expectedSeq)"; return false }
        val data = frame.copyOfRange(3, 3 + NaveeXmodem.BLOCK)
        val crc = ((frame[3 + NaveeXmodem.BLOCK].toInt() and 0xFF) shl 8) or (frame[4 + NaveeXmodem.BLOCK].toInt() and 0xFF)
        if (NaveeXmodem.crc16Xmodem(data) != crc) { message = "block CRC mismatch at seq $seq"; return false }
        data.forEach { out.add(it) }
        expectedSeq = (expectedSeq + 1) and 0xFF
        if (expectedSeq == 0) expectedSeq = 1
        return true
    }

    fun onEot(): Boolean {
        if (!started || aborted) { message = "EOT before start or after abort"; return false }
        message = "dfu_ok (simulated): ${out.size} bytes reassembled"
        return true
    }

    fun reconstructed(): ByteArray? = if (started && !aborted) out.toByteArray() else null
}
