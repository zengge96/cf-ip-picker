package com.cfip.picker.core

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** 测速结果:带宽 + 从 cf-ray 响应头提取的机房代码(对齐原版 extractDataCenter) */
data class SpeedTestResult(
    val bandwidthMbps: Double,
    val colo: String?,
)

/**
 * 下载测速:从 CF 镜像站下载文件,按下载量/耗时估算带宽
 * 与原版 runSpeedTestSimple 一致:单线程下载,达到期望网速(maxSpeed)即提前停止。
 *
 * 原版关键点:测速请求必须连到当前候选 IP,并从响应头 cf-ray(如 "a29e...-KIX")提取实际接入机房。
 * 这里用 OkHttp 自定义 DNS:URL 仍保持 cloudflaremirrors.com(保证 SNI/Host 正确),但 DNS 解析到候选 IP。
 */
object SpeedTester {

    /**
     * 测速:下载 [url] 最多 [maxDurationMs] 毫秒
     * @param ip 当前候选 CF IP,用于自定义 DNS 指向
     * @param expectedMbps 期望网速;>0 时达到即停(原版 maxSpeed 达标即停)
     * @return 带宽 Mbps + 机房代码(失败返回 0.0 + null)
     */
    fun test(ip: String, url: String, maxDurationMs: Long = 3000, expectedMbps: Int = 0): SpeedTestResult {
        return try {
            val host = url.toHttpUrl().host
            val client = OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return if (hostname.equals(host, ignoreCase = true)) {
                            listOf(InetAddress.getByName(ip))
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    }
                })
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CFIP-Picker/1.0")
                .header("Range", "bytes=0-") // 拉流式下载即可
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return SpeedTestResult(0.0, null)
                // 读 cf-ray 响应头,提取机房代码(与原版 extractDataCenter 一致)
                val colo = DataCenterLookup.extractColoStatic(resp.header("cf-ray"))
                val body = resp.body ?: return SpeedTestResult(0.0, colo)
                val start = System.currentTimeMillis()
                var bytes = 0L
                val buffer = ByteArray(64 * 1024)
                val input = body.byteStream()
                // 每 256KB 估算一次带宽,达到期望网速立即停止
                var chunk = 0L
                while (System.currentTimeMillis() - start < maxDurationMs) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    chunk += n
                    if (chunk >= 256 * 1024) {
                        val elapsedSec = (System.currentTimeMillis() - start) / 1000.0
                        if (elapsedSec > 0) {
                            val mbps = (bytes * 8.0) / elapsedSec / 1_000_000.0
                            if (expectedMbps > 0 && mbps >= expectedMbps) {
                                input.close()
                                return SpeedTestResult(mbps, colo)
                            }
                        }
                        chunk = 0
                    }
                }
                input.close()
                val elapsedSec = (System.currentTimeMillis() - start) / 1000.0
                val mbps = if (elapsedSec <= 0) 0.0 else (bytes * 8.0) / elapsedSec / 1_000_000.0
                SpeedTestResult(mbps, colo)
            }
        } catch (e: Exception) {
            SpeedTestResult(0.0, null)
        }
    }
}
