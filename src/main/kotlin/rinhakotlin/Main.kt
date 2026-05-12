package rinhakotlin

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions

fun main() {
    val ds = DataLoader.dataset
    warmup(ds)

    val sockPath = System.getenv("SOCK") ?: "/run/sock/api.sock"

    val options = VertxOptions().setEventLoopPoolSize(4).setPreferNativeTransport(true)
    val vertx = Vertx.vertx(options)
    startHttpServer(vertx, sockPath)
}