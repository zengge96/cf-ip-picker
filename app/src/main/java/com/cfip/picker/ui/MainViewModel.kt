package com.cfip.picker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cfip.picker.core.ApiClient
import com.cfip.picker.core.Scanner
import com.cfip.picker.data.ScanResult
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

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var scanJob: Job? = null

    fun startScan() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, error = null)
        scanJob = viewModelScope.launch {
            try {
                // 1. 拉取测速文件路径
                val speedFile = ApiClient.getSpeedTestUrl()
                val scanner = Scanner { done, total, ip ->
                    _state.value = _state.value.copy(progress = done, total = total, currentIp = ip)
                }
                // 2. 执行扫描(默认每段采 1 个,最多 200 段,避免过久)
                val results = scanner.scan(samplePerRange = 1, maxRanges = 200, speedTestFile = speedFile)
                _state.value = _state.value.copy(scanning = false, results = results)
            } catch (e: Exception) {
                _state.value = _state.value.copy(scanning = false, error = e.message ?: "扫描失败")
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _state.value = _state.value.copy(scanning = false)
    }
}