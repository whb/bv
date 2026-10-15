package dev.oaktech.biliapi.entity.ugc.region

import dev.oaktech.biliapi.entity.ugc.UgcItem

@Deprecated("User region v2 instead")
data class UgcRegionListData(
    val items: List<UgcItem>,
    val next: UgcRegionPage
) {
    companion object {
        fun fromRegionDynamicList(data: dev.oaktech.biliapi.http.entity.region.RegionDynamicList): UgcRegionListData {
            return UgcRegionListData(
                items = data.new.map { UgcItem.fromRegionDynamicListItem(it) },
                next = UgcRegionPage(data.cBottom)
            )
        }
    }
}