package dev.probe.textselection

import android.content.Intent
import android.os.Bundle
import android.util.Log

object IntentDebugLogger {
    const val TAG = "TextSelectionProbe"

    fun log(intent: Intent?) {
        if (intent == null) {
            Log.d(TAG, "intent=null")
            return
        }
        Log.d(TAG, "action=${intent.action}")
        Log.d(TAG, "mimeType=${intent.type}")
        logIngressText(intent)
        logExtras(intent.extras)
    }

    private fun logIngressText(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                logLong("selectedText", text)
            }
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                logLong("sharedText", text)
            }
            else -> Log.d(TAG, "selectedOrSharedText=none")
        }
    }

    private fun logExtras(extras: Bundle?) {
        if (extras == null) {
            Log.d(TAG, "extras=none")
            return
        }
        val keys = extras.keySet()
        if (keys.isEmpty()) {
            Log.d(TAG, "extras=empty")
            return
        }
        for (key in keys) {
            Log.d(TAG, "extra $key=${extraValue(extras, key)}")
        }
    }

    @Suppress("DEPRECATION") // Untyped read: extras may be any parcelable, not only strings.
    private fun extraValue(extras: Bundle, key: String): String {
        val value = try {
            extras.get(key)
        } catch (error: Throwable) {
            return "unreadable (${error.javaClass.simpleName})"
        }
        if (value == null) {
            return "null"
        }
        return try {
            value.toString()
        } catch (error: Throwable) {
            "${value.javaClass.name} (toString failed: ${error.javaClass.simpleName})"
        }
    }

    private fun logLong(label: String, value: String?) {
        if (value == null) {
            Log.d(TAG, "$label=null")
            return
        }
        if (value.length <= LOG_CHUNK) {
            Log.d(TAG, "$label=$value")
            return
        }
        val chunks = value.chunked(LOG_CHUNK)
        chunks.forEachIndexed { index, chunk ->
            Log.d(TAG, "$label part ${index + 1}/${chunks.size}=$chunk")
        }
    }

    private const val LOG_CHUNK = 3000
}
