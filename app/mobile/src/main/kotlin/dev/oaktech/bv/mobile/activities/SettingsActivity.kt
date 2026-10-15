package dev.oaktech.bv.mobile.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.oaktech.bv.mobile.screen.settings.SettingsScreen
import dev.oaktech.bv.mobile.theme.BVMobileTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BVMobileTheme {
                SettingsScreen()
            }
        }
    }
}
