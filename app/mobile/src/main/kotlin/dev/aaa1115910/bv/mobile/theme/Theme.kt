package dev.aaa1115910.bv.mobile.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import dev.aaa1115910.bv.entity.ThemeType
import dev.aaa1115910.bv.util.Prefs

@Composable
fun BVMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val window by lazy { (context as Activity).window }
    val view = LocalView.current
    val systemUiController = rememberSystemUiController()

    val themeType = if (view.isInEditMode) ThemeType.Auto
    else Prefs.themeTypeFlow.collectAsState(Prefs.themeType).value
    val useDark = when (themeType) {
        ThemeType.Auto -> darkTheme
        ThemeType.Dark -> true
        ThemeType.Light -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        useDark -> darkColorScheme()
        else -> lightColorScheme()
    }

    if (!view.isInEditMode) {
        val currentWindow = (view.context as Activity).window
        SideEffect {
            (view.context as Activity).window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(currentWindow, view)
                .isAppearanceLightStatusBars = !useDark
        }
    }

    SideEffect {
        systemUiController.setStatusBarColor(color = Color.Transparent)
        systemUiController.setNavigationBarColor(color = Color.Transparent)
        if (!view.isInEditMode) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
    ) {
        content()
    }
}
