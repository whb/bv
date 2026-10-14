package dev.aaa1115910.bv.mobile.screen.settings.details

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aaa1115910.bv.entity.ThemeType
import dev.aaa1115910.bv.mobile.component.preferences.items.radioPreference
import dev.aaa1115910.bv.mobile.component.preferences.preferenceGroups
import dev.aaa1115910.bv.util.PrefKeys

@Composable
fun AppearanceContent(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp)
    ) {
        preferenceGroups(
            "主题" to {
                radioPreference(
                    title = "主题样式",
                    prefReq = PrefKeys.prefThemeTypeRequest,
                    values = ThemeType.entries.associate { it.ordinal to it.getDisplayName(context) }
                        .toSortedMap()
                )
            }
        )
    }
}
