package dev.probe.backend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import com.sun.net.httpserver.HttpServer

class BackendTest {
    private val systemPrompt = "Explain the passage using the available context."
    private val userPrompt = "Action: EXPLAIN\nContext quality: HIGH\nSelected text: Cells store fuel."

    @Test
    fun configPrefersEnvironmentAndDoesNotRequireAKey() {
        val file = parseDotEnv(
            """
            # comment
            OPENAI_API_KEY=from-file
            OPENAI_MODEL=from-file-model
            PORT=9090
            OPENAI_BASE_URL="http://127.0.0.1:9/v1"

            IGNORED
            """.trimIndent(),
        )
        val config = BackendConfig.load(
            env = mapOf("OPENAI_API_KEY" to "from-env"),
            dotenv = file,
        )

        assertEquals("from-env", config.apiKey)
        assertEquals("from-file-model", config.model)
        assertEquals(9090, config.port)
        assertEquals("http://127.0.0.1:9/v1", config.baseUrl)
        assertFalse(config.apiKey.contains("sk-"))

        val defaults = BackendConfig.load(env = emptyMap(), dotenv = emptyMap())
        assertEquals("", defaults.apiKey)
        assertEquals(BackendConfig.DEFAULT_MODEL, defaults.model)
        assertEquals(BackendConfig.DEFAULT_BASE_URL, defaults.baseUrl)
        assertEquals(8080, defaults.port)
    }

    @Test
    fun dotEnvFileIsIgnoredWhenAbsent() {
        val missing = Files.createTempDirectory("no-env")
        assertTrue(loadDotEnv(listOf(missing.resolve(".env"))).isEmpty())
    }

    @Test
    fun successForwardsThePromptAndReturnsTheContract() {
        val seen = mutableListOf<Pair<String, String>>()
        val handler = CompletionHandler(
            model = LanguageModel { system, user ->
                seen += system to user
                ModelResult.Success("The cell spends fuel in the mitochondria.")
            },
            modelName = "gpt-4o-mini",
            newId = { "response-9" },
        )
        val reply = handler.handle("POST", "/v1/complete", sampleRequest())
        val json = JSONObject(reply.body)

        assertEquals(200, reply.status)
        assertEquals(listOf(systemPrompt to userPrompt), seen)
        assertEquals("request-1", json.getString("requestId"))
        assertEquals("response-9", json.getString("responseId"))
        assertEquals("The cell spends fuel in the mitochondria.", json.getString("content"))
        assertEquals("OpenAI", json.getString("provider"))
        assertEquals("gpt-4o-mini", json.getString("model"))
        assertEquals("SUCCESS", json.getString("status"))
        assertFalse(reply.body.contains("sk-"))
        assertFalse(reply.body.contains("mock-v1"))
    }

    @Test
    fun controlledFailuresDoNotEchoUpstreamSecrets() {
        val cases = listOf(
            ModelResult.Failure("provider HTTP 401") to "provider HTTP 401",
            ModelResult.Failure("Incorrect API key provided: sk-supersecret") to "provider failure",
            ModelResult.Empty to "empty content",
            ModelResult.TimedOut to "timed out",
            ModelResult.Failure("language model is not configured") to "language model is not configured",
        )
        cases.forEach { (result, debug) ->
            val reply = CompletionHandler(
                model = LanguageModel { _, _ -> result },
                modelName = "gpt-4o-mini",
                newId = { "response-err" },
            ).handle("POST", "/v1/complete", sampleRequest(preceding = null))
            val json = JSONObject(reply.body)
            assertEquals("ERROR", json.getString("status"))
            assertEquals("request-1", json.getString("requestId"))
            assertEquals("response-err", json.getString("responseId"))
            assertEquals("OpenAI", json.getString("provider"))
            assertEquals("gpt-4o-mini", json.getString("model"))
            assertTrue(json.getString("content").contains("Could not prepare a response."))
            assertTrue(json.getString("content").contains("Debug: $debug"))
            assertFalse(reply.body.contains("sk-"))
            assertFalse(reply.body.contains("supersecret"))
        }
    }

    @Test
    fun malformedRequestsDoNotCallTheModel() {
        var called = false
        val handler = CompletionHandler(
            model = LanguageModel { _, _ ->
                called = true
                ModelResult.Success("nope")
            },
            modelName = "gpt-4o-mini",
            newId = { "response-err" },
        )
        val missingPrompt = handler.handle("POST", "/v1/complete", """{"requestId":"request-1"}""")
        val wrongMethod = handler.handle("GET", "/v1/complete", sampleRequest())
        val huge = handler.handle("POST", "/v1/complete", "x".repeat(MAX_BODY_CHARS + 1))

        assertEquals(400, missingPrompt.status)
        assertEquals(405, wrongMethod.status)
        assertEquals(413, huge.status)
        assertFalse(called)
        listOf(missingPrompt, wrongMethod, huge).forEach { reply ->
            val json = JSONObject(reply.body)
            assertEquals("ERROR", json.getString("status"))
            assertFalse(reply.body.contains("sk-"))
        }
    }

    @Test
    fun nullSurroundingTextIsAccepted() {
        val parsed = parseIncoming(sampleRequest(preceding = null, following = null))
        assertEquals("request-1", parsed?.requestId)
        assertNull(parsed?.precedingText)
        assertNull(parsed?.followingText)
        assertEquals(systemPrompt, parsed?.systemInstruction)
        assertEquals(userPrompt, parsed?.userContent)
    }

    @Test
    fun localServerReturnsTheFakeModelText() {
        val server = startCompletionServer(
            port = 0,
            handler = CompletionHandler(
                model = LanguageModel { system, user ->
                    assertEquals(systemPrompt, system)
                    assertEquals(userPrompt, user)
                    ModelResult.Success("local fake answer")
                },
                modelName = "gpt-4o-mini",
                newId = { "response-9" },
            ),
            host = "127.0.0.1",
        )
        try {
            val port = server.address.port
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/v1/complete"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(sampleRequest()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val json = JSONObject(response.body())
            assertEquals(200, response.statusCode())
            assertEquals("local fake answer", json.getString("content"))
            assertEquals("OpenAI", json.getString("provider"))
            assertEquals("gpt-4o-mini", json.getString("model"))
            assertEquals("request-1", json.getString("requestId"))
            assertEquals("response-9", json.getString("responseId"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun openAiClientUsesALocalFakeAndDropsUpstreamErrors() {
        val seen = mutableListOf<String>()
        var seenAuth: String? = null
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake.createContext("/v1/chat/completions") { exchange ->
            seenAuth = exchange.requestHeaders.getFirst("Authorization")
            seen += exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val response = """{"choices":[{"message":{"role":"assistant","content":"Real answer"}}]}"""
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        fake.start()
        try {
            val port = fake.address.port
            val model = OpenAiLanguageModel(
                apiKey = "test-key",
                model = "gpt-4o-mini",
                baseUrl = "http://127.0.0.1:$port/v1",
                requestTimeout = Duration.ofSeconds(5),
            )
            val result = model.complete(systemPrompt, userPrompt)
            assertEquals(ModelResult.Success("Real answer"), result)
            assertEquals("Bearer test-key", seenAuth)
            val sent = JSONObject(seen.single())
            assertEquals("gpt-4o-mini", sent.getString("model"))
            assertEquals(systemPrompt, sent.getJSONArray("messages").getJSONObject(0).getString("content"))
            assertEquals(userPrompt, sent.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertFalse(result.toString().contains("test-key"))
        } finally {
            fake.stop(0)
        }
    }

    @Test
    fun openAiInterpreterNeverReturnsTheUpstreamBody() {
        val leaked = """{"error":{"message":"Incorrect API key provided: sk-supersecret.","type":"invalid_request_error"}}"""
        val failure = interpretOpenAi(401, leaked)
        assertEquals(ModelResult.Failure("provider HTTP 401"), failure)
        assertFalse(failure.toString().contains("sk-"))

        assertEquals(
            ModelResult.Empty,
            interpretOpenAi(200, """{"choices":[{"message":{"content":"  "}}]}"""),
        )
        assertEquals(
            ModelResult.Failure("provider failure"),
            interpretOpenAi(200, """{"choices":[]}"""),
        )
    }

    @Test
    fun blankKeyDoesNotCallTheNetwork() {
        val model = OpenAiLanguageModel(
            apiKey = " ",
            model = "gpt-4o-mini",
            baseUrl = "http://127.0.0.1:1/v1",
            requestTimeout = Duration.ofMillis(200),
        )
        assertEquals(
            ModelResult.Failure("language model is not configured"),
            model.complete(systemPrompt, userPrompt),
        )
    }

    @Test
    fun localFakeTimeoutBecomesTimedOut() {
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake.createContext("/v1/chat/completions") { exchange ->
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
            val model = OpenAiLanguageModel(
                apiKey = "test-key",
                model = "gpt-4o-mini",
                baseUrl = "http://127.0.0.1:${fake.address.port}/v1",
                requestTimeout = Duration.ofMillis(300),
            )
            assertEquals(ModelResult.TimedOut, model.complete("system", "user"))
        } finally {
            fake.stop(0)
        }
    }

    @Test
    fun sessionIdIsCorrelationOnlyAndIsNotSentToTheModel() {
        val seen = mutableListOf<Pair<String, String>>()
        var called = false
        val handler = CompletionHandler(
            model = LanguageModel { system, user ->
                called = true
                seen += system to user
                ModelResult.Success("ok")
            },
            modelName = "gpt-4o-mini",
            newId = { "response-9" },
        )
        val withSession = JSONObject(sampleRequest())
            .put("sessionId", "session-correlation-1")
            .toString()
        val reply = handler.handle("POST", "/v1/complete", withSession)
        val json = JSONObject(reply.body)

        assertEquals(200, reply.status)
        assertEquals("session-correlation-1", json.getString("sessionId"))
        assertEquals("request-1", json.getString("requestId"))
        assertEquals("response-9", json.getString("responseId"))
        assertEquals(listOf(systemPrompt to userPrompt), seen)
        assertFalse(seen.single().first.contains("session-correlation-1"))
        assertFalse(seen.single().second.contains("session-correlation-1"))
        assertFalse(reply.body.contains("OLD-SELECTION"))
        assertEquals("session-correlation-1", parseIncoming(withSession)?.sessionId)

        val without = handler.handle("POST", "/v1/complete", sampleRequest())
        assertFalse(JSONObject(without.body).has("sessionId"))
        assertNull(parseIncoming(sampleRequest())?.sessionId)

        called = false
        val notText = JSONObject(sampleRequest()).put("sessionId", 12).toString()
        val rejected = handler.handle("POST", "/v1/complete", notText)
        assertEquals(400, rejected.status)
        assertFalse(called)
    }

    private fun sampleRequest(preceding: String? = "Before.", following: String? = "After."): String {
        return JSONObject()
            .put("requestId", "request-1")
            .put("action", "EXPLAIN")
            .put("selectedText", "Cells store fuel.")
            .put("precedingText", preceding ?: JSONObject.NULL)
            .put("followingText", following ?: JSONObject.NULL)
            .put("contextQuality", "HIGH")
            .put(
                "prompt",
                JSONObject()
                    .put("systemInstruction", systemPrompt)
                    .put("userContent", userPrompt),
            )
            .toString()
    }
}
