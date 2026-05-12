package rinhakotlin

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.SocketAddress
import java.util.concurrent.atomic.AtomicLong

private val SERVICE_UNAVAILABLE =
    "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        .toByteArray(Charsets.US_ASCII)

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 9999
    val backends = (System.getenv("UPSTREAMS") ?: "/run/sock/api1.sock,/run/sock/api2.sock")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val workers = System.getenv("WORKERS")?.toInt() ?: 1

    System.err.println("LB starting: port=$port backends=$backends workers=$workers")

    val vertx = Vertx.vertx(VertxOptions().setEventLoopPoolSize(workers).setPreferNativeTransport(true))
    System.err.println("LB native transport: ${vertx.isNativeTransportEnabled}")

    startLoadBalancer(vertx, port, backends)
}

private fun connectWithFallback(
    client: io.vertx.core.net.NetClient,
    frontend: io.vertx.core.net.NetSocket,
    backends: List<String>,
    startIdx: Int,
    remaining: Int,
) {
    if (remaining == 0) {
        System.err.println("LB: all backends failed, returning 500")
        frontend.write(Buffer.buffer(SERVICE_UNAVAILABLE))
        frontend.close()
        return
    }
    val addr = backends[startIdx % backends.size]
    System.err.println("LB: trying $addr (remaining=$remaining)")
    client.connect(SocketAddress.domainSocketAddress(addr)) { res ->
        if (res.failed()) {
            System.err.println("LB: $addr failed: ${res.cause()?.message}")
            connectWithFallback(client, frontend, backends, startIdx + 1, remaining - 1)
            return@connect
        }
        System.err.println("LB: $addr connected")
        val backend = res.result()
        frontend.handler { buf -> backend.write(buf) }
        backend.handler { buf -> frontend.write(buf) }
        frontend.closeHandler { backend.close() }
        backend.closeHandler { frontend.close() }
        frontend.exceptionHandler { backend.close(); frontend.close() }
        backend.exceptionHandler { backend.close(); frontend.close() }
        frontend.resume()
    }
}

fun startLoadBalancer(vertx: Vertx, port: Int, backends: List<String>) {
    val counter = AtomicLong(0)
    val client = vertx.createNetClient(NetClientOptions().setConnectTimeout(5000))
    val server = vertx.createNetServer(NetServerOptions().setTcpNoDelay(true))

    server.connectHandler { frontend ->
        frontend.pause()
        val startIdx = (counter.getAndIncrement() % backends.size).toInt()
        connectWithFallback(client, frontend, backends, startIdx, backends.size)
    }

    server.listen(port, "0.0.0.0") { res ->
        if (res.succeeded()) System.err.println("LB listening on :$port → $backends")
        else { System.err.println("LB start failed: ${res.cause()}"); System.exit(1) }
    }
}