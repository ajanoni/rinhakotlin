package rinhakotlin

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.concurrent.TimeUnit

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IntegrationTest {

    companion object {
        private lateinit var http: HttpClient
        private const val HOST = "localhost"
        private const val PORT = 9999
        private val BASE_URL = "http://$HOST:$PORT"

        @JvmStatic
        @BeforeAll
        fun setup() {
            assumeTrue(
                File("src/main/resources/data/index.bin.gz").exists(),
                "index.bin.gz missing — run 'cargo run --release --bin build_index' first"
            )

            // Build image explicitly to avoid 'build: !reset null' compat issues in compose
            val build = ProcessBuilder("docker", "build", "--platform=linux/amd64",
                "-t", "rinha-fraud-2026-kotlin:latest", ".")
                .directory(File("."))
                .inheritIO()
                .start()
            check(build.waitFor(15, TimeUnit.MINUTES)) { "docker build timed out" }
            check(build.exitValue() == 0) { "docker build failed with exit ${build.exitValue()}" }

            val up = ProcessBuilder("docker", "compose", "up", "-d")
                .directory(File("."))
                .inheritIO()
                .start()
            check(up.waitFor(2, TimeUnit.MINUTES)) { "docker compose up timed out" }
            check(up.exitValue() == 0) { "docker compose up failed with exit ${up.exitValue()}" }

            waitForPort(HOST, PORT, Duration.ofMinutes(2))

            http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            ProcessBuilder("docker", "compose", "down")
                .directory(File("."))
                .inheritIO()
                .start()
                .waitFor(60, TimeUnit.SECONDS)
        }

        private fun waitForPort(host: String, port: Int, timeout: Duration) {
            val deadline = System.currentTimeMillis() + timeout.toMillis()
            val probeClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            while (System.currentTimeMillis() < deadline) {
                try {
                    val resp = probeClient.send(
                        HttpRequest.newBuilder(URI("http://$host:$port/ready"))
                            .GET().timeout(Duration.ofSeconds(2)).build(),
                        BodyHandlers.discarding()
                    )
                    if (resp.statusCode() == 200) return
                } catch (_: Exception) {}
                Thread.sleep(500)
            }
            throw IllegalStateException("$host:$port/ready not ready after $timeout")
        }

        private val LOW_RISK_PAYLOAD = """{"id":"test-low-001","transaction":{"amount":50.00,"installments":1,"requested_at":"2025-01-15T14:30:00Z"},"customer":{"avg_amount":200.0,"tx_count_24h":1,"known_merchants":["m-001"]},"merchant":{"id":"m-001","mcc":"5411","avg_amount":180.0},"terminal":{"is_online":false,"card_present":true,"km_from_home":1.0},"last_transaction":{"timestamp":"2025-01-15T10:00:00Z","km_from_current":0.5}}"""

        private val HIGH_RISK_PAYLOAD = """{"id":"test-high-001","transaction":{"amount":9500.00,"installments":1,"requested_at":"2025-01-15T03:15:00Z"},"customer":{"avg_amount":80.0,"tx_count_24h":18,"known_merchants":["m-other"]},"merchant":{"id":"m-gambling-001","mcc":"7995","avg_amount":9000.0},"terminal":{"is_online":true,"card_present":false,"km_from_home":950.0},"last_transaction":{"timestamp":"2025-01-15T03:10:00Z","km_from_current":800.0}}"""
    }

    @Test
    @Order(1)
    fun `ready endpoint returns 200`() {
        val resp = http.send(
            HttpRequest.newBuilder(URI("$BASE_URL/ready"))
                .GET().timeout(Duration.ofSeconds(5)).build(),
            BodyHandlers.ofString()
        )
        Assertions.assertEquals(200, resp.statusCode())
    }

    @Test
    @Order(2)
    fun `fraud-score returns valid JSON with approved and fraud_score fields`() {
        val resp = http.send(
            HttpRequest.newBuilder(URI("$BASE_URL/fraud-score"))
                .POST(HttpRequest.BodyPublishers.ofString(LOW_RISK_PAYLOAD))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5)).build(),
            BodyHandlers.ofString()
        )
        Assertions.assertEquals(200, resp.statusCode())
        val body = resp.body()
        Assertions.assertTrue(body.contains("\"approved\""), "missing approved: $body")
        Assertions.assertTrue(body.contains("\"fraud_score\""), "missing fraud_score: $body")
    }

    @Test
    @Order(3)
    fun `low-risk transaction is approved`() {
        val resp = http.send(
            HttpRequest.newBuilder(URI("$BASE_URL/fraud-score"))
                .POST(HttpRequest.BodyPublishers.ofString(LOW_RISK_PAYLOAD))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5)).build(),
            BodyHandlers.ofString()
        )
        Assertions.assertEquals(200, resp.statusCode())
        Assertions.assertTrue(resp.body().contains("\"approved\":true"), "expected approved=true: ${resp.body()}")
    }

    @Test
    @Order(4)
    fun `high-risk transaction is rejected`() {
        val resp = http.send(
            HttpRequest.newBuilder(URI("$BASE_URL/fraud-score"))
                .POST(HttpRequest.BodyPublishers.ofString(HIGH_RISK_PAYLOAD))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5)).build(),
            BodyHandlers.ofString()
        )
        Assertions.assertEquals(200, resp.statusCode())
        Assertions.assertTrue(resp.body().contains("\"approved\":false"), "expected approved=false: ${resp.body()}")
    }

    @Test
    @Order(5)
    fun `unknown path returns 404`() {
        val resp = http.send(
            HttpRequest.newBuilder(URI("$BASE_URL/unknown"))
                .GET().timeout(Duration.ofSeconds(5)).build(),
            BodyHandlers.ofString()
        )
        Assertions.assertEquals(404, resp.statusCode())
    }

    @Test
    @Order(6)
    fun `repeated requests all return 200`() {
        repeat(10) { i ->
            val resp = http.send(
                HttpRequest.newBuilder(URI("$BASE_URL/fraud-score"))
                    .POST(HttpRequest.BodyPublishers.ofString(LOW_RISK_PAYLOAD))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5)).build(),
                BodyHandlers.ofString()
            )
            Assertions.assertEquals(200, resp.statusCode(), "request $i failed: ${resp.body()}")
        }
    }
}
