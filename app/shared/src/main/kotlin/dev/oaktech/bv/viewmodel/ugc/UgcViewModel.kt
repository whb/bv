package dev.oaktech.bv.viewmodel.ugc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oaktech.biliapi.entity.CarouselData
import dev.oaktech.biliapi.entity.ugc.UgcItem
import dev.oaktech.biliapi.entity.ugc.UgcTypeV2
import dev.oaktech.biliapi.entity.ugc.region.UgcFeedPage
import dev.oaktech.biliapi.repositories.UgcRepository
import dev.oaktech.bv.BVApp
import dev.oaktech.bv.util.fInfo
import dev.oaktech.bv.util.fWarn
import dev.oaktech.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class UgcViewModel(
    val ugcRepository: UgcRepository,
    val ugcType: UgcTypeV2
) : ViewModel() {
    private val logger = KotlinLogging.logger("UgcViewModel[$ugcType]")
    // 专用错误 tag：初次进入 UGC 时界面弹出的报错信息同步写入 logcat，
    // 便于用 `adb logcat -s BV_UGC` 抓取（tag 控制在 23 字符内，专用于错误类日志）。
    private val errorLogger = KotlinLogging.logger("BV_UGC")

    /**
     * 轮播图
     */
    val carouselItems = mutableStateListOf<CarouselData.CarouselItem>()

    /**
     * UGC数据列表
     */
    val ugcItems = mutableStateListOf<UgcItem>()

    var nextPage by mutableStateOf(UgcFeedPage())
    var hasMore by mutableStateOf(true)
    var updating by mutableStateOf(false)
    var showCarousel by mutableStateOf(true)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            initUgcRegionData()
        }
    }

    private suspend fun initUgcRegionData() {
        // 首屏只加载第一页；翻页改为用户滚动到底时按需触发（loadMore）。
        // 之前这里会紧接着 eagerly 调用 loadMore() 预拉第二页，而 region/feed/rcmd 的
        // 第二页在 B 站推荐流中很容易返回 code 62013（到底/限流），被当作异常弹出
        // “更多推荐失败”，并且会提前把 hasMore 置为 false 导致无法继续翻页。
        loadUgcRegionData()
    }

    suspend fun loadUgcRegionData() {
        if (!hasMore && updating) return
        updating = true
        logger.fInfo { "load ugc $ugcType region data" }
        // 先加载 feed（核心数据）。轮播与 feed 解耦：轮播失败不应连坐整个 feed。
        runCatching {
            val data = ugcRepository.getRegionFeedRcmd(ugcType, nextPage)
            ugcItems.clear()
            ugcItems.addAll(data.items)
            nextPage = data.nextPage
        }.onFailure {
            val msg = "加载 $ugcType 数据失败: ${it.message}"
            errorLogger.error(it) { msg }
            logger.fWarn { "load $ugcType data failed" }
            withContext(Dispatchers.Main) {
                msg.toast(BVApp.context)
            }
        }
        // 轮播图独立加载，失败仅记日志，不影响 feed 展示
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
        viewModelScope.launch(Dispatchers.IO) {
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
            val msg = "加载 $ugcType 更多推荐失败: ${it.message}"
            errorLogger.error(it) { msg }
            logger.fWarn { "load more $ugcType data failed" }
            withContext(Dispatchers.Main) {
                msg.toast(BVApp.context)
            }
        }
        updating = false
    }

}
