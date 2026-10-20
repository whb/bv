package dev.oaktech.bv.viewmodel.pgc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oaktech.biliapi.entity.CarouselData
import dev.oaktech.biliapi.entity.pgc.PgcFeedData
import dev.oaktech.biliapi.entity.pgc.PgcItem
import dev.oaktech.biliapi.entity.pgc.PgcType
import dev.oaktech.biliapi.repositories.PgcRepository
import dev.oaktech.bv.BVApp
import dev.oaktech.bv.util.addWithMainContext
import dev.oaktech.bv.util.addAllWithMainContext
import dev.oaktech.bv.util.fInfo
import dev.oaktech.bv.util.fWarn
import dev.oaktech.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class PgcViewModel(
    val pgcRepository: PgcRepository,
    val pgcType: PgcType,
) : ViewModel() {
    private val logger = KotlinLogging.logger("PgcViewModel[$pgcType]")
    // 专用错误 tag：初次进入 PGC 时界面弹出的报错信息同步写入 logcat，
    // 便于用 `adb logcat -s BV_PGC` 抓取（tag 控制在 23 字符内，专用于错误类日志）。
    private val errorLogger = KotlinLogging.logger("BV_PGC")

    /**
     * 轮播图
     */
    val carouselItems = mutableStateListOf<CarouselData.CarouselItem>()

    /**
     * 猜你喜欢
     */
    val feedItems = mutableStateListOf<FeedListItem>()
    val cardNumberOfLine = 6

    /**
     * 推荐数据中会穿插排行榜，为了避免出现某一行仅出现单独几个剧集，因此将不满一行的剧集单独存起来
     */
    private val restSubItems = mutableListOf<PgcItem>()

    var updating by mutableStateOf(false)
    var hasNext by mutableStateOf(true)
    private var cursor = 0

    init {
        loadMore()
        viewModelScope.launch(Dispatchers.IO) {
            updateCarousel()
        }
    }

    /**
     * 加载更多推荐数据
     */
    fun loadMore() {
        if (hasNext) {
            viewModelScope.launch(Dispatchers.IO) {
                updateFeed()
            }
        }
    }

    /**
     * 重新加载所有数据，点击界面顶部 Tab 时使用
     */
    fun reloadAll() {
        logger.fInfo { "Reload all $pgcType data" }
        clearAll()
        viewModelScope.launch(Dispatchers.IO) {
            updateCarousel()
            updateFeed()
        }
    }

    /**
     * 清理所有数据
     */
    fun clearAll() {
        logger.fInfo { "Clear all data" }
        carouselItems.clear()
        feedItems.clear()
        restSubItems.clear()
        cursor = 0
        hasNext = true
    }

    /**
     * 更新轮播图
     */
    private suspend fun updateCarousel() {
        logger.fInfo { "Updating $pgcType carousel" }
        runCatching {
            // 历史遗留的「重试 10 次等 pgcRepository 注入」hack 已移除：
            // 根因是 PgcViewModel 曾把 pgcRepository 声明为 open val、子类用 override val 重写，
            // 父类 init 里的协程会在子类字段赋值前读到 null。改为普通 val 后构造期即已赋值，无需重试。
            val carouselData = pgcRepository.getCarousel(pgcType)
            logger.fInfo { "Find $pgcType carousels, size: ${carouselData.items.size}" }
            carouselItems.addAllWithMainContext(carouselData.items)
            logger.debug { "carouselItems: $carouselItems" }
        }.onFailure {
            logger.fInfo { "Update $pgcType carousel failed: ${it.stackTraceToString()}" }
            val msg = "加载 $pgcType 轮播图失败: ${it.message}"
            errorLogger.error(it) { msg }
            withContext(Dispatchers.Main) {
                msg.toast(BVApp.context)
            }
        }
    }

    /**
     * 获取推荐数据
     */
    private suspend fun updateFeed() {
        if (updating) return
        withContext(Dispatchers.Main) { updating = true }
        logger.fInfo { "Update anime feed" }
        runCatching {
            val pgcFeedData = pgcRepository.getFeed(
                pgcType = pgcType,
                cursor = cursor
            )
            cursor = pgcFeedData.cursor
            withContext(Dispatchers.Main) { hasNext = pgcFeedData.hasNext }
            updateFeedItems(pgcFeedData)
        }.onFailure {
            logger.fWarn { "Update $pgcType feeds failed: ${it.stackTraceToString()}" }
            val msg = "加载 $pgcType 推荐数据失败: ${it.message}"
            errorLogger.error(it) { msg }
            withContext(Dispatchers.Main) {
                msg.toast(BVApp.context)
            }
        }
        withContext(Dispatchers.Main) { updating = false }
    }

    /**
     * 对 [updateFeed] 获取到得数据进行二次整理并更新到 feedItems
     */
    private suspend fun updateFeedItems(data: PgcFeedData) {
        logger.fInfo { "update $pgcType feed items: [items: ${data.items.size}, ranks: ${data.ranks.size}]" }
        val epList = mutableStateListOf<PgcItem>()
        epList.addAll(restSubItems)
        epList.addAll(data.items)

        epList.chunked(cardNumberOfLine).forEach { chunkedVCardList ->
            if (chunkedVCardList.size == cardNumberOfLine) {
                feedItems.addWithMainContext(
                    FeedListItem(
                        type = FeedListType.Ep,
                        items = chunkedVCardList
                    )
                )
            } else {
                restSubItems.clear()
                restSubItems.addAll(chunkedVCardList)
            }
        }

        data.ranks.forEach { rank ->
            feedItems.addWithMainContext(
                FeedListItem(
                    type = FeedListType.Rank,
                    rank = rank
                )
            )
        }
    }
}

data class FeedListItem(
    val type: FeedListType,
    val items: List<PgcItem>? = emptyList(),
    val rank: PgcFeedData.FeedRank? = null
)

enum class FeedListType {
    Ep, Rank
}
