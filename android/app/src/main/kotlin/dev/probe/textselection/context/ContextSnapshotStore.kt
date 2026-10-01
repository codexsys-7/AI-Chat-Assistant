package dev.probe.textselection.context

/**
 * In-memory snapshots taken while the host app is still visible.
 * Nothing here is written to disk.
 */
internal data class SelectionSnapshot(
    val sourcePackage: String,
    val selectedText: String,
    val precedingContext: String?,
    val followingContext: String?,
    val nodeCount: Int,
    val selectedFound: Boolean,
)

internal object ContextSnapshotStore {
    private const val MAX_SNAPSHOTS = 8
    private val lock = Any()
    private val recent = ArrayDeque<SelectionSnapshot>()

    fun record(snapshot: SelectionSnapshot) {
        synchronized(lock) {
            recent.addLast(snapshot)
            while (recent.size > MAX_SNAPSHOTS) {
                recent.removeFirst()
            }
        }
    }

    fun match(selectedText: String): SelectionSnapshot? {
        val needle = SentenceWindow.normalize(selectedText)
        if (needle.isEmpty()) return null
        synchronized(lock) {
            return recent.asReversed().firstOrNull { snapshot ->
                val candidate = SentenceWindow.normalize(snapshot.selectedText)
                candidate.isNotEmpty() &&
                    (candidate == needle || candidate.contains(needle) || needle.contains(candidate))
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            recent.clear()
        }
    }
}
