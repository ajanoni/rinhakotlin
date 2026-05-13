package rinhakotlin

import java.io.DataInputStream
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

data class Dataset(
    val centroids: ShortArray,  // SoA: centroids[dim * k + ci]
    val bboxMin: ShortArray,    // row-major: bboxMin[ci * 14 + dim]
    val bboxMax: ShortArray,    // row-major: bboxMax[ci * 14 + dim]
    val offsets: IntArray,      // k+1 block offsets
    val labels: ByteArray,      // total_blocks * 8
    val blocks: ByteBuffer,     // mmap'd: total_blocks * 14 * 8 * i16 LE, column-major within block
    val blocksSeg: MemorySegment, // pre-wrapped segment of blocks, avoids per-request allocation
    val k: Int,
    val n: Int,
    val paddedN: Int,
)

object DataLoader {
    val dataset: Dataset by lazy { load() }

    // Magic bytes: 0x3149564936324852 LE = "RH26IVI1"
    private val MAGIC = byteArrayOf(0x52, 0x48, 0x32, 0x36, 0x49, 0x56, 0x49, 0x31)

    private fun load(): Dataset {
        val stream = DataLoader::class.java.getResourceAsStream("/data/index.bin.gz")
            ?: error("index.bin.gz not found in classpath")

        DataInputStream(GZIPInputStream(stream).buffered(65536)).use { dis ->
            val magic = ByteArray(8)
            dis.readFully(magic)
            check(magic.contentEquals(MAGIC)) { "bad magic: ${magic.toHex()}" }

            // Header: version, n, k, total_blocks, block_size, dims, reserved[8]
            val version     = readIntLE(dis)
            val n           = readIntLE(dis)
            val k           = readIntLE(dis)
            val totalBlocks = readIntLE(dis)
            val blockSize   = readIntLE(dis)
            val dims        = readIntLE(dis)
            check(version == 1) { "unsupported version $version" }
            check(dims == 14)   { "expected dims=14, got $dims" }
            check(blockSize == 8) { "expected block_size=8" }
            repeat(8) { readIntLE(dis) }  // reserved
            // Now at byte offset 64

            // centroids: k * 14 * int16, row-major → transpose to SoA for SIMD scan
            val centroidsRaw = readShortArrayLE(dis, k * 14)
            val centroidsSoA = ShortArray(14 * k)
            for (c in 0 until k) {
                for (d in 0..13) {
                    centroidsSoA[d * k + c] = centroidsRaw[c * 14 + d]
                }
            }

            // bbox_min / bbox_max: k * 14 * int16, keep row-major
            val bboxMin = readShortArrayLE(dis, k * 14)
            val bboxMax = readShortArrayLE(dis, k * 14)

            // Alignment padding to 4 bytes after 64 + 3*k*14*2 bytes
            val afterBbox = 64L + 3L * k * 14 * 2
            val pad4 = ((4 - (afterBbox % 4)) % 4).toInt()
            if (pad4 > 0) { val dummy = ByteArray(pad4); dis.readFully(dummy) }

            // offsets: (k+1) * uint32
            val offsets = readIntArrayLE(dis, k + 1)

            // counts: k * uint32 (used by build tool, not needed at runtime)
            readIntArrayLE(dis, k)

            // labels: total_blocks * 8 bytes
            val labels = ByteArray(totalBlocks * 8)
            dis.readFully(labels)

            // Alignment padding to 2 bytes
            val afterLabels = afterBbox + pad4 + (k.toLong() + 1) * 4 + k.toLong() * 4 + totalBlocks.toLong() * 8
            val pad2 = ((2 - (afterLabels % 2)) % 2).toInt()
            if (pad2 > 0) { val dummy = ByteArray(pad2); dis.readFully(dummy) }

            // blocks: mmap to avoid heap pressure
            val blocks = mmapBlocks(dis, totalBlocks)
            val paddedN = totalBlocks * 8

            return Dataset(centroidsSoA, bboxMin, bboxMax, offsets, labels, blocks,
                MemorySegment.ofBuffer(blocks), k, n, paddedN)
        }
    }

    private fun mmapBlocks(dis: DataInputStream, totalBlocks: Int): ByteBuffer {
        val totalBytes = totalBlocks.toLong() * 14 * 8 * 2
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
                    .order(ByteOrder.LITTLE_ENDIAN)
            }
        }
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

private fun readIntLE(dis: DataInputStream): Int {
    val b0 = dis.read(); val b1 = dis.read(); val b2 = dis.read(); val b3 = dis.read()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun readShortArrayLE(dis: DataInputStream, count: Int): ShortArray {
    val result = ShortArray(count)
    val buf = ByteArray(65536)
    var pos = 0; var remaining = count * 2
    while (remaining > 0) {
        val toRead = minOf(buf.size, remaining)
        dis.readFully(buf, 0, toRead)
        val pairs = toRead / 2
        for (i in 0 until pairs) {
            val b = i * 2
            result[pos++] = ((buf[b].toInt() and 0xFF) or ((buf[b + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        remaining -= toRead
    }
    return result
}

private fun readIntArrayLE(dis: DataInputStream, count: Int): IntArray {
    val result = IntArray(count)
    val buf = ByteArray(65536)
    var pos = 0; var remaining = count * 4
    while (remaining > 0) {
        val toRead = minOf(buf.size, remaining)
        dis.readFully(buf, 0, toRead)
        val quads = toRead / 4
        for (i in 0 until quads) {
            val b = i * 4
            result[pos++] = (buf[b].toInt() and 0xFF) or
                ((buf[b + 1].toInt() and 0xFF) shl 8) or
                ((buf[b + 2].toInt() and 0xFF) shl 16) or
                ((buf[b + 3].toInt() and 0xFF) shl 24)
        }
        remaining -= toRead
    }
    return result
}
