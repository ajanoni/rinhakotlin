package rinhakotlin

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

data class Dataset(
    val centroids: FloatArray,  // d*k, column-major: centroid[dim][clusterIdx]
    val offsets: IntArray,      // k+1 block offsets per cluster
    val labels: ByteArray,      // paddedN labels (0=legit, 1=fraud)
    val blocks: ByteBuffer,     // mmap'd: totalBlocks * 112 i16s LE, column-major within block
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

            val header = readIntArrayLE(dis, 3)
            val n = header[0]; val k = header[1]; val d = header[2]
            check(d == 14) { "expected d=14, got $d" }

            val centroids = readFloatArrayLE(dis, d * k)

            val offsets = readIntArrayLE(dis, k + 1)
            val totalBlocks = offsets[k]
            val paddedN = totalBlocks * 8

            val labels = ByteArray(paddedN)
            dis.readFully(labels)

            val blocks = mmapBlocks(dis, totalBlocks)

            return Dataset(centroids, offsets, labels, blocks, k, n, paddedN)
        }
    }

    // Write raw LE bytes directly to a temp file and mmap READ_ONLY.
    // The 80MB blocks array lives in the OS page cache instead of JVM heap;
    // only the clusters actually probed (8-24 of 4096) stay resident.
    private fun mmapBlocks(dis: DataInputStream, totalBlocks: Int): ByteBuffer {
        val totalBytes = totalBlocks.toLong() * 112 * 2
        val file = java.io.File("/tmp/rinha-blocks.bin")
        val buf = ByteArray(65536)
        java.io.FileOutputStream(file).use { fos ->
            var remaining = totalBytes
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                dis.readFully(buf, 0, toRead)
                fos.write(buf, 0, toRead)
                remaining -= toRead
            }
        }
        return java.io.RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, totalBytes)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            }
        }
    }
}

private fun readIntArrayLE(dis: DataInputStream, count: Int): IntArray {
    val result = IntArray(count)
    val buf = ByteArray(65536)
    var pos = 0
    var remaining = count * 4
    while (remaining > 0) {
        val toRead = minOf(buf.size, remaining)
        dis.readFully(buf, 0, toRead)
        val quads = toRead / 4
        for (i in 0 until quads) {
            val b = i * 4
            result[pos++] = (buf[b].toInt() and 0xFF) or
                ((buf[b+1].toInt() and 0xFF) shl 8) or
                ((buf[b+2].toInt() and 0xFF) shl 16) or
                ((buf[b+3].toInt() and 0xFF) shl 24)
        }
        remaining -= toRead
    }
    return result
}

private fun readFloatArrayLE(dis: DataInputStream, count: Int): FloatArray {
    val result = FloatArray(count)
    val buf = ByteArray(65536)
    var pos = 0
    var remaining = count * 4
    while (remaining > 0) {
        val toRead = minOf(buf.size, remaining)
        dis.readFully(buf, 0, toRead)
        val quads = toRead / 4
        for (i in 0 until quads) {
            val b = i * 4
            val bits = (buf[b].toInt() and 0xFF) or
                ((buf[b+1].toInt() and 0xFF) shl 8) or
                ((buf[b+2].toInt() and 0xFF) shl 16) or
                ((buf[b+3].toInt() and 0xFF) shl 24)
            result[pos++] = java.lang.Float.intBitsToFloat(bits)
        }
        remaining -= toRead
    }
    return result
}