package dev.probe.textselection.context

/**
 * Latest visible text from an app other than the probe.
 * Kept only in memory. Probe window events must not clear it.
 */
internal data class VisibleWindowSnapshot(
    val sourcePackage: String,
    val visibleText: String,
    val nodeCount: Int,
)

internal object ContextSnapshotStore {
    private val lock = Any()
    private var latest: VisibleWindowSnapshot? = null

    fun record(snapshot: VisibleWindowSnapshot) {
        if (snapshot.sourcePackage.isBlank() || snapshot.visibleText.isBlank() || snapshot.nodeCount <= 0) {
            return
        }
        synchronized(lock) {
            latest = snapshot
        }
    }

    fun latest(): VisibleWindowSnapshot? {
        synchronized(lock) {
            return latest
        }
    }
}
