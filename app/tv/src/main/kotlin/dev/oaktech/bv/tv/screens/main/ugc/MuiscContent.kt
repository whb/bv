package dev.oaktech.bv.tv.screens.main.ugc

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.oaktech.biliapi.entity.ugc.UgcTypeV2
import dev.oaktech.bv.viewmodel.ugc.UgcMusicViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun MusicContent(
    modifier: Modifier = Modifier,
    lazyListState: LazyListState,
    ugcViewModel: UgcMusicViewModel = koinViewModel()
) {
    UgcRegionScaffold(
        modifier = modifier,
        lazyListState = lazyListState,
        ugcViewModel = ugcViewModel,
        childRegionButtons = { MusicChildRegionButtons() }
    )
}

@Composable
fun MusicChildRegionButtons(modifier: Modifier = Modifier) {
    UgcChildRegionButtons(
        modifier = modifier.fillMaxWidth(),
        childUgcTypes = UgcTypeV2.musicList
    )
}