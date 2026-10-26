package com.rollwerk.n65i.control

import android.content.Context
import org.json.JSONObject

/** Selected scooter, the first (original) 0x70 readback per scooter, and an archival copy of the latest readback. */
class OriginalStore(context: Context) {
    private val prefs = context.getSharedPreferences("original-settings-v1", Context.MODE_PRIVATE)
    private val session = context.getSharedPreferences("current-settings-v1", Context.MODE_PRIVATE)
    fun remember(device: Found) {
        check(session.edit().putString("selected-address", device.address).putString("selected-name", device.name)
            .putInt("selected-pid", device.pid ?: 0).commit()) { "Cannot remember scooter" }
    }
    fun selected(): Found? = session.getString("selected-address", null)?.let {
        Found(it, session.getString("selected-name", "NAVEE") ?: "NAVEE", 0,
            session.getInt("selected-pid", 0).takeIf { value -> value != 0 })
    }
    fun saveCurrent(address: String, settings: Protocol.Settings, timestamp: Long) {
        // Archival only: never used to populate the connected UI after a restart.
        check(session.edit().putString("$address/raw", settings.rawHex)
            .putLong("$address/timestamp", timestamp).commit()) { "Current readback persistence failed" }
    }
    fun read(address: String): Backup? {
        val encoded = prefs.getString(address, null) ?: return null
        val o = JSONObject(encoded) // Corruption fails closed; an unreadable original is never replaced.
        fun field(key: String) = o.optString(key).takeIf { it.isNotBlank() }
        return Backup(DeviceIdentity(o.getString("address"), field("pid"), field("firmware"), field("controller"), field("region")),
            o.getLong("timestamp"), Protocol.settings(unhex(o.getString("raw"))))
    }
    /** Stores the first readback only; later calls never overwrite it. */
    @Synchronized fun saveFirst(backup: Backup) {
        if (prefs.contains(backup.identity.address)) { read(backup.identity.address); return }
        val d = backup.identity
        val o = JSONObject().put("address", d.address).put("pid", d.pid).put("firmware", d.firmware)
            .put("controller", d.controller).put("region", d.region)
            .put("timestamp", backup.timestamp).put("raw", backup.settings.rawHex)
            .put("verification", "checksum_valid_device_readback").put("setting", "0x70_raw_settings")
        check(prefs.edit().putString(d.address, o.toString()).commit()) { "Original settings persistence failed" }
    }
}
