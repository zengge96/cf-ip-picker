package com.cfip.picker.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 机房位置反查:原版 Go 用 IP 地理库精确反查 IP 归属机房。
 * 重写版用免费 IP 地理 API(ip-api.com,http 免费版)反查,
 * 再与 locations.json 的 301 个 CF 节点匹配,显示 "IATA - City, Region"。
 * 带内存缓存 + 简单限速,避免触发 API 限流。
 */
class DataCenterLookup(locationsJson: String) {

    data class Location(
        val iata: String, val lat: Double, val lon: Double,
        val cca2: String, val region: String, val city: String,
    ) {
        override fun toString(): String = "$iata - $city, $region"
    }

    private val locations: List<Location> = parse(locationsJson)
    private val cache = HashMap<String, String>()
    private var lastRequest = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun parse(json: String): List<Location> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o: JSONObject = arr.getJSONObject(i)
                Location(
                    iata = o.optString("iata", "?"),
                    lat = o.optDouble("lat", 0.0),
                    lon = o.optDouble("lon", 0.0),
                    cca2 = o.optString("cca2", ""),
                    region = o.optString("region", ""),
                    city = o.optString("city", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun size(): Int = locations.size

    /**
     * 反查 IP 归属机房。先查内存缓存,再调 ip-api.com,
     * 拿到城市后与 locations.json 匹配机场代码;匹配不到则返回 "city, region"。
     * 失败返回 "?"。限速 150ms/次。
     */
    fun lookup(ip: String): String {
        cache[ip]?.let { return it }

        val result = try {
            // 限速:两次请求至少间隔 150ms(ip-api 免费版限 45 req/min)
            val wait = 150 - (System.currentTimeMillis() - lastRequest)
            if (wait > 0) Thread.sleep(wait)
            lastRequest = System.currentTimeMillis()

            val request = Request.Builder()
                .url("http://ip-api.com/json/$ip?fields=status,countryCode,regionName,city")
                .header("User-Agent", "CFIP-Picker/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return "?"
                val j = JSONObject(resp.body?.string().orEmpty())
                if (j.optString("status") != "success") return "?"
                val city = j.optString("city", "")
                val region = j.optString("regionName", "")
                val cca2 = j.optString("countryCode", "")

                // 用城市/国家匹配 locations.json 里的 CF 节点
                val match = locations.firstOrNull {
                    it.city.equals(city, true) ||
                    (it.city.isNotEmpty() && city.contains(it.city, true)) ||
                    (it.cca2.equals(cca2, true) && it.city.isNotEmpty() && it.city.contains(city, true))
                }
                if (match != null) {
                    "${match.iata} - ${match.city}, ${match.region}"
                } else if (city.isNotEmpty()) {
                    "$city, $region"
                } else {
                    "?"
                }
            }
        } catch (e: Exception) {
            "?"
        }

        // 只缓存成功结果,避免缓存 "?"
        if (result != "?") cache[ip] = result
        return result
    }

    /** 返回全部位置(供 UI 选择/展示) */
    fun all(): List<Location> = locations
}
