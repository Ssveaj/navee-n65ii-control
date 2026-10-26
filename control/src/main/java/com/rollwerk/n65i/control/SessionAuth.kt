package com.rollwerk.n65i.control

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * NAVEE OEM-style 0x30/0x31 session authentication (anonymous session id + challenge/response).
 *
 * Some bound NAVEE controllers close the BLE link a few seconds after connect unless this
 * challenge/response session is completed. This is the same key and transform the DFU engine uses,
 * so if the DFU can flash, this auth keeps a normal session alive. No captured owner identity.
 */
object SessionAuth {
    // Key index 1 — identical to NaveeDfuEngine.KEY.
    private const val KEY_INDEX = 1
    private val KEY = byteArrayOf(0x44, 0x6D, 0x10, 0x72, 0x6D, 0xBE.toByte(), 0x05, 0xF6.toByte(),
        0x62, 0xDF.toByte(), 0xAA.toByte(), 0xF0.toByte(), 0x13, 0x27, 0x30, 0x3F)
    private val random = SecureRandom()

    enum class Kind { CHALLENGE, COMPLETE, REJECTED, NONE }

    fun newSessionId(): Long = random.nextLong().ushr(1).coerceAtLeast(1L)

    private fun finish(body: ByteArray) =
        body + byteArrayOf((body.sumOf { it.toInt() and 255 } and 255).toByte(), 0xFE.toByte(), 0xFD.toByte())

    /** 0x30 session init: [keyIndex, shareAccount, id(6), 0]. */
    fun initFrame(sessionId: Long, shareAccount: Boolean = false): ByteArray {
        val id = sixBytes(sessionId.coerceAtLeast(1L))
        val payload = ByteArray(9).also {
            it[0] = KEY_INDEX.toByte()
            it[1] = if (shareAccount) 1 else 0
            id.copyInto(it, 2)
            it[8] = 0
        }
        return finish(byteArrayOf(0x55, 0xAA.toByte(), 0, 0x30, payload.size.toByte()) + payload)
    }

    /** 0x31 challenge response frame. */
    fun responseFrame(response: ByteArray): ByteArray {
        require(response.size == 16)
        return finish(byteArrayOf(0x55, 0xAA.toByte(), 0, 0x31, response.size.toByte()) + response)
    }

    fun classify(r: Protocol.Response): Kind = when {
        r.command != 0x30 -> Kind.NONE
        r.status != 0 -> Kind.REJECTED
        r.data.isEmpty() -> Kind.COMPLETE
        else -> Kind.CHALLENGE
    }

    /** The 16-byte response to a 0x30 challenge, or null if the challenge is malformed. */
    fun challengeResponse(r: Protocol.Response): ByteArray? {
        if (r.command != 0x30 || r.status != 0) return null
        val data = r.data
        val challenge = ByteArray(16)
        val useXor: Boolean
        if (data.size > 16) {
            if (data.size < 17) return null
            data.copyInto(challenge, 0, 1, 17)
            useXor = (data[0].toInt() and 0xFF) == 0
        } else {
            if (data.size < 16) return null
            data.copyInto(challenge, 0, 0, 16)
            useXor = false
        }
        return if (useXor) ByteArray(16) { i -> ((challenge[i].toInt() and 0xFF) xor (KEY[i].toInt() and 0xFF)).toByte() }
        else runCatching {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"))
            cipher.doFinal(challenge)
        }.getOrNull()
    }

    /** 0x31 reply: true = accepted, false = rejected, null = not a 0x31 reply. */
    fun parse31(r: Protocol.Response): Boolean? = if (r.command == 0x31) r.status == 0 else null

    private fun sixBytes(raw: Long): ByteArray {
        var value = raw and 0xFFFFFFFFFFFFL
        if (value == 0L) value = 1L
        val out = ByteArray(6)
        for (i in 5 downTo 0) { out[i] = (value and 0xFF).toByte(); value = value ushr 8 }
        val first = out[0].toInt() and 0xFF
        if (first == 0 || first >= 0x80) out[0] = 0x88.toByte()
        return out
    }
}
