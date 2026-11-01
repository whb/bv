package dev.oaktech.bv.player.impl.exo

import android.content.Context
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheWriter
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
import dev.oaktech.bv.player.VideoCacheProvider
import dev.oaktech.bv.player.VideoPlayerOptions
import dev.oaktech.bv.util.formatHourMinSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

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

    /** 最近一次播放的 URL，用于错误自愈时重建媒体源（MediaSource 不可重复 prepare） */
    private var lastVideoUrl: String? = null
    private var lastAudioUrl: String? = null
    /** 可恢复错误的自愈重试计数，播放恢复正常（READY 且正在播放）时清零 */
    private var errorRetryCount = 0
    /** 自愈最多尝试次数，避免对不可恢复错误死循环重试 */
    private val MAX_RECOVERABLE_RETRIES = 3
    /** 自愈前短暂停顿，给 TV 帧率切换导致的 Surface 重建留出沉降时间 */
    private val RECOVERABLE_RETRY_DELAY_MS = 800L
    private val TAG = "ExoMediaPlayer"

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

    /** 暂停预取写入的磁盘缓存（进程级单例，LRU 上限 500MB） */
    private val cache = VideoCacheProvider.get(context)

    /** 暂停预取的最大下载速率：默认 4 MB/s，避免占满带宽/eMMC，不抢占正在播放的流 */
    private val PREFETCH_MAX_BYTES_PER_SEC = 4L * 1024 * 1024

    /** 上游网络数据源（纯网络，不含缓存） */
    @OptIn(UnstableApi::class)
    private val upstreamFactory: DataSource.Factory =
        OkHttpDataSource.Factory(OkHttpUtil.getSharedClient(context)).apply {
            options.userAgent?.let { setUserAgent(it) }
            options.referer?.let { setDefaultRequestProperties(mapOf("referer" to it)) }
        }

    /** 播放用的数据源：在磁盘缓存之上包一层 CacheDataSource。
     * 不设置写数据槽（write data sink），因此为只读缓存：播放时只从缓存读取（命中则省网）、
     * 未命中回源，但绝不写盘——避免播放时产生额外磁盘写入（之前“边看边缓存”正是因 eMMC 写饱和
     * 导致 TV 卡顿的根因）。暂停时的预取写入由独立的 CacheWriter 负责（见 [startPrefetch]）。 */
    @OptIn(UnstableApi::class)
    private val dataSourceFactory: DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** 预取专用可写缓存数据源：带写数据槽，让 CacheWriter 把后续段写入磁盘缓存；
     * 上游使用限速客户端，避免占满带宽/eMMC；读取时命中已缓存段则直接复用。 */
    @OptIn(UnstableApi::class)
    private val prefetchCacheDataSource: CacheDataSource =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpUtil.getThrottledClient(context, PREFETCH_MAX_BYTES_PER_SEC)
                ).apply {
                    options.userAgent?.let { setUserAgent(it) }
                    options.referer?.let { setDefaultRequestProperties(mapOf("referer" to it)) }
                }
            )
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

    /** 预取专用单线程后台执行器，线程优先级设为 BACKGROUND（10）：与播放解码/UI 不在同一优先级，
     * 不抢占用户输入与播放流畅度。 */
    private val prefetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bv-video-prefetch").also { it.priority = Process.THREAD_PRIORITY_BACKGROUND }
    }
    private val prefetchDispatcher = prefetchExecutor.asCoroutineDispatcher()
    private var prefetchJob: Job? = null
    /** 跨线程访问：预取线程写入、主线程 cancelPrefetch 读取，需 @Volatile 保证可见性 */
    @Volatile
    private var cacheWriter: CacheWriter? = null

    /** 是否正在暂停预取（缓存中）；@Volatile 供 UI 线程跨线程读取 */
    @Volatile
    private var isCachingInternal = false
    /**
     * 缓存提示最低可见截止时间（SystemClock.elapsedRealtime）。
     * 预取可能因“该段已在缓存/剩余段很短”而瞬间结束，若 UI 直接读瞬时状态会时显时不显；
     * 这里给“正在缓存”一个最短可见窗口，UI 轮询才能稳定捕捉到。
     */
    @Volatile
    private var cacheVisibleUntil = 0L
    /** 缓存下载速度（字节/秒）；由预取线程的进度回调估算，@Volatile 供 UI 读取 */
    @Volatile
    private var cacheSpeedInternal = 0L
    /** 缓存速度采样窗口累加 */
    private var cacheSampleBytes = 0L
    private var cacheSampleStartMs = 0L

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
        lastVideoUrl = videoUrl
        lastAudioUrl = audioUrl
        mMediaSource = buildMediaSource(videoUrl, audioUrl)
    }

    /**
     * 构建合并媒体源（视频轨 + 可选音频轨）。抽成独立方法供 [playUrl] 与错误自愈复用：
     * MediaSource 实例只能 prepare 一次，自愈时必须用新实例重建，否则 ExoPlayer 会复用失败。
     */
    @OptIn(UnstableApi::class)
    private fun buildMediaSource(videoUrl: String?, audioUrl: String?): MediaSource {
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
        return MergingMediaSource(*mediaSources.toTypedArray())
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
        cancelPrefetch()
        mPlayer?.play()
    }

    override fun pause() {
        mPlayer?.pause()
        // 暂停时启动预取：把后续视频段盘，恢复播放时更抗网络抖动
        startPrefetch()
    }

    override fun stop() {
        cancelPrefetch()
        mPlayer?.stop()
    }

    override fun reset() {
        TODO("Not yet implemented")
    }

    override val isPlaying: Boolean
        get() = mPlayer?.isPlaying == true

    override fun seekTo(time: Long) {
        cancelPrefetch()
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
        // 停止暂停预取并关闭后台线程，避免泄漏
        cancelPrefetch()
        try {
            prefetchExecutor.shutdown()
        } catch (_: Exception) {
            // 忽略
        }
        // 还原主线程优先级，消除全局副作用
        restoreMainThreadPriority()
    }

    // ===================== 暂停预取（磁盘缓冲） =====================
    // 仅在暂停时把后续视频段写入磁盘缓存：恢复播放时命中缓存可抗网络抖动；
    // 播放时 ExoPlayer 用 READ_ONLY 缓存数据源不会写盘，避免 eMMC 写饱和卡顿。
    // 预取在 BACKGROUND 优先级单线程、限速下载，不抢占正在播放的视频流与用户输入。

    /** 暂停时启动预取：从当前已缓冲末尾开始，把视频（以及可选的音频）段写入磁盘缓存。
     *
     * 注意：ExoPlayer 的方法非线程安全，必须在主线程（playbackLooper）读取
     * bufferedPosition/duration，因此这里先在主线程捕获，再交给后台预取线程使用。 */
    @OptIn(UnstableApi::class)
    private fun startPrefetch() {
        val player = mPlayer ?: return
        if (isCachingInternal) return
        val videoUrl = lastVideoUrl ?: return
        // 主线程捕获播放器状态与音频地址，避免后台线程访问 ExoPlayer / 读取非 volatile 字段
        val audioUrl = lastAudioUrl
        val startPos = player.bufferedPosition
        val duration = player.duration
        // 已缓冲到末尾（剩余段 <= 0）则无内容可预取，直接不进入“缓存中”状态，避免误导
        if (duration > 0 && duration - startPos <= 0) return
        isCachingInternal = true
        cacheSpeedInternal = 0L
        // 给“正在缓存”一个最短可见窗口：即便实际预取瞬间完成，UI 也能稳定显示而非一闪而过
        cacheVisibleUntil = SystemClock.elapsedRealtime() + MIN_CACHE_VISIBLE_MS
        Log.i(TAG, "暂停预取启动：从 ${startPos}ms 开始缓存 $videoUrl")
        prefetchJob = CoroutineScope(prefetchDispatcher).launch {
            try {
                prefetchUrl(videoUrl, startPos, duration)
                audioUrl?.let { prefetchUrl(it, startPos, duration) }
            } catch (_: Exception) {
                // 预取失败（取消/网络）忽略，不影响播放
            } finally {
                // 只复位“实际进行中”标志；cacheSpeedInternal 保留最后速度供可见窗口内展示，
                // 由 cancelPrefetch 或下一次 startPrefetch 清零。
                isCachingInternal = false
            }
        }
    }

    /**
     * 预取单个 URL：从 [startPos] 之后下载到缓存（受 LRU 上限控总空间）。
     * 用独立的限速客户端（[PREFETCH_MAX_BYTES_PER_SEC]）避免占满带宽/eMMC。
     * [startPos]/[duration] 由主线程捕获后传入，本方法在后台线程执行，不再访问 ExoPlayer。
     */
    @OptIn(UnstableApi::class)
    private fun prefetchUrl(url: String, startPos: Long, duration: Long) {
        // duration 有效时只预取“剩余部分”，避免无谓下载整份；无效时预取到末尾（LRU 兜底控空间）。
        // -1 即 C.LENGTH_UNSET，表示“下载到末尾”，DataSpec.Builder.setLength 以此约定。
        val length: Long = if (duration > 0) {
            val remaining = duration - startPos
            if (remaining > 0) remaining else -1L
        } else {
            -1L
        }
        val dataSpec = DataSpec.Builder()
            .setUri(url)
            .setPosition(startPos)
            .setLength(length)
            .build()
        cacheSampleBytes = 0L
        cacheSampleStartMs = System.currentTimeMillis()
        cacheWriter = CacheWriter(
            prefetchCacheDataSource,
            dataSpec,
            null
        ) { _, _, newBytesCached ->
            // 估算瞬时下载速度（每 300ms 滑动窗口），供 UI 显示缓存速度
            cacheSampleBytes += newBytesCached
            val now = System.currentTimeMillis()
            val dt = now - cacheSampleStartMs
            if (dt >= CACHE_SPEED_WINDOW_MS) {
                cacheSpeedInternal = (cacheSampleBytes * 1000L) / dt
                cacheSampleBytes = 0L
                cacheSampleStartMs = now
            }
        }
        try {
            cacheWriter?.cache()
        } catch (_: Exception) {
            // 预取失败（网络/被取消）忽略，不影响播放
        } finally {
            cacheWriter = null
        }
    }

    /** 取消暂停预取：停止 CacheWriter 并取消协程，置回非缓存状态 */
    private fun cancelPrefetch() {
        cacheWriter?.cancel()
        cacheWriter = null
        prefetchJob?.cancel()
        prefetchJob = null
        isCachingInternal = false
        cacheVisibleUntil = 0L
        cacheSpeedInternal = 0L
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

    override val cacheSpeed: Long
        get() = cacheSpeedInternal

    override val isCaching: Boolean
        get() = isCachingInternal || SystemClock.elapsedRealtime() < cacheVisibleUntil

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
            // 真正恢复播放，重置自愈计数，允许后续错误再次自愈；同时停止暂停预取
            cancelPrefetch()
            errorRetryCount = 0
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

    // 错误回调在主线程触发：仅做轻量转发或自愈（上层不应在此同步做网络重试/IO）。
    // 可恢复的瞬时错误（网络抖动、直播窗口落后、TV 帧率切换导致 Surface 重建引发的
    // 编解码器资源忙/初始化失败等）尝试原地自愈：重建媒体源并从出错位置续播，
    // 避免播放器变成死态后被迫退出播放页。自愈次数耗尽或错误不可恢复才转发上层。
    override fun onPlayerError(error: PlaybackException) {
        if (isRecoverable(error) && errorRetryCount < MAX_RECOVERABLE_RETRIES) {
            errorRetryCount++
            val resumePos = currentPosition
            Log.w(TAG, "播放错误自愈 #$errorRetryCount (code=${error.errorCode}, ${error.message})，重建媒体源续播 @${resumePos}ms")
            mainScope.launch {
                delay(RECOVERABLE_RETRY_DELAY_MS)
                val player = mPlayer ?: return@launch
                val rebuilt = buildMediaSource(lastVideoUrl, lastAudioUrl)
                mMediaSource = rebuilt
                try {
                    // 复用同一 ExoPlayer 实例（保留 Surface），重建媒体源续播
                    player.setMediaSource(rebuilt)
                    player.prepare()
                    player.seekTo(resumePos)
                    player.playWhenReady = true
                    player.play()
                } catch (e: Exception) {
                    // 自愈执行异常，回退到上层错误流程
                    Log.e(TAG, "播放错误自愈失败，转发上层", e)
                    mPlayerEventListener?.onError(error)
                }
            }
            return
        }
        mPlayerEventListener?.onError(error)
    }

    /** 判断是否为可原地自愈的瞬时错误；非此类（如格式不支持/DRM）直接走上层错误流程 */
    private fun isRecoverable(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> true
            else -> false
        }
    }

    companion object {
        /** AV1 解码兜底检测的等待时间：音频已就绪但这么久还没渲染出首帧，视为解码失败 */
        private const val AV1_DECODE_CHECK_DELAY_MS = 8000L

        /** 缓存提示最短可见时长：避免预取瞬间完成导致 UI 时显时不显 */
        private const val MIN_CACHE_VISIBLE_MS = 1200L

        /** 缓存下载速度采样窗口（毫秒）：越小越灵敏，但抖动越大 */
        private const val CACHE_SPEED_WINDOW_MS = 300L

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
