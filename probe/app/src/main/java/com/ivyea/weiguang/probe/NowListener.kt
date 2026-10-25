package com.ivyea.weiguang.probe

import android.service.notification.NotificationListenerService

// 只为拿"通知使用权"：有了它才能读取其他 App 的播放会话，本身什么都不做
class NowListener : NotificationListenerService()
