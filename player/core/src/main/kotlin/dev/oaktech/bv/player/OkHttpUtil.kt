package dev.oaktech.bv.player

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
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
}
