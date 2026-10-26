package com.rollwerk.n65i.control.firmware

/**
 * XMODEM-128 framing shared by the DFU engine and the offline dry run, so the dry run packages
 * bytes through exactly the same block/CRC logic that goes on the wire. No BLE, no Android.
 */
internal object NaveeXmodem {
    const val SOH = 0x01
    const val BLOCK = 128
    const val PAD = 0x1A
    const val FRAME_SIZE = 3 + BLOCK + 2   // SOH, seq, ~seq, 128 data, crcHi, crcLo = 133

    fun blockCount(imageSize: Int): Int = (imageSize + BLOCK - 1) / BLOCK

    /**
     * Sequence byte for block i. NAVEE wraps 1..255 → 1 (it skips 0), unlike textbook XMODEM
     * (1..255 → 0 → 1). The rules diverge from block index 255; the 369-block BLDC image reaches it.
     * Do not "correct" this to standard XMODEM.
     */
    fun seqForIndex(blockIndex: Int): Int = (blockIndex % 255) + 1

    fun crc16Xmodem(data: ByteArray, start: Int = 0, end: Int = data.size): Int {
        require(start >= 0 && end <= data.size && start <= end)
        var crc = 0
        for (i in start until end) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** Build the 133-byte SOH frame for [blockIndex] of [image] with sequence [seq]. */
    fun buildBlock(image: ByteArray, blockIndex: Int, seq: Int): ByteArray {
        val data = ByteArray(BLOCK) { PAD.toByte() }
        val offset = blockIndex * BLOCK
        val n = (image.size - offset).coerceIn(0, BLOCK)
        if (n > 0) image.copyInto(data, 0, offset, offset + n)
        val crc = crc16Xmodem(data)
        val frame = ByteArray(FRAME_SIZE)
        frame[0] = SOH.toByte()
        frame[1] = (seq and 0xFF).toByte()
        frame[2] = (seq.inv() and 0xFF).toByte()
        data.copyInto(frame, 3)
        frame[3 + BLOCK] = ((crc ushr 8) and 0xFF).toByte()
        frame[4 + BLOCK] = (crc and 0xFF).toByte()
        return frame
    }
}
