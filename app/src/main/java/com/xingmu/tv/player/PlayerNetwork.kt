package com.xingmu.tv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.data.AppImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit

/**
 * 播放网络层：一个 OkHttpClient 贯穿 HLS/点播（与 t4 的 API 客户端分开，避免播放把 API 超时拖死）。
 *
 * 直播播放列表超时 8s / 连接 5s 对齐 assets/live_seed.json policy（playlist_timeout_ms/connect_timeout_ms）。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object PlayerHttp {

    const val USER_AGENT = "TVBox/0.1.0 (Android; Media3/1.5.1)"

    /** VOD 预热首片字节数：720p 约 3–5s/片，4MB 覆盖绝大多数首片，命中后起播即解码。 */
    private const val SEGMENT_PREFETCH_BYTES = 4 * 1024 * 1024

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
    fun playbackClient(context: android.content.Context): OkHttpClient {
        playback?.let { return it }
        return synchronized(this) {
            playback ?: try {
                AppImages.networkClient(context.applicationContext)
                    .newBuilder()
                    .dns(DnsCache)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .writeTimeout(8, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .build()
            } catch (t: Throwable) {
                client
            }.also { playback = it }
        }
    }

    /** 播放器客户端单例：必须复用同一个 ConnectionPool，否则切台预连接形同虚设。 */
    @Volatile
    private var playback: OkHttpClient? = null

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

    // ------------------------------------------------------------------
    // 切台预连接（PERF_ROADMAP P0-2）：DNS 解析缓存 + TLS 预握手
    // ------------------------------------------------------------------

    /**
     * DNS 解析缓存：Android 6 的 InetAddress 缓存很小且不可控，切台时 DNS 常占 20–120ms。
     * 这里做 10 分钟进程级缓存，并给 [preconnect] 一个「提前解析」入口。
     */
    object DnsCache : okhttp3.Dns {
        private const val TTL_MS = 10 * 60 * 1000L
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<java.net.InetAddress>>>()

        override fun lookup(hostname: String): List<java.net.InetAddress> {
            cache[hostname]?.let { (at, ips) ->
                if (System.currentTimeMillis() - at <= TTL_MS) return ips
            }
            val ips = okhttp3.Dns.SYSTEM.lookup(hostname)
            if (ips.isNotEmpty()) cache[hostname] = System.currentTimeMillis() to ips
            return ips
        }

        fun warm(host: String): List<java.net.InetAddress> =
            runCatching { lookup(host) }.getOrDefault(emptyList())
    }

    /**
     * 预连接：对目标 URL 做 DNS + TCP + TLS 预握手，连接留在 [playbackClient] 的
     * ConnectionPool 里，切台时 OkHttp 直接复用（省掉 DNS + 2×RTT 握手）。
     *
     * 先用 HEAD（无 body，连接可回收）；CDN 若拒绝 HEAD 则退回 `Range: bytes=0-0` 的 GET
     * 并读完 1 字节，保证连接真被放回池子。返回耗时（ms），失败返回 -1。
     */
    fun preconnect(context: android.content.Context, url: String): Long {
        val host = hostOf(url)
        if (host == "-") return -1L
        val started = System.currentTimeMillis()
        return runCatching {
            runCatching { DnsCache.warm(host) }
            val client = playbackClient(context)
            val pooled = runCatching {
                val head = okhttp3.Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(head).execute().use { resp -> resp.isSuccessful || resp.code in 200..399 }
            }.getOrDefault(false)
            if (!pooled) {
                runCatching {
                    val get = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Range", "bytes=0-0")
                        .build()
                    client.newCall(get).execute().use { resp ->
                        resp.body?.byteStream()?.let { stream ->
                            stream.read(ByteArray(64))
                        }
                    }
                }
            }
            (System.currentTimeMillis() - started).coerceAtLeast(0L)
        }.getOrElse { -1L }
    }

    // ------------------------------------------------------------------
    // 预热缓存（Media3 SimpleCache，播放与预取共用）
    // ------------------------------------------------------------------

    @Volatile
    private var mediaCache: SimpleCache? = null

    /** 已成功预热的 URL（playlist/子 playlist/分片），用于 PLAYER_EVT cached=1/0。minSdk21 不能用 newKeySet。 */
    private val warmedUrls: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())

    fun wasWarmed(url: String?): Boolean = url != null && warmedUrls.contains(url)

    fun hostOf(url: String?): String =
        url?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "-"

    /** 32MB LRU：缓存 m3u8 文本与首片前缀，切台命中缓存直接起播。 */
    fun cache(context: Context): SimpleCache = synchronized(this) {
        mediaCache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "player_media_cache"),
            LeastRecentlyUsedCacheEvictor(32L * 1024 * 1024),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { mediaCache = it }
    }

    /**
     * 焦点预热：拉 playlist（≤256KB）写入 SimpleCache，再预取首个分片前 64KB（Range）。
     * 调用方在 Dispatchers.IO。
     */
    suspend fun prewarm(
        context: Context,
        playlistUrl: String,
        fetchSegment: Boolean = true,
        fromStart: Boolean = false,
    ): Unit =
        withContext(Dispatchers.IO) {
        val cache = runCatching { cache(context) }.getOrNull() ?: return@withContext
        // 先快速缓存 playlist 头（2KB），让切播放时 cached=1；完整 playlist/分片继续后台预取。
        runCatching { cachePlaylistHead(context, cache, playlistUrl) }
        val headerFactory = HeaderInjectingDataSourceFactory(
            OkHttpDataSource.Factory(playbackClient(context)).setUserAgent(USER_AGENT),
        )
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(headerFactory)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        try {
            // 强制 refresh playlist（否则会读到旧缓存，分片号已从 CDN 淘汰 → 404）
            val fresh = mapOf("Cache-Control" to "no-cache")
            val playlistBytes = readWithCache(factory, playlistUrl, null, 256 * 1024, fresh)
            if (playlistBytes == null || playlistBytes.isEmpty()) return@withContext
            var text = String(playlistBytes, Charsets.UTF_8)
            var baseUrl = playlistUrl
            // master playlist：先缓存被选中的子 playlist，再预取它的最新分片
            if (text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val variant = firstSegmentUrl(text, playlistUrl) ?: return@withContext
                val variantBytes = readWithCache(factory, variant, null, 256 * 1024, fresh)
                if (variantBytes != null && variantBytes.isNotEmpty()) {
                    text = String(variantBytes, Charsets.UTF_8)
                    baseUrl = variant
                    warmedUrls.add(variant)
                    TvLog.d("Prewarm", "variant playlist cached: $variant")
                }
            }
            if (fetchSegment) {
                // VOD 从第 0 片起播：必须预取**首片**（预取最新片对点播毫无作用，t22 实测首帧 5.4s）。
                // 直播/频道才用 lastSegment（live edge）。
                val target = if (fromStart) firstSegmentUrl(text, baseUrl) else lastSegmentUrl(text, baseUrl)
                if (target != null) {
                    val cap = if (fromStart) SEGMENT_PREFETCH_BYTES else 64 * 1024
                    readWithCache(factory, target, "bytes=0-${cap - 1}", cap)
                    warmedUrls.add(target)
                    TvLog.d("Prewarm", "segment prefetch ${cap / 1024}KB (fromStart=$fromStart): $target")
                }
            }
            warmedUrls.add(playlistUrl)
            TvLog.i("Prewarm", "playlist cached (${playlistBytes.size}B): $playlistUrl")
        } catch (t: Throwable) {
            TvLog.w("Prewarm", "failed: ${t.message}")
        }
    }

    /**
     * 只抓 playlist 前 2KB 并手工写入 SimpleCache（startFile/commitFile），用于快速命中
     * `cached=1`；剩余字节由播放时 CacheDataSource 自动补洞。
     */
    private fun cachePlaylistHead(context: Context, cache: SimpleCache, url: String): Boolean {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")
            .build()
        playbackClient(context).newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val stream = body.byteStream()
            val bytes = ByteArray(2048)
            var total = 0
            while (total < bytes.size) {
                val read = stream.read(bytes, total, bytes.size - total)
                if (read <= 0) break
                total += read
            }
            if (total <= 0) return false
            val file = cache.startFile(url, 0L, total.toLong())
            java.io.FileOutputStream(file).use { it.write(bytes, 0, total) }
            cache.commitFile(file, total.toLong())
            warmedUrls.add(url)
            TvLog.i("Prewarm", "playlist head cached ${total}B: $url")
            return true
        }
    }

    private fun readWithCache(
        factory: CacheDataSource.Factory,
        url: String,
        range: String?,
        maxBytes: Int,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray? {
        val dataSource = factory.createDataSource()
        val builder = DataSpec.Builder().setUri(url).setPosition(0)
        val allHeaders = HashMap<String, String>()
        allHeaders.putAll(headers)
        if (range != null) {
            builder.setLength(maxBytes.toLong())
            allHeaders["Range"] = range
        }
        if (allHeaders.isNotEmpty()) {
            builder.setHttpRequestHeaders(allHeaders)
        }
        val spec = builder.build()
        return try {
            dataSource.open(spec)
            val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < maxBytes) {
                val read = dataSource.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (read == -1) break
                out.write(buffer, 0, read)
                total += read
            }
            out.toByteArray()
        } catch (t: Throwable) {
            TvLog.w("Prewarm", "read failed ${url.take(80)}: ${t.message}")
            null
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun firstSegmentUrl(playlist: String, playlistUrl: String): String? {
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            return runCatching {
                java.net.URI(playlistUrl).resolve(line).toString()
            }.getOrDefault(line)
        }
        return null
    }

    /**
     * live media playlist 按时间顺序排列，**最后一条**才是 live edge（首条常已从 CDN 淘汰）。
     * 预取最新分片才能让切台命中缓存。
     */
    private fun lastSegmentUrl(playlist: String, playlistUrl: String): String? {
        var last: String? = null
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            last = line
        }
        val line = last ?: return null
        return runCatching { java.net.URI(playlistUrl).resolve(line).toString() }.getOrDefault(line)
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

/**
 * 播放数据源：OkHttp（headers 注入）→ Media3 SimpleCache（32MB LRU）→ ExoPlayer。
 * 预取的 playlist / 首片前缀与播放共用同一 SimpleCache，切台可命中。
 */
@UnstableApi
class PlaybackDataSourceFactory(context: Context) : DataSource.Factory {

    private val headerFactory = HeaderInjectingDataSourceFactory(
        OkHttpDataSource.Factory(PlayerHttp.playbackClient(context)).setUserAgent(PlayerHttp.USER_AGENT),
    )

    private val cacheFactory: CacheDataSource.Factory = run {
        val cache = runCatching { PlayerHttp.cache(context) }.getOrNull()
        if (cache == null) {
            CacheDataSource.Factory().setUpstreamDataSourceFactory(headerFactory)
        } else {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(headerFactory)
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
    }

    fun setHeaders(headers: Map<String, String>) {
        headerFactory.setHeaders(headers)
    }

    override fun createDataSource(): DataSource = cacheFactory.createDataSource()
}

/** 构造播放用的数据源工厂（OkHttp + UA + 预热缓存，headers 由 [HeaderInjectingDataSourceFactory] 注入）。 */
@UnstableApi
fun createPlaybackDataSourceFactory(context: Context): PlaybackDataSourceFactory =
    PlaybackDataSourceFactory(context)
