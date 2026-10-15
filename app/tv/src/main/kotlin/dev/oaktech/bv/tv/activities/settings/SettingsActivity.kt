package dev.oaktech.bv.tv.activities.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.oaktech.bv.tv.screens.settings.SettingsScreen
import dev.oaktech.bv.ui.theme.BVTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BVTheme {
                SettingsScreen()
            }
        }
    }
}
