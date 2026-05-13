package rinhakotlin

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions

fun main() {
    val ds = DataLoader.dataset
    val centroidsBytes = ds.centroids.size.toLong() * 4
    val offsetsBytes   = ds.offsets.size.toLong()   * 4
    val labelsBytes    = ds.labels.size.toLong()
    val blocksBytes    = ds.blocks.capacity().toLong()
    val totalBytes     = centroidsBytes + offsetsBytes + labelsBytes + blocksBytes
    println("Dataset: n=${ds.n} k=${ds.k} paddedN=${ds.paddedN}")
    println("  centroids=${centroidsBytes/1024}KB offsets=${offsetsBytes/1024}KB labels=${labelsBytes/1024}KB blocks(mmap)=${blocksBytes/1024/1024}MB on-heap=${(totalBytes-blocksBytes)/1024}KB")
    warmup(ds)

    val sockPath = System.getenv("SOCK") ?: "/run/sock/api.sock"

    val options = VertxOptions().setEventLoopPoolSize(2).setPreferNativeTransport(true)
    val vertx = Vertx.vertx(options)
    startHttpServer(vertx, sockPath)
}