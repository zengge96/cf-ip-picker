package com.cfip.picker.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 下载测速:从 CF 镜像站下载文件,按下载量/耗时估算带宽
 * 与原版 runSpeedTestSimple 一致:单线程下载,达到期望网速(maxSpeed)即提前停止
 */
object SpeedTester {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 测速:下载 [url] 最多 [maxDurationMs] 毫秒,返回估算带宽 Mbps
     * @param expectedMbps 期望网速;>0 时达到即停(原版 maxSpeed 达标即停)
     * @return Mbps;失败返回 0.0
     */
    fun test(url: String, maxDurationMs: Long = 3000, expectedMbps: Int = 0): Double {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CFIP-Picker/1.0")
                .header("Range", "bytes=0-") // 拉流式下载即可
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return 0.0
                val body = resp.body ?: return 0.0
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
                                return mbps
                            }
                        }
                        chunk = 0
                    }
                }
                input.close()
                val elapsedSec = (System.currentTimeMillis() - start) / 1000.0
                if (elapsedSec <= 0) 0.0 else (bytes * 8.0) / elapsedSec / 1_000_000.0
            }
        } catch (e: Exception) {
            0.0
        }
    }
}
