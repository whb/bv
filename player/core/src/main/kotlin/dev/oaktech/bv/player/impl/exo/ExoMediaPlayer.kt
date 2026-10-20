package dev.oaktech.bv.player.impl.exo

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import dev.oaktech.bv.player.AbstractVideoPlayer
import dev.oaktech.bv.player.OkHttpUtil
import dev.oaktech.bv.player.VideoPlayerOptions
import dev.oaktech.bv.util.formatHourMinSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class ExoMediaPlayer(
    private val context: Context,
    private val options: VideoPlayerOptions
) : AbstractVideoPlayer(), Player.Listener {
    var mPlayer: ExoPlayer? = null
    protected var mMediaSource: MediaSource? = null

    /** AV1 mime 重写后的解码兜底检测 */
    private var av1FallbackJob: Job? = null
    private var av1FirstFrameRendered = false
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)

    private val analyticsListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            presentationTimeUs: Long
        ) {
            av1FirstFrameRendered = true
        }
    }

    @OptIn(UnstableApi::class)
    private val dataSourceFactory =
        OkHttpDataSource.Factory(OkHttpUtil.generateCustomSslOkHttpClient(context)).apply {
            options.userAgent?.let { setUserAgent(it) }
            options.referer?.let { setDefaultRequestProperties(mapOf("referer" to it)) }
        }

    init {
        initPlayer()
    }

    @OptIn(UnstableApi::class)
    override fun initPlayer() {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(
                when (options.enableFfmpegAudioRenderer) {
                    true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                }
            )
        }
        mPlayer = ExoPlayer
            .Builder(context)
            .setRenderersFactory(renderersFactory)
            .setSeekForwardIncrementMs(1000 * 10)
            .setSeekBackIncrementMs(1000 * 5)
            .build()

        initListener()
    }

    private fun initListener() {
        mPlayer?.addListener(this)
        mPlayer?.addAnalyticsListener(analyticsListener)
    }

    @OptIn(UnstableApi::class)
    override fun setHeader(headers: Map<String, String>) {

    }

    @OptIn(UnstableApi::class)
    override fun playUrl(videoUrl: String?, audioUrl: String?) {
        // MStar / Realtek 等平台的 AV1 解码器注册的是私有 mime video/av1，
        // 需要重写视频轨 mime 才能被 ExoPlayer 找到，见 Av1MimeRewriteExtractorsFactory
        val extractorsFactory = if (options.enableAv1MimeRewrite) {
            Av1MimeRewriteExtractorsFactory()
        } else {
            DefaultExtractorsFactory()
        }
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
        val videoMediaSource = videoUrl?.let {
            mediaSourceFactory.createMediaSource(MediaItem.fromUri(it))
        }
        val audioMediaSource = audioUrl?.let {
            mediaSourceFactory.createMediaSource(MediaItem.fromUri(it))
        }

        val mediaSources = listOfNotNull(videoMediaSource, audioMediaSource)
        mMediaSource = MergingMediaSource(*mediaSources.toTypedArray())
    }

    @OptIn(UnstableApi::class)
    override fun prepare() {
        mPlayer?.setMediaSource(mMediaSource!!)
        mPlayer?.prepare()
        scheduleAv1DecodeCheck()
    }

    /**
     * AV1 mime 重写后的兜底检测：厂商解码器可能实现残缺，匹配上了也解不出画面，
     * 此时既不报错也不出帧。准备播放后若音频已就绪（READY）却迟迟渲染不出首帧，
     * 判定为 AV1 解码失败，回调上层降级到其他编码。
     */
    private fun scheduleAv1DecodeCheck() {
        av1FallbackJob?.cancel()
        av1FirstFrameRendered = false
        if (!options.enableAv1MimeRewrite) return
        av1FallbackJob = mainScope.launch {
            delay(AV1_DECODE_CHECK_DELAY_MS)
            val player = mPlayer ?: return@launch
            // 只在音频已正常播放时才判定失败，避免网络缓冲慢被误判
            if (!av1FirstFrameRendered &&
                player.playbackState == Player.STATE_READY &&
                player.playWhenReady
            ) {
                options.onAv1DecodeFailed?.invoke()
            }
        }
    }

    override fun start() {
        mPlayer?.play()
    }

    override fun pause() {
        mPlayer?.pause()
    }

    override fun stop() {
        mPlayer?.stop()
    }

    override fun reset() {
        TODO("Not yet implemented")
    }

    override val isPlaying: Boolean
        get() = mPlayer?.isPlaying == true

    override fun seekTo(time: Long) {
        mPlayer?.seekTo(time)
    }

    override fun release() {
        av1FallbackJob?.cancel()
        mPlayer?.release()
    }

    override val currentPosition: Long
        get() = mPlayer?.currentPosition ?: 0
    override val duration: Long
        get() = mPlayer?.duration ?: 0
    override val bufferedPercentage: Int
        get() = mPlayer?.bufferedPercentage ?: 0

    override fun setOptions() {
        mPlayer?.playWhenReady = true
    }

    override var speed: Float
        get() = mPlayer?.playbackParameters?.speed ?: 1f
        set(value) {
            mPlayer?.setPlaybackSpeed(value)
        }
    override val tcpSpeed: Long
        get() = 0L

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> mPlayerEventListener?.onIdle()
            Player.STATE_BUFFERING -> mPlayerEventListener?.onBuffering()
            Player.STATE_READY -> mPlayerEventListener?.onReady()
            Player.STATE_ENDED -> mPlayerEventListener?.onEnd()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            mPlayerEventListener?.onPlay()
        } else {
            mPlayerEventListener?.onPause()
        }
    }

    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
        mPlayerEventListener?.onSeekBack(seekBackIncrementMs)
    }

    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
        mPlayerEventListener?.onSeekForward(seekForwardIncrementMs)
    }

    override val debugInfo: String
        get() {
            return """
                player: ${androidx.media3.common.MediaLibraryInfo.VERSION_SLASHY}
                time: ${currentPosition.formatHourMinSec()} / ${duration.formatHourMinSec()}
                buffered: $bufferedPercentage%
                resolution: ${mPlayer?.videoSize?.width} x ${mPlayer?.videoSize?.height}
                audio: ${mPlayer?.audioFormat?.bitrate ?: 0} kbps
                video codec: ${mPlayer?.videoFormat?.sampleMimeType ?: "null"}
                audio codec: ${mPlayer?.audioFormat?.sampleMimeType ?: "null"} (${getAudioRendererName()})
            """.trimIndent()
        }

    private fun getAudioRendererName(): String {
        val rendererCount = mPlayer?.rendererCount ?: return "UnknownRenderer"
        for (i in 0 until rendererCount) {
            val renderer = mPlayer!!.getRenderer(i)
            if (renderer.trackType == C.TRACK_TYPE_AUDIO && renderer.state == Renderer.STATE_STARTED) {
                return renderer.name
            }
        }
        return "UnknownRenderer"
    }

    override val videoWidth: Int
        get() = mPlayer?.videoSize?.width ?: 0
    override val videoHeight: Int
        get() = mPlayer?.videoSize?.height ?: 0

    override fun onPlayerError(error: PlaybackException) {
        mPlayerEventListener?.onError(error)
    }

    companion object {
        /** AV1 解码兜底检测的等待时间：音频已就绪但这么久还没渲染出首帧，视为解码失败 */
        private const val AV1_DECODE_CHECK_DELAY_MS = 8000L
    }
}
