package dev.oaktech.biliapi.repositories

import dev.oaktech.biliapi.entity.CarouselData
import dev.oaktech.biliapi.entity.ugc.UgcType
import dev.oaktech.biliapi.entity.ugc.UgcTypeV2
import dev.oaktech.biliapi.entity.ugc.region.UgcFeedData
import dev.oaktech.biliapi.entity.ugc.region.UgcFeedPage
import dev.oaktech.biliapi.entity.ugc.region.UgcRegionData
import dev.oaktech.biliapi.entity.ugc.region.UgcRegionListData
import dev.oaktech.biliapi.http.BiliHttpApi
import org.koin.core.annotation.Single

@Single
class UgcRepository(
    private val authRepository: AuthRepository
) {
    suspend fun getCarousel(ugcType: UgcTypeV2): CarouselData {
        val regionBanner = BiliHttpApi.getRegionBanner(ugcType.tid).getResponseData()
        val carouselData = CarouselData.fromRegionBanner(regionBanner)
        return carouselData
    }

    @Deprecated("User getRegionFeedRcmd instead")
    suspend fun getRegionData(ugcType: UgcType): UgcRegionData {
        val responseData = BiliHttpApi.getRegionDynamic(
            rid = ugcType.rid,
            accessKey = authRepository.accessToken ?: "",
        ).getResponseData()
        val data = UgcRegionData.fromRegionDynamic(responseData)
        return data
    }

    @Deprecated("User getRegionFeedRcmd instead")
    suspend fun getRegionMoreData(ugcType: UgcType): UgcRegionListData {
        val responseData = BiliHttpApi.getRegionDynamicList(
            rid = ugcType.rid,
            accessKey = authRepository.accessToken ?: "",
        ).getResponseData()
        val data = UgcRegionListData.fromRegionDynamicList(responseData)
        return data
    }

    suspend fun getRegionFeedRcmd(ugcType: UgcTypeV2, page: UgcFeedPage): UgcFeedData {
        val response = BiliHttpApi.getRegionFeedRcmd(
            displayId = page.nextPage,
            fromRegion = ugcType.tid,
            sessData = authRepository.sessionData
        )
        // code 62013 = "暂时没有更多内容了"，是「到底 / 被限流」的良性终态，
        // 不应当作异常抛出（getResponseData 的 else 分支会抛 IllegalStateException）。
        // 直接返回空结果，hasNext=false，让上层不弹错误、且仍可经滚动重试。
        if (response.code == 62013 || (response.code == 0 && response.data == null)) {
            return UgcFeedData(
                hasNext = false,
                nextPage = UgcFeedPage(page.nextPage + 1),
                items = emptyList()
            )
        }
        val responseData = response.getResponseData()
        val ugcFeedData = UgcFeedData.fromRegionFeedRcmd(responseData)
        ugcFeedData.nextPage = UgcFeedPage(page.nextPage + 1)
        return ugcFeedData
    }
}
