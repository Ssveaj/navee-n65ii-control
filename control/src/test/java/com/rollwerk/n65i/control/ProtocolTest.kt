package com.rollwerk.n65i.control

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val real = unhex("55 AA 00 70 25 00 01 05 00 00 00 00 00 01 00 00 00 01 01 04 00 01 00 00 00 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 A5 FE FD")
    private fun reply(command: Int, status: Int = 0, data: ByteArray = byteArrayOf()): ByteArray {
        val body = byteArrayOf(0x55, 0xAA.toByte(), 0, command.toByte(), (data.size + 1).toByte(), status.toByte()) + data
        return body + byteArrayOf((body.sumOf { it.toInt() and 255 } and 255).toByte(), 0xFE.toByte(), 0xFD.toByte())
    }
    private class Transport(val answers: MutableList<ByteArray>) : Exchange {
        val sent = mutableListOf<ByteArray>()
        override suspend fun exchange(bytes: ByteArray, responseCommand: Int): ByteArray { sent += bytes; return answers.removeAt(0) }
    }

    @Test fun realSettingsParse() {
        val s = Protocol.settings(real)
        assertEquals(5, s.mode); assertEquals(0, s.limiter); assertEquals(0, s.maximum)
        assertEquals("55 AA 00 70 6F FE FD", Protocol.command(0x70).hex())
        val bad = real.copyOf(); bad[31] = 40
        assertThrows(IllegalArgumentException::class.java) { Protocol.settings(bad) }
        assertThrows(IllegalArgumentException::class.java) { Protocol.settings(real.copyOf(44)) }
    }

    @Test fun shortAuthSuccessThenTimeThenSettings() = runBlocking {
        val t = Transport(mutableListOf(reply(0x30), reply(0x6F), real))
        val init = Initialization(t)
        val result = init.run(Protocol.ownerFrame(0x01020304), 0x01020304) // synthetic test id only
        assertEquals(listOf(0x30, 0x6F, 0x70), t.sent.map { it[3].toInt() and 255 })
        assertEquals("06 01 02 03 04", t.sent[1].copyOfRange(5, 10).hex())
        assertTrue(init.authenticated); assertEquals(0, result.maximum)
    }

    @Test fun ownerFrameMustBeValidAndChallengeNeverAdvances() = runBlocking {
        val owner = Protocol.ownerFrame(1)
        val t = Transport(mutableListOf(reply(0x30), reply(0x6F), real))
        Initialization(t).run(owner, 123)
        assertArrayEquals(owner, t.sent.first())

        val bad = owner.copyOf(); bad[14] = (bad[14].toInt() xor 1).toByte() // break checksum
        val invalid = Transport(mutableListOf())
        assertTrue(runCatching { Initialization(invalid).run(bad, 123) }.isFailure)
        assertTrue(invalid.sent.isEmpty())

        // A 0x30 challenge payload (non-empty) has no provider and must stop before 0x31.
        val challenge = Transport(mutableListOf(reply(0x30, data = byteArrayOf(1))))
        assertTrue(runCatching { Initialization(challenge).run(owner, 123) }.isFailure)
        assertEquals(1, challenge.sent.size)
    }

    @Test fun authRejectionNeverContinues() = runBlocking {
        for (answer in listOf(reply(0x30, 1), reply(0x30, data = byteArrayOf(1)))) {
            val t = Transport(mutableListOf(answer))
            val init = Initialization(t)
            assertTrue(runCatching { init.run(Protocol.ownerFrame(1), 123) }.isFailure)
            assertEquals(1, t.sent.size); assertFalse(init.authenticated)
        }
        val t = Transport(mutableListOf(reply(0x30), reply(0x6F, 1)))
        assertTrue(runCatching { Initialization(t).run(Protocol.ownerFrame(1), 123) }.isFailure)
        assertEquals(2, t.sent.size)
    }
}
