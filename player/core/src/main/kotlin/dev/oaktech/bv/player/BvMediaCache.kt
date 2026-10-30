package dev.oaktech.bv.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 进程级共享的媒体磁盘缓存（ExoPlayer 官方推荐全局单例 SimpleCache）。
 *
 * 把已下载的流媒体分片缓存到磁盘，使得：
 * - 拖拽进度后的二次缓冲、网络抖动后的恢复可直接从本地读取，不必重新联网；
 * - 重复播放同一段内容（如循环、回看）零等待。
 * 这些都能显著减少 re-buffer、提升播放流畅度。
 *
 * 注意：SimpleCache 必须保证同一目录全局唯一，因此做成单例；
 * 不需要在每次释放播放器时 release，仅进程退出前可选调用 [release]。
 */
object BvMediaCache {
    @Volatile
    private var cache: SimpleCache? = null
    private val lock = Any()

    private const val CACHE_DIR_NAME = "bv_media"
    private const val MAX_CACHE_BYTES = 512L * 1024 * 1024 // 512 MB

    fun get(context: Context): SimpleCache {
        return cache ?: synchronized(lock) {
            cache ?: createCache(context.applicationContext).also { cache = it }
        }
    }

    private fun createCache(context: Context): SimpleCache {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
        val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
        return SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context))
    }

    /** 进程退出前调用（可选），释放缓存索引与数据库 */
    fun release() {
        synchronized(lock) {
            cache?.release()
            cache = null
        }
    }
}
