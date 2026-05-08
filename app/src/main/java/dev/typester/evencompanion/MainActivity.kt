package dev.typester.evencompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.typester.evencompanion.ui.MainScreen
import dev.typester.evencompanion.ui.theme.EvenCompanionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EvenCompanionTheme {
                MainScreen()
            }
        }
    }
}
