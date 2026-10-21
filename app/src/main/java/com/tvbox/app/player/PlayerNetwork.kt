package com.tvbox.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.tvbox.app.data.AppImages
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 播放网络层：一个 OkHttpClient 贯穿 HLS/点播（与 t4 的 API 客户端分开，避免播放把 API 超时拖死）。
 *
 * 直播播放列表超时 8s / 连接 5s 对齐 assets/live_seed.json policy（playlist_timeout_ms/connect_timeout_ms）。
 */
object PlayerHttp {

    const val USER_AGENT = "TVBox/0.1.0 (Android; Media3/1.5.1)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    /**
     * 播放/EPG 专用客户端：复用 [AppImages.networkClient] 的“系统根 + 随包 ISRG Root X1/X2”信任链。
     *
     * Android 6（LeTV X4-50）系统根证书库没有 ISRG Root X1/X2，bfzy1 的 HLS CDN 多为 Let's Encrypt，
     * 直接用默认信任链会 SSLHandshakeException → 表现为「Source error」，真机必现。
     * 仅额外信任两张公开根证书，不放开任意证书。
     */
    fun playbackClient(context: android.content.Context): OkHttpClient = try {
        AppImages.networkClient(context.applicationContext)
            .newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    } catch (t: Throwable) {
        client
    }

    /** 频道焦点预热专用：8MB playlist 缓存（只缓存 m3u8 文本，不缓存分片）。 */
    @Volatile
    private var warm: OkHttpClient? = null

    fun warmClient(context: android.content.Context): OkHttpClient {
        warm?.let { return it }
        return synchronized(this) {
            warm ?: runCatching {
                val cache = okhttp3.Cache(java.io.File(context.cacheDir, "playlist_cache"), 8L * 1024 * 1024)
                playbackClient(context).newBuilder().cache(cache).build()
            }.getOrElse { playbackClient(context) }.also { warm = it }
        }
    }
}

/**
 * 每个 MediaUrl 可带自己的 headers（MacCMS 的播放地址常需要 Referer/UA）。
 * 工厂在 `play()` 前被写入快照，HLS 的 playlist 与每个分片请求都会继承。
 */
@UnstableApi
class HeaderInjectingDataSourceFactory(
    private val upstream: HttpDataSource.Factory,
) : DataSource.Factory {

    @Volatile
    private var headers: Map<String, String> = emptyMap()

    fun setHeaders(newHeaders: Map<String, String>) {
        headers = if (newHeaders.isEmpty()) emptyMap() else HashMap(newHeaders)
    }

    override fun createDataSource(): DataSource {
        val dataSource = upstream.createDataSource()
        val snapshot = headers
        if (snapshot.isNotEmpty()) {
            for ((key, value) in snapshot) {
                dataSource.setRequestProperty(key, value)
            }
        }
        return dataSource
    }
}

/**
 * HLS 优先的 MediaSource 工厂：
 * - 直播（LiveController 播放，mime 强制 m3u8）走 [HlsMediaSource] + `setAllowChunklessPreparation(true)`，
 *   省去逐段解析，起播更快、内存更低；
 * - 点播 mp4 等走 [DefaultMediaSourceFactory]。
 */
@UnstableApi
class HlsFirstMediaSourceFactory(
    dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory {

    private val hls: HlsMediaSource.Factory = HlsMediaSource.Factory(dataSourceFactory)
        .setAllowChunklessPreparation(true)

    private val progressive: DefaultMediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = mediaItem.localConfiguration
        val uri = config?.uri?.toString().orEmpty()
        val mime = config?.mimeType
        val isHls = mime == MimeTypes.APPLICATION_M3U8 ||
            uri.contains(".m3u8", ignoreCase = true)
        return if (isHls) hls.createMediaSource(mediaItem) else progressive.createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray {
        val a = hls.supportedTypes
        val b = progressive.supportedTypes
        return (a.toSet() + b.toSet()).toIntArray()
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        hls.setDrmSessionManagerProvider(provider)
        progressive.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        hls.setLoadErrorHandlingPolicy(policy)
        progressive.setLoadErrorHandlingPolicy(policy)
        return this
    }
}

/** 构造播放用的数据源工厂（OkHttp + UA，headers 由 [HeaderInjectingDataSourceFactory] 注入）。 */
@UnstableApi
fun createPlaybackDataSourceFactory(context: android.content.Context): HeaderInjectingDataSourceFactory {
    val okHttp = OkHttpDataSource.Factory(PlayerHttp.playbackClient(context))
        .setUserAgent(PlayerHttp.USER_AGENT)
    return HeaderInjectingDataSourceFactory(okHttp)
}
