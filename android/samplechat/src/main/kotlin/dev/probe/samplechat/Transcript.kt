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
    ChatMessage(
        fromUser = true,
        paragraphs = listOf("How does retrieval augmented generation work?"),
    ),
    ChatMessage(
        fromUser = false,
        paragraphs = listOf(
            "Retrieval augmented generation combines language models with external knowledge sources. The system first retrieves relevant documents and then provides those documents to the model as context. This can help the model answer questions using information that was not contained in its original training data. The quality of the retrieved information therefore has a major effect on the final answer.",
        ),
    ),
)
