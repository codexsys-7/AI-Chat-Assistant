package dev.probe.textselection.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import com.sun.net.httpserver.HttpServer

class EmbeddingClientTest {
    private val passage = "Cells store fuel before they can spend it."

    @Test
    fun clientSuccessReadsTheVectorModelAndDimensions() {
        val seen = mutableListOf<String>()
        var authorization: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/embeddings") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            seen += exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val bytes = embeddingJson(sampleVector(1.0), "text-embedding-3-small", EMBEDDING_DIMENSIONS)
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = HttpEmbeddingClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                connectTimeoutMs = 2_000,
                readTimeoutMs = 2_000,
            )
            val result = runBlocking { client.embed(passage) }
            val success = result as EmbeddingResult.Success
            assertEquals(sampleVector(1.0), success.embedding)
            assertEquals("text-embedding-3-small", success.model)
            assertEquals(EMBEDDING_DIMENSIONS, success.dimensions)
            assertEquals(EMBEDDING_DIMENSIONS, success.embedding.size)
            assertNull(authorization)
            val sent = parseJsonObject(seen.single())
            assertEquals(setOf("text"), sent.keys)
            assertEquals(passage, sent["text"])
            assertFalse(seen.single().contains("sk-"))
            assertFalse(seen.single().contains("Authorization"))
            assertEquals(
                "http://127.0.0.1:${server.address.port}/v1/embeddings",
                embeddingEndpoint("http://127.0.0.1:${server.address.port}/"),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun invalidResponseIsRejected() {
        assertTrue(interpretEmbeddingBody("") is EmbeddingResult.Invalid)
        assertTrue(interpretEmbeddingBody("{") is EmbeddingResult.Invalid)
        assertTrue(interpretEmbeddingBody("""{"model":"text-embedding-3-small","dimensions":1536}""") is EmbeddingResult.Invalid)
        assertTrue(interpretEmbeddingBody("""{"embedding":"nope","model":"text-embedding-3-small","dimensions":1536}""") is EmbeddingResult.Invalid)
        val numbers = List(EMBEDDING_DIMENSIONS) { "1" }.joinToString(",")
        assertTrue(
            interpretEmbeddingHttp(
                200,
                """{"embedding":[$numbers],"model":"","dimensions":1536}""",
            ) is EmbeddingResult.Invalid,
        )
        val server = textServer(200, """{"embedding":"nope","model":"text-embedding-3-small","dimensions":1536}""")
        try {
            val result = runBlocking {
                HttpEmbeddingClient("http://127.0.0.1:${server.address.port}", 1_000, 1_000).embed(passage)
            }
            assertTrue(result is EmbeddingResult.Invalid)
            assertFalse(result.toString().contains(passage))
            assertFalse(result.toString().contains("sk-"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun emptyVectorIsRejected() {
        val body = """{"embedding":[],"model":"text-embedding-3-small","dimensions":0}"""
        assertTrue(interpretEmbeddingBody(body) is EmbeddingResult.EmptyVector)
        val server = textServer(200, body)
        try {
            val result = runBlocking {
                HttpEmbeddingClient("http://127.0.0.1:${server.address.port}", 1_000, 1_000).embed("passage")
            }
            assertTrue(result is EmbeddingResult.EmptyVector)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun wrongDimensionIsRejected() {
        val short = """{"embedding":[1.0,2.0,3.0],"model":"text-embedding-3-small","dimensions":3}"""
        assertTrue(interpretEmbeddingBody(short) is EmbeddingResult.WrongDimension)
        val declaredMismatch = embeddingJson(sampleVector(1.0), "text-embedding-3-small", 3)
        assertTrue(interpretEmbeddingBody(declaredMismatch) is EmbeddingResult.WrongDimension)
        val server = textServer(200, short)
        try {
            val result = runBlocking {
                HttpEmbeddingClient("http://127.0.0.1:${server.address.port}", 1_000, 1_000).embed("passage")
            }
            assertTrue(result is EmbeddingResult.WrongDimension)
            assertFalse(result.toString().contains("1.0"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun networkFailureDoesNotExposeTheExceptionText() {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        val result = runBlocking {
            HttpEmbeddingClient("http://127.0.0.1:$port", 500, 500).embed("sk-should-not-leak")
        }
        assertTrue(result is EmbeddingResult.NetworkFailure)
        assertFalse(result.toString().contains("sk-"))
        assertEquals(
            EmbeddingResult.NetworkFailure,
            embeddingCallForTransport(java.net.ConnectException("sk-refused")),
        )
        assertEquals(
            EmbeddingResult.TimedOut,
            embeddingCallForTransport(SocketTimeoutException("sk-timeout")),
        )
    }

    @Test
    fun backendErrorDropsTheErrorBody() {
        val body = """{"error":"provider failure","leak":"sk-backend-secret","text":"$passage"}"""
        val server = textServer(502, body)
        try {
            val result = runBlocking {
                HttpEmbeddingClient("http://127.0.0.1:${server.address.port}", 1_000, 1_000).embed(passage)
            }
            assertTrue(result is EmbeddingResult.BackendError)
            assertFalse(result.toString().contains("sk-"))
            assertFalse(result.toString().contains(passage))
            assertFalse(result.toString().contains("provider failure"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun httpTimeoutIsTimedOut() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/embeddings") { exchange ->
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                // The client already gave up.
            }
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val result = runBlocking {
                HttpEmbeddingClient(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    connectTimeoutMs = 500,
                    readTimeoutMs = 300,
                ).embed(passage)
            }
            assertTrue(result is EmbeddingResult.TimedOut)
            assertFalse(result.toString().contains(passage))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun mockModeDoesNotCreateAnEmbeddingClient() {
        assertNull(defaultEmbeddingClient("MOCK", "http://127.0.0.1:9"))
        assertNull(defaultEmbeddingClient("typo", "http://127.0.0.1:9"))
        assertTrue(defaultEmbeddingClient("REMOTE", "http://127.0.0.1:9") is HttpEmbeddingClient)
        assertTrue(defaultEmbeddingClient(" remote ", "http://10.0.2.2:8080/") is HttpEmbeddingClient)
    }

    private fun sampleVector(first: Double): List<Double> {
        return List(EMBEDDING_DIMENSIONS) { index -> if (index == 0) first else 2.0 }
    }

    private fun embeddingJson(vector: List<Double>, model: String, dimensions: Int): String {
        val numbers = vector.joinToString(",") { value ->
            if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
        }
        return """{"embedding":[$numbers],"model":${jsonString(model)},"dimensions":$dimensions}"""
    }

    private fun textServer(status: Int, body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/embeddings") { exchange ->
            exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }
}
