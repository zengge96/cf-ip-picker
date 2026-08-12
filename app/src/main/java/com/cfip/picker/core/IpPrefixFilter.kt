package com.cfip.picker.core

import kotlin.random.Random

/**
 * 用户指定 IP 前缀过滤器。
 * 支持:
 *   - 空: 不过滤,保持原版随机逻辑
 *   - 172.64 / 172.64. / 172.64.x.x / 172.64.*.*
 *   - 172.64.1.x
 *   - 172.64.1.23(精确到单 IP)
 */
data class IpPrefixFilter(private val parts: List<Int?>) {
    val enabled: Boolean = parts.isNotEmpty()

    fun matchesCidr(cidr: String): Boolean {
        val base = cidr.substringBefore('/').split('.')
        if (base.size != 4) return false
        val octets = base.map { it.toIntOrNull() ?: return false }
        // 当前 ips-v4 全是 /24。CIDR 的前 3 段是网络段,第 4 段随机。
        for (i in parts.indices) {
            val p = parts[i] ?: continue
            if (i < 3 && octets[i] != p) return false
            if (i == 3) {
                // 第 4 段精确值时,只要前 3 段匹配即可,具体 IP 由 randomIpFromCidr 返回固定值。
                return true
            }
        }
        return true
    }

    fun randomIpFromCidr(cidr: String): String? {
        if (!matchesCidr(cidr)) return null
        val base = cidr.substringBefore('/').split('.')
        if (base.size != 4) return null
        val fixedLast = parts.getOrNull(3)
        return if (fixedLast != null) {
            "${base[0]}.${base[1]}.${base[2]}.$fixedLast"
        } else {
            "${base[0]}.${base[1]}.${base[2]}.${Random.nextInt(256)}"
        }
    }

    companion object {
        fun parse(raw: String): IpPrefixFilter {
            val s = raw.trim()
                .replace('＊', '*')
                .lowercase()
            if (s.isBlank()) return IpPrefixFilter(emptyList())

            val tokens = s.split('.')
                .filter { it.isNotBlank() }
                .take(4)
            if (tokens.isEmpty()) return IpPrefixFilter(emptyList())

            val parts = tokens.map { token ->
                when (token) {
                    "x", "*" -> null
                    else -> {
                        val n = token.toIntOrNull()
                        require(n != null && n in 0..255) { "IP 前缀格式不对: $raw" }
                        n
                    }
                }
            }
            return IpPrefixFilter(parts)
        }
    }
}
