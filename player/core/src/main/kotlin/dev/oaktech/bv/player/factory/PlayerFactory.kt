package dev.oaktech.bv.player.factory

import android.content.Context
import dev.oaktech.bv.player.AbstractVideoPlayer
import dev.oaktech.bv.player.VideoPlayerOptions

abstract class PlayerFactory<T : AbstractVideoPlayer> {
    abstract fun create(context: Context, options: VideoPlayerOptions): T
}