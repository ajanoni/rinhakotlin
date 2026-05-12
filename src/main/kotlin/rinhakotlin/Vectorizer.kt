package rinhakotlin

data class Payload(
    val amount: Float,
    val customerAvgAmount: Float,
    val merchantAvgAmount: Float,
    val kmFromHome: Float,
    val kmFromCurrent: Float,
    val txCount24h: Int,
    val mcc: Int,
    val minutesSinceLast: Int,
    val installments: Int,
    val hour: Int,
    val dayOfWeek: Int,
    val isOnline: Boolean,
    val cardPresent: Boolean,
    val isUnknownMerchant: Boolean,
    val hasLastTx: Boolean,
)

fun vectorize(p: Payload): FloatArray {
    val v = FloatArray(14)

    v[0] = clamp01(p.amount / 10_000.0f)
    v[1] = clamp01(p.installments / 12.0f)
    val ratio = if (p.customerAvgAmount > 0.0f) {
        (p.amount / p.customerAvgAmount) / 10.0f
    } else {
        1.0f
    }
    v[2] = clamp01(ratio)
    v[3] = round4(p.hour / 23.0f)
    v[4] = round4(p.dayOfWeek / 6.0f)
    if (p.hasLastTx) {
        v[5] = clamp01(p.minutesSinceLast / 1440.0f)
        v[6] = clamp01(p.kmFromCurrent / 1000.0f)
    } else {
        v[5] = -1.0f
        v[6] = -1.0f
    }
    v[7] = clamp01(p.kmFromHome / 1000.0f)
    v[8] = clamp01(p.txCount24h / 20.0f)
    v[9] = if (p.isOnline) 1.0f else 0.0f
    v[10] = if (p.cardPresent) 1.0f else 0.0f
    v[11] = if (p.isUnknownMerchant) 1.0f else 0.0f
    v[12] = mccRisk(p.mcc)
    v[13] = clamp01(p.merchantAvgAmount / 10_000.0f)

    return v
}

private fun round4(x: Float): Float = (x * 10000.0f).let {
    kotlin.math.round(it) * 0.0001f
}

private fun clamp01(v: Float): Float = round4(v.coerceIn(0.0f, 1.0f))

private fun mccRisk(mcc: Int): Float = when (mcc) {
    5411 -> 0.15f
    5812 -> 0.30f
    5912 -> 0.20f
    5944 -> 0.45f
    7801 -> 0.80f
    7802 -> 0.75f
    7995 -> 0.85f
    4511 -> 0.35f
    5311 -> 0.25f
    5999 -> 0.50f
    else -> 0.50f
}