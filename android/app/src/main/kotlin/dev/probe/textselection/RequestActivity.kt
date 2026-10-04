package dev.probe.textselection

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class RequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fields = RequestFields.from(intent)
        setContent {
            RequestScreen(fields)
        }
    }
}
