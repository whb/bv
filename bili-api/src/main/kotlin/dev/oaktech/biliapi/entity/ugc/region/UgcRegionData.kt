package dev.oaktech.biliapi.entity.ugc.region

import dev.oaktech.biliapi.entity.CarouselData
import dev.oaktech.biliapi.entity.ugc.UgcItem

@Deprecated("User region v2 instead")
data class UgcRegionData(
    val carouselData: CarouselData?,
    val items: List<UgcItem>,
    val next: UgcRegionPage
) {
    companion object {
        fun fromRegionDynamic(data: dev.oaktech.biliapi.http.entity.region.RegionDynamic): UgcRegionData {
            return UgcRegionData(
                carouselData = data.banner?.let { CarouselData.fromUgcRegionDynamicBanner(it) },
                items = data.new.map { UgcItem.fromRegionDynamicListItem(it) },
                next = UgcRegionPage(data.cBottom)
            )
        }
    }
}