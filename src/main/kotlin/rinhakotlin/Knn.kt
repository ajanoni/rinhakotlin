package rinhakotlin

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.VectorOperators
import java.lang.foreign.MemorySegment
import java.nio.ByteOrder

private const val VECTOR_SCALE = 0.0001f

private val F256 = FloatVector.SPECIES_256
private val S128 = ShortVector.SPECIES_128
private val I256 = IntVector.SPECIES_256

private class KnnState(k: Int) {
    val dists    = FloatArray(k)
    val qvecs    = arrayOfNulls<FloatVector>(14)
    val topDist  = FloatArray(5)
    val topLabel = ByteArray(5)
    val worstIdx = IntArray(1)
    val distBuf  = FloatArray(8)
}

private val tlState = ThreadLocal.withInitial { KnnState(4096) }

fun knn5FraudCount(query: FloatArray, ds: Dataset): Int {
    val s = tlState.get().also { st ->
        if (st.dists.size != ds.k) tlState.set(KnnState(ds.k))
    }.let { if (it.dists.size != ds.k) tlState.get() else it }

    computeCentroidDists(query, ds.centroids, ds.k, s.dists)
    val best0 = findMin(s.dists, ds.k)

    for (d in 0..13) s.qvecs[d] = FloatVector.broadcast(F256, query[d])
    @Suppress("UNCHECKED_CAST")
    val qvecs = s.qvecs as Array<FloatVector>

    s.topDist.fill(Float.POSITIVE_INFINITY)
    s.topLabel.fill(0)
    s.worstIdx[0] = 0

    scanBlocks(qvecs, ds.blocksSeg, ds.labels, ds.offsets[best0], ds.offsets[best0 + 1],
        s.topDist, s.topLabel, s.worstIdx, s.distBuf)

    val initial = fraudCount(s.topLabel)
    if (initial < 2 || initial > 3) return initial

    for (ci in 0 until ds.k) {
        if (ci == best0) continue
        if (bboxLowerBound(query, ds.bboxMin, ds.bboxMax, ci) >= s.topDist[s.worstIdx[0]]) continue
        scanBlocks(qvecs, ds.blocksSeg, ds.labels, ds.offsets[ci], ds.offsets[ci + 1],
            s.topDist, s.topLabel, s.worstIdx, s.distBuf)
    }

    return fraudCount(s.topLabel)
}

fun warmup(ds: Dataset) {
    var state = 0x12345678
    val q = FloatArray(14)
    repeat(500) {
        for (i in 0..13) {
            state = state * 1664525 + 1013904223
            q[i] = state.ushr(8).toFloat() / 16777216.0f
        }
        knn5FraudCount(q, ds)
    }
}

private fun fraudCount(topLabel: ByteArray): Int {
    var n = 0
    for (l in topLabel) if (l == 1.toByte()) n++
    return n
}

private fun findMin(dists: FloatArray, k: Int): Int {
    var best = 0; var bestD = dists[0]
    for (i in 1 until k) if (dists[i] < bestD) { bestD = dists[i]; best = i }
    return best
}

private fun computeCentroidDists(query: FloatArray, centroids: ShortArray, k: Int, dists: FloatArray) {
    val qd0 = FloatVector.broadcast(F256, query[0])
    var ci = 0
    while (ci + 8 <= k) {
        val sv = ShortVector.fromArray(S128, centroids, ci)
        val fv = (sv.convertShape(VectorOperators.S2I, I256, 0) as IntVector)
            .convert(VectorOperators.I2F, 0) as FloatVector
        val diff = fv.mul(VECTOR_SCALE).sub(qd0)
        diff.mul(diff).intoArray(dists, ci)
        ci += 8
    }
    while (ci < k) { val d = centroids[ci] * VECTOR_SCALE - query[0]; dists[ci] = d * d; ci++ }

    for (dim in 1..13) {
        val base = dim * k
        val qd = FloatVector.broadcast(F256, query[dim])
        ci = 0
        while (ci + 8 <= k) {
            val sv = ShortVector.fromArray(S128, centroids, base + ci)
            val fv = (sv.convertShape(VectorOperators.S2I, I256, 0) as IntVector)
                .convert(VectorOperators.I2F, 0) as FloatVector
            val diff = fv.mul(VECTOR_SCALE).sub(qd)
            val acc = FloatVector.fromArray(F256, dists, ci)
            diff.fma(diff, acc).intoArray(dists, ci)
            ci += 8
        }
        while (ci < k) { val d = centroids[base + ci] * VECTOR_SCALE - query[dim]; dists[ci] += d * d; ci++ }
    }
}

private fun bboxLowerBound(query: FloatArray, bboxMin: ShortArray, bboxMax: ShortArray, ci: Int): Float {
    var s = 0f
    val base = ci * 14
    for (d in 0..13) {
        val mn = bboxMin[base + d] * VECTOR_SCALE
        val mx = bboxMax[base + d] * VECTOR_SCALE
        val diff = if (query[d] < mn) mn - query[d] else if (query[d] > mx) query[d] - mx else 0f
        s += diff * diff
    }
    return s
}

@Suppress("UNCHECKED_CAST")
private fun scanBlocks(
    qvecs: Array<FloatVector>,
    seg: MemorySegment,
    labels: ByteArray,
    startBlock: Int,
    endBlock: Int,
    topDist: FloatArray,
    topLabel: ByteArray,
    worstIdxArr: IntArray,
    distBuf: FloatArray,
) {
    for (blockI in startBlock until endBlock) {
        val bb = blockI * 112
        val threshold = topDist[worstIdxArr[0]]

        fun ld(dim: Int): FloatVector {
            val byteOff = ((bb + dim * 8) * 2).toLong()
            val sv = ShortVector.fromMemorySegment(S128, seg, byteOff, ByteOrder.LITTLE_ENDIAN)
            val iv = sv.convertShape(VectorOperators.S2I, I256, 0) as IntVector
            val fv = iv.convert(VectorOperators.I2F, 0) as FloatVector
            return fv.mul(VECTOR_SCALE).sub(qvecs[dim])
        }

        val d0 = ld(0); var acc0 = d0.mul(d0)
        val d1 = ld(1); var acc1 = d1.mul(d1)
        val d2 = ld(2); acc0 = d2.fma(d2, acc0)
        val d3 = ld(3); acc1 = d3.fma(d3, acc1)
        val d4 = ld(4); acc0 = d4.fma(d4, acc0)
        val d5 = ld(5); acc1 = d5.fma(d5, acc1)
        val d6 = ld(6); acc0 = d6.fma(d6, acc0)
        val d7 = ld(7); acc1 = d7.fma(d7, acc1)

        val partial = acc0.add(acc1)
        if (!partial.compare(VectorOperators.LT, threshold).anyTrue()) continue

        val d8  = ld(8);  acc0 = d8.fma(d8,   acc0)
        val d9  = ld(9);  acc1 = d9.fma(d9,   acc1)
        val d10 = ld(10); acc0 = d10.fma(d10, acc0)
        val d11 = ld(11); acc1 = d11.fma(d11, acc1)
        val d12 = ld(12); acc0 = d12.fma(d12, acc0)
        val d13 = ld(13); acc1 = d13.fma(d13, acc1)

        val acc = acc0.add(acc1)
        val finalMask = acc.compare(VectorOperators.LT, threshold)
        if (!finalMask.anyTrue()) continue

        acc.intoArray(distBuf, 0)
        val labelBase = blockI * 8
        var bits = finalMask.toLong().toInt()
        while (bits != 0) {
            val slot = bits.countTrailingZeroBits()
            bits = bits and (bits - 1)
            val di = distBuf[slot]
            val wi = worstIdxArr[0]
            if (di < topDist[wi]) {
                topDist[wi] = di
                topLabel[wi] = labels[labelBase + slot]
                var maxDist = topDist[0]; var maxIdx = 0
                for (j in 1..4) { if (topDist[j] > maxDist) { maxDist = topDist[j]; maxIdx = j } }
                worstIdxArr[0] = maxIdx
            }
        }
    }
}
