package dev.oaktech.bv.viewmodel.user

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oaktech.biliapi.entity.user.SpaceVideo
import dev.oaktech.biliapi.entity.user.SpaceVideoPage
import dev.oaktech.biliapi.repositories.UserRepository
import dev.oaktech.bv.entity.carddata.VideoCardData
import dev.oaktech.bv.util.Prefs
import dev.oaktech.bv.util.addWithMainContext
import dev.oaktech.bv.util.fInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class UserSpaceViewModel(
    private val userRepository: UserRepository
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    var upName by mutableStateOf("")
    var upMid by mutableLongStateOf(0L)
    var tvSpaceVideos = mutableStateListOf<VideoCardData>()
    var spaceVideos = mutableStateListOf<SpaceVideo>()

    /** 是否正在加载（首次加载或翻页） */
    var loading by mutableStateOf(false)
    /** 最近一次加载是否失败（用于展示错误与重试入口） */
    var loadFailed by mutableStateOf(false)
    /** 是否已完成至少一次成功加载（用于区分"加载中空列表"与"确实没有视频"） */
    var loadedOnce by mutableStateOf(false)
        private set

    private var page = SpaceVideoPage()
    private var updating = false
    private var lastLoadedMid = -1L
    val noMore get() = !page.hasNext

    fun update() {
        // 在主线程（调用线程）捕获 upMid，作为参数传入后台协程，
        // 避免在 Dispatchers.Default 协程里跨线程读 Compose State 拿到默认值 0
        val mid = upMid
        viewModelScope.launch(Dispatchers.Default) {
            updateSpaceVideos(mid)
        }
    }

    private suspend fun updateSpaceVideos(mid: Long) {
        // 仅在切换 up 主（mid 变化）时清空旧数据并重置分页，
        // 同一 up 主翻下一页时不清空，保证 TV 端分页加载不受影响
        if (mid != lastLoadedMid) {
            withContext(Dispatchers.Main) {
                spaceVideos.clear()
                tvSpaceVideos.clear()
                loadedOnce = false
                loadFailed = false
            }
            page = SpaceVideoPage()
            lastLoadedMid = mid
        }
        if (updating || noMore) return

        val firstLoad = withContext(Dispatchers.Main) {
            spaceVideos.isEmpty() && tvSpaceVideos.isEmpty()
        }
        logger.fInfo { "Updating up [mid=$mid] space videos from page $page" }
        updating = true
        withContext(Dispatchers.Main) { loading = true }

        var success = false
        var lastError: Throwable? = null
        val maxAttempts = 3
        repeat(maxAttempts) { attempt ->
            if (success) return@repeat
            runCatching {
                userRepository.getSpaceVideos(
                    mid = mid,
                    page = page,
                    preferApiType = Prefs.apiType
                )
            }.onSuccess { spaceVideoData ->
                // 已关注的 UP 首页返回空列表，多为风控/签名抖动而非真的没有投稿，重试后再下结论
                val suspiciousEmptyFirstPage = spaceVideoData.videos.isEmpty() && firstLoad
                if (suspiciousEmptyFirstPage && attempt < maxAttempts - 1) {
                    logger.fInfo { "Empty first page for mid=$mid, retrying (attempt ${attempt + 1}/$maxAttempts)" }
                    delay(500L)
                    return@onSuccess
                }
                page = spaceVideoData.page
                withContext(Dispatchers.Main) {
                    spaceVideos.addAll(spaceVideoData.videos)
                    loadedOnce = true
                    loadFailed = false
                }
                spaceVideoData.videos.forEach { spaceVideoItem ->
                    tvSpaceVideos.addWithMainContext(
                        VideoCardData(
                            avid = spaceVideoItem.aid,
                            title = spaceVideoItem.title,
                            //TODO 这里在改造 app 端接口时，没找到在空间内显示为合集样式封面的UP,没法进一步测试接口
                            cover = spaceVideoItem.cover,
                            upName = spaceVideoItem.author,
                            time = spaceVideoItem.duration * 1000L
                        )
                    )
                }
                logger.fInfo { "Update up space videos success: ${spaceVideoData.videos.size}" }
                success = true
            }.onFailure { error ->
                if (error is CancellationException) throw error
                lastError = error
                logger.fInfo { "Update up space videos failed (attempt ${attempt + 1}/$maxAttempts): ${error.message}" }
                if (attempt < maxAttempts - 1) delay(400L * (attempt + 1))
            }
        }

        updating = false
        withContext(Dispatchers.Main) {
            loading = false
            loadFailed = !success
        }
        if (!success) {
            logger.fInfo { "Update up space videos final failure for mid=$mid: ${lastError?.stackTraceToString()}" }
        }
    }
}
