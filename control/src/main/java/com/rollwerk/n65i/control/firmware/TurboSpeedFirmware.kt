package com.rollwerk.n65i.control.firmware

import java.security.MessageDigest

/**
 * TurboSpeed firmware builder for the N65i II 6001 controller (BLDC 0.0.0.5).
 *
 * Three byte edits in code, then both CRC-16/XMODEM seals are regenerated:
 *   1) 0x36D0  18 88 (LDRH r0,[r3]) -> 18 46 (MOV r0,r3)  — Turbo (mode 3/5) uses the pool word directly.
 *   2) 0x3964  pointer 0x2000033A   -> capZ(km/h)          — that pool word becomes the Turbo cap.
 *   3) 0x36FE  E2 82 (STRH r2,[r4,#0x16]) -> 00 BF (NOP)   — remove the meter-limit clamp (0x20000242)
 *              that pulls the cap back down to the SE ceiling (~20 km/h).
 *
 * The clamp sits at the merge point every mode passes through, so a meter limit below a mode's own
 * cap no longer applies to ECO/DRIVE either. ECO/DRIVE caps themselves are unchanged. The
 * low-battery speed reduction runs on a separate path (SOC 0x200003D0, ramp at 0x3700+) and is kept.
 *
 * The builder fails closed on any stock byte/signature/hash mismatch, keeps the compressed .data
 * stream byte-identical after decompression and only lets the listed offsets change.
 */
internal object TurboSpeedFirmware {
    const val STOCK_SHA256 = "03c8c60163eeea4e49367edd1b54235d9f9fd0776a1eee84f93a4248d8561b02"
    const val IMAGE_SIZE = 47_232
    const val STOCK_ASSET = "firmware/bldc_0005_stock.bin"

    /** Default Turbo speed. */
    const val INSTALL_KMH = 25

    /**
     * Adjustable range offered in the UI. 20 = stock SE, 25 = EU, 40 = US — all values that appear
     * in the controller's own regional table, so the motor/battery handle them in some region.
     * Higher means longer braking distance, more component stress and (in Sweden) more illegal.
     */
    val SPEED_RANGE = 20..40

    /** Offline-verified SHA-256 of the TurboSpeed 25 image (used by tests and shown for 25). */
    const val TURBOSPEED25_SHA256 = "e586f95bf767ec71b1b9303852c56100d9b386d304a43a8d419f28d433c5c32d"

    private const val BODY_LEN = 0xB768
    private const val COMPRESSED_FILE_OFFSET = 0xB4D0
    private const val COMPRESSED_REGION_SIZE = 0x398
    private const val EXPECTED_COMPRESSED_USED = 0x397
    private const val DECOMPRESSED_SIZE = 0x8A4

    /** km/h -> controller cap units. Anchors: 20 -> 397, 25 -> 497, 40 -> 795. */
    fun capZ(speedKmh: Int): Int {
        require(speedKmh in 1..60) { "Speed must be 1..60 km/h" }
        return (((speedKmh * 199) + 5) / 10) - 1
    }

    data class Result(
        val speedKmh: Int,
        val capZ: Int,
        val bytes: ByteArray,
        val sha256: String,
        val primaryCrc: Int,
        val secondaryCrc: Int,
        val changedOffsets: List<Int>
    )

    fun build(stock: ByteArray, speedKmh: Int): Result {
        verifyStock(stock)
        val cap = capZ(speedKmh)
        val out = stock.copyOf()
        fun expect(off: Int, vararg values: Int) {
            values.forEachIndexed { i, v ->
                require((out[off + i].toInt() and 0xFF) == v) { "Stock signature mismatch @0x${(off + i).toString(16)}" }
            }
        }

        // Exact stock bytes for only what we touch — any firmware drift blocks the build.
        expect(0x36D0, 0x18, 0x88)
        require(readLe32(out, 0x3964) == 0x2000033A) { "Turbo literal pointer changed" }
        expect(0x36FE, 0xE2, 0x82)

        out[0x36D0] = 0x18; out[0x36D1] = 0x46
        writeLe32(out, 0x3964, cap)
        out[0x36FE] = 0x00; out[0x36FF] = 0xBF.toByte()

        reseal(out)
        verifySeals(out)
        require(versionAt80(out) == "0.0.0.5") { "Candidate version changed" }
        require(asciiAt(out, 0x90, 32) == "SZMC-ES-ZM-02831") { "Candidate signature changed" }
        require(readBe32(out, 0x84) == BODY_LEN) { "Candidate body length changed" }

        val stockDecoded = decompress(stock)
        val candidateDecoded = decompress(out)
        require(candidateDecoded.output.contentEquals(stockDecoded.output)) { "Patch changed scatterload .data" }
        require(candidateDecoded.consumed == EXPECTED_COMPRESSED_USED) { "Compressed stream consumption changed" }

        val mutable = setOf(0x13, 0x14, 0xB0, 0xB1, 0x36D0, 0x36D1, 0x36FE, 0x36FF) + (0x3964..0x3967)
        val diffs = stock.indices.filter { stock[it] != out[it] }
        require(diffs.all { it in mutable }) {
            "Patch changed unexpected bytes: ${diffs.filterNot { it in mutable }.joinToString { "0x${it.toString(16)}" }}"
        }
        return Result(speedKmh, cap, out, sha256Hex(out), readBe16(out, 0xB0), readBe16(out, 0x13), diffs)
    }

    private data class Decoded(val output: ByteArray, val consumed: Int)

    private fun verifyStock(stock: ByteArray) {
        require(stock.size == IMAGE_SIZE) { "Stock size ${stock.size} != $IMAGE_SIZE" }
        require(sha256Hex(stock) == STOCK_SHA256) { "Stock SHA-256 mismatch" }
        require(versionAt80(stock) == "0.0.0.5") { "Stock version is not 0.0.0.5" }
        require(asciiAt(stock, 0x90, 32) == "SZMC-ES-ZM-02831") { "Stock controller signature mismatch" }
        require(readBe32(stock, 0x84) == BODY_LEN) { "Stock body length mismatch" }
        verifySeals(stock)
    }

    private fun verifySeals(bytes: ByteArray) {
        val len = readBe32(bytes, 0x84)
        require(0x100 + len <= bytes.size) { "Body range outside image" }
        require(readBe16(bytes, 0xB0) == NaveeXmodem.crc16Xmodem(bytes, 0x100, 0x100 + len)) { "Primary CRC mismatch" }
        require(readBe16(bytes, 0x13) == NaveeXmodem.crc16Xmodem(bytes, 0x80, bytes.size)) { "Secondary CRC mismatch" }
    }

    private fun reseal(bytes: ByteArray) {
        val len = readBe32(bytes, 0x84)
        writeBe16(bytes, 0xB0, NaveeXmodem.crc16Xmodem(bytes, 0x100, 0x100 + len))
        writeBe16(bytes, 0x13, NaveeXmodem.crc16Xmodem(bytes, 0x80, bytes.size))
    }

    /** Decodes the startup scatterload (.data) stream, to prove the patch leaves it untouched. */
    private fun decompress(image: ByteArray): Decoded {
        val source = image.copyOfRange(COMPRESSED_FILE_OFFSET, COMPRESSED_FILE_OFFSET + COMPRESSED_REGION_SIZE)
        val out = ByteArray(DECOMPRESSED_SIZE)
        var src = 0
        var dst = 0
        while (dst < out.size) {
            require(src < source.size) { "Compressed stream exhausted" }
            val control = source[src++].toInt() and 0xFF
            var literalsField = control and 7
            if (literalsField == 0) {
                require(src < source.size) { "Literal extension missing" }
                literalsField = source[src++].toInt() and 0xFF
            }
            var copyField = control ushr 4
            if (copyField == 0) {
                require(src < source.size) { "Copy extension missing" }
                copyField = source[src++].toInt() and 0xFF
            }
            val literalCount = literalsField - 1
            require(literalCount >= 0) { "Invalid literal field" }
            require(src + literalCount <= source.size && dst + literalCount <= out.size) { "Literal range outside stream/output" }
            source.copyInto(out, dst, src, src + literalCount)
            src += literalCount
            dst += literalCount

            if ((control and 0x08) != 0) {
                require(src < source.size) { "Back-reference distance missing" }
                val distance = source[src++].toInt() and 0xFF
                require(distance in 1..dst) { "Invalid back-reference distance $distance at dst=$dst" }
                val count = copyField + 2
                require(dst + count <= out.size) { "Back-reference overruns output" }
                repeat(count) { out[dst] = out[dst - distance]; dst++ }
            } else {
                require(dst + copyField <= out.size) { "Zero run overruns output" }
                repeat(copyField) { out[dst++] = 0 }
            }
        }
        return Decoded(out, src)
    }

    private fun readBe16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    private fun writeBe16(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 8) and 0xFF).toByte()
        b[off + 1] = (value and 0xFF).toByte()
    }
    private fun readBe32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
    fun readLe32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    private fun writeLe32(b: ByteArray, off: Int, value: Int) {
        for (i in 0..3) b[off + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
    private fun versionAt80(b: ByteArray): String = (0 until 4).joinToString(".") { (b[0x80 + it].toInt() and 0xFF).toString() }
    private fun asciiAt(b: ByteArray, off: Int, max: Int): String {
        val end = (off until minOf(off + max, b.size)).firstOrNull {
            (b[it].toInt() and 0xFF) == 0x00 || (b[it].toInt() and 0xFF) == 0xFF
        } ?: minOf(off + max, b.size)
        return b.copyOfRange(off, end).toString(Charsets.US_ASCII).trim()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
