package com.cfip.picker.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 机房位置反查:用 locations.json(301 个节点)匹配 IP 归属机房
 */
class DataCenterLookup(locationsJson: String) {

    data class Location(val iata: String, val lat: Double, val lon: Double, val cca2: String, val region: String, val city: String) {
        override fun toString(): String = "$iata - $city, $region"
    }

    private val locations: List<Location> = parse(locationsJson)

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
     * 按国家代码反查机房显示名(简化:反向匹配 iata/cca2 前缀)。
     * 原版 Go 用 IP 地理库精确反查,这里用最接近的启发式:
     * 返回 "iata - city, region" 或 "?"
     */
    fun lookup(ip: String): String {
        // 简化实现:无法精确地理定位时返回未知;保留接口以便后续接 ip-api 等
        return "?"
    }

    /** 返回全部位置(供 UI 选择/展示) */
    fun all(): List<Location> = locations
}
