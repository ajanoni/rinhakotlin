package rinhakotlin

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

private const val RX_CAP = 8192

private val CR = '\r'.code.toByte()
private val LF = '\n'.code.toByte()

private val PATH_FRAUD = "/fraud-score".toByteArray(Charsets.US_ASCII)
private val PATH_READY = "/ready".toByteArray(Charsets.US_ASCII)
private val CL_KEY = "content-length:".toByteArray(Charsets.US_ASCII)  // 15 bytes

private sealed class Parsed {
    object Incomplete : Parsed()
    object Bad : Parsed()
    class Ready(val consumed: Int) : Parsed()
    class NotFound(val consumed: Int) : Parsed()
    class Fraud(val bodyStart: Int, val bodyEnd: Int, val consumed: Int) : Parsed()
}

fun startHttpServer(sockPath: String) {
    val sockFile = java.io.File(sockPath)
    sockFile.parentFile?.mkdirs()
    sockFile.delete()

    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(sockPath), 4096)

    runCatching {
        Files.setPosixFilePermissions(
            Paths.get(sockPath),
            PosixFilePermissions.fromString("rw-rw-rw-"),
        )
    }
    println("Listening on $sockPath")

    // One virtual thread per connection: blocking I/O, synchronous KNN — no thread pool overhead.
    while (true) {
        val client = server.accept()
        Thread.ofVirtual().start { handleConnection(client) }
    }
}

private fun writeAll(channel: SocketChannel, data: ByteArray) {
    val buf = ByteBuffer.wrap(data)
    while (buf.hasRemaining()) channel.write(buf)
}

private fun handleConnection(channel: SocketChannel) {
    channel.use {
        val buf = ByteArray(RX_CAP)
        var tail = 0

        while (true) {
            val space = RX_CAP - tail
            if (space == 0) { writeAll(it, Response.BAD_REQUEST); return }

            val n = it.read(ByteBuffer.wrap(buf, tail, space))
            if (n <= 0) return
            tail += n

            var head = 0
            parse@ while (head < tail) {
                when (val p = httpParse(buf, head, tail)) {
                    Parsed.Incomplete -> break@parse
                    Parsed.Bad -> { writeAll(it, Response.BAD_REQUEST); return }
                    is Parsed.Ready -> { writeAll(it, Response.READY); head += p.consumed }
                    is Parsed.NotFound -> { writeAll(it, Response.NOT_FOUND); head += p.consumed }
                    is Parsed.Fraud -> {
                        writeAll(it, processFraud(buf, head + p.bodyStart, head + p.bodyEnd))
                        head += p.consumed
                    }
                }
            }

            when {
                head == tail -> tail = 0
                head > 0 -> { buf.copyInto(buf, 0, head, tail); tail -= head }
            }
        }
    }
}

private fun processFraud(buf: ByteArray, start: Int, end: Int): ByteArray {
    val payload = parsePayload(buf, start, end) ?: return Response.FALLBACK
    val query = vectorize(payload)
    val fraudCount = knn5FraudCount(query, DataLoader.dataset)
    return Response.forFraudCount(fraudCount)
}

private fun findHeaderEnd(buf: ByteArray, start: Int, end: Int): Int? {
    val limit = end - 4
    var i = start
    while (i <= limit) {
        if (buf[i] == CR && buf[i+1] == LF && buf[i+2] == CR && buf[i+3] == LF) return i
        i++
    }
    return null
}

private fun pathEq(buf: ByteArray, start: Int, lineEnd: Int, path: ByteArray): Boolean {
    if (lineEnd - start < path.size + 1) return false
    for (i in path.indices) if (buf[start + i] != path[i]) return false
    val next = buf[start + path.size]
    return next == ' '.code.toByte() || next == '?'.code.toByte()
}

private fun parseContentLength(buf: ByteArray, start: Int, end: Int): Int {
    val N = CL_KEY.size
    val limit = end - N
    var i = start
    while (i <= limit) {
        if (buf[i].toInt() or 0x20 != 'c'.code) { i++; continue }
        var j = 1; var ok = true
        while (j < N) {
            if (buf[i + j].toInt() or 0x20 != CL_KEY[j].toInt()) { ok = false; break }
            j++
        }
        if (ok) {
            var p = i + N
            while (p < end && (buf[p] == ' '.code.toByte() || buf[p] == '\t'.code.toByte())) p++
            var v = 0
            while (p < end) {
                val d = buf[p].toInt() - '0'.code
                if (d < 0 || d > 9) break
                v = v * 10 + d; p++
            }
            return v
        }
        i++
    }
    return 0
}

private fun httpParse(buf: ByteArray, head: Int, tail: Int): Parsed {
    if (tail - head < 16) return Parsed.Incomplete

    val headerEnd = findHeaderEnd(buf, head, tail) ?: return Parsed.Incomplete

    var lineEnd = head
    while (lineEnd < headerEnd && buf[lineEnd] != CR) lineEnd++
    if (lineEnd >= headerEnd) return Parsed.Bad

    val consumed = headerEnd - head + 4

    val b0 = buf[head]; val b1 = buf[head+1]; val b2 = buf[head+2]; val b3 = buf[head+3]

    if (b0 == 'P'.code.toByte() && b1 == 'O'.code.toByte() &&
        b2 == 'S'.code.toByte() && b3 == 'T'.code.toByte() && buf[head+4] == ' '.code.toByte()) {

        if (pathEq(buf, head + 5, lineEnd, PATH_FRAUD)) {
            val cl = parseContentLength(buf, lineEnd, headerEnd)
            val bodyStart = headerEnd - head + 4
            val bodyEnd = bodyStart + cl
            if (tail - head < bodyEnd) return Parsed.Incomplete
            return Parsed.Fraud(bodyStart, bodyEnd, bodyEnd)
        }
        return Parsed.NotFound(consumed)
    }

    if (b0 == 'G'.code.toByte() && b1 == 'E'.code.toByte() &&
        b2 == 'T'.code.toByte() && b3 == ' '.code.toByte()) {

        if (pathEq(buf, head + 4, lineEnd, PATH_READY)) return Parsed.Ready(consumed)
        return Parsed.NotFound(consumed)
    }

    return Parsed.Bad
}