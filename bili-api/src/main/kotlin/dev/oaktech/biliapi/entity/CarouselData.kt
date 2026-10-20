package dev.oaktech.biliapi.entity

import dev.oaktech.biliapi.util.UrlUtil
import dev.oaktech.biliapi.util.toBv
import io.ktor.http.Url

data class CarouselData(
    val items: List<CarouselItem>
) {
    companion object {
        fun fromPgcWebInitialStateData(data: dev.oaktech.biliapi.http.entity.pgc.PgcWebInitialStateData): CarouselData {
            val result = mutableListOf<CarouselItem>()
            var needParseIdFromUrl = false
            // 电影、电视剧、综艺板块里的轮播图数据里没有直接包含 episodeId 和 seasonId
            if (listOf(1668, 1675, 1682).contains(data.modules.banner.moduleId))
                needParseIdFromUrl = true
            data.modules.banner.items.filter {
                it.episodeId != null
                        || (needParseIdFromUrl && it.link.contains("bangumi/play/ep"))
                        || (needParseIdFromUrl && it.link.contains("bangumi/play/ss"))
            }.forEach {
                var cover = it.bigCover ?: it.cover
                if (cover.startsWith("//")) cover = "https:$cover"
                var epidFromUrl: Int? = null
                var ssidFromUrl: Int? = null

                if (needParseIdFromUrl) {
                    val idStr = Url(it.link).rawSegments.last()
                    epidFromUrl =
                        idStr.substring(2).takeIf { idStr.startsWith("ep") }?.toIntOrNull()
                    ssidFromUrl =
                        idStr.substring(2).takeIf { idStr.startsWith("ss") }?.toIntOrNull()
                }

                result.add(
                    CarouselItem(
                        cover = cover,
                        title = it.title,
                        seasonId = it.seasonId ?: ssidFromUrl ?: -1,
                        episodeId = it.episodeId ?: epidFromUrl ?: -1
                    )
                )
            }
            return CarouselData(result)
        }

        fun fromUgcRegionDynamicBanner(data: dev.oaktech.biliapi.http.entity.region.RegionDynamic.Banner): CarouselData {
            val result = mutableListOf<CarouselItem>()
            data.top.forEach { top ->
                val avid = if (UrlUtil.isVideoUrl(top.uri)) {
                    runCatching { UrlUtil.parseAidFromUrl(top.uri) }.getOrNull()
                } else null
                val bvid = avid?.toBv()
                result.add(
                    CarouselItem(
                        cover = top.image,
                        title = top.title,
                        avid = avid,
                        bvid = bvid,
                        url = top.uri
                    )
                )
            }
            return CarouselData(result)
        }

        fun fromUgcRegionLocs(data: dev.oaktech.biliapi.http.entity.region.RegionLocs): CarouselData {
            val result = mutableListOf<CarouselItem>()
            data.data.forEach { (_, value) ->
                value.filter { it.url.contains("/video/") }.forEach { item ->
                    result.add(
                        CarouselItem(
                            cover = item.pic,
                            title = item.title,
                            bvid = Url(item.url).rawSegments.last()
                        )
                    )
                }
            }
            return CarouselData(result)
        }

        fun fromRegionBanner(data: dev.oaktech.biliapi.http.entity.region.RegionBanner): CarouselData {
            val result = mutableListOf<CarouselItem>()
            data.regionBannerList.forEach { item ->
                // 保留全部 banner（含活动/话题/投稿页等非视频链接），仅对视频链接解析 avid。
                // 旧逻辑用 isVideoUrl 过滤会丢弃所有非视频 banner，导致游戏/舞蹈等
                // 分区（banner 全是活动页）轮播图整块为空、不显示。
                val avid = if (UrlUtil.isVideoUrl(item.url)) {
                    runCatching { UrlUtil.parseAidFromUrl(item.url) }.getOrNull()
                } else null
                val bvid = avid?.toBv()
                result.add(
                    CarouselItem(
                        cover = item.image,
                        title = item.title,
                        avid = avid,
                        bvid = bvid,
                        url = item.url
                    )
                )
            }
            return CarouselData(result)
        }
    }

    data class CarouselItem(
        val cover: String,
        val title: String,
        val seasonId: Int? = null,
        val episodeId: Int? = null,
        val avid: Long? = null,
        val bvid: String? = null,
        /** 原始跳转链接；非视频类 banner（活动/话题/投稿页）无 avid，点击时据此打开网页 */
        val url: String? = null
    )
}
