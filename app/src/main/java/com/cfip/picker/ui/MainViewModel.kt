package com.cfip.picker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cfip.picker.core.ApiClient
import com.cfip.picker.core.Scanner
import com.cfip.picker.core.initApiCache
import com.cfip.picker.data.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class UiState(
    val scanning: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val currentIp: String = "",
    val results: List<ScanResult> = emptyList(),
    val error: String? = null,
    // 期望网速(Mbps),0 = 不限(原版 editBandwidth)
    val expectedSpeed: Int = 0,
    // 期望时延(ms),0 = 不限;RTT 测试后筛选,只保留延迟 <= 期望值的 IP
    val expectedLatencyMs: Int = 0,
    // 用户指定 IPv4 前缀,如 172.64.x.x;空 = 保持原版全量随机逻辑
    val ipPrefix: String = "",
    // 是否通过百度前置代理测试(开启后 RTT/测速走 cloudnproxy.baidu.com 隧道)
    val useBaiduProxy: Boolean = false,
    // 是否随机选择测速候选(默认关 = 按 RTT 排序取前10;开 = RTT 有响应的 IP 随机取 N 个)
    val randomSelectCandidates: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var scanJob: Job? = null
    private var scannerRef: Scanner? = null

    init {
        // 初始化数据缓存目录(对应原版 setCacheDir)
        initApiCache(app)
    }

    /** 设置期望网速(原版 normalizeBandwidthInput,0 表示不限) */
    fun setExpectedSpeed(mbps: Int) {
        _state.value = _state.value.copy(expectedSpeed = mbps.coerceAtLeast(0))
    }

    /** 设置期望时延(ms),0 表示不限 */
    fun setExpectedLatency(ms: Int) {
        _state.value = _state.value.copy(expectedLatencyMs = ms.coerceAtLeast(0))
    }

    /** 设置 IPv4 前缀过滤;空表示不限制 */
    fun setIpPrefix(prefix: String) {
        _state.value = _state.value.copy(ipPrefix = prefix.trim())
    }

    /** 设置是否通过百度前置代理测试 */
    fun setUseBaiduProxy(enable: Boolean) {
        _state.value = _state.value.copy(useBaiduProxy = enable)
    }

    /** 设置是否随机选择测速候选(默认关 = 按 RTT 排序取前10) */
    fun setRandomSelectCandidates(enable: Boolean) {
        _state.value = _state.value.copy(randomSelectCandidates = enable)
    }

    fun startScan() {
        // 防御:若旧任务仍在跑(取消未完全停止),先取消它,避免多任务并发
        scannerRef?.cancel()
        scanJob?.cancel()
        scannerRef = null
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, error = null)
        val expected = if (_state.value.expectedSpeed > 0) _state.value.expectedSpeed else 1 // 留空默认 1Mbps
        val expectedLatency = _state.value.expectedLatencyMs
        val prefix = _state.value.ipPrefix
        val useProxy = _state.value.useBaiduProxy
        val randomSelect = _state.value.randomSelectCandidates
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 拉取测速文件路径 + 机房位置 + 联通性测试 URL(网络操作必须在 IO 线程)
                val speedFile = ApiClient.getSpeedTestUrl()
                val locations = ApiClient.getLocations()
                val httpTestUrl = ApiClient.getHttpTestUrl()
                val scanner = Scanner { done, total, ip ->
                    _state.value = _state.value.copy(progress = done, total = total, currentIp = ip)
                }
                scannerRef = scanner
                // 2. 执行扫描:批次循环对齐原版(每批≤100随机采样 → 并发RTT → 联通性筛选 → 前10测速 → 达标即停,没达标下一批)
                val results = scanner.scan(
                    batchSize = 100,
                    speedTestCandidates = 10,
                    speedTestFile = speedFile,
                    httpTestUrl = httpTestUrl,
                    expectedSpeedMbps = expected,
                    expectedLatencyMs = expectedLatency,
                    maxBatches = 20,
                    locationsJson = locations,
                    ipPrefix = prefix,
                    useBaiduProxy = useProxy,
                    randomSelectCandidates = randomSelect,
                )
                scannerRef = null
                _state.value = _state.value.copy(scanning = false, results = results)
            } catch (e: Exception) {
                scannerRef = null
                android.util.Log.e("CFIPPicker", "扫描失败", e)
                _state.value = _state.value.copy(scanning = false, error = e.message ?: "扫描失败")
            }
        }
    }

    fun cancelScan() {
        // 1. 先通知 Scanner 停止循环(阻塞的 RTT/测速会在检查点尽快退出)
        val scanner = scannerRef
        scanner?.cancel()
        scannerRef = null
        // 2. 取消协程(若有 awaitAll 等挂起点会抛 CancellationException)
        scanJob?.cancel()
        scanJob = null
        // 3. 提取已测的部分结果并显示(用户要求:取消时保留已有结果)
        val partial = scanner?.currentResults()
        if (partial.isNullOrEmpty()) {
            _state.value = _state.value.copy(scanning = false)
        } else {
            _state.value = _state.value.copy(scanning = false, results = partial)
        }
    }
}