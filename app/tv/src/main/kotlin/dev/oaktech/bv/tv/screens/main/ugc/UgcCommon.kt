package dev.oaktech.bv.tv.screens.main.ugc

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.oaktech.biliapi.entity.CarouselData
import dev.oaktech.biliapi.entity.ugc.UgcItem
import dev.oaktech.biliapi.entity.ugc.UgcTypeV2
import dev.oaktech.biliapi.entity.ugc.region.UgcFeedPage
import dev.oaktech.biliapi.repositories.UgcRepository
import dev.oaktech.bv.entity.carddata.VideoCardData
import dev.oaktech.bv.tv.activities.video.VideoInfoActivity
import dev.oaktech.bv.tv.component.UgcCarousel
import dev.oaktech.bv.tv.component.videocard.SmallVideoCard
import dev.oaktech.bv.tv.screens.main.ContentColumnWidth
import dev.oaktech.bv.util.fInfo
import dev.oaktech.bv.util.fWarn
import dev.oaktech.bv.util.toast
import dev.oaktech.bv.viewmodel.ugc.UgcViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger("UgcCommon")

private fun openCarouselLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }.onFailure {
        logger.fWarn { "open carousel link failed: $url, ${it.message}" }
    }
}

@Composable
fun UgcRegionScaffold(
    modifier: Modifier = Modifier,
    lazyListState: LazyListState,
    ugcViewModel: UgcViewModel,
    childRegionButtons: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    var currentFocusedIndex by remember { mutableIntStateOf(0) }
    val shouldLoadMore by remember {
        derivedStateOf { currentFocusedIndex + 24 > ugcViewModel.ugcItems.size }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            ugcViewModel.loadMore()
            currentFocusedIndex = -100
        }
    }

    LazyColumn(
        modifier = modifier,
        state = lazyListState
    ) {
        if (ugcViewModel.showCarousel) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.Center
                ) {
                    UgcCarousel(
                        modifier = Modifier
                            .width(ContentColumnWidth)
                            .padding(32.dp, 0.dp),
                        data = ugcViewModel.carouselItems,
                        onClick = { item ->
                            val avid = item.avid
                            val url = item.url
                            when {
                                avid != null ->
                                    VideoInfoActivity.actionStart(context = context, aid = avid)
                                !url.isNullOrBlank() ->
                                    openCarouselLink(context, url)
                            }
                        }
                    )
                }
            }
        }

        if (childRegionButtons != null) {
            item {
                childRegionButtons()
            }
        } else {
            item {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                )
            }
        }

        gridItems(
            data = ugcViewModel.ugcItems,
            columnCount = 4,
            modifier = Modifier
                .width(ContentColumnWidth)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            itemContent = { index, item ->
                SmallVideoCard(
                    data = VideoCardData(
                        avid = item.aid,
                        title = item.title,
                        cover = item.cover,
                        play = item.play,
                        danmaku = item.danmaku,
                        upName = item.author,
                        time = item.duration * 1000L
                    ),
                    onClick = { VideoInfoActivity.actionStart(context, item.aid) },
                    onFocus = { currentFocusedIndex = index }
                )
            }
        )
    }
}

fun <T> LazyListScope.gridItems(
    data: List<T>,
    key: ((index: Int) -> Any)? = null,
    columnCount: Int,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    itemContent: @Composable BoxScope.(Int, T) -> Unit,
) {
    val size = data.count()
    val rows = if (size == 0) 0 else 1 + (size - 1) / columnCount
    items(rows, key = key) { rowIndex ->
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            item {
                Row(
                    verticalAlignment = verticalAlignment,
                    horizontalArrangement = horizontalArrangement,
                    modifier = modifier
                ) {
                    for (columnIndex in 0 until columnCount) {
                        val itemIndex = rowIndex * columnCount + columnIndex
                        if (itemIndex < size) {
                            Box(
                                modifier = Modifier.weight(1F, fill = true),
                                propagateMinConstraints = true
                            ) {
                                itemContent(itemIndex, data[itemIndex])
                            }
                        } else {
                            Spacer(Modifier.weight(1F, fill = true))
                        }
                    }
                }
            }
        }
    }
}

data class UgcScaffoldState(
    val context: Context,
    val scope: CoroutineScope,
    val lazyListState: LazyListState,
    val ugcType: UgcTypeV2,
    private val ugcRepository: UgcRepository
) {
    companion object {
        val logger = KotlinLogging.logger { }
    }

    val carouselItems = mutableStateListOf<CarouselData.CarouselItem>()
    val ugcItems = mutableStateListOf<UgcItem>()
    var nextPage by mutableStateOf(UgcFeedPage())
    var hasMore by mutableStateOf(true)
    var updating by mutableStateOf(false)
    var showCarousel by mutableStateOf(true)

    suspend fun initUgcRegionData() {
        // 首屏只加载第一页；翻页由 UI 滚动到底时按需触发（loadMore），
        // 避免 eagerly 预拉第二页触发 B 站推荐流 code 62013（到底/限流）被误报为失败。
        loadUgcRegionData()
    }

    suspend fun loadUgcRegionData() {
        if (!hasMore && updating) return
        updating = true
        logger.fInfo { "load ugc $ugcType region data" }
        // 先加载 feed（核心数据），轮播失败不应连坐 feed
        runCatching {
            val data = ugcRepository.getRegionFeedRcmd(ugcType, nextPage)
            ugcItems.clear()
            ugcItems.addAll(data.items)
            nextPage = data.nextPage
        }.onFailure {
            logger.fWarn { "load $ugcType data failed: ${it.stackTraceToString()}" }
            withContext(Dispatchers.Main) {
                "加载 $ugcType 数据失败: ${it.message}".toast(context)
            }
        }
        // 轮播独立加载，失败仅记日志
        carouselItems.clear()
        runCatching {
            val carouselData = ugcRepository.getCarousel(ugcType)
            carouselItems.addAll(carouselData.items)
        }.onFailure {
            logger.fWarn { "load $ugcType carousel failed: ${it.stackTraceToString()}" }
        }
        showCarousel = carouselItems.isNotEmpty()
        hasMore = true
        updating = false
    }

    fun reloadAll() {
        logger.fInfo { "reload all $ugcType data" }
        scope.launch(Dispatchers.IO) {
            nextPage = UgcFeedPage()
            hasMore = true
            showCarousel = true
            carouselItems.clear()
            ugcItems.clear()
            initUgcRegionData()
        }
    }

    suspend fun loadMore() {
        if (!hasMore && updating) return
        updating = true
        runCatching {
            val data = ugcRepository.getRegionFeedRcmd(ugcType, nextPage)
            ugcItems.addAll(data.items)
            nextPage = data.nextPage
            hasMore = data.items.isNotEmpty()
        }.onFailure {
            logger.fWarn { "load more $ugcType data failed: ${it.stackTraceToString()}" }
            withContext(Dispatchers.Main) {
                "加载 $ugcType 更多推荐失败: ${it.message}".toast(context)
            }
        }
        updating = false
    }
}
