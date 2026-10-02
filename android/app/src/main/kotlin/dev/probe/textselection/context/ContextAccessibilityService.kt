package dev.probe.textselection.context

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Stores visible text while another app is still in front.
 * ACTION_PROCESS_TEXT then brings the probe forward, so this service does not
 * read the window again from the activity, and probe events do not replace
 * the last other-app snapshot.
 */
class ContextAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        ContextCaptureLog.serviceState(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val watched = type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!watched) return

        val eventPackage = event.packageName?.toString() ?: return
        if (isProbe(eventPackage)) return

        val now = android.os.SystemClock.uptimeMillis()
        val root = rootInActiveWindow ?: return
        val rootPackage = root.packageName?.toString()
        if (rootPackage.isNullOrBlank() || isProbe(rootPackage)) {
            root.recycle()
            return
        }

        val nodes = try {
            VisibleTextExtractor.collect(root)
        } finally {
            root.recycle()
        }
        if (nodes.isEmpty()) return
        val visibleText = nodes.joinToString("\n") { it.text }
        if (visibleText.isBlank()) return

        val previous = ContextSnapshotStore.latest()
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            previous?.visibleText == visibleText &&
            now - lastContentSnapshotAt < CONTENT_THROTTLE_MS
        ) {
            return
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastContentSnapshotAt = now
        }
        ContextSnapshotStore.record(
            VisibleWindowSnapshot(
                sourcePackage = rootPackage,
                visibleText = visibleText,
                nodeCount = nodes.size,
            ),
        )
        ContextCaptureLog.visibleWindow(
            sourcePackage = rootPackage,
            nodeCount = nodes.size,
            textLength = visibleText.length,
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        running = false
        ContextCaptureLog.serviceState(false)
        super.onDestroy()
    }

    private fun isProbe(candidate: String): Boolean {
        return candidate == packageName || candidate == PROBE_PACKAGE
    }

    companion object {
        private const val PROBE_PACKAGE = "dev.probe.textselection"
        private const val CONTENT_THROTTLE_MS = 400L

        @Volatile
        var running: Boolean = false
            private set

        private var lastContentSnapshotAt = 0L
    }
}
