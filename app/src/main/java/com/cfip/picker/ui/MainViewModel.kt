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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var scanJob: Job? = null

    init {
        // 初始化数据缓存目录(对应原版 setCacheDir)
        initApiCache(app)
    }

    fun startScan() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, error = null)
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 拉取测速文件路径 + 机房位置(网络操作必须在 IO 线程)
                val speedFile = ApiClient.getSpeedTestUrl()
                val locations = ApiClient.getLocations()
                val scanner = Scanner { done, total, ip ->
                    _state.value = _state.value.copy(progress = done, total = total, currentIp = ip)
                }
                // 2. 执行扫描:v4+v6 双栈 + 机房反查(与原版功能对齐)
                val results = scanner.scan(
                    samplePerRange = 1,
                    maxRanges = 200,
                    speedTestFile = speedFile,
                    useIpv6 = true,
                    locationsJson = locations,
                )
                _state.value = _state.value.copy(scanning = false, results = results)
            } catch (e: Exception) {
                android.util.Log.e("CFIPPicker", "扫描失败", e)
                _state.value = _state.value.copy(scanning = false, error = e.message ?: "扫描失败")
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _state.value = _state.value.copy(scanning = false)
    }
}