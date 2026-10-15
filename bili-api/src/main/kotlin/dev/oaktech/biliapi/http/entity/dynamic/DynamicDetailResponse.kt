package dev.oaktech.biliapi.http.entity.dynamic

import kotlinx.serialization.Serializable

@Serializable
data class DynamicDetailData(
    val item: DynamicItem
)
