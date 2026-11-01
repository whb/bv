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
 * 仅用于“暂停时预取”把后续视频段落盘：LRU 上限 [MAX_CACHE_BYTES]，超出自动淘汰最旧内容，
 * 从而把硬盘占用控制在可接受范围（见 [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor]）。
 *
 * 播放时并不写盘（ExoPlayer 使用只读缓存数据源），因此该缓存只会在暂停预取时增长，
 * 并由 LRU 自然回收，不会无限膨胀。详见 ExoMediaPlayer 的暂停预取逻辑。
 */
object VideoCacheProvider {
    @Volatile
    private var cache: Cache? = null
    private val lock = Any()

    /** 磁盘缓存上限：500 MB，LRU 淘汰最旧内容，控制硬盘占用 */
    const val MAX_CACHE_BYTES = 500L * 1024 * 1024

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
