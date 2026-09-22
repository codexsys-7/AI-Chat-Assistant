package dev.probe.samplechat

data class ChatMessage(
    val fromUser: Boolean,
    val paragraphs: List<String>,
)

/**
 * The middle paragraph of the assistant energy note is the sentence the
 * emulator harness selects and sends to the text-selection probe.
 */
const val DISTINCTIVE_SENTENCE =
    "The mitochondria converts nutrients into usable energy for the cell."

val sampleTranscript: List<ChatMessage> = listOf(
    ChatMessage(
        fromUser = true,
        paragraphs = listOf("What should I know about how a cell makes energy?"),
    ),
    ChatMessage(
        fromUser = false,
        paragraphs = listOf(
            "Cells store fuel, but they still have to turn it into something they can spend.",
            DISTINCTIVE_SENTENCE,
            "Everything after that sentence is only there so the target sits in the middle.",
        ),
    ),
    ChatMessage(
        fromUser = true,
        paragraphs = listOf("I will select the mitochondria sentence and open the probe."),
    ),
    ChatMessage(
        fromUser = false,
        paragraphs = listOf(
            "Use the normal text selection gesture on that sentence, then choose Text Selection Probe from the toolbar.",
        ),
    ),
    ChatMessage(
        fromUser = true,
        paragraphs = listOf("Starting there."),
    ),
)
