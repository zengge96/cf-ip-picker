package com.cfip.picker.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 数据源客户端:拉取 CF IP 段、机房位置、测速文件地址
 * (逆向自原版 APK 的 4 个端点,无鉴权)
 * 与原版一致:downloadAllData 拉取后缓存到本地,减少重复请求
 */
object ApiClient {
    private const val BASE = "https://pan.1996999.xyz"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L  // 缓存 6 小时

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 缓存目录(由 App 初始化时设置,对应原版 setCacheDir)
    @Volatile
    var cacheDir: File? = null

    private fun get(path: String): String {
        val request = Request.Builder()
            .url("$BASE/$path")
            .header("User-Agent", "CFIP-Picker/1.0")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    /** 带缓存读取:优先用本地缓存,过期则重新拉取 */
    private fun getCached(path: String): String {
        val dir = cacheDir ?: return get(path)
        val file = File(dir, path.replace("/", "_"))
        val now = System.currentTimeMillis()
        if (file.exists() && now - file.lastModified() < CACHE_TTL_MS) {
            return file.readText()
        }
        return try {
            val data = get(path)
            file.writeText(data)
            data
        } catch (e: Exception) {
            // 拉取失败但有旧缓存,退回旧缓存(对应原版 getFileContent 兜底)
            if (file.exists()) file.readText() else throw e
        }
    }

    /** 清空数据缓存(对应原版 clearCache) */
    fun clearCache() {
        cacheDir?.listFiles()?.forEach { it.delete() }
    }

    /** CF 全部 IPv4 段(每行一个 CIDR,如 1.0.0.0/24) */
    fun getIpv4Ranges(): List<String> =
        getCached("ips-v4").lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** CF 全部 IPv6 段 */
    fun getIpv6Ranges(): List<String> =
        getCached("ips-v6").lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** 机房位置列表(JSON,301 个节点) */
    fun getLocations(): String = getCached("locations")

    /** 测速下载文件相对路径(如 cloudflaremirrors.com/oracle/...iso) */
    fun getSpeedTestUrl(): String = getCached("url").trim()
}

/** 供 Application 初始化缓存目录 */
fun initApiCache(context: Context) {
    ApiClient.cacheDir = File(context.cacheDir, "cfip_data").apply { mkdirs() }
}
