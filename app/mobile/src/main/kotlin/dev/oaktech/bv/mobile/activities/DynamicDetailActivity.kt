package dev.oaktech.bv.mobile.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.oaktech.bv.mobile.screen.DynamicDetailScreen
import dev.oaktech.bv.mobile.theme.BVMobileTheme
import io.github.oshai.kotlinlogging.KotlinLogging

class DynamicDetailActivity : ComponentActivity() {
    companion object {
        private val logger = KotlinLogging.logger { }

        fun actionStart(context: Context, dynamicId: String) {
            logger.info { "actionStart: dynamicId=$dynamicId" }
            context.startActivity(
                Intent(context, DynamicDetailActivity::class.java).apply {
                    putExtra("dynamicId", dynamicId)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BVMobileTheme {
                DynamicDetailScreen()
            }
        }
    }
}