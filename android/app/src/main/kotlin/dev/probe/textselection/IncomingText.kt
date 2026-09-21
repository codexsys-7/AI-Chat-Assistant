package dev.probe.textselection

import android.content.Intent

object IncomingText {
    fun read(intent: Intent?): ReceivedText {
        if (intent == null) {
            return ReceivedText.none()
        }
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> ProcessText.read(intent)
            Intent.ACTION_SEND -> ShareText.read(intent)
            else -> ReceivedText.none(action = intent.action, mimeType = intent.type)
        }
    }
}
