package dev.oaktech.bv.tv.screens.main.ugc

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.oaktech.biliapi.entity.ugc.UgcTypeV2
import dev.oaktech.bv.viewmodel.ugc.UgcDougaViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun DougaContent(
    modifier: Modifier = Modifier,
    lazyListState: LazyListState,
    ugcViewModel: UgcDougaViewModel = koinViewModel()
) {
    UgcRegionScaffold(
        modifier = modifier,
        lazyListState = lazyListState,
        ugcViewModel = ugcViewModel,
        childRegionButtons = { DougaChildRegionButtons() }
    )
}

@Composable
fun DougaChildRegionButtons(modifier: Modifier = Modifier) {
    UgcChildRegionButtons(
        modifier = modifier.fillMaxWidth(),
        childUgcTypes = UgcTypeV2.dougaList
    )
}