package com.ivyea.weiguang.probe

// 录制服务和界面之间共享的状态（同一进程，直接读写）
object Probe {
    @Volatile var captureBands = FloatArray(32)
    @Volatile var captureLevel = 0f
    @Volatile var capturePeak = 0f
    @Volatile var captureStatus = "未开始"
}
