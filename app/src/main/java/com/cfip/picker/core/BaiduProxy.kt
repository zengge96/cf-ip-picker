package com.cfip.picker.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 百度云前置代理:HTTP CONNECT 隧道(复用 baidu-proxy 项目参数)。
 * 代理地址 cloudnproxy.baidu.com:443,认证头 X-T5-Auth: 482857715。
 * 通过它建立到目标 IP:port 的隧道,后续流量(含 TLS)都走这条隧道。
 */
object BaiduProxy {

    const val HOST = "cloudnproxy.baidu.com"
    const val PORT = 443
    const val AUTH = "482857715"

    /**
     * 建立到 [targetHost]:[targetPort] 的百度 CONNECT 隧道。
     * @return 已连通且代理已确认 200 的原始 Socket
     */
    fun connect(targetHost: String, targetPort: Int, timeoutMs: Int = 5000): Socket {
        val sock = Socket()
        sock.connect(InetSocketAddress(HOST, PORT), timeoutMs)
        sock.soTimeout = timeoutMs
        try {
            val req = "CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
                    "Host: sptest.baidu.com\r\n" +
                    "X-T5-Auth: $AUTH\r\n" +
                    "User-Agent: okhttp/3.11.0\r\n" +
                    "Proxy-Connection: keep-alive\r\n" +
                    "Connection: keep-alive\r\n\r\n"
            sock.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            sock.getOutputStream().flush()

            // 读响应头直到 \r\n\r\n,判断 200
            val input = sock.getInputStream()
            val buf = ByteArray(1024)
            val sb = StringBuilder()
            while (!sb.contains("\r\n\r\n")) {
                val n = input.read(buf)
                if (n < 0) throw IOException("百度代理连接被关闭")
                sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (sb.length > 65536) throw IOException("百度代理响应头过大")
            }
            val status = sb.toString().substringBefore("\r\n")
            if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200")) {
                sock.close()
                throw IOException("百度代理 CONNECT 失败: $status")
            }
            return sock
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            throw e
        }
    }
}