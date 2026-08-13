package com.cfip.picker.data

/**
 * CF优选IP 数据模型
 */

/** 单个 IP 的扫描结果 */
data class ScanResult(
    val ip: String,          // IP 地址
    val latencyMs: Int = -1, // RTT 延迟(毫秒),-1 = 失败
    val bandwidthMbps: Double = 0.0, // 测速带宽(Mbps)
    val dataCenter: String = "?",    // 机房位置(如 HKG - Hong Kong)
    val elapsedMs: Long = 0,         // 单次扫描耗时
)

/** 数据源常量(逆向自原版 APK) */
object Api {
    const val BASE = "https://xiaoyahelper.zngle.cf"
    const val IPS_V4 = "$BASE/ips-v4"       // CF IPv4 段
    const val IPS_V6 = "$BASE/ips-v6"       // CF IPv6 段
    const val LOCATIONS = "$BASE/locations" // 301 个机房坐标
    const val URL = "$BASE/url"             // 测速下载文件路径
}
