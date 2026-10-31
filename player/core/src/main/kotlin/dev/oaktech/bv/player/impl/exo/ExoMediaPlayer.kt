package dev.oaktech.bv.player.impl.exo

import android.content.Context
import android.os.Looper
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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

    /** 播放期间提升主线程调度优先级前的原始值，release 时还原 */
    private var savedMainThreadPriority: Int? = null

    /** 最近一次带宽估算（bits/sec），由 onBandwidthEstimate 更新，用于展示缓冲下载速度 */
    @Volatile
    private var latestBitrateEstimate: Long = 0L

    private val analyticsListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            presentationTimeUs: Long
        ) {
            av1FirstFrameRendered = true
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bandwidthEstimate: Long
        ) {
            latestBitrateEstimate = bandwidthEstimate
        }
    }

    @OptIn(UnstableApi::class)
    private val dataSourceFactory: DataSource.Factory =
        OkHttpDataSource.Factory(OkHttpUtil.getSharedClient(context)).apply {
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
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            // 优先按时间维度缓冲，弱网下更快攒够可播放时长，减少卡顿
            .setPrioritizeTimeOverSizeThresholds(true)
            // 缓冲字节上限改为基于进程堆上限动态计算（见 TARGET_BUFFER_BYTES_CAP 说明），
            // 仅在内存维度兜底，避免低堆设备（如模拟器 192MB）把样本缓冲撑爆导致 OOM；
            // 普通/大码流仍由时间上限（MIN/MAX_BUFFER_MS）主导可播放时长。
            .setTargetBufferBytes(TARGET_BUFFER_BYTES_CAP)
            .build()
        mPlayer = ExoPlayer
            .Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            // 播放期间持有网络/CPU 唤醒锁，防止 TV/移动端休眠或降频导致掉帧卡顿
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekForwardIncrementMs(1000 * 10)
            .setSeekBackIncrementMs(1000 * 5)
            .build()

        initListener()
        // 提升主线程调度优先级，保证卡顿/出错时用户输入仍优先响应
        boostMainThreadPriority()
    }

    private fun initListener() {
        mPlayer?.addListener(this)
        mPlayer?.addAnalyticsListener(analyticsListener)
    }

    /**
     * 提升处理输入/UI 的主线程调度优先级。
     *
     * 卡顿或出错时，播放器内部消息（buffer/track 选择/state 机）与 UI 输入共用主线程消息队列，
     * 重活会挤占输入响应。这里把主线程优先级提到 URGENT_DISPLAY(-8)：高于普通后台(10)/默认(0)，
     * 保证输入及时被调度；但仍低于解码线程(AUDIO -16)，不会饿死解码导致更卡。
     * release 时通过 [restoreMainThreadPriority] 精确还原，避免对进程其他界面造成长期全局影响。
     */
    private fun boostMainThreadPriority() {
        val mainThread = Looper.getMainLooper().thread
        if (!mainThread.isAlive) return
        try {
            savedMainThreadPriority = Process.getThreadPriority(mainThread.id.toInt())
            Process.setThreadPriority(mainThread.id.toInt(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Exception) {
            // 无权限或失败则忽略，不影响播放功能
        }
    }

    /** 还原主线程优先级，消除全局副作用 */
    private fun restoreMainThreadPriority() {
        val prio = savedMainThreadPriority ?: return
        try {
            val mainThread = Looper.getMainLooper().thread
            if (mainThread.isAlive) Process.setThreadPriority(mainThread.id.toInt(), prio)
        } catch (_: Exception) {
            // 忽略：还原失败不影响后续
        } finally {
            savedMainThreadPriority = null
        }
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
        val mediaSource = mMediaSource ?: return
        mPlayer?.setMediaSource(mediaSource)
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
                // 降级回调由上层实现，可能抛异常；此处兜底，避免主线程被未捕获异常拖崩进程
                try {
                    options.onAv1DecodeFailed?.invoke()
                } catch (_: Exception) {
                    // 忽略上层降级回调异常，播放器自身仍可用
                }
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
        av1FallbackJob = null
        val player = mPlayer ?: run {
            // 已释放过：仍还原主线程优先级并直接返回，保证幂等
            restoreMainThreadPriority()
            return
        }
        // 释放前先剥离视频 Surface：MediaCodec 仍持有旧 Surface 时直接 release
        // 是 Media3 常见的 native 崩溃来源（SIGSEGV），先 clear 掉可消除该风险。
        try {
            player.clearVideoSurface()
        } catch (_: Exception) {
            // Surface 可能已被系统回收，忽略
        }
        // 释放路径必须异常安全：任何异常都绝不能向上冒泡，
        // 否则会在非预期线程触发未捕获异常 -> 进程被 SIGKILL（data_app_crash）。
        try {
            player.release()
        } catch (_: Exception) {
            // 重复释放等异常，忽略
        }
        mPlayer = null
        // 还原主线程优先级，消除全局副作用
        restoreMainThreadPriority()
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
        get() = latestBitrateEstimate / 8

    // 以下 Player.Listener 回调均在主线程（playbackLooper=main）触发，必须保持轻量：
    // 不得做同步阻塞重活（网络/IO/大解析），否则会挤占输入响应。重活一律异步到 IO 线程，
    // 这也是“卡顿/出错时用户输入仍响应”的关键保障之一。
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

    // 错误回调在主线程触发：仅做轻量转发（上层不应在此同步做网络重试/IO）。
    // 若需错误后恢复/重试，必须在 IO 线程异步执行。
    override fun onPlayerError(error: PlaybackException) {
        mPlayerEventListener?.onError(error)
    }

    companion object {
        /** AV1 解码兜底检测的等待时间：音频已就绪但这么久还没渲染出首帧，视为解码失败 */
        private const val AV1_DECODE_CHECK_DELAY_MS = 8000L

        // 缓冲策略：比 ExoPlayer 默认（min/max 各 50s）更激进，弱网下更少 re-buffer
        private const val MIN_BUFFER_MS = 60_000
        private const val MAX_BUFFER_MS = 120_000
        private const val BUFFER_FOR_PLAYBACK_MS = 2_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        // 缓冲字节上限：基于进程堆上限动态计算，绝不能硬编码大值。
        // ExoPlayer 的样本缓冲（DefaultAllocator）分配在 Java 堆上，
        // 硬编码 150MB 在低堆设备（如本机 192MB 上限）会直接 OOM——
        // 见 bugreport：java.lang.OutOfMemoryError @ Okio Segment.kt:62（网络读取时堆耗尽）。
        // 策略：取 maxMemory 的 30%，绝对不超过 80MB，至少 15MB，
        // 给应用自身、OkHttp/Okio 网络缓冲与解码器留足余量。
        //   192MB 堆设备 → 约 57MB（≈30Mbps 下 15s 缓冲，足够吸收网络抖动）
        //   512MB 堆设备 → 封顶 80MB（≈30Mbps 下 21s 缓冲）
        private val TARGET_BUFFER_BYTES_CAP: Int =
            (Runtime.getRuntime().maxMemory() * 30 / 100)
                .coerceAtMost(80L * 1024 * 1024)
                .coerceAtLeast(15L * 1024 * 1024)
                .toInt()
    }
}
