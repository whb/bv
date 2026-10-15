package dev.oaktech.bv.player.impl.exo

import android.content.Context
import dev.oaktech.bv.player.VideoPlayerOptions
import dev.oaktech.bv.player.factory.PlayerFactory

class ExoPlayerFactory : PlayerFactory<ExoMediaPlayer>() {
    override fun create(context: Context, options: VideoPlayerOptions): ExoMediaPlayer {
        return ExoMediaPlayer(context, options)
    }
}