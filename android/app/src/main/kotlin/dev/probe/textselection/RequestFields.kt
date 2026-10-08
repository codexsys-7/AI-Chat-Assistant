package dev.probe.textselection

import android.content.Context
import android.content.Intent
import dev.probe.textselection.ai.RequestInspectionBuilder
import dev.probe.textselection.interaction.AIRequest

/**
 * Display values copied from an [AIRequest] the factory already built.
 * This does not create a request.
 */
data class RequestFields(
    val action: String,
    val selectedText: String,
    val precedingText: String,
    val followingText: String,
    val sourceApp: String,
    val captureMethod: String,
    val contextQuality: String,
    val requestId: String,
    val relevance: String,
    val segments: String,
    val systemInstruction: String,
    val userContent: String,
) {
    fun toIntent(context: Context): Intent {
        return Intent(context, RequestActivity::class.java)
            .putExtra(ACTION, action)
            .putExtra(SELECTED_TEXT, selectedText)
            .putExtra(PRECEDING_TEXT, precedingText)
            .putExtra(FOLLOWING_TEXT, followingText)
            .putExtra(SOURCE_APP, sourceApp)
            .putExtra(CAPTURE_METHOD, captureMethod)
            .putExtra(CONTEXT_QUALITY, contextQuality)
            .putExtra(REQUEST_ID, requestId)
            .putExtra(RELEVANCE, relevance)
            .putExtra(SEGMENTS, segments)
            .putExtra(SYSTEM_INSTRUCTION, systemInstruction)
            .putExtra(USER_CONTENT, userContent)
    }

    companion object {
        private const val ACTION = "dev.probe.textselection.request.action"
        private const val SELECTED_TEXT = "dev.probe.textselection.request.selectedText"
        private const val PRECEDING_TEXT = "dev.probe.textselection.request.precedingText"
        private const val FOLLOWING_TEXT = "dev.probe.textselection.request.followingText"
        private const val SOURCE_APP = "dev.probe.textselection.request.sourceApp"
        private const val CAPTURE_METHOD = "dev.probe.textselection.request.captureMethod"
        private const val CONTEXT_QUALITY = "dev.probe.textselection.request.contextQuality"
        private const val REQUEST_ID = "dev.probe.textselection.request.requestId"
        private const val RELEVANCE = "dev.probe.textselection.request.relevance"
        private const val SEGMENTS = "dev.probe.textselection.request.segments"
        private const val SYSTEM_INSTRUCTION = "dev.probe.textselection.request.systemInstruction"
        private const val USER_CONTENT = "dev.probe.textselection.request.userContent"

        fun from(request: AIRequest): RequestFields {
            val inspection = RequestInspectionBuilder.from(request)
            return RequestFields(
                action = request.action.label,
                selectedText = inspection.normalizedSelectedText,
                precedingText = inspection.normalizedPrecedingText,
                followingText = inspection.normalizedFollowingText,
                sourceApp = request.sourcePackage.orEmpty(),
                captureMethod = request.captureMethod.name,
                contextQuality = inspection.contextQuality,
                requestId = request.requestId,
                relevance = inspection.relevance,
                segments = inspection.segments,
                systemInstruction = inspection.systemInstruction,
                userContent = inspection.userContent,
            )
        }

        fun from(intent: Intent?) = RequestFields(
            action = intent?.getStringExtra(ACTION).orEmpty(),
            selectedText = intent?.getStringExtra(SELECTED_TEXT).orEmpty(),
            precedingText = intent?.getStringExtra(PRECEDING_TEXT).orEmpty(),
            followingText = intent?.getStringExtra(FOLLOWING_TEXT).orEmpty(),
            sourceApp = intent?.getStringExtra(SOURCE_APP).orEmpty(),
            captureMethod = intent?.getStringExtra(CAPTURE_METHOD).orEmpty(),
            contextQuality = intent?.getStringExtra(CONTEXT_QUALITY).orEmpty(),
            requestId = intent?.getStringExtra(REQUEST_ID).orEmpty(),
            relevance = intent?.getStringExtra(RELEVANCE).orEmpty(),
            segments = intent?.getStringExtra(SEGMENTS).orEmpty(),
            systemInstruction = intent?.getStringExtra(SYSTEM_INSTRUCTION).orEmpty(),
            userContent = intent?.getStringExtra(USER_CONTENT).orEmpty(),
        )
    }
}
