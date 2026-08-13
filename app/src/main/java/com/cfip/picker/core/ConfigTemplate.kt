package com.cfip.picker.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v2ray 配置模板管理
 *
 * 6 个内置模板(assets/templates/):
 *   config-japan.json / config-korea.json / config-cf.json       (带百度 chained-proxy)
 *   config-japan-no-baidu.json / ...-no-baidu.json / ...          (no-baidu 直连)
 *
 * 模板按 "百度前置代理" 开关过滤:
 *   useBaiduProxy=true  -> 仅显示带百度版(address=cf-baidu.zngle.cc.cd,走 chained-proxy 隧道)
 *   useBaiduProxy=false -> 仅显示 no-baidu 版(address=jparm/krarm/magic.zngle.de5.net,直连)
 *
 * 生成配置:只替换 vnext[].address = 优选 IP,其余(含 SNI/Host/UUID/path/DNS/分流)全部保留。
 */
object ConfigTemplate {

    /** 模板定义:文件名 + 中文显示名 */
    data class Template(
        val assetPath: String,   // assets/templates/xxx.json
        val displayName: String, // 弹窗里显示的中文名
    )

    /** 全部 6 个模板 */
    private val ALL = listOf(
        Template("config-japan.json",        "🇯🇵 日本节点"),
        Template("config-korea.json",        "🇰🇷 韩国节点"),
        Template("config-cf.json",           "⭐ CF 优选"),
        Template("config-japan-no-baidu.json", "🇯🇵 日本节点"),
        Template("config-korea-no-baidu.json", "🇰🇷 韩国节点"),
        Template("config-cf-no-baidu.json",    "⭐ CF 优选"),
    )

    /** 按百度开关过滤后的可用模板列表(每组 3 个) */
    fun available(useBaiduProxy: Boolean): List<Template> {
        return if (useBaiduProxy) {
            ALL.take(3)  // 前 3 个是带百度版
        } else {
            ALL.drop(3)  // 后 3 个是 no-baidu 版
        }
    }

    /**
     * 用模板 + 优选 IP 生成完整 v2ray 配置 JSON。
     * 仅替换 outbounds[tag=proxy].settings.vnext[0].address = ip,其余结构/SNI/Host 全部原样保留。
     *
     * @throws Exception 模板加载/解析/字段缺失时
     */
    fun generate(context: Context, template: Template, ip: String): String {
        val raw = context.assets.open("templates/${template.assetPath}")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(raw)

        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalStateException("模板 ${template.assetPath} 缺少 outbounds")
        var proxyIdx = -1
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.getJSONObject(i)
            if (ob.optString("tag") == "proxy") { proxyIdx = i; break }
        }
        if (proxyIdx < 0) throw IllegalStateException("模板 ${template.assetPath} 找不到 tag=proxy 出站")

        val proxy = outbounds.getJSONObject(proxyIdx)
        val vnext = proxy.getJSONObject("settings").getJSONArray("vnext")
        val node = vnext.getJSONObject(0)

        // 关键:只换 address,SNI/Host/UUID/path 全部保留原样
        node.put("address", ip)

        // 美化输出(2 空格缩进),方便用户查看
        return root.toString(2)
    }
}
