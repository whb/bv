package dev.oaktech.bv.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 视频磁盘缓存单例（进程级）。
 *
 * 承载两类写入，二者写入【同一个】缓存、共享 [MAX_CACHE_BYTES] 的 LRU 上限：
 * 1. 播放即缓存（media3 默认机制）：ExoPlayer 播放时把消费到的视频段写入缓存，
 *    恢复播放/跳转/网络抖动时命中缓存可更快续播；
 * 2. 暂停预取：暂停时由 CacheWriter 把“已缓存字节前沿”之后的后续段继续写入，
 *    预取从播放已缓存的位置续写，二者通过同一缓存自然衔接。
 *
 * LRU 上限 [MAX_CACHE_BYTES]，超出自动淘汰最旧内容，把硬盘占用控制在可接受范围
 * （见 [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor]）。
 * 播放新视频前会清空（见 ExoMediaPlayer.clearCacheAsync），缓存区只保留当前视频。
 */
object VideoCacheProvider {
    @Volatile
    private var cache: Cache? = null
    private val lock = Any()

    /** 磁盘缓存上限：1 GB（播放缓存与暂停预取共享），LRU 淘汰最旧内容，控制硬盘占用 */
    const val MAX_CACHE_BYTES = 1024L * 1024 * 1024

    fun get(context: Context): Cache {
        return cache ?: synchronized(lock) {
            cache ?: create(context.applicationContext).also { cache = it }
        }
    }

    private fun create(context: Context): Cache {
        val cacheDir = File(context.cacheDir, "pv_video_cache")
        val databaseProvider = StandaloneDatabaseProvider(context)
        val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
        return SimpleCache(cacheDir, evictor, databaseProvider)
    }

    /**
     * 清空磁盘缓存（逐个 key 删除片段）。
     *
     * 播放新视频前调用，保证缓存区只保留"当前播放视频"的内容，不会跨视频长期占用硬盘。
     * 用 [Cache.removeResource] 而不是删目录：SimpleCache 实例仍被各数据源持有，
     * 直接删目录会破坏其索引；removeResource 会同步更新索引与 LRU 计数。
     *
     * 单线程预取执行器上调用可保证与预取任务串行，避免与正在写入的片段冲突。
     */
    fun clear(context: Context) {
        val cache = get(context)
        val keys = try {
            cache.keys.toList()
        } catch (_: Exception) {
            emptyList()
        }
        for (key in keys) {
            try {
                cache.removeResource(key)
            } catch (_: Exception) {
                // 个别片段被占用/已失效时忽略，继续删其余部分
            }
        }
    }

    /** 应用退出前释放（可选）；重复调用安全 */
    fun release() {
        synchronized(lock) {
            cache?.release()
            cache = null
        }
    }
}
