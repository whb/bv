package dev.oaktech.bv.viewmodel.index

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.oaktech.biliapi.entity.pgc.PgcItem
import dev.oaktech.biliapi.entity.pgc.PgcType
import dev.oaktech.biliapi.entity.pgc.index.Area
import dev.oaktech.biliapi.entity.pgc.index.Copyright
import dev.oaktech.biliapi.entity.pgc.index.IndexOrder
import dev.oaktech.biliapi.entity.pgc.index.IndexOrderType
import dev.oaktech.biliapi.entity.pgc.index.IsFinish
import dev.oaktech.biliapi.entity.pgc.index.PgcIndexData
import dev.oaktech.biliapi.entity.pgc.index.Producer
import dev.oaktech.biliapi.entity.pgc.index.ReleaseDate
import dev.oaktech.biliapi.entity.pgc.index.SeasonMonth
import dev.oaktech.biliapi.entity.pgc.index.SeasonStatus
import dev.oaktech.biliapi.entity.pgc.index.SeasonVersion
import dev.oaktech.biliapi.entity.pgc.index.SpokenLanguage
import dev.oaktech.biliapi.entity.pgc.index.Style
import dev.oaktech.biliapi.entity.pgc.index.Year
import dev.oaktech.biliapi.repositories.PgcRepository
import dev.oaktech.bv.BVApp
import dev.oaktech.bv.util.addAllWithMainContext
import dev.oaktech.bv.util.fError
import dev.oaktech.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class PgcIndexViewModel(
    private val pgcRepository: PgcRepository,
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger { }
        // 专用错误 tag：与 PgcViewModel 保持一致，便于 `adb logcat -s BV_PGC` 统一抓取。
        private val errorLogger = KotlinLogging.logger("BV_PGC")
    }

    val indexResultItems = mutableStateListOf<PgcItem>()

    private var updating = false
    private var nextPage = PgcIndexData.PgcIndexPage()
    val noMore get() = nextPage.hasNext.not()

    var pgcType by mutableStateOf(PgcType.Anime)

    var indexOrder by mutableStateOf(IndexOrder.FollowCount)
    var indexOrderType by mutableStateOf(IndexOrderType.Desc)
    var seasonVersion by mutableStateOf(SeasonVersion.All)
    var spokenLanguage by mutableStateOf(SpokenLanguage.All)
    var area by mutableStateOf(Area.All)
    var isFinish by mutableStateOf(IsFinish.All)
    var copyright by mutableStateOf(Copyright.All)
    var seasonStatus by mutableStateOf(SeasonStatus.All)
    var seasonMonth by mutableStateOf(SeasonMonth.All)
    var producer by mutableStateOf(Producer.All)
    var year by mutableStateOf(Year.All)
    var releaseDate by mutableStateOf(ReleaseDate.All)
    var style by mutableStateOf(Style.All)

    fun changePgcType(pgcType: PgcType) {
        this.pgcType = pgcType
        indexOrder = IndexOrder.getList(pgcType).first()
    }

    suspend fun loadMore() {
        if (!updating) loadData()
    }

    private suspend fun loadData() {
        updating = true
        if (!nextPage.hasNext) {
            updating = false
            return
        }
        runCatching {
            val result = pgcRepository.getPgcIndex(
                pgcType = pgcType,
                indexOrder = indexOrder,
                indexOrderType = indexOrderType,
                seasonVersion = seasonVersion,
                spokenLanguage = spokenLanguage,
                area = area,
                isFinish = isFinish,
                copyright = copyright,
                seasonStatus = seasonStatus,
                seasonMonth = seasonMonth,
                producer = producer,
                year = year,
                releaseDate = releaseDate,
                style = style,
                page = nextPage
            )
            indexResultItems.addAllWithMainContext(result.list)
            nextPage = result.nextPage
            logger.info { "load more $pgcType list success, size: ${result.list.size}" }
        }.onFailure {
            logger.fError { "Load $pgcType index list failed: ${it.stackTraceToString()}" }
            val msg = "加载 $pgcType 索引失败: ${it.localizedMessage}"
            errorLogger.error(it) { msg }
            withContext(Dispatchers.Main) {
                msg.toast(BVApp.context)
            }
        }
        updating = false
    }

    fun clearData() {
        indexResultItems.clear()
        nextPage = PgcIndexData.PgcIndexPage()
        updating = false
    }
}