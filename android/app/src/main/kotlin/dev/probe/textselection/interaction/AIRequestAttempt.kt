package dev.probe.textselection.interaction

sealed class AIRequestAttempt {
    data class Ready(val request: AIRequest) : AIRequestAttempt()

    data class Rejected(val reason: String) : AIRequestAttempt()
}
