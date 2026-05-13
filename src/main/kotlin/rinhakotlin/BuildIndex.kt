package rinhakotlin

import java.io.DataInputStream
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.min
import kotlin.math.roundToInt

private const val DIMS = 14
private const val BLOCK = 8
private const val INDEX_MAGIC = 0x3149564936324852L   // "RH26IVI1" LE
private const val K_DEFAULT = 4096
private const val SAMPLE_DEFAULT = 50_000
private const val ITERS_DEFAULT = 10

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: build-index <references.json.gz> <output.bin> [k=$K_DEFAULT] [sample=$SAMPLE_DEFAULT] [iters=$ITERS_DEFAULT]")
        return
    }
    val refsPath = args[0]
    val outPath  = args[1]
    val k          = if (args.size > 2) args[2].toInt() else K_DEFAULT
    val sampleSize = if (args.size > 3) args[3].toInt() else SAMPLE_DEFAULT
    val iters      = if (args.size > 4) args[4].toInt() else ITERS_DEFAULT

    val t0 = System.currentTimeMillis()
    System.err.println("Parsing $refsPath...")
    val (vectors, labels) = parseRefs(refsPath)
    val n = labels.size
    System.err.println("Parsed $n refs in ${System.currentTimeMillis() - t0}ms")

    val sample = makeSample(n, sampleSize, 42L)
    System.err.println("k-means++ init (k=$k, sample=${sample.size})...")
    val centroids = kMeansPPInit(vectors, sample, k, 42L)
    trainKMeans(vectors, sample, centroids, k, iters)

    System.err.println("Assigning all $n vectors...")
    val tA = System.currentTimeMillis()
    val (assignment, counts) = assignAll(vectors, n, centroids, k)
    System.err.println("Assigned in ${System.currentTimeMillis() - tA}ms")

    val minC = counts.min(); val maxC = counts.max()
    val meanC = counts.map { it.toLong() }.sum() / k
    System.err.println("Cluster sizes: min=$minC max=$maxC mean=$meanC")

    writeIndex(outPath, vectors, labels, centroids, assignment, counts, k, n)
    System.err.println("Done in ${(System.currentTimeMillis() - t0) / 1000}s")
}

private fun qround(v: Float): Short = (v.coerceIn(-1f, 1f) * 10000f).roundToInt().toShort()

private fun parseRefs(path: String): Pair<ShortArray, ByteArray> {
    System.err.println("  decompressing...")
    val raw = GZIPInputStream(FileInputStream(path).buffered(1 shl 20), 1 shl 20).readBytes()
    System.err.println("  decompressed: ${raw.size / 1024 / 1024}MB")

    val maxN = 3_500_000
    val vecBuf = ShortArray(maxN * DIMS)
    val lblBuf = ByteArray(maxN)
    var count = 0
    var vecPos = 0

    val VEC = "\"vector\"".toByteArray()
    val LBL = "\"label\"".toByteArray()
    val LBRK = '['.code.toByte()
    val COLON = ':'.code.toByte()
    val QUOTE = '"'.code.toByte()

    fun matchAt(pos: Int, pat: ByteArray): Boolean {
        if (pos + pat.size > raw.size) return false
        for (i in pat.indices) if (raw[pos + i] != pat[i]) return false
        return true
    }

    var i = 0
    while (i < raw.size) {
        // find "vector"
        if (raw[i] != QUOTE || !matchAt(i, VEC)) { i++; continue }
        i += VEC.size
        while (i < raw.size && raw[i] != LBRK) i++
        i++

        for (d in 0 until DIMS) {
            while (i < raw.size) { val c = raw[i].toInt() and 0xFF; if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == ','.code) i++ else break }
            val start = i
            if (i < raw.size && raw[i] == '-'.code.toByte()) i++
            while (i < raw.size) {
                val c = raw[i].toInt() and 0xFF
                if (c in '0'.code..'9'.code || c == '.'.code || c == 'e'.code || c == 'E'.code || c == '+'.code || c == '-'.code) i++ else break
            }
            vecBuf[vecPos++] = qround(raw.decodeToString(start, i).toFloat())
        }

        // find "label"
        while (i < raw.size) { if (raw[i] == QUOTE && matchAt(i, LBL)) { i += LBL.size; break }; i++ }
        while (i < raw.size && raw[i] != COLON) i++; i++
        while (i < raw.size && raw[i] != QUOTE) i++; i++
        lblBuf[count++] = if (raw[i] == 'f'.code.toByte()) 1 else 0

        if (count % 200_000 == 0) System.err.println("  parsed $count...")
    }

    check(count > 0 && vecPos == count * DIMS) { "parse error: count=$count vecPos=$vecPos" }
    return vecBuf.copyOf(count * DIMS) to lblBuf.copyOf(count)
}

private fun makeSample(n: Int, size: Int, seed: Long): IntArray {
    val sz = min(size, n)
    val rng = java.util.Random(seed)
    return IntArray(sz) { rng.nextInt(n) }
}

private fun distToFloat(vectors: ShortArray, id: Int, centroid: FloatArray): Float {
    var s = 0f
    val base = id * DIMS
    for (d in 0 until DIMS) { val diff = vectors[base + d] - centroid[d]; s += diff * diff }
    return s
}

private fun kMeansPPInit(vectors: ShortArray, sample: IntArray, k: Int, seed: Long): FloatArray {
    val centroids = FloatArray(k * DIMS)
    val dmin = FloatArray(sample.size) { Float.POSITIVE_INFINITY }
    val rng = java.util.Random(seed)

    // First centroid: random sample point
    val first = sample[rng.nextInt(sample.size)]
    for (d in 0 until DIMS) centroids[d] = vectors[first * DIMS + d].toFloat()

    for (c in 1 until k) {
        val prevBase = (c - 1) * DIMS
        val prev = FloatArray(DIMS) { centroids[prevBase + it] }
        var sum = 0.0
        for (i in sample.indices) {
            val p = vectors
            val id = sample[i]
            var s = 0f
            val b = id * DIMS
            for (d in 0 until DIMS) { val diff = p[b + d] - prev[d]; s += diff * diff }
            if (s < dmin[i]) dmin[i] = s
            sum += dmin[i]
        }
        val cBase = c * DIMS
        if (sum <= 0.0) {
            val pick = sample[rng.nextInt(sample.size)]
            for (d in 0 until DIMS) centroids[cBase + d] = vectors[pick * DIMS + d].toFloat()
            continue
        }
        var target = rng.nextDouble() * sum
        var chosen = sample.size - 1
        for (i in sample.indices) { target -= dmin[i]; if (target <= 0.0) { chosen = i; break } }
        val pick = sample[chosen]
        for (d in 0 until DIMS) centroids[cBase + d] = vectors[pick * DIMS + d].toFloat()
        if ((c and 255) == 0) System.err.println("  init centroid $c/$k")
    }
    return centroids
}

private fun nearestCentroid(vectors: ShortArray, id: Int, centroids: FloatArray, k: Int): Int {
    val base = id * DIMS
    var best = 0; var bestD = Float.POSITIVE_INFINITY
    for (c in 0 until k) {
        val cb = c * DIMS
        var s = 0f
        for (d in 0 until DIMS) { val diff = vectors[base + d] - centroids[cb + d]; s += diff * diff }
        if (s < bestD) { bestD = s; best = c }
    }
    return best
}

private fun trainKMeans(vectors: ShortArray, sample: IntArray, centroids: FloatArray, k: Int, iters: Int) {
    val assign = IntArray(sample.size)
    for (iter in 0 until iters) {
        var changed = 0L
        for (i in sample.indices) {
            val c = nearestCentroid(vectors, sample[i], centroids, k)
            if (c != assign[i]) { changed++; assign[i] = c }
        }

        val sums = DoubleArray(k * DIMS)
        val counts = IntArray(k)
        for (i in sample.indices) {
            val c = assign[i]; val b = sample[i] * DIMS; val cb = c * DIMS
            counts[c]++
            for (d in 0 until DIMS) sums[cb + d] += vectors[b + d]
        }

        val rng = java.util.Random(0xC0FFEEL + iter)
        for (c in 0 until k) {
            val cb = c * DIMS
            if (counts[c] == 0) {
                val pick = sample[rng.nextInt(sample.size)]
                for (d in 0 until DIMS) centroids[cb + d] = vectors[pick * DIMS + d].toFloat()
            } else {
                val inv = 1.0 / counts[c]
                for (d in 0 until DIMS) centroids[cb + d] = (sums[cb + d] * inv).toFloat()
            }
        }
        System.err.println("  iter ${iter + 1}/$iters changed=$changed")
    }
}

private fun assignAll(vectors: ShortArray, n: Int, centroids: FloatArray, k: Int): Pair<ShortArray, IntArray> {
    val assignment = ShortArray(n)
    val localCounts = Array(Runtime.getRuntime().availableProcessors()) { IntArray(k) }

    (0 until n).toList().parallelStream().forEach { i ->
        val tid = (Thread.currentThread().threadId() % localCounts.size).toInt()
        val c = nearestCentroid(vectors, i, centroids, k)
        assignment[i] = c.toShort()
        localCounts[tid][c]++
    }

    val counts = IntArray(k)
    for (lc in localCounts) for (c in 0 until k) counts[c] += lc[c]
    return assignment to counts
}

private fun alignUp(v: Long, a: Long) = (v + a - 1) and (a - 1).inv()

private fun writeIndex(
    outPath: String,
    vectors: ShortArray,
    labels: ByteArray,
    centroids: FloatArray,
    assignment: ShortArray,
    counts: IntArray,
    k: Int,
    n: Int,
) {
    val totalBlocks = counts.fold(0L) { acc, c -> acc + (c + BLOCK - 1) / BLOCK }.toInt()

    // Layout matching C++ layout_for
    var off = 64L
    val centroidsOff = off; off += k.toLong() * DIMS * 2
    val bboxMinOff = off; off += k.toLong() * DIMS * 2
    val bboxMaxOff = off; off += k.toLong() * DIMS * 2
    off = alignUp(off, 4)
    val offsetsOff = off; off += (k.toLong() + 1) * 4
    val countsOff = off; off += k.toLong() * 4
    val labelsOff = off; off += totalBlocks.toLong() * BLOCK
    off = alignUp(off, 2)
    val blocksOff = off; off += totalBlocks.toLong() * DIMS * BLOCK * 2
    val totalSize = off

    // Build block offsets and sort order
    val blockOffsets = IntArray(k + 1)
    for (c in 0 until k) blockOffsets[c + 1] = blockOffsets[c] + (counts[c] + BLOCK - 1) / BLOCK

    val starts = IntArray(k + 1)
    for (c in 0 until k) starts[c + 1] = starts[c] + counts[c]
    val cursor = starts.copyOf()
    val order = IntArray(n)
    for (i in 0 until n) { val c = assignment[i].toInt() and 0xFFFF; order[cursor[c]++] = i }

    // Quantize centroids (row-major c*DIMS+d)
    val qCentroids = ShortArray(k * DIMS) { idx ->
        centroids[idx].roundToInt().toShort()
    }

    // Build bbox, out_labels, blocks
    // Padding lanes initialized to Short.MAX_VALUE so their distance is huge (won't enter top-5)
    val bboxMin = ShortArray(k * DIMS) { Short.MAX_VALUE }
    val bboxMax = ShortArray(k * DIMS) { Short.MIN_VALUE }
    val outLabels = ByteArray(totalBlocks * BLOCK)
    val blocks = ShortArray(totalBlocks * DIMS * BLOCK) { Short.MAX_VALUE }

    for (c in 0 until k) {
        if (counts[c] == 0) {
            for (d in 0 until DIMS) { bboxMin[c * DIMS + d] = 0; bboxMax[c * DIMS + d] = 0 }
            continue
        }
        for (pos in 0 until counts[c]) {
            val orig = order[starts[c] + pos]
            val block = blockOffsets[c] + pos / BLOCK
            val lane = pos % BLOCK
            outLabels[block * BLOCK + lane] = labels[orig]
            val srcBase = orig * DIMS
            val dstBase = block * DIMS * BLOCK
            for (d in 0 until DIMS) {
                val v = vectors[srcBase + d]
                blocks[dstBase + d * BLOCK + lane] = v
                val idx = c * DIMS + d
                if (v < bboxMin[idx]) bboxMin[idx] = v
                if (v > bboxMax[idx]) bboxMax[idx] = v
            }
        }
    }

    System.err.println("Writing $outPath (${totalSize / 1024 / 1024}MB)...")
    RandomAccessFile(outPath, "rw").use { raf ->
        raf.setLength(totalSize)
        val ch = raf.channel

        // Header 64 bytes LE
        val hdr = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putLong(INDEX_MAGIC)
        hdr.putInt(1)   // version
        hdr.putInt(n)
        hdr.putInt(k)
        hdr.putInt(totalBlocks)
        hdr.putInt(BLOCK)
        hdr.putInt(DIMS)
        repeat(8) { hdr.putInt(0) }
        hdr.flip(); ch.write(hdr, 0)

        fun writeShorts(arr: ShortArray, fileOff: Long) {
            val CHUNK = 32_768
            var pos = 0; var fp = fileOff
            while (pos < arr.size) {
                val len = min(CHUNK, arr.size - pos)
                val buf = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (j in pos until pos + len) buf.putShort(arr[j])
                buf.flip(); ch.write(buf, fp); fp += len * 2L; pos += len
            }
        }

        fun writeInts(arr: IntArray, fileOff: Long) {
            val CHUNK = 16_384
            var pos = 0; var fp = fileOff
            while (pos < arr.size) {
                val len = min(CHUNK, arr.size - pos)
                val buf = ByteBuffer.allocate(len * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (j in pos until pos + len) buf.putInt(arr[j])
                buf.flip(); ch.write(buf, fp); fp += len * 4L; pos += len
            }
        }

        writeShorts(qCentroids, centroidsOff)
        writeShorts(bboxMin, bboxMinOff)
        writeShorts(bboxMax, bboxMaxOff)
        writeInts(blockOffsets, offsetsOff)
        writeInts(counts, countsOff)
        ch.write(ByteBuffer.wrap(outLabels), labelsOff)
        writeShorts(blocks, blocksOff)
    }

    System.err.println("Written $outPath (${totalSize / 1024 / 1024}MB)")
}