package com.cfip.picker.core

import com.cfip.picker.data.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 扫描调度器:复刻原版工作流
 *   拉数据 → 随机采样 IP → RTT 测延迟 → 下载测速 → 排序
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
     */
    suspend fun scan(
        samplePerRange: Int = 1,
        maxRanges: Int = 300,
        speedTestFile: String,
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        cancelled = false
        val results = mutableListOf<ScanResult>()

        // 1. 拉取 CF IPv4 段(IPv6 段数量大,默认只用 v4,保持与原版一致的速度)
        val ranges = ApiClient.getIpv4Ranges().take(maxRanges)
        val total = ranges.size
        var done = 0

        for (cidr in ranges) {
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

                results.add(
                    ScanResult(
                        ip = ip,
                        latencyMs = latency,
                        bandwidthMbps = bandwidth,
                        dataCenter = "?",
                        elapsedMs = System.currentTimeMillis(),
                    )
                )
            }
            done++
        }

        // 5. 排序:延迟优先,带宽次之
        results.sortedWith(
            compareBy<ScanResult> { it.latencyMs }
                .thenByDescending { it.bandwidthMbps }
        )
    }
}
