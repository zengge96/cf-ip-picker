package com.cfip.picker.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cfip.picker.data.ScanResult
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "CF优选IP",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Cloudflare IP 优选 · 逆向重写版",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // 期望网速设置(原版 editBandwidth,达标即停;0 = 不限)
        OutlinedTextField(
            value = if (state.expectedSpeed == 0) "" else state.expectedSpeed.toString(),
            onValueChange = { v ->
                val n = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                viewModel.setExpectedSpeed(n)
            },
            label = { Text("期望网速 (Mbps, 留空不限)") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            ),
            enabled = !state.scanning,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        // IP 前缀过滤(增强功能;为空则保持原版全量随机逻辑)
        OutlinedTextField(
            value = state.ipPrefix,
            onValueChange = { v -> viewModel.setIpPrefix(v) },
            label = { Text("IP 前缀 (如 172.64.x.x, 留空不限)") },
            singleLine = true,
            enabled = !state.scanning,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        // 通过百度前置代理测试(增强功能;开启后 RTT/测速走 cloudnproxy.baidu.com 隧道)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("通过百度前置代理测试", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "走 cloudnproxy.baidu.com 隧道测速",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.useBaiduProxy,
                onCheckedChange = { v -> viewModel.setUseBaiduProxy(v) },
                enabled = !state.scanning,
            )
        }
        Spacer(Modifier.height(12.dp))

        // 控制按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { viewModel.startScan() },
                enabled = !state.scanning,
            ) {
                Text(if (state.scanning) "扫描中…" else "开始扫描")
            }
            if (state.scanning) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { viewModel.cancelScan() }) { Text("取消") }
            }
        }

        // 进度
        if (state.scanning) {
            Spacer(Modifier.height(8.dp))
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.progress.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = "${state.progress}/${state.total}  ${state.currentIp}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // 错误
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        // 结果列表
        Spacer(Modifier.height(12.dp))
        if (state.results.isEmpty() && !state.scanning) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("点击「开始扫描」获取最优 CF IP", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.results, key = { it.ip }) { r ->
                    ScanResultRow(r, state.expectedSpeed)
                }
            }
        }
    }
}

/**
 * 达标的判定 = 设定了期望网速(expectedSpeedMbps > 0)且实测带宽 >= 期望值。
 * 达标行背景显示绿色,点击整行复制 IP 到剪贴板 + Toast 提示。
 */
@Composable
private fun ScanResultRow(r: ScanResult, expectedSpeedMbps: Int) {
    val context = LocalContext.current
    val isHit = expectedSpeedMbps > 0 && r.bandwidthMbps + 0.05 >= expectedSpeedMbps

    val bg = if (isHit) Color(0xFF1F7A3A).copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable {
                copyIpToClipboard(context, r.ip)
            },
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.ip, fontWeight = FontWeight.SemiBold)
                    if (isHit) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = {
                                copyIpToClipboard(context, r.ip)
                            },
                            label = { Text("✓ 达标", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFF1F7A3A).copy(alpha = 0.25f),
                                labelColor = Color(0xFF1F7A3A),
                            ),
                        )
                    }
                }
                Text(
                    "延迟 ${r.latencyMs}ms · 带宽 %.1f Mbps".format(r.bandwidthMbps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.dataCenter.isNotEmpty() && r.dataCenter != "?") {
                    Text(
                        "📍 ${r.dataCenter}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                "${r.latencyMs}ms",
                style = MaterialTheme.typography.titleMedium,
                color = if (r.latencyMs < 150) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun copyIpToClipboard(context: Context, ip: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("CF IP", ip))
    Toast.makeText(context, "已复制 IP: $ip", Toast.LENGTH_SHORT).show()
}
