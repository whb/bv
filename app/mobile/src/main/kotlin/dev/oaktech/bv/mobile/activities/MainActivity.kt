package dev.oaktech.bv.mobile.activities

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.oaktech.bv.mobile.screen.MobileMainScreen
import dev.oaktech.bv.mobile.theme.BVMobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (isTvDevice()) {
            startActivity(
                Intent().setComponent(
                    ComponentName(packageName, "dev.oaktech.bv.tv.activities.MainActivity")
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
