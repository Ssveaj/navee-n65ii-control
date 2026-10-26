package com.rollwerk.n65i.control.firmware

/**
 * Identity gates for the N65i II 6001, split by where the data comes from.
 *
 * The full match (PID + Meter + BLDC) needs the BLDC application alive, because the BLDC version
 * (0x73) comes from the controller. After a failed flash that version can be missing, so the
 * recovery flash gates on the meter-side match only (PID from the 0x74 serial, Meter from 0x73).
 */
internal object ScooterIdentity {
    const val PID = "2509"
    const val METER = "2058"
    const val BLDC = "0005"

    private fun digits(v: String?): String = v.orEmpty().filter(Char::isDigit)

    /** Four-digit PID from the 0x74 serial (first optional letter + four digits). */
    fun pidFromSerial(serial: String?): String? =
        serial?.let { Regex("[A-Za-z]?(\\d{4})").find(it)?.groupValues?.getOrNull(1) }

    fun fullMatch(pid: String?, meter: String?, bldc: String?): Boolean =
        pid == PID && digits(meter) == METER && digits(bldc) == BLDC

    fun meterSideMatch(pid: String?, meter: String?): Boolean =
        pid == PID && digits(meter) == METER
}
