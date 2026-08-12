package com.cfip.picker.core

import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

/**
 * RTT 延迟测试:TCP 连接耗时
 */
object RttTester {

    /**
     * 测试与 [ip]:[port] 的 TCP 连接延迟
     * @return 延迟毫秒;失败返回 -1
     */
    fun test(ip: String, port: Int = 443, timeoutMs: Int = 3000): Int {
        return try {
            val socket = Socket()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            val elapsed = System.currentTimeMillis() - start
            socket.close()
            elapsed.toInt()
        } catch (e: Exception) {
            -1
        }
    }
}
