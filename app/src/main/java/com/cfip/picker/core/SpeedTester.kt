package com.cfip.picker.core

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** 测速结果:带宽 + 从 cf-ray 响应头提取的机房代码(对齐原版 extractDataCenter) */
data class SpeedTestResult(
    val bandwidthMbps: Double,
    val colo: String?,
)

/**
 * 下载测速:从 CF 镜像站下载文件,按下载量/耗时估算带宽
 * 与原版 runSpeedTestSimple 一致:单线程下载,达到期望网速(maxSpeed)即提前停止。
 *
 * 两种模式:
 *  - 直连:URL 保持 cloudflaremirrors.com(保证 SNI/Host 正确),DNS 解析到候选 IP。
 *  - 百度前置代理:先 CONNECT 隧道到候选 IP:443,隧道内 TLS(SNI 用 URL host)+ HTTP 测速。
 */
object SpeedTester {

    /**
     * 直连测速:下载 [url] 最多 [maxDurationMs] 毫秒
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

    /**
     * 通过百度前置代理测速:
     *  1) BaiduProxy.connect 建立 CONNECT 隧道到候选 IP:443
     *  2) 隧道上做 TLS 握手,SNI 用 URL 的 host(保证 CF 正确路由)
     *  3) 发 HTTP GET 下载,逐块测速,达期望即停
     * @param ip 当前候选 CF IP(隧道目标)
     * @param expectedMbps 期望网速;>0 时达到即停
     */
    fun testViaBaiduProxy(ip: String, url: String, maxDurationMs: Long = 3000, expectedMbps: Int = 0): SpeedTestResult {
        var raw: java.net.Socket? = null
        var ssl: SSLSocket? = null
        try {
            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val path = httpUrl.encodedPath + (if (httpUrl.encodedQuery != null) "?${httpUrl.encodedQuery}" else "")

            // 1. 百度 CONNECT 隧道到候选 IP:443
            raw = BaiduProxy.connect(ip, 443, 8000)

            // 2. TLS 握手(SNI = URL host,证书校验 host)
            val sslFactory = SSLContext.getDefault().socketFactory
            ssl = sslFactory.createSocket(raw, host, 443, true) as SSLSocket
            ssl.soTimeout = (maxDurationMs + 2000).toInt()
            ssl.startHandshake()

            // 3. HTTP GET(带 Range 拉流式)
            val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: CFIP-Picker/1.0\r\n" +
                    "Range: bytes=0-\r\n" +
                    "Connection: close\r\n\r\n"
            ssl.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            ssl.getOutputStream().flush()

            // 读响应头(找 cf-ray)
            val input = ssl.getInputStream()
            val headerBuf = java.io.ByteArrayOutputStream()
            val scout = ByteArray(512)
            var headerEnd = -1
            var colo: String? = null
            while (headerEnd < 0) {
                val n = input.read(scout)
                if (n < 0) break
                headerBuf.write(scout, 0, n)
                val head = headerBuf.toString(Charsets.ISO_8859_1)
                val idx = head.indexOf("\r\n\r\n")
                if (idx >= 0) {
                    headerEnd = idx + 4
                    // 从响应头提取 cf-ray
                    head.substring(0, idx).lineSequence().forEach { line ->
                        if (line.startsWith("cf-ray:", ignoreCase = true)) {
                            colo = DataCenterLookup.extractColoStatic(line.substringAfter(":").trim())
                        }
                    }
                    break
                }
                if (headerBuf.size() > 65536) break
            }
            if (headerEnd < 0) return SpeedTestResult(0.0, colo)
            // 响应头里可能已带部分 body 字节,忽略(只影响极少量)

            // 4. 下载测速
            val start = System.currentTimeMillis()
            var bytes = 0L
            val buffer = ByteArray(64 * 1024)
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
                            return SpeedTestResult(mbps, colo)
                        }
                    }
                    chunk = 0
                }
            }
            val elapsedSec = (System.currentTimeMillis() - start) / 1000.0
            val mbps = if (elapsedSec <= 0) 0.0 else (bytes * 8.0) / elapsedSec / 1_000_000.0
            return SpeedTestResult(mbps, colo)
        } catch (e: Exception) {
            return SpeedTestResult(0.0, null)
        } finally {
            try { ssl?.close() } catch (_: Exception) {}
            try { raw?.close() } catch (_: Exception) {}
        }
    }
}