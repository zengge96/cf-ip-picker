package com.cfip.picker.core

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * 联通性测试:验证候选 CF IP 能否正常访问 http_test_url 端点的 URL。
 * 在 RTT 延迟测试之后、单线程测速之前执行(并发),只有联通的 IP 才进入测速阶段。
 *
 * 两种模式:
 *  - 直连:URL 保持原 host(保证 SNI/Host 正确),DNS 解析到候选 IP,发 GET 看响应。
 *  - 百度前置代理:先 CONNECT 隧道到候选 IP:443,隧道内 TLS(SNI 用 URL host) + GET,看响应。
 */
object ConnectivityTester {

    /**
     * 直连联通性测试
     * @param ip 候选 CF IP
     * @param url 联通性测试 URL(/http_test_url 端点返回)
     * @return true = 能成功建立连接并拿到 HTTP 响应
     */
    fun test(ip: String, url: String, timeoutMs: Long = 5000): Boolean {
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
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CFIP-Picker/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过百度前置代理联通性测试
     * @param ip 候选 CF IP(隧道目标)
     * @param url 联通性测试 URL(/http_test_url 端点返回)
     * @return true = 隧道 + TLS + HTTP 都能通
     */
    fun testViaBaiduProxy(ip: String, url: String, timeoutMs: Long = 8000): Boolean {
        var raw: java.net.Socket? = null
        var ssl: SSLSocket? = null
        try {
            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val path = httpUrl.encodedPath + (if (httpUrl.encodedQuery != null) "?${httpUrl.encodedQuery}" else "")

            // 1. 百度 CONNECT 隧道到候选 IP:443
            raw = BaiduProxy.connect(ip, 443, timeoutMs.toInt())

            // 2. TLS 握手(SNI = URL host,证书校验 host)
            val sslFactory = SSLContext.getDefault().socketFactory
            ssl = sslFactory.createSocket(raw, host, 443, true) as SSLSocket
            ssl.soTimeout = timeoutMs.toInt()
            ssl.startHandshake()

            // 3. HTTP GET(只读响应头,不下载 body)
            val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: CFIP-Picker/1.0\r\n" +
                    "Connection: close\r\n\r\n"
            ssl.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            ssl.getOutputStream().flush()

            val input = ssl.getInputStream()
            val buf = ByteArray(1024)
            var total = 0
            val sb = StringBuilder()
            while (total < 65536) {
                val n = input.read(buf)
                if (n < 0) break
                sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                total += n
                if (sb.contains("\r\n\r\n")) break // 响应头读完即可
            }
            // HTTP 200/3xx = 联通
            val ok = sb.startsWith("HTTP/1.1 2") || sb.startsWith("HTTP/1.0 2") ||
                    sb.startsWith("HTTP/1.1 3") || sb.startsWith("HTTP/1.0 3")
            return ok
        } catch (e: Exception) {
            return false
        } finally {
            try { ssl?.close() } catch (_: Exception) {}
            try { raw?.close() } catch (_: Exception) {}
        }
    }
}