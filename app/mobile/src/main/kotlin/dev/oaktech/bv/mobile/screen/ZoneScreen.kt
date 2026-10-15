package dev.oaktech.bv.mobile.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import dev.oaktech.biliapi.entity.ugc.UgcTypeV2
import dev.oaktech.bv.entity.carddata.VideoCardData
import dev.oaktech.bv.mobile.activities.VideoPlayerActivity
import dev.oaktech.bv.mobile.component.videocard.SmallVideoCard
import dev.oaktech.bv.util.OnBottomReached
import dev.oaktech.bv.util.fInfo
import dev.oaktech.bv.util.getChildUgcTypes
import dev.oaktech.bv.util.getDisplayName
import dev.oaktech.bv.viewmodel.ugc.ZoneDetailViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.androidx.compose.koinViewModel

/**
 * 移动端主菜单「分区」入口页。
 * 内部维护两级导航：分区网格 -> 单个分区的视频内容页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun ZoneScreen(
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "zone_grid",
        modifier = modifier
    ) {
        composable("zone_grid") {
            ZoneGridScreen(
                onCategoryClick = { ugcType ->
                    navController.navigate("zone_detail/${ugcType.tid}")
                }
            )
        }
        composable(
            route = "zone_detail/{tid}",
            arguments = listOf(navArgument("tid") { type = NavType.IntType })
        ) { backStackEntry ->
            val tid = backStackEntry.arguments?.getInt("tid") ?: 1005
            val ugcType = UgcTypeV2.entries.firstOrNull { it.tid == tid } ?: UgcTypeV2.Douga
            ZoneDetailScreen(
                ugcType = ugcType,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun ZoneGridScreen(
    modifier: Modifier = Modifier,
    onCategoryClick: (UgcTypeV2) -> Unit
) {
    val parentCategories = remember {
        UgcTypeV2.entries.filter { it.channelId != null }
    }
    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(parentCategories) { ugcType ->
            ZoneCategoryItem(
                ugcType = ugcType,
                onClick = { onCategoryClick(ugcType) }
            )
        }
    }
}

@Composable
private fun ZoneCategoryItem(
    ugcType: UgcTypeV2,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ugcType.getDisplayName(context),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
private fun ZoneDetailScreen(
    ugcType: UgcTypeV2,
    onBack: () -> Unit,
    zoneDetailViewModel: ZoneDetailViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val logger = KotlinLogging.logger { }
    val gridState = rememberLazyGridState()

    val childTypes = remember(ugcType) { listOf(ugcType) + ugcType.getChildUgcTypes() }
    var selectedChild by remember(ugcType) { mutableStateOf(ugcType) }

    LaunchedEffect(ugcType) {
        zoneDetailViewModel.setUgcType(ugcType)
        selectedChild = ugcType
    }
    LaunchedEffect(selectedChild) {
        if (selectedChild != ugcType) zoneDetailViewModel.setUgcType(selectedChild)
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = zoneDetailViewModel.loading,
        onRefresh = { zoneDetailViewModel.reloadAll() }
    )

    gridState.OnBottomReached(loading = zoneDetailViewModel.updating) {
        logger.fInfo { "on reached zone detail bottom" }
        zoneDetailViewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = ugcType.getDisplayName(context)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .pullRefresh(pullRefreshState)
        ) {
            when {
                zoneDetailViewModel.loading && !zoneDetailViewModel.loadedOnce -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                zoneDetailViewModel.loadFailed && !zoneDetailViewModel.loadedOnce -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("加载失败")
                        Button(onClick = { zoneDetailViewModel.reloadAll() }) {
                            Text("重试")
                        }
                    }
                }

                zoneDetailViewModel.loadedOnce && zoneDetailViewModel.ugcItems.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "没有视频",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(180.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                lazyRowItems(childTypes) { child ->
                                    FilterChip(
                                        selected = selectedChild == child,
                                        onClick = { selectedChild = child },
                                        label = { Text(child.getDisplayName(context)) }
                                    )
                                }
                            }
                        }

                        if (zoneDetailViewModel.showCarousel && zoneDetailViewModel.carouselItems.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    lazyRowItems(zoneDetailViewModel.carouselItems) { banner ->
                                        AsyncImage(
                                            model = banner.cover,
                                            contentDescription = banner.title,
                                            modifier = Modifier
                                                .height(120.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable(enabled = banner.avid != null) {
                                                    VideoPlayerActivity.actionStart(
                                                        context = context,
                                                        aid = banner.avid ?: 0
                                                    )
                                                },
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }

                        items(zoneDetailViewModel.ugcItems) { video ->
                            SmallVideoCard(
                                data = VideoCardData(
                                    avid = video.aid,
                                    title = video.title,
                                    cover = video.cover,
                                    play = video.play.takeIf { it >= 0 },
                                    danmaku = video.danmaku.takeIf { it >= 0 },
                                    upName = video.author,
                                    time = if (video.duration > 0) video.duration * 1000L else null
                                ),
                                onClick = {
                                    VideoPlayerActivity.actionStart(
                                        context = context,
                                        aid = video.aid
                                    )
                                }
                            )
                        }
                    }
                }
            }

            PullRefreshIndicator(
                refreshing = zoneDetailViewModel.loading,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
