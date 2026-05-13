package rinhakotlin

import kotlin.math.pow

private val FRAC_POWERS = doubleArrayOf(
    1e0, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9,
    1e-10, 1e-11, 1e-12, 1e-13, 1e-14, 1e-15, 1e-16, 1e-17, 1e-18,
)

private val DOW_T = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)

private const val B_QUOTE = '"'.code.toByte()
private const val B_COLON = ':'.code.toByte()
private const val B_BRACKET = '['.code.toByte()
private const val B_RBRACKET = ']'.code.toByte()
private const val B_t = 't'.code.toByte()
private const val B_n = 'n'.code.toByte()
private const val B_MINUS = '-'.code.toByte()
private const val B_DOT = '.'.code.toByte()

// JSON layout (from example-payloads.json):
// {"id":"...","transaction":{"amount":F,"installments":I,"requested_at":"ISO"},
//  "customer":{"avg_amount":F,"tx_count_24h":I,"known_merchants":[...strings...]},
//  "merchant":{"id":"...","mcc":"NNNN","avg_amount":F},
//  "terminal":{"is_online":B,"card_present":B,"km_from_home":F},
//  "last_transaction":null|{"timestamp":"ISO","km_from_current":F}}

fun parsePayload(buf: ByteArray, start: Int, end: Int): Payload? {
    val len = end
    val p = IntArray(1) { start }

    toNextValue(p, buf, len) || return null  // → transaction id value
    skipString(p, buf, len)   || return null  // skip id

    toNextValue(p, buf, len) || return null  // → transaction object
    toNextValue(p, buf, len) || return null  // → amount value
    val amount = scanF32(p, buf, len)

    toNextValue(p, buf, len) || return null  // → installments value
    val installments = scanU32(p, buf, len)

    toNextValue(p, buf, len) || return null  // → requested_at value
    val reqDt = scanIso(p, buf, len) ?: return null

    toNextValue(p, buf, len) || return null  // → customer object
    toNextValue(p, buf, len) || return null  // → avg_amount value
    val customerAvgAmount = scanF32(p, buf, len)

    toNextValue(p, buf, len) || return null  // → tx_count_24h value
    val txCount24h = scanU32(p, buf, len)

    toNextValue(p, buf, len) || return null  // → known_merchants array
    if (p[0] < len && buf[p[0]] == B_BRACKET) p[0]++  // skip '['

    val mStarts = IntArray(16)
    val mLens = IntArray(16)
    var mc = 0
    while (p[0] < len && buf[p[0]] != B_RBRACKET) {
        if (buf[p[0]] == B_QUOTE) {
            p[0]++
            val start = p[0]
            while (p[0] < len && buf[p[0]] != B_QUOTE) p[0]++
            if (p[0] >= len) return null
            if (mc < 16) { mStarts[mc] = start; mLens[mc] = p[0] - start; mc++ }
            p[0]++
        } else {
            p[0]++
        }
    }
    if (p[0] < len) p[0]++  // skip ']'

    toNextValue(p, buf, len) || return null  // → merchant object
    toNextValue(p, buf, len) || return null  // → merchant id value
    if (p[0] >= len || buf[p[0]] != B_QUOTE) return null
    p[0]++
    val midStart = p[0]
    while (p[0] < len && buf[p[0]] != B_QUOTE) p[0]++
    if (p[0] >= len) return null
    val midLen = p[0] - midStart
    p[0]++

    toNextValue(p, buf, len) || return null  // → mcc value
    val mcc = scanMcc(p, buf, len)

    toNextValue(p, buf, len) || return null  // → merchant avg_amount value
    val merchantAvgAmount = scanF32(p, buf, len)

    toNextValue(p, buf, len) || return null  // → terminal object
    toNextValue(p, buf, len) || return null  // → is_online value
    val isOnline = scanBool(p, buf, len)

    toNextValue(p, buf, len) || return null  // → card_present value
    val cardPresent = scanBool(p, buf, len)

    toNextValue(p, buf, len) || return null  // → km_from_home value
    val kmFromHome = scanF32(p, buf, len)

    toNextValue(p, buf, len) || return null  // → last_transaction value
    val hasLastTx = p[0] < len && buf[p[0]] != B_n

    val minutesSinceLast: Int
    val kmFromCurrent: Float
    if (hasLastTx) {
        toNextValue(p, buf, len) || return null  // → timestamp value
        val lastDt = scanIso(p, buf, len) ?: return null
        toNextValue(p, buf, len) || return null  // → km_from_current value
        kmFromCurrent = scanF32(p, buf, len)
        minutesSinceLast = minutesBetween(lastDt, reqDt)
    } else {
        minutesSinceLast = 0
        kmFromCurrent = 0.0f
    }

    val isUnknownMerchant = !merchantMatchesAny(buf, midStart, midLen, mStarts, mLens, mc)

    return Payload(
        amount = amount,
        customerAvgAmount = customerAvgAmount,
        merchantAvgAmount = merchantAvgAmount,
        kmFromHome = kmFromHome,
        kmFromCurrent = kmFromCurrent,
        txCount24h = txCount24h,
        mcc = mcc,
        minutesSinceLast = minutesSinceLast,
        installments = installments,
        hour = reqDt.h,
        dayOfWeek = dayOfWeek(reqDt.y, reqDt.mo, reqDt.d),
        isOnline = isOnline,
        cardPresent = cardPresent,
        isUnknownMerchant = isUnknownMerchant,
        hasLastTx = hasLastTx,
    )
}

// Scan forward to next unquoted ':'. Skip quoted strings encountered along the way.
private fun toNextValue(p: IntArray, buf: ByteArray, len: Int): Boolean {
    while (true) {
        while (p[0] < len && buf[p[0]] != B_COLON && buf[p[0]] != B_QUOTE) p[0]++
        if (p[0] >= len) return false
        if (buf[p[0]] == B_COLON) {
            p[0]++
            while (p[0] < len && (buf[p[0]] == ' '.code.toByte() || buf[p[0]] == '\t'.code.toByte() ||
                    buf[p[0]] == '\n'.code.toByte() || buf[p[0]] == '\r'.code.toByte())) p[0]++
            return true
        }
        // skip quoted string
        p[0]++
        while (p[0] < len && buf[p[0]] != B_QUOTE) p[0]++
        if (p[0] >= len) return false
        p[0]++
    }
}

private fun skipString(p: IntArray, buf: ByteArray, len: Int): Boolean {
    if (p[0] < len && buf[p[0]] == B_QUOTE) p[0]++
    while (p[0] < len && buf[p[0]] != B_QUOTE) p[0]++
    if (p[0] >= len) return false
    p[0]++
    return true
}

private fun scanF32(p: IntArray, buf: ByteArray, len: Int): Float {
    var pos = p[0]
    val neg = pos < len && buf[pos] == B_MINUS
    if (neg) pos++

    var intPart = 0L
    while (pos < len) {
        val d = buf[pos].toInt() - 48  // '0' = 48
        if (d < 0 || d > 9) break
        intPart = intPart * 10 + d
        pos++
    }
    var v = intPart.toDouble()

    if (pos < len && buf[pos] == B_DOT) {
        pos++
        val fracStart = pos
        var frac = 0L
        while (pos < len) {
            val d = buf[pos].toInt() - 48
            if (d < 0 || d > 9) break
            if (pos - fracStart < 18) frac = frac * 10 + d
            pos++
        }
        v += frac.toDouble() * FRAC_POWERS[minOf(pos - fracStart, 18)]
    }

    if (pos < len && (buf[pos] == 'e'.code.toByte() || buf[pos] == 'E'.code.toByte())) {
        pos++
        var esign = 1
        if (pos < len) {
            if (buf[pos] == '+'.code.toByte()) pos++
            else if (buf[pos] == '-'.code.toByte()) { esign = -1; pos++ }
        }
        var e = 0
        while (pos < len) {
            val d = buf[pos].toInt() - 48
            if (d < 0 || d > 9) break
            e = e * 10 + d; pos++
        }
        v *= 10.0.pow(esign * e)
    }

    p[0] = pos
    return (if (neg) -v else v).toFloat()
}

private fun scanU32(p: IntArray, buf: ByteArray, len: Int): Int {
    var v = 0
    while (p[0] < len) {
        val d = buf[p[0]].toInt() - 48
        if (d < 0 || d > 9) break
        v = v * 10 + d; p[0]++
    }
    return v
}

private fun scanBool(p: IntArray, buf: ByteArray, len: Int): Boolean {
    val isTrue = p[0] < len && buf[p[0]] == B_t
    p[0] += if (isTrue) 4 else 5
    return isTrue
}

private fun scanMcc(p: IntArray, buf: ByteArray, len: Int): Int {
    if (p[0] < len && buf[p[0]] == B_QUOTE) p[0]++
    val v = scanU32(p, buf, len)
    if (p[0] < len && buf[p[0]] == B_QUOTE) p[0]++
    return v
}

// Returns (y, mo, d, h, min) encoded in a Long to avoid allocation
// Format: YYYY-MM-DDTHH:MM (positions 0-15 are all we need, 20 bytes total)
private class Dt(val y: Int, val mo: Int, val d: Int, val h: Int, val min: Int)

private fun scanIso(p: IntArray, buf: ByteArray, len: Int): Dt? {
    if (p[0] < len && buf[p[0]] == B_QUOTE) p[0]++
    if (p[0] + 16 > len) return null
    val i = p[0]
    val b = buf
    val y = (b[i].toInt() - 48) * 1000 + (b[i+1].toInt() - 48) * 100 +
            (b[i+2].toInt() - 48) * 10 + (b[i+3].toInt() - 48)
    val mo = (b[i+5].toInt() - 48) * 10 + (b[i+6].toInt() - 48)
    val d  = (b[i+8].toInt() - 48) * 10 + (b[i+9].toInt() - 48)
    val h  = (b[i+11].toInt() - 48) * 10 + (b[i+12].toInt() - 48)
    val mn = (b[i+14].toInt() - 48) * 10 + (b[i+15].toInt() - 48)
    p[0] += 16
    // skip rest of timestamp to closing '"'
    while (p[0] < len && buf[p[0]] != B_QUOTE) p[0]++
    if (p[0] < len) p[0]++
    return Dt(y, mo, d, h, mn)
}

private fun merchantMatchesAny(
    buf: ByteArray, idStart: Int, idLen: Int,
    starts: IntArray, lens: IntArray, count: Int
): Boolean {
    for (i in 0 until count) {
        if (lens[i] != idLen) continue
        var match = true
        for (j in 0 until idLen) {
            if (buf[starts[i] + j] != buf[idStart + j]) { match = false; break }
        }
        if (match) return true
    }
    return false
}

private fun dayOfWeek(y: Int, m: Int, d: Int): Int {
    val ya = if (m < 3) y - 1 else y
    val dow = (ya + ya / 4 - ya / 100 + ya / 400 + DOW_T[m - 1] + d) % 7
    return (dow + 6) % 7
}

private fun daysSinceEpoch(y: Int, m: Int, d: Int): Long {
    val yy = if (m <= 2) y - 1 else y
    val era = if (yy >= 0) yy / 400 else (yy - 399) / 400
    val yoe = yy - era * 400
    val mm = if (m > 2) m - 3 else m + 9
    val doy = (153 * mm + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097 + doe.toLong() - 719468
}

private fun minutesBetween(from: Dt, to: Dt): Int {
    val d1 = daysSinceEpoch(from.y, from.mo, from.d)
    val d2 = daysSinceEpoch(to.y, to.mo, to.d)
    val m1 = d1 * 1440 + from.h * 60 + from.min
    val m2 = d2 * 1440 + to.h * 60 + to.min
    return maxOf(0L, m2 - m1).toInt()
}
