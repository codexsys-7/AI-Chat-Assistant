package dev.probe.textselection.interaction

/**
 * Instructions for a provider. [dev.probe.textselection.ai.PromptEngine] builds these.
 * The remote provider forwards the fields as the prompt and does not rebuild them.
 */
data class Prompt(
    val systemInstruction: String,
    val userContent: String,
)
