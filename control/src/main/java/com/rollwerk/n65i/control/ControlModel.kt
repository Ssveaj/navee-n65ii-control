@file:Suppress("MissingPermission", "DEPRECATION")
package com.rollwerk.n65i.control

import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rollwerk.n65i.control.firmware.FlashKind
import com.rollwerk.n65i.control.firmware.FlashPreflight
import com.rollwerk.n65i.control.firmware.FlashRecord
import com.rollwerk.n65i.control.firmware.FlashSnapshot
import com.rollwerk.n65i.control.firmware.NaveeDfuEngine
import com.rollwerk.n65i.control.firmware.NaveeXmodem
import com.rollwerk.n65i.control.firmware.TurboSpeedDryRun
import com.rollwerk.n65i.control.firmware.TurboSpeedFirmware
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class ControlModel(app: Application) : AndroidViewModel(app), Exchange, ControlActions {
    companion object {
        val SERVICE: UUID = UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212")
        fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        val TX = uuid("b002"); val RX = uuid("b003"); val CCCD = uuid("2902")
    }
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val store = OriginalStore(app)
    private val telemetryFile = java.io.File(app.filesDir, "readonly-modes.jsonl")
    private val mutable = MutableStateFlow(Screen(remembered = store.selected()))
    val state = mutable.asStateFlow()
    private val sequence = AtomicLong()
    private var gatt: BluetoothGatt? = null
    private var operation: CompletableDeferred<Int>? = null
    private var response: Pair<Int, CompletableDeferred<ByteArray>>? = null
    private var scanJob: Job? = null
    private var workJob: Job? = null
    @Volatile private var autoConnect = false

    private fun update(block: (Screen) -> Screen) { mutable.value = block(mutable.value) }
    private fun log(text: String) {
        val line = "${Instant.now()} #${sequence.incrementAndGet()} $text"
        Log.i("N65I_BLE", line)
        update { it.copy(logs = (it.logs + line).takeLast(250)) }
        runCatching {
            val file = java.io.File(getApplication<Application>().filesDir, "ble-diagnostics.log")
            if (file.length() > 2_000_000) file.renameTo(java.io.File(file.parentFile, "ble-diagnostics.previous.log"))
            file.appendText(line + "\n")
        }
    }
    /** 0x90/0x92 ride log (JSON lines in app storage), used to verify the real speed after a flash. */
    private fun recordTelemetry(kind: String, now: Long, mode: Int?, speed: Double?) {
        val line = org.json.JSONObject().put("timestamp", Instant.ofEpochMilli(now).toString())
            .put("event", kind).put("address", state.value.device?.address ?: "unknown")
            .put("mode", mode ?: org.json.JSONObject.NULL).put("speedKmh", speed ?: org.json.JSONObject.NULL)
            .put("version", BuildConfig.VERSION_NAME).toString()
        runCatching { telemetryFile.appendText(line + "\n") }
        log("TELEMETRY $line")
    }
    fun failure(text: String) { log("ERROR $text"); update { it.copy(status = text) } }
    private fun work(block: suspend () -> Unit) {
        if (state.value.busy || dfuEngine != null) return
        update { it.copy(busy = true) }
        workJob = viewModelScope.launch {
            try { block() } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure(e.message ?: e.javaClass.simpleName)
            } finally { update { it.copy(busy = false) } }
        }
    }

    // ── Scan and connect ──────────────────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            if (!state.value.scanning || state.value.device != null) return
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!DeviceFlow.isNavee(name)) return
            val pid = result.scanRecord?.bytes?.let(DeviceFlow::advertisedPid)
                ?: state.value.devices.find { it.address == result.device.address }?.pid
            val device = Found(result.device.address, name, result.rssi, pid)
            update { it.copy(devices = (it.devices.filterNot { d -> d.address == device.address } + device).sortedByDescending { it.rssi }) }
            // One-button "scan & connect": auto-connect to the last-used scooter when it reappears, or
            // to the first NAVEE scooter found if none is remembered yet (first run / new scooter).
            val remembered = state.value.remembered
            if (autoConnect && (remembered == null || device.address.equals(remembered.address, true))) {
                autoConnect = false
                viewModelScope.launch { connect(device) }
            }
        }
        override fun onScanFailed(errorCode: Int) { autoConnect = false; stopScan(); failure("Scan failed: $errorCode") }
    }
    override fun scan() {
        if (state.value.busy || state.value.scanning || state.value.device != null) return
        try {
            check(manager.adapter?.isEnabled == true) { "Turn Bluetooth on" }
            autoConnect = true
            update { it.copy(scanning = true, devices = emptyList(), status = "Scanning for your scooter…") }
            manager.adapter.bluetoothLeScanner.startScan(scanCallback)
            scanJob = viewModelScope.launch { delay(10_000); stopScan() }
        } catch (e: Exception) { update { it.copy(scanning = false) }; failure(e.message ?: "Scan unavailable") }
    }
    private fun stopScan() {
        autoConnect = false
        scanJob?.cancel(); scanJob = null
        if (state.value.scanning) runCatching { manager.adapter.bluetoothLeScanner.stopScan(scanCallback) }
        update { it.copy(scanning = false, status = if (it.status == "Scanning…") "Scan complete" else it.status) }
    }
    private suspend fun gattOperation(start: () -> Boolean) {
        check(operation == null) { "GATT operation already pending" }
        val wait = CompletableDeferred<Int>(); operation = wait
        try { check(start()) { "GATT request rejected locally" }; check(withTimeout(12_000) { wait.await() } == 0) { "GATT operation failed" } }
        finally { operation = null }
    }
    override fun connect(found: Found) = work {
        require(DeviceFlow.isNavee(found.name)) { "Not a NAVEE scooter" }
        store.remember(found)
        stopScan(); close()
        update { it.copy(device = found, status = "Connecting…") }
        try {
            gattOperation { gatt = manager.adapter.getRemoteDevice(found.address).connectGatt(getApplication(), false, callbacks, BluetoothDevice.TRANSPORT_LE); gatt != null }
            update { it.copy(connected = true, status = "Requesting MTU 148…") }
            gattOperation { log("MTU_REQUEST mtu=148"); gatt!!.requestMtu(148) }
            gattOperation { log("DISCOVERY_START"); gatt!!.discoverServices() }
            val service = requireNotNull(gatt!!.getService(SERVICE)) { "NAVEE d0ff service missing" }
            requireNotNull(service.getCharacteristic(TX)) { "b002 command channel missing" }
            val rx = requireNotNull(service.getCharacteristic(RX)) { "b003 response channel missing" }
            require(rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) { "b003 NOTIFY property missing" }
            check(gatt!!.setCharacteristicNotification(rx, true))
            val descriptor = requireNotNull(rx.getDescriptor(CCCD)) { "b003 CCCD missing" }
            gattOperation {
                log("CCCD_TX char=b003 value=0100")
                if (Build.VERSION.SDK_INT >= 33) gatt!!.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                else { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt!!.writeDescriptor(descriptor) }
            }
            update { it.copy(subscribed = true, status = "Control channel ready. Authenticating owner session…", backup = store.read(found.address)) }
        } catch (e: Exception) { close(); update { it.copy(authResult = "Failed: ${e.message}") }; throw e }
        try {
            initializeSession()
        } catch (e: Exception) {
            // A response timeout is a TimeoutCancellationException; only a real job cancellation propagates.
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            // Keep the link: the meter still answers read-only identity requests, which is all the
            // recovery flash needs.
            log("LIMITED_SESSION after init failure: ${e.message}")
            readOnlyRefresh()
            update { it.copy(status = "Not authenticated — read-only session. Recovery flash only.") }
        }
    }
    private suspend fun initializeSession() {
        check(state.value.connected && state.value.subscribed)
        update { it.copy(authenticated = false, authResult = "Authenticating…") }
        delay(100)
        // Try the OEM challenge/response. If the controller rejects it (e.g. a lingering session or
        // bond), fall back to a plain session: NAVEE still answers time-sync + reads. A valid 0x70
        // readback means the session is usable, so we show the full UI either way.
        val oemAuth = runCatching { authenticate() }.getOrElse { log("AUTH_ERROR ${it.message}"); false }
        runCatching { exchange(Protocol.timeInitialization(System.currentTimeMillis() / 1000), 0x6F) }
            .onFailure { log("TIME_SYNC_FAILED ${it.message}") }
        // Identity first (0x74/0x73) so identity() is populated before the original is stored.
        for (command in listOf(0x74, 0x73, 0x72)) {
            runCatching { Protocol.response(exchange(Protocol.command(command), command)) }
                .onFailure { log("DEVICE_INFO cmd=%02X failed: ${it.message}".format(command)) }
        }
        val settings = runCatching { Protocol.settings(exchange(Protocol.command(0x70), 0x70)) }.getOrNull()
        if (settings == null) {
            update { it.copy(authResult = "Incomplete: no settings readback (read-only session)") }
            throw IllegalStateException("no 0x70 settings readback")
        }
        store.saveFirst(Backup(identity(), System.currentTimeMillis(), settings))
        update { it.copy(authenticated = true, settings = settings, backup = store.read(identity().address),
            authResult = if (oemAuth) "Auth OK" else "Session OK (plain)",
            status = if (oemAuth) "Connected / Auth OK" else "Connected / plain session") }
        log("SESSION_READY oem=$oemAuth originalMax=${settings.maximum}")
    }

    /**
     * Anonymous challenge/response session auth (0x30/0x31), the same key the DFU engine uses.
     * Returns true when the controller accepts the session (short-success or up to 4 challenge
     * rounds), false on rejection. Failing here is harmless — the app falls back to read-only.
     */
    private suspend fun authenticate(): Boolean {
        val id = SessionAuth.newSessionId()
        var reply = Protocol.response(exchange(SessionAuth.initFrame(id), 0x30))
        var rounds = 0
        while (true) {
            when (SessionAuth.classify(reply)) {
                SessionAuth.Kind.COMPLETE -> return true
                SessionAuth.Kind.REJECTED, SessionAuth.Kind.NONE -> return false
                SessionAuth.Kind.CHALLENGE -> {
                    val resp = SessionAuth.challengeResponse(reply) ?: return false
                    val r31 = Protocol.response(exchange(SessionAuth.responseFrame(resp), 0x31))
                    if (SessionAuth.parse31(r31) != true) return false
                    rounds++
                    if (rounds >= 4) return true
                    reply = Protocol.response(exchange(SessionAuth.initFrame(id), 0x30))
                }
            }
        }
    }
    private fun identity() = DeviceIdentity(state.value.device?.address.orEmpty(), state.value.pid?.toString(),
        state.value.dashboard, state.value.bldc, state.value.region)

    // ── Normal on/off controls (lock, lights, cruise, TCS, eco, unit) ─────────────────────────

    override fun setToggle(key: String, on: Boolean) {
        val toggle = ScooterControls.byKey[key] ?: return
        if (!DeviceFlow.ready(state.value.connected, state.value.authenticated, state.value.settings?.maximum)) return
        work {
            update { it.copy(togglePending = key, status = "${toggle.label}: ${if (on) "on" else "off"}…") }
            try {
                val ack = Protocol.response(exchange(toggle.frame(on), toggle.cmd))
                require(ack.status == 0) { "${toggle.label} rejected (status ${ack.status})" }
                update { it.copy(toggles = it.toggles + (key to on), status = "${toggle.label}: ${if (on) "on" else "off"}") }
                runCatching { exchange(Protocol.command(0x70), 0x70) } // refresh settings after the change
            } finally {
                update { it.copy(togglePending = null) }
            }
        }
    }

    override fun setEnergyRecovery(value: Int) {
        if (value !in ScooterControls.ENERGY_VALUES) return
        if (!DeviceFlow.ready(state.value.connected, state.value.authenticated, state.value.settings?.maximum)) return
        work {
            val ack = Protocol.response(exchange(ScooterControls.energyFrame(value), ScooterControls.ENERGY_CMD))
            require(ack.status == 0) { "Energy recovery rejected (status ${ack.status})" }
            update { it.copy(energyRecovery = value, status = "Energy recovery: ${if (value == 60) "high" else "low"}") }
        }
    }

    override fun setStartSpeed(value: Int) {
        if (value !in ScooterControls.START_SPEED_RANGE) return
        if (!DeviceFlow.ready(state.value.connected, state.value.authenticated, state.value.settings?.maximum)) return
        if (value < ScooterControls.minStartSpeed(state.value.region)) {
            failure("Start speed $value is blocked on a non-USA variant (meter clamps 0–2)")
            return
        }
        work {
            val ack = Protocol.response(exchange(ScooterControls.startSpeedFrame(value), ScooterControls.START_SPEED_CMD))
            require(ack.status == 0) { "Start speed rejected (status ${ack.status})" }
            update { it.copy(startSpeed = value, status = "Start speed: $value") }
        }
    }

    // ── BLE request/response ──────────────────────────────────────────────────────────────────

    override suspend fun exchange(bytes: ByteArray, responseCommand: Int): ByteArray {
        check(dfuEngine == null) { "Controller DFU active — normal commands paused" }
        check(DeviceFlow.allowedTx(bytes, unhex(BuildConfig.OWNER_AUTH),
            DeviceFlow.ready(state.value.connected, state.value.authenticated, state.value.settings?.maximum),
            state.value.connected && state.value.subscribed)) { "Command not permitted in this session" }
        check(response == null && state.value.connected && state.value.subscribed)
        val wait = CompletableDeferred<ByteArray>(); response = responseCommand to wait
        try {
            val tx = requireNotNull(gatt?.getService(SERVICE)?.getCharacteristic(TX))
            require(tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "Response write unavailable" }
            gattOperation {
                // Auth identities must not appear in logs, debug views or exports.
                log(if (responseCommand == 0x30) "TX char=b002 cmd=30 identity=REDACTED len=${bytes.size}" else "TX char=b002 len=${bytes.size} hex=${bytes.hex()}")
                if (Build.VERSION.SDK_INT >= 33) gatt!!.writeCharacteristic(tx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                else { tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; tx.value = bytes; gatt!!.writeCharacteristic(tx) }
            }
            return withTimeout(8_000) { wait.await() }
        } finally { response = null }
    }
    private fun received(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        // During DFU the b003 stream is ASCII/XMODEM, not 55 AA frames: hand it to the engine only.
        dfuEngine?.let { engine ->
            if (characteristic.uuid == RX) engine.onNotify(bytes)
            return
        }
        val sensitive = bytes.size > 3 && ((bytes[3].toInt() and 255) == 0x31 || ((bytes[3].toInt() and 255) == 0x30 && bytes.size > 9))
        log("RX_NOTIFY char=${characteristic.uuid} len=${bytes.size} hex=${if (sensitive) "REDACTED_AUTH" else bytes.hex()}")
        if (characteristic.uuid != RX || characteristic.service.uuid != SERVICE) return
        val decoded = runCatching { Protocol.response(bytes) }.getOrElse { log("DECODE_REJECT ${it.message}"); return }
        update { it.copy(lastResponse = if (sensitive) "Authentication response (redacted)" else bytes.hex()) }
        if (!sensitive) update { it.copy(rawResponses = it.rawResponses +
            (decoded.command to "${Instant.now()} status=${decoded.status} ${bytes.hex()}")) }
        if (decoded.status == 0) {
            if (decoded.command == 0x73) {
                val versions = Protocol.versions(bytes)
                update { it.copy(dashboard = versions[0], bldc = versions[1], bms = versions[2]) }
            }
            if (decoded.command == 0x74) {
                val serial = Protocol.serial(bytes)
                update { it.copy(serial = serial, region = serial.takeIf { value -> value.length >= 10 }?.substring(8, 10)) }
            }
            val now = System.currentTimeMillis()
            LiveTelemetry.activeMode(bytes)?.let { mode ->
                recordTelemetry("0x90", now, mode, state.value.speed?.takeIf { now - (state.value.speedAt ?: 0) <= 3000 })
                update { it.copy(activeMode = mode, modeAt = now) }
            }
            Protocol.battery(bytes)?.let { soc -> update { it.copy(battery = soc) } }
            LiveTelemetry.drivingStatus(bytes)?.let { status -> update { it.copy(drivingStatus = status) } }
            LiveTelemetry.speedKmh(bytes)?.let { speed ->
                recordTelemetry("0x92", now, state.value.activeMode?.takeIf { now - (state.value.modeAt ?: 0) <= 3000 }, speed)
                update { it.copy(speed = speed, speedAt = now, sessionMaximum = maxOf(it.sessionMaximum ?: speed, speed)) }
            }
            if (decoded.command == 0x70) runCatching { Protocol.settings(bytes) }.getOrNull()?.let { settings ->
                update { it.copy(settings = settings, settingsAt = now) }
                runCatching { store.saveCurrent(identity().address, settings, now) }
                    .onFailure { failure(it.message ?: "Cannot persist current readback") }
            }
        }
        log("FRAME cmd=%02X status=${decoded.status} checksum=valid".format(decoded.command))
        response?.takeIf { it.first == decoded.command }?.second?.complete(bytes.copyOf())
    }
    private val callbacks = object : BluetoothGattCallback() {
        private fun dispatch(g: BluetoothGatt, action: () -> Unit) { viewModelScope.launch { if (g === gatt) action() } }
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = dispatch(g) {
            log("CONNECTION status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) operation?.complete(0)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != 0) {
                dfuEngine?.onLinkLost() // the transfer fails; it never resumes on its own
                operation?.completeExceptionally(IllegalStateException("Disconnected: GATT $status"))
                response?.second?.completeExceptionally(IllegalStateException("Disconnected")); close()
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = dispatch(g) {
            log("MTU mtu=$mtu status=$status")
            if (status == 0) update { it.copy(mtu = mtu) }
            operation?.complete(status)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = dispatch(g) { log("DISCOVERY status=$status services=${g.services.size}"); operation?.complete(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = dispatch(g) {
            log("CCCD_WRITE char=${d.characteristic.uuid} value=0100 status=$status"); operation?.complete(status)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = dispatch(g) {
            if (dfuEngine == null) log("WRITE_ACK char=${c.uuid} status=$status") // 369 DFU blocks: keep the log readable
            operation?.complete(status)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = dispatch(g) { received(c, value) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { if (Build.VERSION.SDK_INT < 33) { val value = c.value?.copyOf() ?: byteArrayOf(); dispatch(g) { received(c, value) } } }
    }

    // ── Controller firmware: TurboSpeed 25 install and recovery stock ────────────────────────
    // Offline build + dry run, risk ack, ARM (preflight, 60 s window), START (preflight again,
    // rebuild, pinned SHA), then target-2 XMODEM DFU. Never automatic.

    private val flashPrefs = app.getSharedPreferences("bldc-flash-v1", Context.MODE_PRIVATE)
    private val fwMutable = MutableStateFlow(FirmwareUi(lastFlash = readLastFlash()))
    val firmware = fwMutable.asStateFlow()
    private var dfuEngine: NaveeDfuEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var armDeadline = 0L
    private var armTicker: Job? = null
    private fun fw(block: (FirmwareUi) -> FirmwareUi) { fwMutable.value = block(fwMutable.value) }

    private fun readLastFlash(): FlashRecord? {
        val kind = flashPrefs.getString("kind", null) ?: return null
        return FlashRecord(kind, flashPrefs.getString("sha", "") ?: "", flashPrefs.getLong("timestamp", 0L),
            flashPrefs.getBoolean("success", false), flashPrefs.getString("message", "") ?: "")
    }
    @Suppress("ApplySharedPref") // must be on disk before anything else happens to the scooter
    private fun saveLastFlash(record: FlashRecord) {
        flashPrefs.edit().putString("kind", record.kind).putString("sha", record.sha256).putLong("timestamp", record.timestamp)
            .putBoolean("success", record.success).putString("message", record.message).commit()
    }
    private fun fwLog(line: String) {
        log("DFU $line")
        val stamped = "%tT  %s".format(System.currentTimeMillis(), line)
        fw { it.copy(log = (it.log + stamped).takeLast(40)) }
    }
    private fun stockImage(): ByteArray? = runCatching {
        getApplication<Application>().assets.open(TurboSpeedFirmware.STOCK_ASSET).use { it.readBytes() }
    }.getOrNull()

    /** Fresh read-only 0x73/0x74/0x72/0x92 so every gate uses current values, never cached ones. */
    private suspend fun readOnlyRefresh() {
        for (command in listOf(0x73, 0x74, 0x72, 0x92)) {
            runCatching { exchange(Protocol.command(command), command) }
                .onFailure { log("READ_ONLY_REFRESH cmd=%02X failed: ${it.message}".format(command)) }
        }
    }
    private suspend fun snapshot(): FlashSnapshot {
        update { it.copy(speed = null, drivingStatus = null) } // a stale 0x92 must not pass the gate
        readOnlyRefresh()
        val s = state.value
        return FlashSnapshot(s.connected, s.subscribed, s.authenticated, dfuEngine != null, s.serialPid, s.pid,
            s.dashboard, s.bldc, s.mtu, s.battery, s.speed, s.drivingStatus, stockImage())
    }

    /** Pick the Turbo cap speed. Changing it invalidates a previous dry run. */
    override fun setTargetSpeed(kmh: Int) {
        val clamped = kmh.coerceIn(TurboSpeedFirmware.SPEED_RANGE)
        fw { it.copy(targetKmh = clamped, dryRunOk = if (it.dryRunSpeed == clamped) it.dryRunOk else null,
            status = if (it.dryRunSpeed == clamped) it.status else "Turbo cap $clamped km/h — run the dry run again") }
    }

    /** Offline only: build the TurboSpeed image for the chosen speed and frame it through the receiver. */
    override fun dryRun() {
        val kmh = fwMutable.value.targetKmh
        fw { it.copy(status = "Building + dry run ($kmh km/h)…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val stock = checkNotNull(stockImage()) { "Stock asset missing" }
                    TurboSpeedFirmware.build(stock, kmh) to TurboSpeedDryRun.run(stock, kmh)
                }
            }.onSuccess { (built, report) ->
                fw { it.copy(dryRunSpeed = kmh, candidateSha256 = built.sha256, primaryCrc = "0x%04X".format(built.primaryCrc),
                    secondaryCrc = "0x%04X".format(built.secondaryCrc),
                    changedOffsets = built.changedOffsets.joinToString(" ") { off -> "0x%X".format(off) },
                    blockCount = NaveeXmodem.blockCount(built.bytes.size), dryRunOk = report.ok, dryRunMessage = report.message,
                    status = if (report.ok) "Dry run OK — $kmh km/h image built & framed byte-exact" else "Dry run FAILED — ${report.message}") }
            }.onFailure { e -> fw { it.copy(dryRunOk = false, dryRunMessage = e.message, status = "Dry run error — ${e.message}") } }
        }
    }

    override fun armInstall() = arm(FlashKind.TURBOSPEED) { FlashPreflight.install(it) }
    override fun armRecovery() = arm(FlashKind.RECOVERY) { FlashPreflight.recovery(it) }

    private fun arm(kind: String, gate: (FlashSnapshot) -> String?) = work {
        disarm("")
        val blocked = gate(snapshot())
        if (blocked != null) { fw { it.copy(status = "$kind preflight blocked — $blocked") }; return@work }
        armDeadline = SystemClock.elapsedRealtime() + FlashPreflight.ARM_WINDOW_MS
        fw { it.copy(armedKind = kind, armSecondsLeft = 60, status = "$kind ARMED 60 s — press START to flash the controller") }
        armTicker = viewModelScope.launch {
            while (true) {
                val left = ((armDeadline - SystemClock.elapsedRealtime() + 999) / 1000).toInt()
                if (left <= 0) { disarm("Arm expired — arm again"); break }
                fw { it.copy(armSecondsLeft = left) }
                delay(500)
            }
        }
    }
    override fun cancelArm() = disarm("Cancelled")
    private fun disarm(message: String) {
        armTicker?.cancel(); armTicker = null; armDeadline = 0L
        fw { it.copy(armedKind = null, armSecondsLeft = 0, status = message.ifBlank { it.status }) }
    }

    override fun startInstall() = start(FlashKind.TURBOSPEED, { FlashPreflight.install(it) }) { stock ->
        val kmh = fwMutable.value.targetKmh
        // Flash only the speed that was just dry-run'd, and only an image build() fully verified.
        check(fwMutable.value.dryRunSpeed == kmh && fwMutable.value.dryRunOk == true) { "Run the dry run for $kmh km/h first" }
        val built = TurboSpeedFirmware.build(stock, kmh) // throws on any stock/patch anomaly
        check(built.sha256 == fwMutable.value.candidateSha256) { "Image SHA changed since dry run — refusing to flash" }
        built.bytes to built.sha256
    }
    override fun startRecovery() = start(FlashKind.RECOVERY, { FlashPreflight.recovery(it) }) { stock ->
        stock to TurboSpeedFirmware.STOCK_SHA256
    }

    private fun start(kind: String, gate: (FlashSnapshot) -> String?, image: (ByteArray) -> Pair<ByteArray, String>) = work {
        if (fwMutable.value.armedKind != kind || SystemClock.elapsedRealtime() > armDeadline) {
            disarm("Arm expired — arm again"); return@work
        }
        disarm("")
        val snap = snapshot()
        gate(snap)?.let { blocked -> fw { it.copy(status = "Final preflight blocked — $blocked") }; return@work }
        val (bytes, sha) = runCatching { image(checkNotNull(snap.stock)) }
            .getOrElse { e -> fw { it.copy(status = "$kind blocked — ${e.message}") }; return@work }
        startDfu(bytes, sha, kind)
    }

    /** Target-2 (BLDC) DFU. A link drop fails the transfer and it never restarts on its own. */
    private fun startDfu(image: ByteArray, sha: String, kind: String) {
        fw { it.copy(dfuActive = true, dfuKind = kind, progress = 0, block = 0, blocks = NaveeXmodem.blockCount(image.size),
            phase = "STARTING", status = "Starting $kind — do NOT power off or close the app", log = emptyList()) }
        fwLog("Controller DFU (target 2) — $kind")
        fwLog("Image SHA-256 $sha  size ${image.size}  blocks ${NaveeXmodem.blockCount(image.size)}")
        val host = object : NaveeDfuEngine.Host {
            override fun write(frame: ByteArray): Boolean = dfuWrite(frame)
            override fun setHighPriority(high: Boolean) {
                runCatching { gatt?.requestConnectionPriority(if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
            }
            override fun progress(percent: Int, block: Int, count: Int, phase: String) {
                fw { it.copy(progress = percent.coerceIn(0, 100), block = block, blocks = count, phase = phase.uppercase(Locale.US),
                    status = "$kind — $phase $percent% ($block/$count)") }
            }
            override fun log(line: String) = fwLog(line)
            override fun state(state: String, message: String?) { fw { it.copy(phase = state.uppercase(Locale.US), status = message ?: state) } }
            override fun finished(success: Boolean) {
                val result = if (success) "SUCCESS — $kind dfu_ok received (transfer complete; verify speed on a test ride)"
                    else "DFU FAILED / INTERRUPTED ($kind) — do NOT power-cycle blindly; reconnect and use recovery stock"
                fwLog(result)
                val record = FlashRecord(kind, sha, System.currentTimeMillis(), success, result)
                saveLastFlash(record)
                fw { it.copy(dfuActive = false, phase = if (success) "DONE" else "FAILED", status = result,
                    progress = if (success) 100 else it.progress, lastFlash = record) }
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                wakeLock = null
                dfuEngine = null
                if (success) viewModelScope.launch {
                    delay(2_500)
                    if (state.value.connected) work { readOnlyRefresh(); runCatching { exchange(Protocol.command(0x70), 0x70) } }
                }
            }
        }
        val engine = NaveeDfuEngine(host)
        dfuEngine = engine
        runCatching {
            val pm = getApplication<Application>().getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "n65i:ControllerDFU")?.apply { acquire(10 * 60 * 1000L) }
        }
        engine.start(image, targetComponent = 2)
    }

    /** Raw b002 write for DFU (no response wait). Write-without-response when the characteristic supports it. */
    private fun dfuWrite(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = g.getService(SERVICE)?.getCharacteristic(TX) ?: return false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, frame, type) == BluetoothStatusCodes.SUCCESS
        else { c.writeType = type; c.value = frame; g.writeCharacteristic(c) }
    }

    override fun disconnect() {
        if (dfuEngine != null) { failure("Controller DFU active — keep the app open and connected"); return }
        workJob?.cancel()
        operation?.cancel(); response?.second?.cancel()
        stopScan(); close()
    }
    private fun close() {
        val old = gatt; gatt = null
        old?.disconnect(); old?.close()
        update { Screen(busy = it.busy, remembered = store.selected(), logs = it.logs) }
    }
    override fun onCleared() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        stopScan(); scanJob?.cancel(); close()
    }
}
