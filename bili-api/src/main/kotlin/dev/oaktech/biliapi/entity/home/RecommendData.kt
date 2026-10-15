package dev.oaktech.biliapi.entity.home

import dev.oaktech.biliapi.entity.ugc.UgcItem

data class RecommendData(
    val items: List<UgcItem>,
    val nextPage: RecommendPage
)

data class RecommendPage(
    val nextWebIdx: Int = 1,
    val nextAppIdx: Int = 0
)
