package dev.probe.textselection

import android.content.Intent

/**
 * ACTION_PROCESS_TEXT only. Reads [Intent.EXTRA_PROCESS_TEXT] and never returns a replacement.
 */
object ProcessText {
    fun read(intent: Intent): ReceivedText {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        return ReceivedText(
            path = TextPath.Selected,
            action = intent.action,
            mimeType = intent.type,
            text = text,
        )
    }
}
