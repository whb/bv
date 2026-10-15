package dev.oaktech.bv.tv.activities.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.oaktech.bv.tv.screens.settings.LogsScreen
import dev.oaktech.bv.ui.theme.BVTheme

class LogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BVTheme {
                LogsScreen()
            }
        }
    }
}