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
     * @param httpTestUrl 联通性测试 URL(来自 /http_test_url 端点,可为空 = 跳过联通性测试)
     * @param expectedSpeedMbps 期望网速;>0 时达标即停,0 表示不限
     * @param maxBatches 最多批次(防死循环;原版理论上直到找到)
     * @param locationsJson 机房位置数据(用于反查 IP 归属,可为空)
     * @param ipPrefix 用户指定 IPv4 前缀(如 172.64.x.x);为空则保持原版全量随机逻辑
     * @param useBaiduProxy 是否通过百度前置代理测试(开启后 RTT/测速都走代理隧道)
     */
    suspend fun scan(
        batchSize: Int = 100,
        speedTestCandidates: Int = 10,
        speedTestFile: String,
        httpTestUrl: String = "",
        expectedSpeedMbps: Int = 0,
        maxBatches: Int = 20,
        locationsJson: String = "[]",
        ipPrefix: String = "",
        useBaiduProxy: Boolean = false,
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        cancelled = false
        val lookup = DataCenterLookup(locationsJson)
        val prefixFilter = IpPrefixFilter.parse(ipPrefix)

        // 1. 拉取全部 IPv4 段(只扫 IPv4,用户明确不要 IPv6)
        val allRanges = ApiClient.getIpv4Ranges()
        val fullUrl = if (speedTestFile.startsWith("http")) speedTestFile
                     else "https://$speedTestFile"

        // 2. 批次循环:每批随机采样 batchSize 个 IP → RTT → 前10测速 → 达标即停
        val results = mutableListOf<ScanResult>()
        var batchDone = 0

        batchLoop@ for (batch in 1..maxBatches) {
            if (cancelled) break

            // 2a. 对齐原版 getRandomIPv4s + randomSample:
            //     先遍历全部 /24 段,每段随机 1 个 IP;再对完整候选池整体随机采样 min(n,100)。
            //     注意:不是顺序取前100个 CIDR 段。
            //     增强:前缀过滤导致候选不足 batchSize 时(如 172.62.225.x 只命中 1 段),
            //     继续多轮生成并去重,直到选够 batchSize 个,最多 100 轮。
            val pool = LinkedHashSet<String>()
            var round = 0
            while (pool.size < batchSize && round < 100) {
                if (cancelled) break
                round++
                for (cidr in allRanges) {
                    if (cancelled || pool.size >= batchSize) break
                    val ip = if (prefixFilter.enabled) {
                        prefixFilter.randomIpFromCidr(cidr)
                    } else {
                        CidrParser.randomIps(cidr, 1).firstOrNull()
                    }
                    if (ip != null) pool.add(ip)
                }
            }
            val ips = pool.shuffled().take(batchSize)
            if (ips.isEmpty()) break

            val prefixText = if (prefixFilter.enabled) "前缀匹配" else "随机采样"
            onProgress(0, ips.size, "第${batch}批:${prefixText}${ips.size}个,并发测延迟…")

            // 2b. 【阶段一】并发 RTT 测延迟(原版 runRTTTest goroutine 并发;代理模式走百度隧道)
            //     每批 20 个并发,100 个分 5 批(避免一次 100 并发太猛)
            val rttResults = mutableListOf<Pair<String, Int>>()
            for (chunk in ips.chunked(20)) {
                if (cancelled) break
                val chunkResults = coroutineScope {
                    chunk.map { ip ->
                        async {
                            if (cancelled) null
                            else {
                                val latency = if (useBaiduProxy) RttTester.testViaBaiduProxy(ip, 443) else RttTester.test(ip, 443)
                                if (latency < 0) null else ip to latency
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                rttResults += chunkResults
                if (cancelled) break
            }
            // 按延迟升序排序
            val sorted = rttResults.sortedBy { it.second }
            if (sorted.isEmpty()) continue // 本批全挂,下一批

            // 2b+. 【阶段一.5】并发联通性测试(对齐新版:用 /http_test_url 的 URL,只有联通的才进测速)
            //     同样每批 20 个并发(100 个分 5 批)
            val reachable = if (httpTestUrl.isNotBlank()) {
                onProgress(0, sorted.size, "第${batch}批:联通性测试…")
                val reachableList = mutableListOf<Pair<String, Int>>()
                for (chunk in sorted.chunked(20)) {
                    if (cancelled) break
                    val chunkOk = coroutineScope {
                        chunk.map { (ip, latency) ->
                            async {
                                if (cancelled) null
                                else {
                                    val ok = if (useBaiduProxy) {
                                        ConnectivityTester.testViaBaiduProxy(ip, httpTestUrl)
                                    } else {
                                        ConnectivityTester.test(ip, httpTestUrl)
                                    }
                                    if (ok) ip to latency else null
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    reachableList += chunkOk
                    if (cancelled) break
                }
                reachableList.sortedBy { it.second }
            } else {
                sorted
            }
            if (reachable.isEmpty()) continue // 联通性全挂,下一批

            onProgress(0, reachable.size, "第${batch}批:联通性通过,单线程测速")

            // 2c. 【阶段二】单线程测速前 N 个(原版前10),达期望网速即停
            val candidates = reachable.take(speedTestCandidates)
            var tested = 0
            for ((ip, latency) in candidates) {
                if (cancelled) break@batchLoop
                tested++
                batchDone++
                onProgress(tested, candidates.size, ip)

                val speed = if (useBaiduProxy) {
                    SpeedTester.testViaBaiduProxy(ip, fullUrl, expectedMbps = expectedSpeedMbps)
                } else {
                    SpeedTester.test(ip, fullUrl, expectedMbps = expectedSpeedMbps)
                }
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

            // 2d. 本批没达标 → 重新随机生成下一批(原版外层循环)
        }

        // 3. 排序:延迟优先,带宽次之(与原版一致)
        results.sortedWith(
            compareBy<ScanResult> { it.latencyMs }
                .thenByDescending { it.bandwidthMbps }
        )
    }
}
