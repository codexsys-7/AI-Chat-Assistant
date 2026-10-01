package dev.probe.textselection.context

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Reads the visible window only while the host app is still showing.
 * ACTION_PROCESS_TEXT brings the probe forward, so this service does not
 * look at the window again from the activity.
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
        val selectionEvent = type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        val contentEvent = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!selectionEvent && !contentEvent) return

        val sourcePackage = event.packageName?.toString() ?: return
        if (sourcePackage == packageName) return

        val now = android.os.SystemClock.uptimeMillis()
        if (contentEvent && now - lastContentSnapshotAt < CONTENT_THROTTLE_MS) return

        val root = rootInActiveWindow
        val rootPackage = root?.packageName?.toString()
        val visibleNodes = if (root != null && rootPackage != packageName) {
            try {
                VisibleTextExtractor.collect(root)
            } finally {
                root.recycle()
            }
        } else {
            root?.recycle()
            emptyList()
        }

        val eventText = event.text.firstOrNull()?.toString().orEmpty()
        val snapshot = SelectionContextBuilder.build(
            sourcePackage = sourcePackage,
            eventText = eventText,
            selectionStart = event.fromIndex,
            selectionEnd = event.toIndex,
            selectionEvent = selectionEvent,
            visibleNodes = visibleNodes,
        ) ?: return

        if (contentEvent) {
            lastContentSnapshotAt = now
        }
        ContextSnapshotStore.record(snapshot)
        ContextCaptureLog.snapshot(
            sourcePackage = snapshot.sourcePackage,
            nodeCount = snapshot.nodeCount,
            selectedLength = snapshot.selectedText.length,
            selectedPreview = snapshot.selectedText,
            selectedFound = snapshot.selectedFound,
            precedingFound = !snapshot.precedingContext.isNullOrBlank(),
            followingFound = !snapshot.followingContext.isNullOrBlank(),
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        running = false
        ContextCaptureLog.serviceState(false)
        super.onDestroy()
    }

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        private const val CONTENT_THROTTLE_MS = 400L
        private var lastContentSnapshotAt = 0L
    }
}
