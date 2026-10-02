package dev.probe.textselection.context

import android.util.Log

internal object ContextCaptureLog {
    const val TAG = "ContextCapture"

    fun serviceState(enabled: Boolean) {
        Log.i(TAG, "accessibilityServiceEnabled=$enabled")
    }

    fun visibleWindow(sourcePackage: String, nodeCount: Int, textLength: Int) {
        Log.i(
            TAG,
            "visibleWindow sourcePackage=$sourcePackage nodeCount=$nodeCount textLength=$textLength",
        )
    }

    fun result(
        capture: ContextCapture,
        accessibilityEnabled: Boolean,
        nodeCount: Int?,
        windowPackage: String?,
        selectedContained: Boolean,
    ) {
        Log.i(
            TAG,
            "selectedTextReceived length=${capture.selectedText?.length ?: 0} " +
                "preview=${preview(capture.selectedText.orEmpty())} " +
                "sourcePackage=${capture.sourcePackage ?: windowPackage ?: "none"} " +
                "accessibilityEnabled=$accessibilityEnabled " +
                "nodeCount=${nodeCount?.toString() ?: "unavailable"} " +
                "selectedContained=$selectedContained " +
                "precedingFound=${!capture.precedingContext.isNullOrBlank()} " +
                "followingFound=${!capture.followingContext.isNullOrBlank()} " +
                "success=${capture.success} status=${capture.status} method=${capture.captureMethod} " +
                "failureReason=${capture.failureReason ?: "none"}",
        )
    }

    private fun preview(text: String): String {
        val normalized = SentenceWindow.normalize(text)
        if (normalized.isEmpty()) return "none"
        return if (normalized.length <= 48) normalized else normalized.take(48) + "…"
    }
}
