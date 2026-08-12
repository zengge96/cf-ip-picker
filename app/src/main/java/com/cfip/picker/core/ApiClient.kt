package com.cfip.picker.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 数据源客户端:拉取 CF IP 段、机房位置、测速文件地址
 * (逆向自原版 APK 的 4 个端点,无鉴权)
 */
object ApiClient {
    private const val BASE = "https://www.baipiao.eu.org/cloudflare"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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

    /** CF 全部 IPv4 段(每行一个 CIDR,如 1.0.0.0/24) */
    fun getIpv4Ranges(): List<String> =
        get("ips-v4").lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** CF 全部 IPv6 段 */
    fun getIpv6Ranges(): List<String> =
        get("ips-v6").lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** 机房位置列表(JSON) */
    fun getLocations(): String = get("locations")

    /** 测速下载文件相对路径(如 cloudflaremirrors.com/oracle/...iso) */
    fun getSpeedTestUrl(): String = get("url").trim()
}
