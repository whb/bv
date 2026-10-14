package dev.aaa1115910.bv.mobile.activities

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.aaa1115910.bv.mobile.screen.MobileMainScreen
import dev.aaa1115910.bv.mobile.theme.BVMobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (isTvDevice()) {
            startActivity(
                Intent().setComponent(
                    ComponentName(packageName, "dev.aaa1115910.bv.tv.activities.MainActivity")
                )
            )
            finish()
            return
        }

        setContent {
            BVMobileTheme {
                MobileMainScreen()
            }
        }
    }

    private fun isTvDevice(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
