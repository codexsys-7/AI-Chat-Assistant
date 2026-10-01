package dev.probe.textselection.context

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import dev.probe.textselection.ReceivedText
import dev.probe.textselection.TextPath

/**
 * Boundary between selection capture and the debug UI.
 * Matches [EXTRA_PROCESS_TEXT] against an in-memory snapshot. It does not read the window itself.
 */
object ContextCaptureRepository {
    fun capture(context: Context, received: ReceivedText): ContextCapture {
        val matched = if (received.path == TextPath.Selected && !received.text.isNullOrBlank()) {
            ContextSnapshotStore.match(received.text)
        } else {
            null
        }
        val capture = when (received.path) {
            TextPath.Selected -> fromSelection(context, received.text, matched)
            TextPath.Shared -> sharedOnly(received.text)
            TextPath.None -> ContextCapture.idle()
        }
        if (received.path != TextPath.None) {
            ContextCaptureLog.result(
                capture = capture,
                accessibilityEnabled = accessibilityEnabled(context),
                nodeCount = matched?.nodeCount,
                selectedFound = matched?.selectedFound == true,
            )
        }
        return capture
    }

    fun accessibilityEnabled(context: Context): Boolean {
        if (ContextAccessibilityService.running) return true
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val component = ComponentName(context, ContextAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun fromSelection(
        context: Context,
        selectedText: String?,
        snapshot: SelectionSnapshot?,
    ): ContextCapture {
        if (selectedText.isNullOrBlank()) {
            return failed(
                selectedText = selectedText,
                method = CaptureMethod.SelectedText,
                reason = "Selected text was empty.",
            )
        }
        if (snapshot == null) {
            val reason = if (accessibilityEnabled(context)) {
                "No visible-text snapshot matched this selection."
            } else {
                "Accessibility service is off."
            }
            return failed(
                selectedText = selectedText,
                method = CaptureMethod.SelectedText,
                reason = reason,
            )
        }
        val preceding = snapshot.precedingContext
        val following = snapshot.followingContext
        val hasPreceding = !preceding.isNullOrBlank()
        val hasFollowing = !following.isNullOrBlank()
        val status = when {
            hasPreceding && hasFollowing -> CaptureStatus.SUCCESS
            hasPreceding || hasFollowing -> CaptureStatus.PARTIAL
            else -> CaptureStatus.FAILED
        }
        return ContextCapture(
            selectedText = selectedText,
            precedingContext = preceding,
            followingContext = following,
            sourcePackage = snapshot.sourcePackage,
            captureMethod = CaptureMethod.Accessibility,
            success = status == CaptureStatus.SUCCESS,
            failureReason = when (status) {
                CaptureStatus.SUCCESS -> null
                CaptureStatus.PARTIAL -> "Only one side of the surrounding text was visible."
                CaptureStatus.FAILED -> "Selected text was found, but no surrounding sentences were visible."
            },
            status = status,
        )
    }

    private fun sharedOnly(text: String?): ContextCapture {
        return ContextCapture(
            selectedText = text,
            precedingContext = null,
            followingContext = null,
            sourcePackage = null,
            captureMethod = CaptureMethod.Other,
            success = false,
            failureReason = "Surrounding context is only captured around selected text.",
            status = CaptureStatus.FAILED,
        )
    }

    private fun failed(
        selectedText: String?,
        method: CaptureMethod,
        reason: String,
    ) = ContextCapture(
        selectedText = selectedText,
        precedingContext = null,
        followingContext = null,
        sourcePackage = null,
        captureMethod = method,
        success = false,
        failureReason = reason,
        status = CaptureStatus.FAILED,
    )
}
