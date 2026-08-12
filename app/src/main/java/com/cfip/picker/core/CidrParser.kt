package com.cfip.picker.core

import java.net.InetAddress
import kotlin.random.Random

/**
 * CIDR 解析/随机 IP 生成:对齐原版 getRandomIPv4s/getRandomIPv6s。
 *
 * 原版 IPv4 列表全部是 /24:
 *   - 遍历每个 CIDR 段
 *   - 取前三段作为网络前缀
 *   - 最后一段 nextRandomIntn(256),即 0..255
 *   - 每个 CIDR 生成 1 个随机 IP
 * 后续再由 randomSample 对完整候选池随机采样。
 */
object CidrParser {

    /** 解析单个 CIDR,随机返回段内 [sample] 个 IP */
    fun randomIps(cidr: String, sample: Int = 1): List<String> {
        val (base, prefixStr) = cidr.split("/")
        val prefix = prefixStr.toIntOrNull() ?: return emptyList()
        val addr = InetAddress.getByName(base).address

        return if (addr.size == 4) {
            randomIpv4Original(base, prefix, sample)
        } else {
            randomIpv6Original(addr, prefix, sample)
        }
    }

    /**
     * 对齐原版 getRandomIPv4s:
     * 原版数据源是 /24,直接在最后一个 octet 上 rand.Intn(256)。
     * 如果未来出现非 /24,则 fallback 到通用 CIDR 主机位随机。
     */
    private fun randomIpv4Original(base: String, prefix: Int, sample: Int): List<String> {
        if (prefix == 24) {
            val parts = base.split(".")
            if (parts.size == 4) {
                val net = parts.take(3).joinToString(".")
                return List(sample) { "$net.${Random.nextInt(256)}" }
            }
        }
        return randomIpv4Generic(InetAddress.getByName(base).address, prefix, sample)
    }

    /** 通用 IPv4 CIDR fallback,按主机位随机。 */
    private fun randomIpv4Generic(base: ByteArray, prefix: Int, sample: Int): List<String> {
        val hostBits = (32 - prefix).coerceIn(0, 31)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        var network = 0
        for (b in base) network = (network shl 8) or (b.toInt() and 0xff)
        network = network and mask
        val maxHosts = if (hostBits == 0) 1 else 1 shl hostBits
        return List(sample) {
            val host = Random.nextInt(maxHosts)
            val v = network or host
            "%d.%d.%d.%d".format(
                (v ushr 24) and 0xff,
                (v ushr 16) and 0xff,
                (v ushr 8) and 0xff,
                v and 0xff,
            )
        }
    }

    /**
     * 对齐原版 getRandomIPv6s:保留网络前缀,其余 16-bit hextet 用 nextRandomIntn(65536) 填充。
     * 当前用户已要求只扫 IPv4,此处仅保留兼容实现。
     */
    private fun randomIpv6Original(base: ByteArray, prefix: Int, sample: Int): List<String> {
        val fixedBytes = prefix / 8
        val results = mutableListOf<String>()
        repeat(sample) {
            val ip = base.copyOf()
            var idx = fixedBytes
            while (idx + 1 < 16) {
                val v = Random.nextInt(65536)
                ip[idx] = ((v ushr 8) and 0xff).toByte()
                ip[idx + 1] = (v and 0xff).toByte()
                idx += 2
            }
            results.add(InetAddress.getByAddress(ip).hostAddress)
        }
        return results
    }
}
