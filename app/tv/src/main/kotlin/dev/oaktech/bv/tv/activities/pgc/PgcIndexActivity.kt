package dev.oaktech.bv.tv.activities.pgc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.oaktech.biliapi.entity.pgc.PgcType
import dev.oaktech.bv.tv.screens.main.pgc.PgcIndexScreen
import dev.oaktech.bv.ui.theme.BVTheme

class PgcIndexActivity : ComponentActivity() {
    companion object {
        fun actionStart(
            context: Context,
            pgcType: PgcType
        ) {
            context.startActivity(
                Intent(context, PgcIndexActivity::class.java).apply {
                    putExtra("pgcType", pgcType.ordinal)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BVTheme {
                PgcIndexScreen()
            }
        }
    }
}