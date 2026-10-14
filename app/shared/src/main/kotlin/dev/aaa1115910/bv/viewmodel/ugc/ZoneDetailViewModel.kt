package dev.aaa1115910.bv.viewmodel.ugc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.CarouselData
import dev.aaa1115910.biliapi.entity.ugc.UgcItem
import dev.aaa1115910.biliapi.entity.ugc.UgcTypeV2
import dev.aaa1115910.biliapi.entity.ugc.region.UgcFeedPage
import dev.aaa1115910.biliapi.repositories.UgcRepository
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.getDisplayName
import dev.aaa1115910.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

/**
 * 移动端「分区」内容页使用的 ViewModel。
 * 与 TV 端每个分区一个 ViewModel 子类不同，这里只用单个 ViewModel，
 * 通过可变的 [currentUgcType] 切换分区，切换时自动重新拉取数据。
 */
@KoinViewModel
class ZoneDetailViewModel(
    private val ugcRepository: UgcRepository
) : ViewModel() {
    private val logger = KotlinLogging.logger("ZoneDetailViewModel")

    val carouselItems = mutableStateListOf<CarouselData.CarouselItem>()
    val ugcItems = mutableStateListOf<UgcItem>()

    var nextPage by mutableStateOf(UgcFeedPage())
    var hasMore by mutableStateOf(true)
    var updating by mutableStateOf(false)
    var showCarousel by mutableStateOf(true)

    // UI 状态
    var loading by mutableStateOf(false)
    var loadFailed by mutableStateOf(false)
    var loadedOnce by mutableStateOf(false)

    var currentUgcType by mutableStateOf<UgcTypeV2?>(null)

    fun setUgcType(ugcType: UgcTypeV2) {
        if (currentUgcType == ugcType) return
        currentUgcType = ugcType
        reloadAll()
    }

    fun reloadAll() {
        val ugcType = currentUgcType ?: return
        logger.fInfo { "reload all ${ugcType.name} data" }
        viewModelScope.launch(Dispatchers.IO) {
            nextPage = UgcFeedPage()
            hasMore = true
            showCarousel = true
            loading = true
            loadFailed = false
            carouselItems.clear()
            ugcItems.clear()
            runCatching { loadUgcRegionData() }
            loading = false
            loadedOnce = true
        }
    }

    private suspend fun loadUgcRegionData() {
        val ugcType = currentUgcType ?: return
        if (!hasMore && updating) return
        updating = true
        logger.fInfo { "load ugc $ugcType region data" }
        runCatching {
            val carouselData = runCatching { ugcRepository.getCarousel(ugcType) }
                .getOrDefault(CarouselData(emptyList()))
            val data = ugcRepository.getRegionFeedRcmd(ugcType, nextPage)
            carouselItems.clear()
            ugcItems.clear()
            carouselItems.addAll(carouselData.items)
            ugcItems.addAll(data.items)
            nextPage = data.nextPage
            showCarousel = carouselItems.isNotEmpty()
        }.onFailure {
            logger.fInfo { "load $ugcType data failed: ${it.stackTraceToString()}" }
            loadFailed = true
            withContext(Dispatchers.Main) {
                "加载 ${ugcType.getDisplayName(BVApp.context)} 数据失败: ${it.message}".toast(BVApp.context)
            }
        }
        hasMore = true
        updating = false
    }

    fun loadMore() {
        val ugcType = currentUgcType ?: return
        if (!hasMore && updating) return
        viewModelScope.launch(Dispatchers.IO) {
            updating = true
            runCatching {
                val data = ugcRepository.getRegionFeedRcmd(ugcType, nextPage)
                ugcItems.addAll(data.items)
                nextPage = data.nextPage
                hasMore = data.items.isNotEmpty()
            }.onFailure {
                logger.fInfo { "load more $ugcType data failed: ${it.stackTraceToString()}" }
                withContext(Dispatchers.Main) {
                    "加载 ${ugcType.getDisplayName(BVApp.context)} 更多推荐失败: ${it.message}".toast(BVApp.context)
                }
            }
            updating = false
        }
    }
}
