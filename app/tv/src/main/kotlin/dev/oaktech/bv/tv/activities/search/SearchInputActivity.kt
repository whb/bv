package dev.oaktech.bv.tv.activities.search

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import dev.oaktech.bv.tv.screens.search.SearchInputScreen
import dev.oaktech.bv.ui.theme.BVTheme

class SearchInputActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val defaultFocusRequester = remember { FocusRequester() }
            BVTheme {
                SearchInputScreen(defaultFocusRequester = defaultFocusRequester)
            }
        }
    }
}