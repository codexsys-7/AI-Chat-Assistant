package dev.probe.textselection

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.probe.textselection.ai.AppServices
import dev.probe.textselection.ai.PendingRequests
import kotlinx.coroutines.delay

class ResponseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getStringExtra(REQUEST_ID).orEmpty()
        val request = PendingRequests.find(requestId)
        var phase by mutableStateOf<ResponsePhase>(ResponsePhase.Preparing)
        setContent {
            LaunchedEffect(requestId) {
                // The mock provider returns without suspending, so this hold is what
                // lets the preparing line paint and stay readable. Generation still
                // runs on this coroutine.
                delay(PREPARING_VISIBLE_MS)
                phase = if (request == null) {
                    ResponsePhase.Failed(message = "Could not prepare a response.")
                } else {
                    ResponsePhase.Ready(AppServices.ai.generate(request))
                }
            }
            ResponseScreen(
                phase = phase,
                onOpenRequestDebug = request?.let { held ->
                    { startActivity(RequestFields.from(held).toIntent(this)) }
                },
            )
        }
    }

    companion object {
        private const val PREPARING_VISIBLE_MS = 500L
        private const val REQUEST_ID = "dev.probe.textselection.response.requestId"

        fun intent(context: Context, requestId: String): Intent {
            return Intent(context, ResponseActivity::class.java)
                .putExtra(REQUEST_ID, requestId)
        }
    }
}
