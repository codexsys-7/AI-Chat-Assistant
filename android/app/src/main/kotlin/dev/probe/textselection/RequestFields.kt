package dev.probe.textselection

import android.content.Context
import android.content.Intent
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

        fun from(request: AIRequest) = RequestFields(
            action = request.action.label,
            selectedText = request.selectedText,
            precedingText = request.precedingContext.orEmpty(),
            followingText = request.followingContext.orEmpty(),
            sourceApp = request.sourcePackage.orEmpty(),
            captureMethod = request.captureMethod.name,
            contextQuality = request.contextQuality.name,
            requestId = request.requestId,
        )

        fun from(intent: Intent?) = RequestFields(
            action = intent?.getStringExtra(ACTION).orEmpty(),
            selectedText = intent?.getStringExtra(SELECTED_TEXT).orEmpty(),
            precedingText = intent?.getStringExtra(PRECEDING_TEXT).orEmpty(),
            followingText = intent?.getStringExtra(FOLLOWING_TEXT).orEmpty(),
            sourceApp = intent?.getStringExtra(SOURCE_APP).orEmpty(),
            captureMethod = intent?.getStringExtra(CAPTURE_METHOD).orEmpty(),
            contextQuality = intent?.getStringExtra(CONTEXT_QUALITY).orEmpty(),
            requestId = intent?.getStringExtra(REQUEST_ID).orEmpty(),
        )
    }
}
