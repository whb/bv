package dev.oaktech.bv.tv.screens.main.ugc

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.oaktech.biliapi.entity.ugc.UgcTypeV2
import dev.oaktech.bv.viewmodel.ugc.UgcLifeExperienceViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun LifeExperienceContent(
    modifier: Modifier = Modifier,
    lazyListState: LazyListState,
    ugcViewModel: UgcLifeExperienceViewModel = koinViewModel()
) {
    UgcRegionScaffold(
        modifier = modifier,
        lazyListState = lazyListState,
        ugcViewModel = ugcViewModel,
        childRegionButtons = { LifeExperienceChildRegionButtons() }
    )
}

@Composable
fun LifeExperienceChildRegionButtons(modifier: Modifier = Modifier) {
    UgcChildRegionButtons(
        modifier = modifier.fillMaxWidth(),
        childUgcTypes = UgcTypeV2.lifeExperienceList
    )
}