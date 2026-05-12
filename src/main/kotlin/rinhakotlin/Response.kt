package rinhakotlin

object Response {
    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    private val FRAUD = arrayOf(
        bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 35\r\n\r\n{\"approved\":true,\"fraud_score\":0.0}"),
        bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 35\r\n\r\n{\"approved\":true,\"fraud_score\":0.2}"),
        bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 35\r\n\r\n{\"approved\":true,\"fraud_score\":0.4}"),
        bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 36\r\n\r\n{\"approved\":false,\"fraud_score\":0.6}"),
        bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 36\r\n\r\n{\"approved\":false,\"fraud_score\":0.8}"),
        bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 36\r\n\r\n{\"approved\":false,\"fraud_score\":1.0}"),
    )

    val READY = bytes("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
    val NOT_FOUND = bytes("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
    val BAD_REQUEST = bytes("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
    val FALLBACK = FRAUD[0]

    fun forFraudCount(count: Int): ByteArray = FRAUD[count.coerceIn(0, 5)]
}