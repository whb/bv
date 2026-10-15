package dev.oaktech.biliapi.entity.ugc.region

import dev.oaktech.biliapi.entity.ugc.UgcItem

data class UgcFeedData(
    var hasNext: Boolean,
    var nextPage: UgcFeedPage,
    var items: List<UgcItem> = emptyList()
) {
    companion object {
        fun fromRegionFeedRcmd(data: dev.oaktech.biliapi.http.entity.region.RegionFeedRcmd): UgcFeedData {
            return UgcFeedData(
                hasNext = data.archives.isNotEmpty(),
                nextPage = UgcFeedPage(),
                items = data.archives.map { UgcItem.fromRegionRcmdArchive(it) }
            )
        }
    }
}
