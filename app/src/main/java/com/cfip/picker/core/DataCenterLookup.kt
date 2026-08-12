package com.cfip.picker.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 机房反查:对齐原版 lookupDataCenter(已通过动态抓包确认)。
 * 原版机制:测速请求 CF IP 时,CF 响应头带 `cf-ray: xxxxxxxx-XXXX`(如 a29e...-KIX),
 * 其中 `-` 后 3 位大写字母就是【实际接入机房】代码。
 * extractDataCenter 用 ':' 找冒号解析响应头提取该代码 → lookupDataCenter 查 locations.json → 显示城市名。
 * CF IP 是 anycast,IP 注册地 ≠ 实际接入机房,不能用 ip-api 查归属地!
 */
class DataCenterLookup(locationsJson: String) {

    data class Location(
        val iata: String, val lat: Double, val lon: Double,
        val cca2: String, val region: String, val city: String,
    )

    private val locations: List<Location> = parse(locationsJson)
    private val cache = HashMap<String, String>()

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

    companion object {
        /**
         * 从 cf-ray 响应头提取机房代码(与原版 extractDataCenter 一致)。
         * 输入形如 "a29e45251da9fcb0-KIX",返回 "KIX";无法解析返回 null。
         */
        fun extractColoStatic(cfRayHeader: String?): String? {
            if (cfRayHeader.isNullOrBlank()) return null
            // cf-ray 格式: <16位hex>-<3位机场代码>,取最后一个 '-' 后 2-5 位大写字母
            val idx = cfRayHeader.lastIndexOf('-')
            if (idx < 0 || idx == cfRayHeader.length - 1) return null
            val tail = cfRayHeader.substring(idx + 1).trim()
            // 只接受 2~5 位大写字母(如 KIX/HKG/NRT/LAX/JFK)
            if (tail.isEmpty() || !tail.all { it in 'A'..'Z' } || tail.length > 5) return null
            return tail
        }
    }

    fun extractColo(cfRayHeader: String?): String? = extractColoStatic(cfRayHeader)

    /**
     * 用机房代码(如 "KIX")查 locations.json,返回城市名(与原版 history 记录 "Osaka" 一致)。
     * 找不到返回代码本身,失败返回 "?"。
     */
    fun lookup(colo: String?): String {
        if (colo.isNullOrBlank()) return "?"
        cache[colo]?.let { return it }
        val match = locations.firstOrNull { it.iata == colo }
        val result = if (match != null) match.city else colo
        cache[colo] = result
        return result
    }

    /** 返回全部位置(供 UI 选择/展示) */
    fun all(): List<Location> = locations
}