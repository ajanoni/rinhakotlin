package rinhakotlin

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.SocketAddress

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

private class ConnState {
    val rx = ByteArray(RX_CAP)
    var head = 0
    var tail = 0
}

fun startHttpServer(vertx: Vertx, sockPath: String) {
    val opts = NetServerOptions().setTcpNoDelay(true)
    val server = vertx.createNetServer(opts)

    server.connectHandler { socket ->
        val state = ConnState()

        socket.handler { inBuf ->
            val bytes = inBuf.bytes
            val n = bytes.size
            if (state.tail + n > RX_CAP) { socket.close(); return@handler }
            bytes.copyInto(state.rx, state.tail)
            state.tail += n

            val out = Buffer.buffer()
            var closeAfter = false

            loop@ while (state.head < state.tail) {
                when (val p = httpParse(state.rx, state.head, state.tail)) {
                    Parsed.Incomplete -> break@loop
                    Parsed.Bad -> {
                        out.appendBytes(Response.BAD_REQUEST)
                        closeAfter = true
                        break@loop
                    }
                    is Parsed.Ready -> {
                        out.appendBytes(Response.READY)
                        state.head += p.consumed
                    }
                    is Parsed.NotFound -> {
                        out.appendBytes(Response.NOT_FOUND)
                        state.head += p.consumed
                    }
                    is Parsed.Fraud -> {
                        val abs = state.head
                        val body = state.rx.copyOfRange(abs + p.bodyStart, abs + p.bodyEnd)
                        out.appendBytes(processFraud(body))
                        state.head += p.consumed
                    }
                }
            }

            if (out.length() > 0) socket.write(out)
            if (closeAfter) { socket.close(); return@handler }

            // Compact rx buffer
            when {
                state.head == state.tail -> { state.head = 0; state.tail = 0 }
                state.head > 0 -> {
                    state.rx.copyInto(state.rx, 0, state.head, state.tail)
                    state.tail -= state.head
                    state.head = 0
                }
            }
        }

        socket.exceptionHandler { socket.close() }
    }

    val sockFile = java.io.File(sockPath)
    sockFile.parentFile?.mkdirs()
    sockFile.delete()

    server.listen(SocketAddress.domainSocketAddress(sockPath))
        .onSuccess {
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    java.nio.file.Paths.get(sockPath),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"),
                )
            }
            println("Listening on $sockPath")
        }
        .onFailure { err ->
            System.err.println("Server failed to start: $err")
            err.printStackTrace()
            System.exit(1)
        }
}

private fun processFraud(body: ByteArray): ByteArray {
    val payload = parsePayload(body, body.size) ?: return Response.FALLBACK
    val query = vectorize(payload)
    val fraudCount = knn5FraudCount(query, DataLoader.dataset)
    return Response.forFraudCount(fraudCount)
}

// Returns absolute position of \r\n\r\n start, or null
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

// Case-insensitive search for "content-length:" in headers, returns the value
private fun parseContentLength(buf: ByteArray, start: Int, end: Int): Int {
    val N = CL_KEY.size  // 15
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

// All positions are absolute into buf. consumed/bodyStart/bodyEnd are relative to head.
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
