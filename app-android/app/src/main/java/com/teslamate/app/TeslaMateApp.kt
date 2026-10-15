package com.teslamate.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 自定义 Application：集中初始化 Coil 的 ImageLoader。
 *
 * 关键点：Coil 默认会用自己的网络栈加载图片，但我们的服务器端是 Bearer Token 鉴权
 * （Tailscale 隧道 + 静态图端点都在 auth() 里要求 token）。
 * 通过共用 OkHttp 客户端 + Token 拦截器，所有 Coil 图片请求自动带 Authorization 头。
 *
 * Token 不持久化在拦截器里（应用启动时 DataStore 还没准备好），
 * 而是从 DataStore 流式 collect 后实时更新一个可写引用（@Volatile）。
 */
class TeslaMateApp : Application(), ImageLoaderFactory {

    @Volatile
    private var bearerToken: String = ""

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 异步加载 token；DataStore 一旦有值就刷到 bearerToken
        scope.launch {
            this@TeslaMateApp.settingsFlow().collect {
                bearerToken = it.token.trim()
            }
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request()
        val tok = bearerToken
        if (tok.isNotBlank() && req.header("Authorization") == null
            && req.url.host.endsWith("8090")) {
            chain.proceed(req.newBuilder().header("Authorization", "Bearer $tok").build())
        } else {
            chain.proceed(req)
        }
    }

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttp)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20) // 20% 内存
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img_cache"))
                    .maxSizeBytes(80L * 1024 * 1024) // 80 MB
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false) // 服务端 30 min cache 优先，客户端仍可缓存到过期为止
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
}
