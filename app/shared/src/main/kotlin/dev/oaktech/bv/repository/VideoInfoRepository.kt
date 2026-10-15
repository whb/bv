package dev.oaktech.bv.repository

import dev.oaktech.bv.player.entity.VideoListItem
import org.koin.core.annotation.Single

@Single
class VideoInfoRepository {
    val videoList = mutableListOf<VideoListItem>()
}
