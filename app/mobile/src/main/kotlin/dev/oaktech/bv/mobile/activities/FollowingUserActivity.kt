package dev.oaktech.bv.mobile.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.oaktech.bv.mobile.screen.FollowingUserScreen
import dev.oaktech.bv.mobile.theme.BVMobileTheme

class FollowingUserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BVMobileTheme {
                FollowingUserScreen()
            }
        }
    }
}