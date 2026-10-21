package com.tvbox.app.data

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Coil 图片管线（docs/TOOLCHAIN.md 冻结参数）：
 *  - 复用共享 OkHttp（连接池 / 超时 / UA）；
 *  - memoryCache ≤24MB、diskCache ≤128MB、独立 image_cache 目录；
 *  - 不做 crossfade（低端机避免额外重绘）；尺寸由请求方固定 320×450。
 *
 * 兼容性补丁：真机 LeTV X4-50 = Android 6（API 23），系统根证书库缺少 ISRG Root X1/X2，
 * 访问 Let's Encrypt 图片 CDN 会报 `Trust anchor for certification path not found`。
 * 这里在系统信任之外**额外**信任随包附带的 ISRG 公信根（仅两张公开根证书，不放开任意证书）。
 */
object AppImages {

    @Volatile
    private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader {
        loader?.let { return it }
        return synchronized(this) {
            loader ?: build(context.applicationContext).also { loader = it }
        }
    }

    fun networkClient(context: Context): okhttp3.OkHttpClient {
        val trustManager = compositeTrustManager(context.applicationContext)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), SecureRandom())
        }
        return Http.client.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    private fun build(context: Context): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(networkClient(context))
        .respectCacheHeaders(false)
        .crossfade(false)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizeBytes(24 * 1024 * 1024)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache"))
                .maxSizeBytes(128L * 1024 * 1024)
                .build()
        }
        .build()

    private fun compositeTrustManager(context: Context): X509TrustManager {
        val system = trustManager(null)
        val extra = trustManager(bundledRoots(context))
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                system.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (systemFailure: CertificateException) {
                    // Android 6 缺 ISRG 根：用随包根证书重新校验证书链
                    extra.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                system.acceptedIssuers + extra.acceptedIssuers
        }
    }

    private fun trustManager(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun bundledRoots(context: Context): KeyStore {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        val certificateFactory = CertificateFactory.getInstance("X.509")
        var index = 0
        for (asset in listOf("isrgrootx1.pem", "isrgrootx2.pem")) {
            runCatching {
                context.assets.open(asset).use { input ->
                    certificateFactory.generateCertificates(input).forEach { certificate ->
                        keyStore.setCertificateEntry("extra-root-${index++}", certificate)
                    }
                }
            }
        }
        return keyStore
    }
}
