package dev.probe.textselection.ai

/**
 * The only place that chooses [MockAIProvider]. Screens talk to [AIService].
 */
object AppServices {
    val ai: AIService = AIService(provider = MockAIProvider())
}
