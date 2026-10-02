package dev.probe.textselection.context

enum class CaptureStatus {
    SUCCESS,
    PARTIAL,
    FAILED,
}

enum class CaptureMethod {
    SelectedText,
    Accessibility,
    Other,
}

data class ContextCapture(
    val selectedText: String?,
    val precedingContext: String?,
    val followingContext: String?,
    val sourcePackage: String?,
    val captureMethod: CaptureMethod,
    val success: Boolean,
    val failureReason: String?,
    val status: CaptureStatus,
) {
    companion object {
        fun idle() = ContextCapture(
            selectedText = null,
            precedingContext = null,
            followingContext = null,
            sourcePackage = null,
            captureMethod = CaptureMethod.Other,
            success = false,
            failureReason = null,
            status = CaptureStatus.FAILED,
        )
    }
}
