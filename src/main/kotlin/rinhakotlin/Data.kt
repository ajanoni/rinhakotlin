package rinhakotlin

import java.io.DataInputStream
import java.util.zip.GZIPInputStream

data class Dataset(
    val centroids: FloatArray,  // d*k, column-major: centroid[dim][clusterIdx]
    val offsets: IntArray,      // k+1 block offsets per cluster
    val labels: ByteArray,      // paddedN labels (0=legit, 1=fraud)
    val blocks: ShortArray,     // totalBlocks * 112 i16s, column-major within block
    val k: Int,
    val n: Int,
    val paddedN: Int,
)

object DataLoader {
    val dataset: Dataset by lazy { load() }

    private val MAGIC = byteArrayOf('I'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())

    private fun load(): Dataset {
        val stream = DataLoader::class.java.getResourceAsStream("/data/index.bin.gz")
            ?: error("index.bin.gz not found in classpath")

        DataInputStream(GZIPInputStream(stream).buffered(65536)).use { dis ->
            val magic = ByteArray(4)
            dis.readFully(magic)
            check(magic.contentEquals(MAGIC)) { "bad magic: ${magic.decodeToString()}" }

            val n = dis.readIntLE()
            val k = dis.readIntLE()
            val d = dis.readIntLE()
            check(d == 14) { "expected d=14, got $d" }

            val centroids = FloatArray(d * k) { dis.readFloatLE() }

            val offsets = IntArray(k + 1) { dis.readIntLE() }
            val totalBlocks = offsets[k]
            val paddedN = totalBlocks * 8

            val labels = ByteArray(paddedN)
            dis.readFully(labels)

            val blocks = ShortArray(totalBlocks * 112) { dis.readShortLE() }

            return Dataset(centroids, offsets, labels, blocks, k, n, paddedN)
        }
    }
}

private fun DataInputStream.readIntLE(): Int {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun DataInputStream.readFloatLE(): Float =
    java.lang.Float.intBitsToFloat(readIntLE())

private fun DataInputStream.readShortLE(): Short {
    val b0 = read()
    val b1 = read()
    return ((b0 or (b1 shl 8)) and 0xFFFF).toShort()
}
