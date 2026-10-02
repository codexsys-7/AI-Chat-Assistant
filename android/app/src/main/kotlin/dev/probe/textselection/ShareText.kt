package dev.probe.textselection

import android.content.Intent

/**
 * ACTION_SEND only. Reads [Intent.EXTRA_TEXT].
 */
object ShareText {
    fun read(intent: Intent): ReceivedText {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        return ReceivedText(
            path = TextPath.Shared,
            action = intent.action,
            mimeType = intent.type,
            text = text,
        )
    }
}
