package com.dsh.wechatshot.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 后台配对服务（[PairingWaitService]）与前台页面（[AutoPairScreen]）之间的结果桥。
 *
 * 使用场景：用户点「打开无线调试设置」后 App 退到后台，由前台服务在设置界面里完成
 * 「截图 → OCR → 配对 → 连接」。服务把最终结果写在这里，用户回到 App 时页面读取它，
 * 直接展示「已连接」或失败原因。
 */
object PairingBridge {

    data class Result(
        val success: Boolean,
        val message: String,
    )

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    fun publish(result: Result) {
        _result.value = result
    }

    fun clear() {
        _result.value = null
    }
}
