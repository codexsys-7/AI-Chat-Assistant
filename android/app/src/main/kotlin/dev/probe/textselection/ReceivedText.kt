package dev.probe.textselection

enum class TextPath {
    Selected,
    Shared,
    None,
}

data class ReceivedText(
    val path: TextPath,
    val action: String?,
    val mimeType: String?,
    val text: String?,
) {
    companion object {
        fun none(action: String? = null, mimeType: String? = null) = ReceivedText(
            path = TextPath.None,
            action = action,
            mimeType = mimeType,
            text = null,
        )
    }
}
