package dev.probe.textselection.interaction

/**
 * Local instructions for a provider. Nothing here is sent over the network.
 */
data class Prompt(
    val systemInstruction: String,
    val userContent: String,
)
