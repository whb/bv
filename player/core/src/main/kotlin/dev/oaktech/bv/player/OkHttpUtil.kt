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

    private fun buildClient(context: Context): OkHttpClient {
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
                val certificateInputStream = context.assets.open(caFilename)
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
            .build()
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
