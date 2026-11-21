package io.github.abhaypanwar00.knockspot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.abhaypanwar00.knockspot.guard
import io.github.abhaypanwar00.knockspot.service.GuardService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KnockspotTheme {
                MainScreen(guard)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val g = guard
        g.refreshShizuku()
        g.scope.launch {
            g.sync()
            // Opening the app is a reliable moment to (re)start the service if Android refused earlier.
            if (g.currentState().protectionOn) GuardService.start(applicationContext)
        }
    }
}
