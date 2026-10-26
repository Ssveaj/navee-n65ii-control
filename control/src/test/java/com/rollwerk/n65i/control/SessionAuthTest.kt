package com.rollwerk.n65i.control

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class SessionAuthTest {
    private val key = byteArrayOf(0x44, 0x6D, 0x10, 0x72, 0x6D, 0xBE.toByte(), 0x05, 0xF6.toByte(),
        0x62, 0xDF.toByte(), 0xAA.toByte(), 0xF0.toByte(), 0x13, 0x27, 0x30, 0x3F)

    private fun frame(cmd: Int, status: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf(0x55, 0xAA.toByte(), 0, cmd.toByte(), (data.size + 1).toByte(), status.toByte()) + data
        return body + byteArrayOf((body.sumOf { it.toInt() and 255 } and 255).toByte(), 0xFE.toByte(), 0xFD.toByte())
    }

    @Test fun initFrameIsAValid0x30SessionFrame() {
        val f = SessionAuth.initFrame(0x0102030405L)
        val r = Protocol.response(f) // valid envelope/length/checksum
        assertEquals(0x30, r.command)
        assertEquals(9, r.data.size + 1)       // status byte + 8 payload = 9 payload bytes
        assertEquals(1, r.status)              // payload[0] = key index 1 (parsed as "status" slot)
    }

    @Test fun shortSuccessIsComplete() {
        assertEquals(SessionAuth.Kind.COMPLETE, SessionAuth.classify(Protocol.response(frame(0x30, 0, byteArrayOf()))))
    }

    @Test fun nonZeroStatusIsRejected() {
        assertEquals(SessionAuth.Kind.REJECTED, SessionAuth.classify(Protocol.response(frame(0x30, 1, ByteArray(16)))))
    }

    @Test fun aesChallengeProducesEncryptedResponse() {
        val challenge = ByteArray(16) { (it + 1).toByte() }
        val r = Protocol.response(frame(0x30, 0, challenge))   // 16-byte challenge, AES mode
        assertEquals(SessionAuth.Kind.CHALLENGE, SessionAuth.classify(r))
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        assertArrayEquals(cipher.doFinal(challenge), SessionAuth.challengeResponse(r))
    }

    @Test fun xorChallengeWhenModeZero() {
        val challenge = ByteArray(16) { (it * 7).toByte() }
        val r = Protocol.response(frame(0x30, 0, byteArrayOf(0) + challenge))  // mode byte 0 -> XOR
        assertEquals(SessionAuth.Kind.CHALLENGE, SessionAuth.classify(r))
        val expected = ByteArray(16) { i -> (challenge[i].toInt() xor key[i].toInt()).toByte() }
        assertArrayEquals(expected, SessionAuth.challengeResponse(r))
    }

    @Test fun parse31AcceptsStatusZero() {
        assertEquals(true, SessionAuth.parse31(Protocol.response(frame(0x31, 0, byteArrayOf()))))
        assertEquals(false, SessionAuth.parse31(Protocol.response(frame(0x31, 1, byteArrayOf()))))
        assertNull(SessionAuth.parse31(Protocol.response(frame(0x30, 0, byteArrayOf()))))
    }

    @Test fun responseFrameIsValid0x31() {
        val resp = ByteArray(16) { it.toByte() }
        val r = Protocol.response(SessionAuth.responseFrame(resp))
        assertEquals(0x31, r.command)
    }
}
