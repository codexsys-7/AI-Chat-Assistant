package dev.probe.textselection.ai

import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.engine.ContextQuality
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.AIRequestAttempt
import dev.probe.textselection.interaction.AIRequestFactory
import dev.probe.textselection.interaction.Prompt
import dev.probe.textselection.interaction.ResponseStatus
import dev.probe.textselection.interaction.UserAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

class RemoteAiProviderTest {
    private val selected = "Cells store fuel before they can spend it."
    private val preceding = "A cell still has to turn stored fuel into usable energy."
    private val following = "That next step is what the mitochondria sentence describes."
    private val sourcePackage = "dev.probe.samplechat"

    @Test
    fun requestJsonUsesTheContractAndOmitsDeviceData() {
        val source = request(UserAction.EXPLAIN, ContextQuality.HIGH)
        val prompt = PromptEngine.build(source)
        val payload = payloadFor(source, prompt)
        val json = parseJsonObject(payload.toJson())

        assertEquals(
            setOf(
                "requestId",
                "action",
                "selectedText",
                "precedingText",
                "followingText",
                "contextQuality",
                "prompt",
            ),
            json.keys,
        )
        assertEquals(source.requestId, json["requestId"])
        assertEquals("EXPLAIN", json["action"])
        assertEquals(selected, json["selectedText"])
        assertEquals(preceding, json["precedingText"])
        assertEquals(following, json["followingText"])
        assertEquals("HIGH", json["contextQuality"])
        @Suppress("UNCHECKED_CAST")
        val promptJson = json["prompt"] as Map<String, Any?>
        assertEquals(setOf("systemInstruction", "userContent"), promptJson.keys)
        assertEquals(prompt.systemInstruction, promptJson["systemInstruction"])
        assertEquals(prompt.userContent, promptJson["userContent"])
        val raw = payload.toJson()
        assertFalse(raw.contains(sourcePackage))
        assertFalse(raw.contains("analytics"))
        assertFalse(raw.contains("Authorization"))
        assertFalse(raw.contains("apiKey"))
    }

    @Test
    fun unavailableContextSerializesNullSurroundingsAndThePrompt() {
        val source = request(UserAction.EXAMPLE, ContextQuality.UNAVAILABLE, preceding = null, following = null)
        val prompt = PromptEngine.build(source)
        val json = parseJsonObject(payloadFor(source, prompt).toJson())

        assertNull(json["precedingText"])
        assertNull(json["followingText"])
        assertTrue(json.containsKey("precedingText"))
        assertTrue(json.containsKey("followingText"))
        assertEquals("UNAVAILABLE", json["contextQuality"])
        assertEquals("EXAMPLE", json["action"])
        @Suppress("UNCHECKED_CAST")
        val userContent = (json["prompt"] as Map<String, Any?>)["userContent"] as String
        assertEquals(prompt.userContent, userContent)
        assertTrue(userContent.contains("Only the selected text is available."))
        assertTrue(userContent.contains("Selected text: $selected"))
        assertFalse(userContent.contains("Preceding context:"))
        assertFalse(userContent.contains(preceding))
    }

    @Test
    fun quotedSelectedTextStaysValidJson() {
        val source = request(UserAction.FOLLOW_UP, ContextQuality.LOW, preceding = null, following = "After \"this\".")
        val payload = payloadFor(source, Prompt("sys", "user says \"hello\""))
        val json = parseJsonObject(payload.toJson())

        assertEquals(selected, json["selectedText"])
        assertNull(json["precedingText"])
        assertEquals("After \"this\".", json["followingText"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("user says \"hello\"", (json["prompt"] as Map<String, Any?>)["userContent"])
    }

    @Test
    fun responseJsonBecomesTheExistingAiResponse() {
        val body = """
            {
              "requestId": "request-1",
              "responseId": "response-9",
              "content": "The cell spends fuel in the mitochondria.",
              "provider": "OpenAI",
              "model": "gpt-4o-mini",
              "status": "SUCCESS"
            }
        """.trimIndent()
        val parsed = parseBackendResponse(body)

        assertTrue(parsed is ResponseParse.Ok)
        val payload = (parsed as ResponseParse.Ok).payload
        assertEquals("request-1", payload.requestId)
        assertEquals("response-9", payload.responseId)
        assertEquals("OpenAI", payload.provider)
        assertEquals("gpt-4o-mini", payload.model)
        assertEquals(ResponseStatus.SUCCESS, payload.status)
    }

    @Test
    fun malformedAndEmptyBodiesDoNotParse() {
        assertTrue(parseBackendResponse(null) is ResponseParse.Empty)
        assertTrue(parseBackendResponse("  ") is ResponseParse.Empty)
        assertTrue(parseBackendResponse("{") is ResponseParse.Malformed)
        assertTrue(parseBackendResponse("[]") is ResponseParse.Malformed)
        assertTrue(parseBackendResponse("""{"requestId":"request-1"}""") is ResponseParse.Malformed)
        assertTrue(parseBackendResponse(successBody(requestId = "request-1", status = "OK")) is ResponseParse.Malformed)
        assertTrue(parseBackendResponse(successBody(requestId = " ")) is ResponseParse.Malformed)
    }

    @Test
    fun successUsesServerProviderAndModelAndKeepsTheRequestId() {
        val source = request(UserAction.EXPLAIN, ContextQuality.HIGH)
        val prompt = PromptEngine.build(source)
        val fake = FakeBackend {
            BackendExchange.Response(
                statusCode = 200,
                body = successBody(
                    requestId = source.requestId,
                    responseId = "response-9",
                    content = "Fuel is spent in the mitochondria.",
                    provider = "OpenAI",
                    model = "gpt-4o-mini",
                ),
            )
        }
        val response = runBlocking {
            RemoteAIProvider(fake, newId = { "local-should-not-be-used" }).generateResponse(source, prompt)
        }

        assertEquals(1, fake.payloads.size)
        assertEquals(prompt.systemInstruction, fake.payloads.single().systemInstruction)
        assertEquals(prompt.userContent, fake.payloads.single().userContent)
        assertEquals(source.requestId, response.requestId)
        assertEquals("response-9", response.responseId)
        assertEquals(UserAction.EXPLAIN, response.action)
        assertEquals("Fuel is spent in the mitochondria.", response.content)
        assertEquals("OpenAI", response.provider)
        assertEquals("gpt-4o-mini", response.model)
        assertEquals(ResponseStatus.SUCCESS, response.status)
        assertNotEquals(MockAIProvider.PROVIDER_NAME, response.provider)
        assertNotEquals(MockAIProvider.MODEL_NAME, response.model)
    }

    @Test
    fun providerForwardsTheGivenPromptInsteadOfRebuildingIt() {
        val source = request(UserAction.EXPLAIN, ContextQuality.HIGH)
        val custom = Prompt(systemInstruction = "CUSTOM SYSTEM", userContent = "CUSTOM USER")
        val fake = FakeBackend { BackendExchange.TimedOut }
        runBlocking { RemoteAIProvider(fake).generateResponse(source, custom) }

        assertEquals("CUSTOM SYSTEM", fake.payloads.single().systemInstruction)
        assertEquals("CUSTOM USER", fake.payloads.single().userContent)
        assertNotEquals(PromptEngine.build(source).userContent, fake.payloads.single().userContent)
    }

    @Test
    fun explainExampleAndFollowUpSendTheirOwnPrompts() {
        val seen = mutableListOf<BackendRequestPayload>()
        val fake = FakeBackend { payload ->
            seen += payload
            BackendExchange.Response(
                statusCode = 200,
                body = successBody(requestId = payload.requestId, content = "ok ${payload.action}"),
            )
        }
        val provider = RemoteAIProvider(fake, newId = { "unused" })
        UserAction.entries.forEach { action ->
            val source = request(action, ContextQuality.HIGH)
            val prompt = PromptEngine.build(source)
            val response = runBlocking { provider.generateResponse(source, prompt) }
            assertEquals(source.requestId, response.requestId)
            assertEquals(action, response.action)
            assertEquals(ResponseStatus.SUCCESS, response.status)
            assertEquals("ok ${action.name}", response.content)
        }
        assertEquals(listOf("EXPLAIN", "EXAMPLE", "FOLLOW_UP"), seen.map { it.action })
        assertEquals(
            listOf(
                "Explain the passage using the available context.",
                "Give a concrete example of the passage using the available context.",
                "Answer the implied follow-up using the passage and the available context.",
            ),
            seen.map { it.systemInstruction },
        )
        seen.forEach { payload ->
            assertTrue(payload.userContent.contains("Action: ${payload.action}"))
            assertTrue(payload.userContent.contains("Selected text: $selected"))
            assertTrue(payload.userContent.contains("Preceding context: $preceding"))
            assertTrue(payload.userContent.contains("Following context: $following"))
            assertEquals("HIGH", payload.contextQuality)
        }
    }

    @Test
    fun servicePassesThePromptEnginePromptThroughTheRemoteProvider() {
        val source = request(UserAction.FOLLOW_UP, ContextQuality.MEDIUM, following = null)
        val fake = FakeBackend { payload ->
            BackendExchange.Response(200, successBody(payload.requestId))
        }
        val response = runBlocking {
            AIService(RemoteAIProvider(fake)).generate(source)
        }
        val expected = PromptEngine.build(source)

        assertEquals(expected.systemInstruction, fake.payloads.single().systemInstruction)
        assertEquals(expected.userContent, fake.payloads.single().userContent)
        assertEquals("MEDIUM", fake.payloads.single().contextQuality)
        assertEquals(null, fake.payloads.single().followingText)
        assertEquals(source.requestId, response.requestId)
        assertEquals(ResponseStatus.SUCCESS, response.status)
    }

    @Test
    fun httpTimeoutMalformedAndEmptyMapToControlledErrors() {
        val source = request(UserAction.EXPLAIN, ContextQuality.HIGH)
        val prompt = PromptEngine.build(source)
        val cases = listOf(
            BackendExchange.TimedOut to RemoteAIProvider.DEBUG_TIMED_OUT,
            BackendExchange.Unreachable to RemoteAIProvider.DEBUG_UNREACHABLE,
            BackendExchange.NoNetwork to RemoteAIProvider.DEBUG_NO_NETWORK,
            BackendExchange.Response(200, "") to RemoteAIProvider.DEBUG_EMPTY_RESPONSE,
            BackendExchange.Response(200, "{") to RemoteAIProvider.DEBUG_MALFORMED,
            BackendExchange.Response(500, "") to "HTTP 500",
            BackendExchange.Response(500, "not-json") to "HTTP 500",
            BackendExchange.Response(200, successBody(requestId = "someone-else")) to RemoteAIProvider.DEBUG_MALFORMED,
        )
        cases.forEach { (exchange, debug) ->
            val response = runBlocking {
                RemoteAIProvider(
                    FakeBackend { exchange },
                    newId = { "error-id" },
                ).generateResponse(source, prompt)
            }
            assertEquals(source.requestId, response.requestId)
            assertEquals("error-id", response.responseId)
            assertEquals(ResponseStatus.ERROR, response.status)
            assertEquals(UserAction.EXPLAIN, response.action)
            assertEquals(remoteErrorContent(debug), response.content)
            assertEquals(RemoteAIProvider.PROVIDER_NAME, response.provider)
            assertFalse(response.content.contains(selected))
            assertFalse(response.content.contains("sk-"))
        }
    }

    @Test
    fun emptyModelContentKeepsServerMetadataAndOriginalRequestId() {
        val source = request(UserAction.EXAMPLE, ContextQuality.HIGH)
        val response = runBlocking {
            RemoteAIProvider(
                FakeBackend {
                    BackendExchange.Response(
                        200,
                        successBody(requestId = source.requestId, responseId = "response-9", content = "   "),
                    )
                },
                newId = { "local-id" },
            ).generateResponse(source, PromptEngine.build(source))
        }

        assertEquals(source.requestId, response.requestId)
        assertEquals("response-9", response.responseId)
        assertEquals("OpenAI", response.provider)
        assertEquals("gpt-4o-mini", response.model)
        assertEquals(ResponseStatus.ERROR, response.status)
        assertEquals(remoteErrorContent(RemoteAIProvider.DEBUG_EMPTY_CONTENT), response.content)
        assertFalse(response.content.contains(selected))
    }

    @Test
    fun httpErrorContractKeepsResponseIdAndOriginalRequestId() {
        val source = request(UserAction.EXAMPLE, ContextQuality.UNAVAILABLE, preceding = null, following = null)
        val body = successBody(
            requestId = source.requestId,
            responseId = "backend-error-1",
            content = "Could not prepare a response.\nDebug: provider HTTP 401",
            status = "ERROR",
        )
        val response = runBlocking {
            RemoteAIProvider(FakeBackend { BackendExchange.Response(502, body) }, newId = { "local" })
                .generateResponse(source, PromptEngine.build(source))
        }

        assertEquals(source.requestId, response.requestId)
        assertEquals("backend-error-1", response.responseId)
        assertEquals("OpenAI", response.provider)
        assertEquals("gpt-4o-mini", response.model)
        assertEquals(ResponseStatus.ERROR, response.status)
        assertTrue(response.content.contains("Debug: provider HTTP 401"))
        assertFalse(response.content.contains(selected))
    }

    @Test
    fun secretLookingErrorContentIsReplaced() {
        val source = request(UserAction.EXPLAIN, ContextQuality.LOW, following = null)
        val body = successBody(
            requestId = source.requestId,
            responseId = "backend-error-2",
            content = "Incorrect API key provided: sk-supersecret",
            status = "ERROR",
        )
        val response = runBlocking {
            RemoteAIProvider(FakeBackend { BackendExchange.Response(502, body) })
                .generateResponse(source, PromptEngine.build(source))
        }

        assertEquals(ResponseStatus.ERROR, response.status)
        assertEquals(source.requestId, response.requestId)
        assertFalse(response.content.contains("sk-"))
        assertFalse(response.content.contains("supersecret"))
        assertTrue(response.content.contains(AIService.ERROR_MESSAGE))
    }

    @Test
    fun unexpectedClientExceptionDoesNotCrashOrLeak() {
        val source = request(UserAction.FOLLOW_UP, ContextQuality.HIGH)
        val exploding = object : AIBackendClient {
            override suspend fun exchange(payload: BackendRequestPayload): BackendExchange {
                throw IllegalStateException("boom sk-should-not-leak")
            }
        }
        val response = runBlocking {
            RemoteAIProvider(exploding, newId = { "error-id" })
                .generateResponse(source, PromptEngine.build(source))
        }

        assertEquals(ResponseStatus.ERROR, response.status)
        assertEquals("error-id", response.responseId)
        assertEquals(source.requestId, response.requestId)
        assertEquals(remoteErrorContent(RemoteAIProvider.DEBUG_UNEXPECTED), response.content)
        assertFalse(response.content.contains("sk-"))
        assertFalse(response.content.contains("boom"))
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val source = request(UserAction.EXPLAIN, ContextQuality.HIGH)
        val cancelling = object : AIBackendClient {
            override suspend fun exchange(payload: BackendRequestPayload): BackendExchange {
                throw CancellationException("stop")
            }
        }
        val error = runCatching {
            runBlocking {
                RemoteAIProvider(cancelling).generateResponse(source, PromptEngine.build(source))
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test
    fun transportClassifierIgnoresExceptionText() {
        assertTrue(classifyTransportFailure(SocketTimeoutException("sk-timeout")) is BackendExchange.TimedOut)
        assertTrue(classifyTransportFailure(UnknownHostException("sk-host")) is BackendExchange.NoNetwork)
        assertTrue(classifyTransportFailure(ConnectException("sk-refused")) is BackendExchange.Unreachable)
        assertEquals("http://10.0.2.2:8080/v1/complete", backendEndpoint("http://10.0.2.2:8080/"))
        assertEquals("http://10.0.2.2:8080/v1/complete", backendEndpoint(" http://10.0.2.2:8080 "))
    }

    @Test
    fun mockModeStaysOfflineAndRemoteModeSelectsTheRemoteProvider() {
        val mock = createProvider("MOCK", "http://127.0.0.1:9")
        assertTrue(mock is MockAIProvider)
        val source = request(UserAction.EXPLAIN, ContextQuality.UNAVAILABLE, preceding = null, following = null)
        val mockResponse = runBlocking { mock.generateResponse(source, PromptEngine.build(source)) }
        assertEquals(MockAIProvider.PROVIDER_NAME, mockResponse.provider)
        assertEquals(MockAIProvider.MODEL_NAME, mockResponse.model)
        assertEquals(ResponseStatus.SUCCESS, mockResponse.status)
        assertEquals(source.requestId, mockResponse.requestId)
        assertTrue(mockResponse.content.contains("Only the selected text is available."))

        assertTrue(createProvider("remote", "http://10.0.2.2:8080") is RemoteAIProvider)
        assertTrue(createProvider("typo", "http://10.0.2.2:8080") is MockAIProvider)
        val injected = FakeBackend { BackendExchange.TimedOut }
        val remote = createProvider("REMOTE", "http://127.0.0.1:9", remoteClient = injected)
        assertTrue(remote is RemoteAIProvider)
        val remoteResponse = runBlocking {
            remote.generateResponse(source, PromptEngine.build(source))
        }
        assertEquals(ResponseStatus.ERROR, remoteResponse.status)
        assertEquals(1, injected.payloads.size)
        assertEquals("UNAVAILABLE", injected.payloads.single().contextQuality)
    }

    private fun payloadFor(source: AIRequest, prompt: Prompt): BackendRequestPayload {
        return BackendRequestPayload(
            requestId = source.requestId,
            action = source.action.name,
            selectedText = source.selectedText,
            precedingText = source.precedingContext,
            followingText = source.followingContext,
            contextQuality = source.contextQuality.name,
            systemInstruction = prompt.systemInstruction,
            userContent = prompt.userContent,
        )
    }

    private fun successBody(
        requestId: String,
        responseId: String = "response-9",
        content: String = "A mitochondrion makes ATP.",
        provider: String = "OpenAI",
        model: String = "gpt-4o-mini",
        status: String = "SUCCESS",
    ): String {
        return buildString {
            append('{')
            append("\"requestId\":").append(jsonString(requestId))
            append(",\"responseId\":").append(jsonString(responseId))
            append(",\"content\":").append(jsonString(content))
            append(",\"provider\":").append(jsonString(provider))
            append(",\"model\":").append(jsonString(model))
            append(",\"status\":").append(jsonString(status))
            append('}')
        }
    }

    private fun request(
        action: UserAction,
        quality: ContextQuality,
        preceding: String? = this.preceding,
        following: String? = this.following,
    ): AIRequest {
        val attempt = AIRequestFactory.create(
            ContextPackage(
                selectedText = selected,
                precedingContext = preceding,
                followingContext = following,
                sourcePackage = sourcePackage,
                captureMethod = CaptureMethod.Accessibility,
                captureStatus = CaptureStatus.SUCCESS,
                contextQuality = quality,
            ),
            action,
            newId = { "request-1" },
            now = { Instant.parse("2026-10-05T00:00:00Z") },
        )
        return (attempt as AIRequestAttempt.Ready).request
    }

    private class FakeBackend(
        private val handler: (BackendRequestPayload) -> BackendExchange,
    ) : AIBackendClient {
        val payloads = mutableListOf<BackendRequestPayload>()

        override suspend fun exchange(payload: BackendRequestPayload): BackendExchange {
            payloads += payload
            return handler(payload)
        }
    }
}
