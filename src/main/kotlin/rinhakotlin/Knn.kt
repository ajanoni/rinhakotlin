package rinhakotlin

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.VectorOperators

private const val FAST_NPROBE = 8
private const val FULL_NPROBE = 24
private const val VECTOR_SCALE = 0.0001f

private val F256 = FloatVector.SPECIES_256
private val S128 = ShortVector.SPECIES_128
private val I256 = IntVector.SPECIES_256

fun knn5FraudCount(query: FloatArray, ds: Dataset): Int {
    val dists = FloatArray(ds.k)
    computeCentroidDists(query, ds.centroids, ds.k, dists)

    val fastProbes = topN(dists, ds.k, FAST_NPROBE)
    val fast = scanAndCount(fastProbes, FAST_NPROBE, ds, query)
    if (fast != 2 && fast != 3) return fast

    val fullProbes = topN(dists, ds.k, FULL_NPROBE)
    return scanAndCount(fullProbes, FULL_NPROBE, ds, query)
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

private fun computeCentroidDists(query: FloatArray, centroids: FloatArray, k: Int, dists: FloatArray) {
    // dim 0: write (not accumulate)
    val qd0 = FloatVector.broadcast(F256, query[0])
    var ci = 0
    while (ci + 8 <= k) {
        val diff = FloatVector.fromArray(F256, centroids, ci).sub(qd0)
        diff.mul(diff).intoArray(dists, ci)
        ci += 8
    }
    while (ci < k) { val d = centroids[ci] - query[0]; dists[ci] = d * d; ci++ }

    // dims 1-13: accumulate with fma
    for (dim in 1..13) {
        val base = dim * k
        val qd = FloatVector.broadcast(F256, query[dim])
        ci = 0
        while (ci + 8 <= k) {
            val diff = FloatVector.fromArray(F256, centroids, base + ci).sub(qd)
            val acc = FloatVector.fromArray(F256, dists, ci)
            diff.fma(diff, acc).intoArray(dists, ci)
            ci += 8
        }
        while (ci < k) { val d = centroids[base + ci] - query[dim]; dists[ci] += d * d; ci++ }
    }
}

private fun topN(dists: FloatArray, k: Int, n: Int): IntArray {
    val topDists = FloatArray(n) { Float.POSITIVE_INFINITY }
    val topIdx = IntArray(n)
    for (ci in 0 until k) {
        val di = dists[ci]
        if (di < topDists[n - 1]) {
            var lo = 0; var hi = n
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (topDists[mid] < di) lo = mid + 1 else hi = mid
            }
            for (j in n - 1 downTo lo + 1) {
                topDists[j] = topDists[j - 1]; topIdx[j] = topIdx[j - 1]
            }
            topDists[lo] = di; topIdx[lo] = ci
        }
    }
    return topIdx
}

private fun scanAndCount(probes: IntArray, nProbes: Int, ds: Dataset, query: FloatArray): Int {
    val qvecs = Array(14) { d -> FloatVector.broadcast(F256, query[d]) }
    val topDist = FloatArray(5) { Float.POSITIVE_INFINITY }
    val topLabel = ByteArray(5)
    val worstIdxArr = IntArray(1)  // boxed by-ref worst index

    for (pi in 0 until nProbes) {
        val ci = probes[pi]
        scanBlocks(qvecs, ds.blocks, ds.labels, ds.offsets[ci], ds.offsets[ci + 1],
            topDist, topLabel, worstIdxArr)
    }

    var count = 0
    for (l in topLabel) if (l == 1.toByte()) count++
    return count
}

@Suppress("UNCHECKED_CAST")
private fun scanBlocks(
    qvecs: Array<FloatVector>,
    blocks: ShortArray,
    labels: ByteArray,
    startBlock: Int,
    endBlock: Int,
    topDist: FloatArray,
    topLabel: ByteArray,
    worstIdxArr: IntArray,
) {
    val distBuf = FloatArray(8)

    for (blockI in startBlock until endBlock) {
        val bb = blockI * 112
        val threshold = topDist[worstIdxArr[0]]

        // load 8 i16 values for dim, dequantize, subtract query
        fun ld(dim: Int): FloatVector {
            val sv = ShortVector.fromArray(S128, blocks, bb + dim * 8)
            val iv = sv.convertShape(VectorOperators.S2I, I256, 0) as IntVector
            val fv = iv.convert(VectorOperators.I2F, 0) as FloatVector
            return fv.mul(VECTOR_SCALE).sub(qvecs[dim])
        }

        // First 8 dims — two-accumulator ILP pattern, early exit after partial sum
        val d0 = ld(0); var acc0 = d0.mul(d0)
        val d1 = ld(1); var acc1 = d1.mul(d1)
        val d2 = ld(2); acc0 = d2.fma(d2, acc0)
        val d3 = ld(3); acc1 = d3.fma(d3, acc1)
        val d4 = ld(4); acc0 = d4.fma(d4, acc0)
        val d5 = ld(5); acc1 = d5.fma(d5, acc1)
        val d6 = ld(6); acc0 = d6.fma(d6, acc0)
        val d7 = ld(7); acc1 = d7.fma(d7, acc1)

        // If no lane's partial distance < threshold, all 8 vectors can't enter top-5
        val partial = acc0.add(acc1)
        if (!partial.compare(VectorOperators.LT, threshold).anyTrue()) continue

        // Remaining 6 dims
        val d8 = ld(8); acc0 = d8.fma(d8, acc0)
        val d9 = ld(9); acc1 = d9.fma(d9, acc1)
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
                for (j in 1..4) {
                    if (topDist[j] > maxDist) { maxDist = topDist[j]; maxIdx = j }
                }
                worstIdxArr[0] = maxIdx
            }
        }
    }
}
