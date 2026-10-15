package dev.oaktech.biliapi.entity.search


data class Hotword(
    val keyword: String,
    val showName: String,
    val icon: String?,
) {
    companion object {
        fun fromHttpWebHotword(hotword: dev.oaktech.biliapi.http.entity.search.Hotword) =
            Hotword(
                keyword = hotword.keyword,
                showName = hotword.showName,
                icon = hotword.icon
            )

        fun fromHttpAppSquareDataItem(squareDataItem: dev.oaktech.biliapi.http.entity.search.AppSearchSquareData.SquareData.SquareDataItem) =
            Hotword(
                keyword = squareDataItem.keyword ?: "",
                showName = squareDataItem.showName ?: "",
                icon = squareDataItem.icon
            )

        fun fromHttpAppSearchTrendingHotword(hotword: dev.oaktech.biliapi.http.entity.search.SearchTendingData.Hotword) =
            Hotword(
                keyword = hotword.keyword,
                showName = hotword.showName,
                icon = hotword.icon
            )
    }
}