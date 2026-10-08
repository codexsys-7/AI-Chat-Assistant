package dev.probe.textselection.interaction

import java.time.Instant
import java.util.UUID

enum class SessionState {
    ACTIVE,
    ENDED,
}

/**
 * Identity for a short run of interactions. This is not stored text, a transcript,
 * or model context. Selected text stays on each [AIRequest].
 */
data class ConversationSession(
    val sessionId: String,
    val state: SessionState,
    val interactionCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Values the request debug screen shows for the active session.
 * An ended or missing session is [UNAVAILABLE] so the previous id is not presented as current.
 */
data class SessionDebug(
    val sessionId: String,
    val interactionCount: String,
)

const val SESSION_UNAVAILABLE = "unavailable"

fun sessionDebug(session: ConversationSession?): SessionDebug {
    val active = session?.takeIf { it.state == SessionState.ACTIVE }
    return if (active == null) {
        SessionDebug(sessionId = SESSION_UNAVAILABLE, interactionCount = SESSION_UNAVAILABLE)
    } else {
        SessionDebug(
            sessionId = active.sessionId,
            interactionCount = active.interactionCount.toString(),
        )
    }
}

/**
 * Process-local session. No timers, network, or disk.
 * Only the current session is held. Reset drops it.
 */
class ConversationSessionManager(
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Instant = { Instant.now() },
) {
    private val lock = Any()
    private var session: ConversationSession? = null

    fun create(): ConversationSession = synchronized(lock) { start() }

    fun current(): ConversationSession? = synchronized(lock) { session }

    fun exists(): Boolean = synchronized(lock) { session != null }

    fun touch(): ConversationSession? = synchronized(lock) {
        update { it.copy(updatedAt = now()) }
    }

    fun incrementInteractionCount(): ConversationSession? = synchronized(lock) {
        update { it.copy(interactionCount = it.interactionCount + 1, updatedAt = now()) }
    }

    fun end(): ConversationSession? = synchronized(lock) {
        update { it.copy(state = SessionState.ENDED, updatedAt = now()) }
    }

    fun reset() {
        synchronized(lock) {
            session = null
        }
    }

    /**
     * Id for one interaction that has already passed request validation.
     * Creates a session only when none is [SessionState.ACTIVE].
     */
    fun idForValidInteraction(): String = synchronized(lock) {
        if (session?.state != SessionState.ACTIVE) {
            start()
        }
        val updated = session!!.copy(
            interactionCount = session!!.interactionCount + 1,
            updatedAt = now(),
        )
        session = updated
        updated.sessionId
    }

    private fun start(): ConversationSession {
        val stamp = now()
        return ConversationSession(
            sessionId = newId(),
            state = SessionState.ACTIVE,
            interactionCount = 0,
            createdAt = stamp,
            updatedAt = stamp,
        ).also { session = it }
    }

    private fun update(transform: (ConversationSession) -> ConversationSession): ConversationSession? {
        val current = session ?: return null
        return transform(current).also { session = it }
    }
}

/**
 * The app's one in-memory session. Screens share it. Nothing here is written out.
 */
object ConversationSessions {
    val manager: ConversationSessionManager = ConversationSessionManager()
}
