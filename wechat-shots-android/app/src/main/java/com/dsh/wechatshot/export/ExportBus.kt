package com.dsh.wechatshot.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ExportUiState(
    val running: Boolean = false,
    val stopping: Boolean = false,
    val contact: String = "",
    val modeLabel: String = "",
    val pagesScanned: Int = 0,
    val imagesSaved: Int = 0,
    val currentDate: String = "",
    val message: String = "",
    val log: List<String> = emptyList(),
    val resultDir: String? = null,
    val zipPath: String? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val finished: Boolean = false,
)

/**
 * 前台服务与 Compose UI 之间的单向状态总线。
 */
object ExportBus {
    private const val MAX_LOG_LINES = 120
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    fun update(transform: (ExportUiState) -> ExportUiState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = ExportUiState()
    }

    fun log(line: String) {
        if (line.isBlank()) return
        _state.update { current ->
            current.copy(log = (current.log + line).takeLast(MAX_LOG_LINES))
        }
    }
}
