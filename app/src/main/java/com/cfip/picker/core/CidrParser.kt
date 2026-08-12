package com.cfip.picker.core

import java.net.InetAddress

/**
 * CIDR 解析:把 "1.0.0.0/24" 变成可用的 IP 列表
 * 只取段内少量随机 IP,避免枚举整个段(内存/时间开销)
 */
object CidrParser {

    /** 解析单个 CIDR,随机返回段内 [sample] 个 IP */
    fun randomIps(cidr: String, sample: Int = 1): List<String> {
        val (base, prefixStr) = cidr.split("/")
        val prefix = prefixStr.toIntOrNull() ?: return emptyList()
        val addr = InetAddress.getByName(base).address

        return if (addr.size == 4) {
            randomIpv4(addr, prefix, sample)
        } else {
            randomIpv6(addr, prefix, sample)
        }
    }

    private fun randomIpv4(base: ByteArray, prefix: Int, sample: Int): List<String> {
        // 主机位数 = 32 - prefix
        val hostBits = 32 - prefix
        // 段内地址总数(前缀 >=30 时只枚举)
        val total = if (hostBits >= 8) 1L shl hostBits else 1L shl hostBits

        val results = mutableListOf<String>()
        repeat(sample) {
            // 随机主机部分
            val host = if (hostBits <= 31) (Math.random() * total).toLong() else 0L
            val ip = base.copyOf()
            // 把 host 写入后 hostBits 位
            for (i in 0 until hostBits) {
                val bit = ((host shr (hostBits - 1 - i)) and 1L).toInt()
                if (bit == 1) {
                    val byteIdx = 3 - (i / 8)
                    ip[byteIdx] = (ip[byteIdx].toInt() or (1 shl (i % 8))).toByte()
                }
            }
            results.add(InetAddress.getByAddress(ip).hostAddress)
        }
        return results
    }

    private fun randomIpv6(base: ByteArray, prefix: Int, sample: Int): List<String> {
        val hostBits = 128 - prefix
        val results = mutableListOf<String>()
        repeat(sample) {
            val ip = base.copyOf()
            // 随机填充后 hostBits 位(简化:按字节随机)
            val bytesToRandomize = (hostBits + 7) / 8
            for (i in 1..bytesToRandomize) {
                val idx = 16 - i
                if (idx >= 0) {
                    ip[idx] = (Math.random() * 256).toInt().toByte()
                }
            }
            results.add(InetAddress.getByAddress(ip).hostAddress)
        }
        return results
    }
}
