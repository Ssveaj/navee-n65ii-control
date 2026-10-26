package com.rollwerk.n65i.control

import java.nio.ByteBuffer

fun ByteArray.hex() = joinToString(" ") { "%02X".format(it.toInt() and 255) }
fun unhex(text: String): ByteArray =
    text.trim().takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.map { it.toInt(16).toByte() }?.toByteArray() ?: ByteArray(0)

/** NAVEE 55 AA frames on d0ff b002/b003. Parameter meanings: PARAMETERS.md. */
object Protocol {
    data class Response(val command: Int, val status: Int, val data: ByteArray)
    data class Settings(val rawHex: String, val mode: Int, val limiter: Int, val maximum: Int)

    private fun finish(body: ByteArray) = body + byteArrayOf((body.sumOf { it.toInt() and 255 } and 255).toByte(), 0xFE.toByte(), 0xFD.toByte())

    /** Request frame. Only the commands this app uses can be built. */
    fun command(command: Int, data: ByteArray? = null): ByteArray {
        // reads/settings + the normal controls (lock, lights, cruise, TCS, eco, unit, horn/key, regen, start speed)
        require(command in listOf(0x6F, 0x70, 0x72, 0x73, 0x74, 0x92,
            0x51, 0x52, 0x53, 0x54, 0x55, 0x57, 0x5F, 0x60, 0x61, 0x6A, 0x6D))
        return finish(byteArrayOf(0x55, 0xAA.toByte(), 0, command.toByte()) +
            (data?.let { byteArrayOf(it.size.toByte()) + it } ?: byteArrayOf()))
    }

    fun response(raw: ByteArray): Response {
        require(raw.size >= 9 && raw[0] == 0x55.toByte() && raw[1] == 0xAA.toByte() && raw[2] == 0.toByte()) { "Unsupported response header" }
        require(raw[raw.size - 2] == 0xFE.toByte() && raw.last() == 0xFD.toByte()) { "Response trailer" }
        require((raw[4].toInt() and 255) + 8 == raw.size) { "Response length" }
        require((raw.dropLast(3).sumOf { it.toInt() and 255 } and 255) == (raw[raw.size - 3].toInt() and 255)) { "Response checksum" }
        return Response(raw[3].toInt() and 255, raw[5].toInt() and 255, raw.copyOfRange(6, raw.size - 3))
    }

    /** 0x70: 36 data bytes. Mode = DATA[1], limiter = DATA[20], requested maximum = DATA[25]. */
    fun settings(raw: ByteArray): Settings {
        val r = response(raw)
        require(r.command == 0x70 && r.status == 0 && r.data.size == 36) { "Expected successful 36-byte 0x70 settings data" }
        return Settings(raw.hex(), r.data[1].toInt() and 255, r.data[20].toInt() and 255, r.data[25].toInt() and 255)
    }

    /** 0x72: battery state of charge in DATA[1]. */
    fun battery(raw: ByteArray): Int? {
        val r = response(raw)
        return if (r.command == 0x72 && r.status == 0 && r.data.size >= 2) r.data[1].toInt() and 255 else null
    }

    /** 0x73: dashboard, BLDC and BMS versions, four ASCII digits each. */
    fun versions(raw: ByteArray): List<String?> {
        val r = response(raw)
        require(r.command == 0x73 && r.status == 0)
        return (0..2).map { index ->
            val start = index * 4
            if (r.data.size >= start + 4) r.data.copyOfRange(start, start + 4)
                .map { (it.toInt() and 255).toChar().toString() }.joinToString(".") else null
        }
    }

    /** 0x74: 17-character serial. Characters 8–9 are the region (e.g. SE). */
    fun serial(raw: ByteArray): String {
        val r = response(raw)
        require(r.command == 0x74 && r.status == 0)
        return r.data.take(17).toByteArray().toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    /** 0x6F subcommand 6: clock sync with big-endian epoch seconds, sent right after authentication. */
    fun timeInitialization(epochSeconds: Long) = command(0x6F, byteArrayOf(6) + ByteBuffer.allocate(8).putLong(epochSeconds).array().copyOfRange(4, 8))

    /**
     * Owner-session 0x30 frame: [selector, bound, id(6), 0]. In production the owner frame is read
     * verbatim from BuildConfig.OWNER_AUTH; this builder exists so tests can construct a valid one.
     */
    fun ownerFrame(id: Long, selector: Int = 1, bound: Boolean = false): ByteArray {
        require(id > 0 && selector in 0..4)
        val idBytes = ByteArray(6) { i -> (id ushr (40 - 8 * i)).toByte() }
        if (idBytes[0] <= 0) idBytes[0] = 0x85.toByte()
        return finish(byteArrayOf(0x55, 0xAA.toByte(), 0, 0x30, 9, selector.toByte(), if (bound) 1 else 0) + idBytes + byteArrayOf(0))
    }
}

interface Exchange { suspend fun exchange(bytes: ByteArray, responseCommand: Int): ByteArray }

/** Session start: owner 0x30 frame → status 0 → 0x6F/06 clock sync → first 0x70 settings read. */
class Initialization(private val transport: Exchange) {
    var authenticated = false; private set
    suspend fun run(ownerFrame: ByteArray, epochSeconds: Long): Protocol.Settings {
        authenticated = false
        require(ownerFrame.size == 17 && ownerFrame[3] == 0x30.toByte() && ownerFrame[4] == 9.toByte()) { "Invalid owner-session construction" }
        Protocol.response(ownerFrame) // Same envelope/length/checksum check as a response.
        val auth = Protocol.response(transport.exchange(ownerFrame.copyOf(), 0x30))
        require(auth.command == 0x30 && auth.status == 0) { "Authentication rejected" }
        require(auth.data.isEmpty()) { "Challenge received: no challenge provider; stopped without 0x31" }
        val init = Protocol.response(transport.exchange(Protocol.timeInitialization(epochSeconds), 0x6F))
        require(init.command == 0x6F && init.status == 0) { "Initialization rejected" }
        authenticated = true
        return Protocol.settings(transport.exchange(Protocol.command(0x70), 0x70))
    }
}

data class DeviceIdentity(val address: String, val pid: String?, val firmware: String?, val controller: String?, val region: String?)
data class Backup(val identity: DeviceIdentity, val timestamp: Long, val settings: Protocol.Settings)
