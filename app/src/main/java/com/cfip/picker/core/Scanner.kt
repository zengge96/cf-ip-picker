package com.cfip.picker.core

import com.cfip.picker.data.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 扫描调度器:复刻原版工作流
 *   拉数据 → 随机采样 IPv4 → 【并发 RTT 测延迟】 → 按延迟排序 → 【单线程测速(达期望网速即停)】
 * 对应原版: getRandomIPv4s → runRTTTest(goroutine 并发) → runSpeedTestSimple(单线程, maxSpeed 达标即停)
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
     * @param expectedSpeedMbps 期望网速;>0 时达到即停,0 表示不限
     * @param maxSpeedTestCandidates 延迟排序后参与测速的最大 IP 数(原版只对较优的一批测速)
     * @param locationsJson 机房位置数据(用于反查 IP 归属,可为空)
     */
    suspend fun scan(
        samplePerRange: Int = 1,
        maxRanges: Int = 300,
        speedTestFile: String,
        expectedSpeedMbps: Int = 0,
        maxSpeedTestCandidates: Int = 15,
        locationsJson: String = "[]",
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        cancelled = false
        val lookup = DataCenterLookup(locationsJson)

        // 1. 只拉取 IPv4 段(用户明确:不要 IPv6)
        val ranges = ApiClient.getIpv4Ranges()
        val selected = ranges.take(maxRanges)
        val total = selected.size
        var done = 0

        // 2. 采样 IP(与原版 randomSample 一致:每段随机取 1 个)
        val ips = mutableListOf<String>()
        for (cidr in selected) {
            if (cancelled) break
            ips += CidrParser.randomIps(cidr, samplePerRange)
        }
        onProgress(0, ips.size, "并发测延迟中…")

        // 3. 【阶段一】并发 RTT 测延迟(原版 runRTTTest 是 goroutine 并发)
        val rttResults = coroutineScope {
            ips.map { ip ->
                async {
                    if (cancelled) null
                    else {
                        val latency = RttTester.test(ip, 443)
                        if (latency < 0) null else ip to latency
                    }
                }
            }.awaitAll().filterNotNull()
        }
        // 按延迟升序排序
        val sorted = rttResults.sortedBy { it.second }
        onProgress(0, sorted.size, "延迟排序完成,开始测速")

        // 4. 【阶段二】单线程测速(原版 runSpeedTestSimple),达到期望网速即停
        val results = mutableListOf<ScanResult>()
        val fullUrl = if (speedTestFile.startsWith("http")) speedTestFile
                     else "https://$speedTestFile"

        val candidates = sorted.take(maxSpeedTestCandidates)
        var tested = 0
        for ((ip, latency) in candidates) {
            if (cancelled) break
            tested++
            onProgress(tested, candidates.size, ip)

            val bandwidth = SpeedTester.test(fullUrl, expectedMbps = expectedSpeedMbps)
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
            // 达到期望网速 → 立即停止(原版 maxSpeed 达标即停)
            if (expectedSpeedMbps > 0 && bandwidth >= expectedSpeedMbps) break
        }

        // 5. 排序:延迟优先,带宽次之(与原版一致)
        results.sortedWith(
            compareBy<ScanResult> { it.latencyMs }
                .thenByDescending { it.bandwidthMbps }
        )
    }
}
