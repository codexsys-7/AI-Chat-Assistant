package dev.probe.textselection.context

import android.util.Log

internal object ContextCaptureLog {
    const val TAG = "ContextCapture"

    fun serviceState(enabled: Boolean) {
        Log.i(TAG, "accessibilityServiceEnabled=$enabled")
    }

    fun snapshot(
        sourcePackage: String,
        nodeCount: Int,
        selectedLength: Int,
        selectedPreview: String,
        selectedFound: Boolean,
        precedingFound: Boolean,
        followingFound: Boolean,
    ) {
        Log.i(
            TAG,
            "snapshot sourcePackage=$sourcePackage nodeCount=$nodeCount " +
                "selectedLength=$selectedLength preview=${preview(selectedPreview)} " +
                "selectedFound=$selectedFound precedingFound=$precedingFound followingFound=$followingFound",
        )
    }

    fun result(
        capture: ContextCapture,
        accessibilityEnabled: Boolean,
        nodeCount: Int?,
        selectedFound: Boolean,
    ) {
        Log.i(
            TAG,
            "selectedTextReceived length=${capture.selectedText?.length ?: 0} " +
                "preview=${preview(capture.selectedText.orEmpty())} " +
                "sourcePackage=${capture.sourcePackage ?: "none"} " +
                "accessibilityEnabled=$accessibilityEnabled " +
                "nodeCount=${nodeCount?.toString() ?: "unavailable"} " +
                "selectedFound=$selectedFound " +
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
