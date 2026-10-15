package dev.oaktech.bv.entity.carddata

import dev.oaktech.biliapi.http.entity.search.SearchMediaResult
import dev.oaktech.bv.util.ImageSize
import dev.oaktech.bv.util.resizedImageUrl

data class SeasonCardData(
    val seasonId: Int,
    val title: String,
    val subTitle: String? = null,
    val cover: String,
    val rating: String? = null,
    val badge: SearchMediaResult.Badge? = null,
) {
    companion object {
        fun fromPgcItem(pgcItem: dev.oaktech.biliapi.entity.pgc.PgcItem): SeasonCardData {
            return SeasonCardData(
                seasonId = pgcItem.seasonId,
                title = pgcItem.title,
                subTitle = pgcItem.subTitle,
                cover = pgcItem.cover.resizedImageUrl(ImageSize.SeasonCoverThumbnail),
                rating = pgcItem.rating,
                badge = null
            )
        }

        fun fromFollowingSeason(followingSeason: dev.oaktech.biliapi.entity.season.FollowingSeason): SeasonCardData {
            return SeasonCardData(
                seasonId = followingSeason.seasonId,
                title = followingSeason.title,
                cover = followingSeason.cover,
                rating = null,
                badge = null
            )
        }
    }
}
