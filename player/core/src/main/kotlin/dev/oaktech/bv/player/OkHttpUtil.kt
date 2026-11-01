package dev.oaktech.bv.player

import android.content.Context
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

object OkHttpUtil {
    @Volatile
    private var sharedClient: OkHttpClient? = null
    private val lock = Any()

    /**
     * 全进程共享的 OkHttpClient 单例。
     *
     * 复用连接池与 TLS 会话，避免每次创建播放器都新建客户端、导致流媒体逐分片
     * 重复建连与握手（这是卡顿和首帧慢的常见根源）。同时对连接池、HTTP/2、
     * 超时与重试做了针对性调优。
     */
    fun getSharedClient(context: Context): OkHttpClient {
        return sharedClient ?: synchronized(lock) {
            sharedClient ?: buildClient(context.applicationContext).also { sharedClient = it }
        }
    }

    /** 兼容旧调用点 */
    fun generateCustomSslOkHttpClient(context: Context): OkHttpClient = getSharedClient(context)

    /**
     * 预取专用限速客户端：在 [buildBaseBuilder] 配置基础上叠加 [ThrottlingInterceptor]，
     * 把下载速率限制在 maxBytesPerSecond 以内。暂停预取用它，避免占满带宽/磁盘 IO，
     * 从而不抢占正在播放的视频流（用户输入与播放流畅度优先）。
     */
    private val throttledClients = ConcurrentHashMap<Long, OkHttpClient>()

    fun getThrottledClient(context: Context, maxBytesPerSecond: Long): OkHttpClient {
        return throttledClients[maxBytesPerSecond] ?: synchronized(throttledClients) {
            throttledClients[maxBytesPerSecond] ?: buildBaseBuilder(context.applicationContext)
                .addInterceptor(ThrottlingInterceptor(maxBytesPerSecond))
                .build()
                .also { throttledClients[maxBytesPerSecond] = it }
        }
    }

    private fun buildClient(context: Context): OkHttpClient {
        return buildBaseBuilder(context.applicationContext).build()
    }

    /**
     * 共享客户端的基础配置（SSL/CA、TCP 窗口、DNS 缓存、连接池、HTTP/2、超时、重试）。
     * [buildClient] 与 [getThrottledClient] 共用，避免配置漂移。
     */
    private fun buildBaseBuilder(context: Context): OkHttpClient.Builder {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val customCaMap = mapOf(
            "custom:r5" to "GlobalSign ECC Root CA R5.crt"
        )

        val systemKeyStore = KeyStore.getInstance("AndroidCAStore").apply {
            load(null, null)
        }
        val customKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)

            systemKeyStore.aliases().toList().forEach {
                setCertificateEntry(it, systemKeyStore.getCertificate(it))
            }
            customCaMap.forEach { (alias, caFilename) ->
                val certificateInputStream = context.applicationContext.assets.open(caFilename)
                val certificate = certificateFactory.generateCertificate(certificateInputStream)
                setCertificateEntry(alias, certificate)
            }
        }

        val tmfAlgorithm: String = TrustManagerFactory.getDefaultAlgorithm()
        val trustManagerFactory: TrustManagerFactory =
            TrustManagerFactory.getInstance(tmfAlgorithm).apply {
                init(customKeyStore)
            }

        val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, trustManagerFactory.trustManagers, null)
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(
                sslContext.socketFactory,
                trustManagerFactory.trustManagers[0] as X509TrustManager
            )
            // 放大 TCP 收发窗口：高码流 + 高延迟/弱 WiFi 下直接抬升单连接吞吐上限，
            // 内核若按 rmem_max 封顶则自动回落，无副作用
            .socketFactory(ThroughputSocketFactory(rcvBufBytes = 2 * 1024 * 1024, sndBufBytes = 256 * 1024))
            // DNS 结果缓存：B 站 CDN 域名繁多，避免重复解析；解析抖动时回退旧值不卡
            .dns(CachingDns(ttlMillis = 5 * 60 * 1000))
            // 复用 TCP/TLS 连接：最多 8 个空闲连接，保活 5 分钟，减少分片间握手
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            // 优先 HTTP/2 多路复用，降低分片请求的延迟与队头阻塞
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            // 流式读取：30s 内无新字节才判定连接失效（避免正常慢速流被误杀）
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
    }

    /**
     * 放大底层 TCP 收发缓冲区的 SocketFactory。
     * 大码流视频对单连接吞吐敏感：在高带宽-延迟积（BDP）链路（如弱 WiFi、跨网）
     * 上，默认接收窗口可能成为瓶颈；放大 SO_RCVBUF 让内核能开更大的滑动窗口，
     * 从而减少“带宽够但窗口不够”导致的吞吐上不去。SO_SNDBUF 同理（影响回传/ACK 节奏）。
     * 若系统按 rmem_max 封顶，则自动回落到系统上限，不会更差。
     */
    private class ThroughputSocketFactory(
        private val delegate: SocketFactory = SocketFactory.getDefault(),
        private val rcvBufBytes: Int,
        private val sndBufBytes: Int
    ) : SocketFactory() {
        override fun createSocket(): Socket = delegate.createSocket().apply { tune() }
        override fun createSocket(host: String?, port: Int): Socket =
            delegate.createSocket(host, port).apply { tune() }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort).apply { tune() }
        override fun createSocket(address: InetAddress?, port: Int): Socket =
            delegate.createSocket(address, port).apply { tune() }
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            delegate.createSocket(address, port, localAddress, localPort).apply { tune() }

        private fun Socket.tune() {
            try {
                setReceiveBufferSize(rcvBufBytes)
                setSendBufferSize(sndBufBytes)
            } catch (_: Exception) {
                // 部分平台忽略该设置，忽略即可
            }
        }
    }

    /**
     * 带 TTL 的 DNS 缓存。
     * - 命中且在 TTL 内直接返回，省去重复解析（B 站 CDN 同域名在会话内会反复命中）。
     * - 解析失败时若有过期缓存，返回旧值而非抛异常，避免一次 DNS 抖动就让播放卡死。
     */
    private class CachingDns(
        private val delegate: Dns = Dns.SYSTEM,
        private val ttlMillis: Long
    ) : Dns {
        private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            val cached = cache[hostname]
            if (cached != null && now - cached.first < ttlMillis) {
                return cached.second
            }
            return try {
                delegate.lookup(hostname).also { cache[hostname] = now to it }
            } catch (e: Exception) {
                // 解析失败（UnknownHostException 及 SecurityException / IllegalArgumentException 等）：
                // 若有过期缓存则返回旧值，把“连不上”交给连接层去重试/转成可处理的 PlaybackException；
                // 没有旧值才重新抛出。
                // 必须在非主线程（OkHttp 调度线程）安全失败——否则未捕获异常会经
                // KillApplicationHandler 直接 SIGKILL 整个进程（data_app_crash，且无主线程 FATAL EXCEPTION）。
                cached?.second ?: throw e
            }
        }
    }
}

/**
 * 按字节/秒限制读取速率的 OkHttp 拦截器。
 *
 * 包装响应正文（ResponseBody），在读取循环中按 [maxBytesPerSecond] 限速：每读一段就 sleep 掉
 * “应消耗时间 - 实际耗时”的差值。限速发生在预取的后台线程上，不会阻塞主线程；
 * 其目的是让暂停预取的带宽/磁盘 IO 占用可控，避免抢占正在播放的视频流与系统 IO，
 * 从而保障用户输入与播放流畅度。
 */
private class ThrottlingInterceptor(
    private val maxBytesPerSecond: Long
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        return response.newBuilder()
            .body(ThrottledResponseBody(body, maxBytesPerSecond))
            .build()
    }

    private class ThrottledResponseBody(
        private val delegate: ResponseBody,
        private val maxBytesPerSecond: Long
    ) : ResponseBody() {
        private var windowStartNanos = System.nanoTime()
        private var bytesReadInWindow = 0L

        override fun contentLength(): Long = delegate.contentLength()
        override fun contentType() = delegate.contentType()
        override fun source(): BufferedSource {
            val throttled = object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, byteCount)
                    if (read == -1L) return read
                    if (maxBytesPerSecond <= 0) return read
                    bytesReadInWindow += read
                    val now = System.nanoTime()
                    val elapsedNanos = now - windowStartNanos
                    // 期望消耗时间 = 已读字节 / 速率（纳秒）
                    val expectedNanos = bytesReadInWindow * 1_000_000_000L / maxBytesPerSecond
                    val sleepNanos = expectedNanos - elapsedNanos
                    if (sleepNanos > 0) {
                        // 限速：让出 IO，确保下载速率不超过上限，避免占满带宽/eMMC
                        try {
                            Thread.sleep(
                                sleepNanos / 1_000_000,
                                (sleepNanos % 1_000_000).toInt()
                            )
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        windowStartNanos = System.nanoTime()
                        bytesReadInWindow = 0L
                    } else if (elapsedNanos >= 1_000_000_000L) {
                        // 累计满 1s 也重置窗口，保持速率统计平滑
                        windowStartNanos = now
                        bytesReadInWindow = 0L
                    }
                    return read
                }
            }
            return throttled.buffer()
        }

        override fun close() = delegate.close()
    }
}
