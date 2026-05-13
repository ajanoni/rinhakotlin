package rinhakotlin

fun main() {
    val ds = DataLoader.dataset
    val centroidsBytes = ds.centroids.size.toLong() * 2
    val bboxBytes      = (ds.bboxMin.size.toLong() + ds.bboxMax.size.toLong()) * 2
    val offsetsBytes   = ds.offsets.size.toLong() * 4
    val labelsBytes    = ds.labels.size.toLong()
    val blocksBytes    = ds.blocks.capacity().toLong()
    val onHeap         = centroidsBytes + bboxBytes + offsetsBytes + labelsBytes
    println("Dataset: n=${ds.n} k=${ds.k} paddedN=${ds.paddedN}")
    println("  centroids=${centroidsBytes/1024}KB bbox=${bboxBytes/1024}KB offsets=${offsetsBytes/1024}KB labels=${labelsBytes/1024}KB blocks(mmap)=${blocksBytes/1024/1024}MB on-heap=${onHeap/1024}KB")
    warmup(ds)

    val sockPath = System.getenv("SOCK") ?: "/run/sock/api.sock"
    startHttpServer(sockPath)
}