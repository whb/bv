package dev.oaktech.bv.tv.activities.user

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
// import dev.oaktech.bv.screen.user.HistoryScreen
import dev.oaktech.bv.tv.screens.user.ToViewScreen
import dev.oaktech.bv.ui.theme.BVTheme

class ToViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BVTheme {
                // HistoryScreen()
                ToViewScreen()
            }
        }
    }
}
