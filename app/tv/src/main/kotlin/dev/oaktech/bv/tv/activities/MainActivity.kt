package dev.oaktech.bv.tv.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.oaktech.bv.tv.screens.MainScreen
import dev.oaktech.bv.ui.theme.BVTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            BVTheme {
                MainScreen()
            }
        }
    }
}
