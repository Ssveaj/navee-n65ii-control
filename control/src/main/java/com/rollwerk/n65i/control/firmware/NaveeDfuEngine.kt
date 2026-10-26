package com.rollwerk.n65i.control.firmware

import android.os.Handler
import android.os.Looper
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * NAVEE XMODEM-128 DFU engine for the controller (target 2, BLDC).
 *
 * Flow: 0x30 auth init -> "down dfu_start 2" -> "down ble_rand" -> "down ble_key" -> 'C'
 * -> XMODEM-128 blocks -> EOT -> "dfu_ok". The host owns BLE writes and UI/state reporting;
 * the engine never chooses a firmware image. A lost link always fails the transfer (no resume).
 */
internal class NaveeDfuEngine(private val host: Host) {
    interface Host {
        fun write(frame: ByteArray): Boolean
        fun setHighPriority(high: Boolean)
        fun progress(percent: Int, block: Int, count: Int, phase: String)
        fun log(line: String)
        fun state(state: String, message: String?)
        fun finished(success: Boolean)
    }

    private enum class Phase { IDLE, AUTH, DFU_START, BLE_RAND, WAIT_C, XMODEM, EOT, FINISH, DONE, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.IDLE
    private var running = false
    private var image = byteArrayOf()
    private var target = 2

    private val rx = ByteArray(1024)
    private var rxLen = 0

    private var blockCount = 0
    private var blockIndex = 0
    private var seq = 1
    private var blockRetries = 0
    private var eotTries = 0
    private var enterTicks = 0
    private var bleKeyFrame: ByteArray? = null
    private var timeout: Runnable? = null

    companion object {
        private const val EOT = 0x04
        private const val ACK = 0x06
        private const val NAK = 0x15
        private const val CAN = 0x18
        private const val CRCREQ = 0x43
        private const val BLOCK = 128

        private const val AUTH_SETTLE_MS = 1_500L
        private const val STEP_TIMEOUT_MS = 3_000L
        private const val ENTER_TIMEOUT_MS = 60_000L
        private const val FINISH_TIMEOUT_MS = 3_000L
        private const val BLOCK_RETRIES = 10
        private const val EOT_RETRIES = 5
        private const val DFU_START_TICKS = 11
        private const val BLE_RAND_TICKS = 6
        private const val BLE_KEY_TICKS = 6
        private const val KEY_IDX = 1

        /** NAVEE session key (index 1) used for the 0x30/0x31 challenge and the ble_key step. */
        private val KEY = byteArrayOf(0x44,0x6D,0x10,0x72,0x6D,0xBE.toByte(),0x05,0xF6.toByte(),0x62,0xDF.toByte(),0xAA.toByte(),0xF0.toByte(),0x13,0x27,0x30,0x3F)
    }

    fun isRunning(): Boolean = running
    fun start(img: ByteArray, targetComponent: Int = 2) {
        main.post {
            if (running) return@post
            if (img.size < BLOCK) {
                fail("empty firmware image")
                return@post
            }
            running = true
            phase = Phase.IDLE
            image = img.copyOf()
            target = targetComponent
            blockCount = (image.size + BLOCK - 1) / BLOCK
            blockIndex = 0
            seq = 1
            blockRetries = 0
            eotTries = 0
            rxLen = 0
            host.setHighPriority(true)
            host.state("running", null)
            host.progress(0, 0, blockCount, "prepare")
            host.log("image ${image.size} bytes, $blockCount blocks, target $target")
            beginAuth()
        }
    }

    fun onNotify(value: ByteArray) {
        if (value.isEmpty() || !running) return
        val copy = value.copyOf()
        main.post {
            if (!running) return@post
            runCatching { handleNotify(copy) }
                .onFailure { fail("notify error: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun onLinkLost() {
        main.post {
            if (running) fail("BLE link lost during DFU")
        }
    }

    private fun beginAuth() {
        phase = Phase.AUTH
        host.progress(0, 0, blockCount, "enter")
        host.log("auth init (key $KEY_IDX)")
        if (!host.write(buildWrite(0x30, authInitPayload(KEY_IDX)))) {
            fail("could not send 0x30 auth init")
            return
        }
        arm(AUTH_SETTLE_MS) { beginDfuStart() }
    }

    private fun beginDfuStart() {
        phase = Phase.DFU_START
        rxLen = 0
        enterTicks = DFU_START_TICKS
        resendDfuStart()
    }

    private fun resendDfuStart() {
        host.log("-> down dfu_start $target")
        if (!host.write(ascii("down dfu_start $target\r"))) {
            fail("could not send dfu_start")
            return
        }
        enterTicks--
        if (enterTicks <= 0) arm(STEP_TIMEOUT_MS) { fail("no reply to dfu_start") }
        else arm(STEP_TIMEOUT_MS) { resendDfuStart() }
    }

    private fun beginBleRand() {
        phase = Phase.BLE_RAND
        rxLen = 0
        enterTicks = BLE_RAND_TICKS
        resendBleRand()
    }

    private fun resendBleRand() {
        host.log("-> down ble_rand")
        if (!host.write(ascii("down ble_rand\r"))) {
            fail("could not send ble_rand")
            return
        }
        enterTicks--
        if (enterTicks <= 0) arm(STEP_TIMEOUT_MS) { fail("no reply to ble_rand") }
        else arm(STEP_TIMEOUT_MS) { resendBleRand() }
    }

    private fun sendBleKey(mode: Int, rand16: ByteArray) {
        val response = transform(mode, rand16, KEY) ?: run {
            fail("ble_key transform failed")
            return
        }
        val prefix = ascii("down ble_key ")
        bleKeyFrame = ByteArray(prefix.size + 16 + 1).also { frame ->
            prefix.copyInto(frame, 0)
            response.copyInto(frame, prefix.size)
            frame[frame.lastIndex] = 0x0D
        }
        phase = Phase.WAIT_C
        rxLen = 0
        enterTicks = BLE_KEY_TICKS
        host.log("-> down ble_key (mode $mode)")
        resendBleKey()
    }

    private fun resendBleKey() {
        val frame = bleKeyFrame ?: run {
            fail("ble_key frame missing")
            return
        }
        if (!host.write(frame)) {
            fail("could not send ble_key")
            return
        }
        enterTicks--
        if (enterTicks <= 0) arm(ENTER_TIMEOUT_MS) { fail("scooter did not enter DFU (no C)") }
        else arm(STEP_TIMEOUT_MS) { resendBleKey() }
    }

    private fun handleNotify(v: ByteArray) {
        when (phase) {
            Phase.AUTH -> onAuthNotify(v)
            Phase.DFU_START -> {
                append(v)
                if (contains(ascii("ok\r"))) {
                    clearTimeout()
                    beginBleRand()
                }
            }
            Phase.BLE_RAND -> {
                append(v)
                tryBleRand()
            }
            Phase.WAIT_C -> {
                if (hasByte(v, CRCREQ)) {
                    clearTimeout()
                    host.log("C received")
                    beginXmodem()
                } else {
                    append(v)
                    if (contains(ascii("ok\r"))) {
                        clearTimeout()
                        rxLen = 0
                        host.log("ble_key ok - waiting for C")
                        arm(ENTER_TIMEOUT_MS) { fail("scooter did not enter DFU (no C)") }
                    }
                }
            }
            Phase.XMODEM -> onXmodemNotify(v)
            Phase.EOT -> {
                append(v)
                when {
                    contains(ascii("dfu_error")) -> { clearTimeout(); fail("scooter reported dfu_error") }
                    contains(ascii("dfu_ok")) -> { clearTimeout(); succeed("done") }
                    hasByte(v, ACK) -> { clearTimeout(); beginFinish() }
                }
            }
            Phase.FINISH -> onFinishNotify(v)
            else -> Unit
        }
    }

    private fun onAuthNotify(v: ByteArray) {
        append(v)
        val start = findFrame(0x30)
        if (start < 0) return
        val len = rx[start + 4].toInt() and 0xFF
        if ((rx[start + 5].toInt() and 0xFF) == 0xFF) {
            rxLen = 0
            host.log("0x30 rejected (bound) - continuing to DFU")
            return
        }
        val dataStart = start + 6
        val dataLen = len - 1
        if (dataLen >= 16 && dataStart + dataLen <= rxLen) {
            val challenge = rx.copyOfRange(dataStart + dataLen - 16, dataStart + dataLen)
            val response = aesEcb(KEY, challenge)
            if (response != null) {
                host.log("auth challenge -> 0x31")
                host.write(buildWrite(0x31, response))
            }
        }
        rxLen = 0
    }

    private fun tryBleRand() {
        var i = 0
        while (i + 20 < rxLen) {
            if ((rx[i].toInt() and 0xFF) == 0x6F &&
                (rx[i + 1].toInt() and 0xFF) == 0x6B &&
                (rx[i + 2].toInt() and 0xFF) == 0x20
            ) {
                if (i + 21 > rxLen) return
                val mode = rx[i + 3].toInt() and 0xFF
                val rand16 = rx.copyOfRange(i + 4, i + 20)
                clearTimeout()
                sendBleKey(mode, rand16)
                return
            }
            i++
        }
    }

    private fun beginXmodem() {
        phase = Phase.XMODEM
        blockIndex = 0
        seq = 1
        host.progress(0, 0, blockCount, "flash")
        blockRetries = 0
        sendBlock()
    }

    private fun sendBlock() {
        // Same framing as the offline dry run: both go through NaveeXmodem.buildBlock.
        val frame = NaveeXmodem.buildBlock(image, blockIndex, seq)
        if (!host.write(frame)) {
            fail("BLE write rejected for block $seq")
            return
        }
        arm(STEP_TIMEOUT_MS) { fail("block $seq ACK timeout") }
    }

    private fun onXmodemNotify(v: ByteArray) {
        for (i in v.indices) {
            when (val c = v[i].toInt() and 0xFF) {
                NAK -> {
                    clearTimeout()
                    host.log("block $seq NAK")
                    blockRetries++
                    if (blockRetries >= BLOCK_RETRIES) fail("block $seq failed after $BLOCK_RETRIES retries")
                    else sendBlock()
                    return
                }
                CAN -> {
                    clearTimeout()
                    fail("scooter aborted (CAN)")
                    return
                }
                ACK -> {
                    // Standard XMODEM: a bare 0x06 acknowledges the outstanding block. This
                    // controller sends ACK followed by CR (0x0D); any trailing bytes are ignored.
                    clearTimeout()
                    advanceBlock()
                    return
                }
            }
        }
    }

    private fun advanceBlock() {
        blockIndex++
        blockRetries = 0
        val percent = (blockIndex * 100.0 / blockCount).roundToInt().coerceIn(0, 100)
        host.progress(percent, blockIndex, blockCount, "flash")
        if (blockIndex >= blockCount) {
            beginEot()
            return
        }
        seq = (seq + 1) and 0xFF
        if (seq == 0) seq = 1
        sendBlock()
    }

    private fun beginEot() {
        phase = Phase.EOT
        eotTries = 0
        rxLen = 0
        host.progress(100, blockCount, blockCount, "finish")
        sendEot()
    }

    private fun sendEot() {
        host.log("-> EOT")
        if (!host.write(byteArrayOf(EOT.toByte()))) {
            fail("could not send EOT")
            return
        }
        arm(STEP_TIMEOUT_MS) {
            eotTries++
            if (eotTries >= EOT_RETRIES) beginFinish() else sendEot()
        }
    }

    private fun beginFinish() {
        phase = Phase.FINISH
        rxLen = 0
        host.log("EOT complete - waiting for dfu_ok")
        arm(FINISH_TIMEOUT_MS) { fail("no result token (dfu_ok) from scooter") }
    }

    private fun onFinishNotify(v: ByteArray) {
        append(v)
        when {
            contains(ascii("dfu_error")) -> { clearTimeout(); fail("scooter reported dfu_error") }
            contains(ascii("dfu_ok")) -> { clearTimeout(); succeed("done") }
        }
    }

    private fun authInitPayload(keyIdx: Int): ByteArray {
        var userId = Random.nextLong(1, 1_000_000_001L) and 0xFFFFFFFFFFFFL
        val s = ByteArray(6)
        for (i in 5 downTo 0) {
            s[i] = (userId and 0xFF).toByte()
            userId = userId ushr 8
        }
        val first = s[0].toInt() and 0xFF
        if (first == 0 || first >= 0x80) s[0] = 0x88.toByte()
        return ByteArray(9).also {
            it[0] = keyIdx.toByte()
            it[1] = 0
            s.copyInto(it, 2)
            it[8] = 0
        }
    }

    private fun transform(mode: Int, random: ByteArray, key: ByteArray): ByteArray? {
        if (random.size != 16 || key.size != 16) return null
        if (mode == 0) return ByteArray(16) { i -> (random[i].toInt() xor key[i].toInt()).toByte() }
        return aesEcb(key, random)
    }

    private fun aesEcb(key: ByteArray, block: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.doFinal(block)
    }.getOrNull()

    private fun append(v: ByteArray) {
        for (b in v) {
            if (rxLen >= rx.size) {
                val keep = rx.size / 2
                rx.copyInto(rx, 0, rx.size - keep, rx.size)
                rxLen = keep
            }
            rx[rxLen++] = b
        }
    }

    private fun hasByte(v: ByteArray, target: Int): Boolean = v.any { (it.toInt() and 0xFF) == target }

    private fun contains(pattern: ByteArray): Boolean {
        if (pattern.isEmpty() || pattern.size > rxLen) return false
        outer@ for (i in 0..(rxLen - pattern.size)) {
            for (j in pattern.indices) if (rx[i + j] != pattern[j]) continue@outer
            return true
        }
        return false
    }

    private fun findFrame(command: Int): Int {
        var i = 0
        while (i + 8 <= rxLen) {
            if ((rx[i].toInt() and 0xFF) == 0x55 &&
                (rx[i + 1].toInt() and 0xFF) == 0xAA &&
                (rx[i + 3].toInt() and 0xFF) == command
            ) {
                val total = 8 + (rx[i + 4].toInt() and 0xFF)
                if (i + total > rxLen) return -1
                if ((rx[i + total - 2].toInt() and 0xFF) == 0xFE &&
                    (rx[i + total - 1].toInt() and 0xFF) == 0xFD
                ) return i
            }
            i++
        }
        return -1
    }

    /** NAVEE 55 AA write frame: header, flag, command, length, payload, checksum, FE FD. */
    private fun buildWrite(command: Int, payload: ByteArray, flag: Int = 0): ByteArray {
        require(command in 0..255 && flag in 0..255 && payload.size <= 255)
        val out = ByteArray(8 + payload.size)
        out[0] = 0x55.toByte()
        out[1] = 0xAA.toByte()
        out[2] = flag.toByte()
        out[3] = command.toByte()
        out[4] = payload.size.toByte()
        payload.copyInto(out, 5)
        val checksumIndex = 5 + payload.size
        var sum = 0
        for (i in 0 until checksumIndex) sum = (sum + (out[i].toInt() and 0xFF)) and 0xFF
        out[checksumIndex] = sum.toByte()
        out[checksumIndex + 1] = 0xFE.toByte()
        out[checksumIndex + 2] = 0xFD.toByte()
        return out
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    private fun arm(delayMs: Long, action: () -> Unit) {
        clearTimeout()
        val r = Runnable { action() }
        timeout = r
        main.postDelayed(r, delayMs)
    }

    private fun clearTimeout() {
        timeout?.let(main::removeCallbacks)
        timeout = null
    }

    private fun succeed(message: String) = finish(true, "success", message)
    private fun fail(message: String) {
        host.log("FAIL: $message")
        finish(false, "failed", message)
    }

    private fun finish(ok: Boolean, stateName: String, message: String) {
        clearTimeout()
        phase = if (ok) Phase.DONE else Phase.FAILED
        running = false
        rxLen = 0
        runCatching { host.setHighPriority(false) }
        runCatching { host.state(stateName, message) }
        runCatching { host.finished(ok) }
    }
}
