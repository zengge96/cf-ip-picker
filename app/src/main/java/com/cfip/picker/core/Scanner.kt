package com.cfip.picker.core

import com.cfip.picker.data.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 扫描调度器:复刻原版批次循环工作流
 *   随机采样(每批≤100) → 【并发 RTT 测延迟】 → 排序 → 【取前10测速】
 *   → 有 IP 达期望网速(maxSpeed)即停 → 没达标下一批100循环,直到找到或 IP 池耗尽
 * 对应原版: getRandomIPv4s(randomSample) → runRTTTest(goroutine 并发) → runSpeedTestSimple(单线程, maxSpeed 达标即停)
 */
class Scanner(
    private val onProgress: (done: Int, total: Int, current: String) -> Unit,
) {
    private var cancelled = false

    fun cancel() { cancelled = true }

    /**
     * 执行完整扫描(批次循环,对齐原版)
     * @param batchSize 每批随机采样 IP 数(原版上限 100)
     * @param speedTestCandidates 延迟排序后参与测速的数量(原版前 10)
     * @param speedTestFile 测速文件路径(来自 /url 端点)
     * @param expectedSpeedMbps 期望网速;>0 时达标即停,0 表示不限
     * @param maxBatches 最多批次(防死循环;原版理论上直到找到)
     * @param locationsJson 机房位置数据(用于反查 IP 归属,可为空)
     */
    suspend fun scan(
        batchSize: Int = 100,
        speedTestCandidates: Int = 10,
        speedTestFile: String,
        expectedSpeedMbps: Int = 0,
        maxBatches: Int = 20,
        locationsJson: String = "[]",
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        cancelled = false
        val lookup = DataCenterLookup(locationsJson)

        // 1. 拉取全部 IPv4 段(只扫 IPv4,用户明确不要 IPv6)
        val allRanges = ApiClient.getIpv4Ranges()
        val fullUrl = if (speedTestFile.startsWith("http")) speedTestFile
                     else "https://$speedTestFile"

        // 2. 批次循环:每批随机采样 batchSize 个 IP → RTT → 前10测速 → 达标即停
        val results = mutableListOf<ScanResult>()
        var rangeIndex = 0
        var batchDone = 0

        batchLoop@ for (batch in 1..maxBatches) {
            if (cancelled) break

            // 2a. 随机采样本批 IP(从剩余段里随机取,对齐原版 randomSample)
            val ips = mutableListOf<String>()
            while (ips.size < batchSize && rangeIndex < allRanges.size) {
                if (cancelled) break@batchLoop
                // 随机挑一个未用的段(简单顺序取段+段内随机IP;原版是全局随机采样)
                val cidr = allRanges[rangeIndex]
                rangeIndex++
                val sampled = CidrParser.randomIps(cidr, 1)
                if (sampled.isNotEmpty()) ips.addAll(sampled)
            }
            if (ips.isEmpty()) break // IP 池耗尽

            onProgress(0, ips.size, "第${batch}批:并发测延迟…")

            // 2b. 【阶段一】并发 RTT 测延迟(原版 runRTTTest goroutine 并发)
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
            if (sorted.isEmpty()) continue // 本批全挂,下一批

            onProgress(0, sorted.size, "第${batch}批:延迟排序完成,测速")

            // 2c. 【阶段二】单线程测速前 N 个(原版前10),达期望网速即停
            val candidates = sorted.take(speedTestCandidates)
            var tested = 0
            for ((ip, latency) in candidates) {
                if (cancelled) break@batchLoop
                tested++
                batchDone++
                onProgress(tested, candidates.size, ip)

                val speed = SpeedTester.test(ip, fullUrl, expectedMbps = expectedSpeedMbps)
                val dc = lookup.lookup(speed.colo)
                results.add(
                    ScanResult(
                        ip = ip,
                        latencyMs = latency,
                        bandwidthMbps = speed.bandwidthMbps,
                        dataCenter = dc,
                        elapsedMs = System.currentTimeMillis(),
                    )
                )
                // 达到期望网速 → 立即停止整个扫描(原版 maxSpeed 达标即停)
                if (expectedSpeedMbps > 0 && speed.bandwidthMbps >= expectedSpeedMbps) {
                    break@batchLoop
                }
            }

            // 2d. 本批没达标 → 继续下一批(循环)
            if (rangeIndex >= allRanges.size) break // IP 池耗尽
        }

        // 3. 排序:延迟优先,带宽次之(与原版一致)
        results.sortedWith(
            compareBy<ScanResult> { it.latencyMs }
                .thenByDescending { it.bandwidthMbps }
        )
    }
}
