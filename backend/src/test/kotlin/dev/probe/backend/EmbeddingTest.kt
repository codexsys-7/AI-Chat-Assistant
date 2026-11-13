package dev.probe.backend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

class EmbeddingTest {
    private val passage = "Cells store fuel before they can spend it."
    private val secretKey = "sk-supersecret-embedding"
    private val allowedLogs = setOf(
        "method",
        "too-large",
        "invalid-request",
        "empty-text",
        "timeout",
        "unexpected-vector",
        "provider-error",
        "unexpected",
    )

    @Test
    fun validRequestReturnsTheEmbeddingContract() {
        val seen = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val vector = sampleVector(0.25)
        val handler = EmbeddingHandler(
            model = EmbeddingModel { text ->
                seen += text
                EmbeddingModelResult.Success(vector)
            },
            log = { logs += it },
        )

        val reply = handler.handle("POST", "/v1/embeddings", requestBody(passage))
        val json = JSONObject(reply.body)

        assertEquals(200, reply.status)
        assertEquals(listOf(passage), seen)
        assertEquals(setOf("embedding", "model", "dimensions"), json.keySet())
        assertEquals(EMBEDDING_MODEL, json.getString("model"))
        assertEquals(EMBEDDING_DIMENSIONS, json.getInt("dimensions"))
        assertEquals(vector, jsonArray(json.getJSONArray("embedding")))
        assertFalse(reply.body.contains(passage))
        assertFalse(reply.body.contains("requestId"))
        assertFalse(reply.body.contains("content"))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun emptyTextDoesNotCallTheModel() {
        val logs = mutableListOf<String>()
        var called = false
        val handler = EmbeddingHandler(
            model = EmbeddingModel {
                called = true
                EmbeddingModelResult.Success(sampleVector(0.1))
            },
            log = { logs += it },
        )

        listOf("", "   ", "\n\t").forEach { text ->
            val reply = handler.handle("POST", "/v1/embeddings", requestBody(text))
            assertEquals(400, reply.status)
            assertEquals("empty text", JSONObject(reply.body).getString("error"))
            assertFalse(reply.body.contains(secretKey))
        }
        assertFalse(called)
        assertEquals(listOf("empty-text", "empty-text", "empty-text"), logs)
        assertTrue(logs.all { it in allowedLogs })
    }

    @Test
    fun invalidRequestDoesNotCallTheModel() {
        val logs = mutableListOf<String>()
        var called = false
        val handler = EmbeddingHandler(
            model = EmbeddingModel {
                called = true
                EmbeddingModelResult.Success(sampleVector(0.1))
            },
            log = { logs += it },
        )

        val cases = listOf(
            "" to 400,
            "{" to 400,
            """{"text":1}""" to 400,
            """{"input":"Cells store fuel."}""" to 400,
            "null" to 400,
            "[]" to 400,
        )
        cases.forEach { (body, status) ->
            val reply = handler.handle("POST", "/v1/embeddings", body)
            assertEquals(status, reply.status)
            assertEquals("invalid request", JSONObject(reply.body).getString("error"))
            assertEquals(setOf("error"), JSONObject(reply.body).keySet())
        }
        val wrongMethod = handler.handle("GET", "/v1/embeddings", requestBody(passage))
        assertEquals(405, wrongMethod.status)
        assertEquals("method not allowed", JSONObject(wrongMethod.body).getString("error"))
        val huge = handler.handle("POST", "/v1/embeddings", "x".repeat(MAX_BODY_CHARS + 1))
        assertEquals(413, huge.status)
        assertEquals("request too large", JSONObject(huge.body).getString("error"))
        assertFalse(huge.body.contains("xxxxx"))
        assertFalse(called)
        assertTrue(logs.all { it in allowedLogs })
        assertFalse(logs.joinToString(" ").contains(passage))
    }

    @Test
    fun providerSuccessUsesTheEmbeddingModelAndDimensions() {
        val seen = mutableListOf<String>()
        var seenAuth: String? = null
        val vector = sampleVector(0.5)
        val fake = embeddingServer { exchange ->
            seenAuth = exchange.requestHeaders.getFirst("Authorization")
            seen += exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val bytes = openAiBody(vector).toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val model = OpenAiEmbeddingModel(
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:${fake.address.port}/v1",
                requestTimeout = Duration.ofSeconds(5),
            )
            val result = model.embed(passage)
            assertEquals(EmbeddingModelResult.Success(vector), result)
            assertEquals("Bearer test-key", seenAuth)
            val sent = JSONObject(seen.single())
            assertEquals(EMBEDDING_MODEL, sent.getString("model"))
            assertEquals(EMBEDDING_DIMENSIONS, sent.getInt("dimensions"))
            assertEquals(passage, sent.getString("input"))
            assertFalse(sent.has("messages"))

            val reply = EmbeddingHandler(model = EmbeddingModel { result }).handle(
                "POST",
                "/v1/embeddings",
                requestBody(passage),
            )
            val json = JSONObject(reply.body)
            assertEquals(200, reply.status)
            assertEquals(EMBEDDING_MODEL, json.getString("model"))
            assertEquals(EMBEDDING_DIMENSIONS, json.getInt("dimensions"))
            assertEquals(EMBEDDING_DIMENSIONS, json.getJSONArray("embedding").length())
            assertFalse(reply.body.contains("test-key"))
            assertFalse(reply.body.contains(passage))
        } finally {
            fake.stop(0)
        }
    }

    @Test
    fun providerErrorDoesNotReturnTheUpstreamBody() {
        val leaked = """{"error":{"message":"Incorrect API key provided: $secretKey"}}"""
        val fake = embeddingServer { exchange ->
            val bytes = leaked.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val model = OpenAiEmbeddingModel(
                apiKey = secretKey,
                baseUrl = "http://127.0.0.1:${fake.address.port}/v1",
                requestTimeout = Duration.ofSeconds(5),
            )
            val result = model.embed(passage)
            assertEquals(EmbeddingModelResult.Failure("provider HTTP 401"), result)
            assertFalse(result.toString().contains("sk-"))
            assertFalse(result.toString().contains(passage))

            val logs = mutableListOf<String>()
            val reply = EmbeddingHandler(
                model = EmbeddingModel { result },
                log = { logs += it },
            ).handle("POST", "/v1/embeddings", requestBody(passage))
            assertEquals(502, reply.status)
            assertEquals("provider HTTP 401", JSONObject(reply.body).getString("error"))
            assertFalse(reply.body.contains(secretKey))
            assertFalse(reply.body.contains(passage))
            assertFalse(reply.body.contains("Incorrect API key"))
            assertEquals(listOf("provider-error"), logs)
        } finally {
            fake.stop(0)
        }
    }

    @Test
    fun timeoutBecomesTimedOut() {
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake.createContext("/v1/embeddings") { exchange ->
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                // The client already gave up.
            }
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        fake.start()
        try {
            val model = OpenAiEmbeddingModel(
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:${fake.address.port}/v1",
                requestTimeout = Duration.ofMillis(300),
            )
            val result = model.embed(passage)
            assertEquals(EmbeddingModelResult.TimedOut, result)
            val logs = mutableListOf<String>()
            val reply = EmbeddingHandler(
                model = EmbeddingModel { result },
                log = { logs += it },
            ).handle("POST", "/v1/embeddings", requestBody(passage))
            assertEquals(504, reply.status)
            assertEquals("timed out", JSONObject(reply.body).getString("error"))
            assertEquals(setOf("error"), JSONObject(reply.body).keySet())
            assertFalse(reply.body.contains(passage))
            assertFalse(reply.body.contains("test-key"))
            assertEquals(listOf("timeout"), logs)
        } finally {
            fake.stop(0)
        }
    }

    @Test
    fun unexpectedVectorIsRejected() {
        val short = listOf(9.876, 8.765)
        val interpreted = interpretOpenAiEmbedding(
            200,
            """{"data":[{"embedding":[9.876,8.765]}]}""",
            EMBEDDING_DIMENSIONS,
        )
        assertEquals(EmbeddingModelResult.UnexpectedVector, interpreted)
        assertEquals(
            EmbeddingModelResult.UnexpectedVector,
            interpretOpenAiEmbedding(200, """{"data":[{"embedding":[]}]}""", EMBEDDING_DIMENSIONS),
        )
        assertEquals(
            EmbeddingModelResult.UnexpectedVector,
            interpretOpenAiEmbedding(200, """{"data":[{"embedding":["nope"]}]}""", EMBEDDING_DIMENSIONS),
        )
        assertFalse(interpreted.toString().contains("9.876"))

        val logs = mutableListOf<String>()
        val reply = EmbeddingHandler(
            model = EmbeddingModel { EmbeddingModelResult.Success(short) },
            log = { logs += it },
        ).handle("POST", "/v1/embeddings", requestBody(passage))
        assertEquals(502, reply.status)
        assertEquals("unexpected vector", JSONObject(reply.body).getString("error"))
        assertFalse(reply.body.contains("9.876"))
        assertFalse(reply.body.contains("8.765"))
        assertFalse(reply.body.contains(passage))
        assertEquals(listOf("unexpected-vector"), logs)

        val malformed = interpretOpenAiEmbedding(200, """{"choices":[]}""", EMBEDDING_DIMENSIONS)
        assertEquals(EmbeddingModelResult.Failure("provider failure"), malformed)
    }

    @Test
    fun modelAndDimensionsStayFixedOnTheEmbeddingRoute() {
        val vector = sampleVector(0.125)
        val reply = EmbeddingHandler(
            model = EmbeddingModel { EmbeddingModelResult.Success(vector) },
        ).handle("POST", "/v1/embeddings", requestBody(passage))
        val json = JSONObject(reply.body)
        assertEquals(EMBEDDING_MODEL, json.getString("model"))
        assertEquals(EMBEDDING_DIMENSIONS, json.getInt("dimensions"))
        assertEquals(EMBEDDING_DIMENSIONS, json.getJSONArray("embedding").length())
        assertEquals("text-embedding-3-small", EMBEDDING_MODEL)
        assertEquals(1536, EMBEDDING_DIMENSIONS)
    }

    @Test
    fun credentialsNeverAppearInTheResponseOrLogs() {
        val logs = mutableListOf<String>()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(stdout, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(stderr, true, StandardCharsets.UTF_8))
        val sentinel = 0.123456789
        try {
            val leaked = EmbeddingHandler(
                model = EmbeddingModel {
                    EmbeddingModelResult.Failure("Incorrect API key provided: $secretKey text=$passage")
                },
                log = { logs += it },
            ).handle("POST", "/v1/embeddings", requestBody(passage))
            val success = EmbeddingHandler(
                model = EmbeddingModel { EmbeddingModelResult.Success(sampleVector(sentinel)) },
                log = { logs += it },
            ).handle("POST", "/v1/embeddings", requestBody(passage))
            val blankKey = OpenAiEmbeddingModel(
                apiKey = " ",
                baseUrl = "http://127.0.0.1:1/v1",
                requestTimeout = Duration.ofMillis(200),
            ).embed(passage)

            assertEquals("provider failure", JSONObject(leaked.body).getString("error"))
            assertFalse(leaked.body.contains("sk-"))
            assertFalse(leaked.body.contains(secretKey))
            assertFalse(leaked.body.contains(passage))
            assertEquals(200, success.status)
            assertFalse(success.body.contains(secretKey))
            assertFalse(success.body.contains(passage))
            assertFalse(success.body.contains("Bearer"))
            assertEquals(EmbeddingModelResult.Failure("embedding model is not configured"), blankKey)
            assertFalse(blankKey.toString().contains(passage))

            val written = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8)
            val logged = logs.joinToString("\n")
            assertFalse(written.contains(secretKey))
            assertFalse(written.contains(passage))
            assertFalse(written.contains("0.123456789"))
            assertFalse(written.contains("Bearer"))
            assertFalse(logged.contains(secretKey))
            assertFalse(logged.contains(passage))
            assertFalse(logged.contains("0.123456789"))
            assertTrue(logs.all { it in allowedLogs })
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
    }

    @Test
    fun embeddingRouteDoesNotChangeTheChatContract() {
        val vector = sampleVector(0.2)
        val server = startCompletionServer(
            port = 0,
            handler = CompletionHandler(
                model = LanguageModel { _, _ -> ModelResult.Success("chat answer") },
                modelName = "gpt-4o-mini",
                newId = { "response-9" },
            ),
            host = "127.0.0.1",
            embeddings = EmbeddingHandler(
                model = EmbeddingModel { EmbeddingModelResult.Success(vector) },
            ),
        )
        try {
            val port = server.address.port
            val embedded = post(port, "/v1/embeddings", requestBody(passage))
            val embeddedJson = JSONObject(embedded.body())
            assertEquals(200, embedded.statusCode())
            assertEquals(EMBEDDING_MODEL, embeddedJson.getString("model"))
            assertEquals(EMBEDDING_DIMENSIONS, embeddedJson.getInt("dimensions"))
            assertFalse(embedded.body().contains("chat answer"))
            assertFalse(embedded.body().contains(passage))

            val chatBody = JSONObject()
                .put("requestId", "request-1")
                .put("action", "EXPLAIN")
                .put("selectedText", "Cells store fuel.")
                .put("precedingText", JSONObject.NULL)
                .put("followingText", JSONObject.NULL)
                .put("contextQuality", "HIGH")
                .put(
                    "prompt",
                    JSONObject()
                        .put("systemInstruction", "Explain.")
                        .put("userContent", "Selected text: Cells store fuel."),
                )
                .toString()
            val chat = post(port, "/v1/complete", chatBody)
            val chatJson = JSONObject(chat.body())
            assertEquals(200, chat.statusCode())
            assertEquals(
                setOf("requestId", "responseId", "content", "provider", "model", "status"),
                chatJson.keySet(),
            )
            assertEquals("chat answer", chatJson.getString("content"))
            assertEquals("SUCCESS", chatJson.getString("status"))
            assertEquals("gpt-4o-mini", chatJson.getString("model"))
            assertEquals("request-1", chatJson.getString("requestId"))
            assertEquals("response-9", chatJson.getString("responseId"))
            assertFalse(chat.body().contains("embedding"))
        } finally {
            server.stop(0)
        }
    }

    private fun requestBody(text: String): String {
        return JSONObject().put("text", text).toString()
    }

    private fun sampleVector(first: Double): List<Double> {
        return List(EMBEDDING_DIMENSIONS) { index -> if (index == 0) first else 2.0 }
    }

    private fun jsonArray(array: JSONArray): List<Double> {
        return List(array.length()) { index -> array.getDouble(index) }
    }

    private fun openAiBody(vector: List<Double>): String {
        val embedding = JSONArray()
        vector.forEach { embedding.put(it) }
        return JSONObject()
            .put("data", JSONArray().put(JSONObject().put("embedding", embedding)))
            .toString()
    }

    private fun embeddingServer(handler: (HttpExchange) -> Unit): HttpServer {
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake.createContext("/v1/embeddings") { exchange -> handler(exchange) }
        fake.start()
        return fake
    }

    private fun post(port: Int, path: String, body: String): HttpResponse<String> {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }
}
