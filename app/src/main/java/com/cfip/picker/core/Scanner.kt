package com.cfip.picker.core

import com.cfip.picker.data.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扫描调度器:复刻原版工作流
 *   拉数据 → 随机采样 IP(v4+v6)→ RTT 测延迟 → 下载测速 → 机房反查 → 排序
 */
class Scanner(
    private val onProgress: (done: Int, total: Int, current: String) -> Unit,
) {
    private var cancelled = false

    fun cancel() { cancelled = true }

    /**
     * 执行一次完整扫描
     * @param samplePerRange 每个 CIDR 段采样几个 IP
     * @param maxRanges 最多用几个段
     * @param speedTestFile 测速文件路径(来自 /url 端点)
     * @param useIpv6 是否同时扫描 IPv6 段(原版支持 v4+v6 双栈)
     * @param locationsJson 机房位置数据(用于反查 IP 归属,可为空)
     */
    suspend fun scan(
        samplePerRange: Int = 1,
        maxRanges: Int = 300,
        speedTestFile: String,
        useIpv6: Boolean = true,
        locationsJson: String = "[]",
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        cancelled = false
        val results = mutableListOf<ScanResult>()
        val lookup = DataCenterLookup(locationsJson)

        // 1. 拉取 CF IPv4 + IPv6 段(与原版 getRandomIPv4s/getRandomIPv6s 一致)
        val ranges = mutableListOf<String>()
        ranges += ApiClient.getIpv4Ranges()
        if (useIpv6) {
            ranges += ApiClient.getIpv6Ranges()
        }
        val selected = ranges.take(maxRanges)
        val total = selected.size
        var done = 0

        for (cidr in selected) {
            if (cancelled) break

            // 2. 采样 IP
            val ips = CidrParser.randomIps(cidr, samplePerRange)
            for (ip in ips) {
                if (cancelled) break
                onProgress(done, total, ip)

                // 3. RTT 测延迟(443 端口)
                val latency = RttTester.test(ip, 443)
                if (latency < 0) continue // 连不上,跳过

                // 4. 下载测速(拼接完整测速 URL)
                val fullUrl = if (speedTestFile.startsWith("http")) speedTestFile
                             else "https://$speedTestFile"
                val bandwidth = SpeedTester.test(fullUrl)

                // 5. 机房反查(原版 lookupDataCenter,基于 locations.json 的 IP 归属)
                val dc = lookup.lookup(ip)

                results.add(
                    ScanResult(
                        ip = ip,
                        latencyMs = latency,
                        bandwidthMbps = bandwidth,
                        dataCenter = dc,
                        elapsedMs = System.currentTimeMillis(),
                    )
                )
            }
            done++
        }

        // 6. 排序:延迟优先,带宽次之(与原版一致)
        results.sortedWith(
            compareBy<ScanResult> { it.latencyMs }
                .thenByDescending { it.bandwidthMbps }
        )
    }
}
