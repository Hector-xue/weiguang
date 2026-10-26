package com.ivyea.weiguang.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// 从通知点进来要去的地方（recap_week / recap_month / moment）；界面接住后清掉
object Nav {
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target
    fun go(what: String) { _target.value = what }
    fun done() { _target.value = null }
}
