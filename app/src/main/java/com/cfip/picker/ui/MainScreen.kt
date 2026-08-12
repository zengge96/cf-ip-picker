package com.cfip.picker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
                    ScanResultRow(r)
                }
            }
        }
    }
}

@Composable
private fun ScanResultRow(r: com.cfip.picker.data.ScanResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.ip, fontWeight = FontWeight.SemiBold)
                Text(
                    "延迟 ${r.latencyMs}ms · 带宽 %.1f Mbps".format(r.bandwidthMbps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 机房位置(原版 lookupDataCenter 显示,如 "HKG - Hong Kong")
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