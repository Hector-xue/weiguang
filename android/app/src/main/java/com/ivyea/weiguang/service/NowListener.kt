package com.ivyea.weiguang.service

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import com.ivyea.weiguang.MainActivity
import com.ivyea.weiguang.core.Library
import com.ivyea.weiguang.core.NowPlaying
import com.ivyea.weiguang.core.Prefs
import kotlin.math.abs

// 通知使用权的载体：系统一直绑着它，所以读播放会话、挂悬浮光球都放这里，不用另开前台服务
class NowListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: NowListener? = null
            private set
        val orbShowing: Boolean get() = instance?.orb != null
    }

    private var orb: OrbView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onListenerConnected() {
        instance = this
        Prefs.init(this)
        Library.init(this)
        NowPlaying.attach(this)
        syncOrb()
    }

    override fun onListenerDisconnected() {
        removeOrb()
        instance = null
    }

    override fun onDestroy() {
        removeOrb()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun syncOrb() {
        if (Prefs.orb && Settings.canDrawOverlays(this)) showOrb() else removeOrb()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrb() {
        if (orb != null) return
        val wm = getSystemService(WindowManager::class.java)
        val size = (56 * resources.displayMetrics.density).toInt()
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = if (Prefs.orbX >= 0) Prefs.orbX else dm.widthPixels - size - (12 * dm.density).toInt()
            y = if (Prefs.orbY >= 0) Prefs.orbY else (dm.heightPixels * 0.62f).toInt()
        }
        val v = OrbView(this)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // 松手吸到最近的左右边
                        val dm = resources.displayMetrics
                        val edge = (12 * dm.density).toInt()
                        p.x = if (p.x + size / 2 < dm.widthPixels / 2) edge else dm.widthPixels - size - edge
                        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
                        Prefs.orbX = p.x; Prefs.orbY = p.y
                    } else {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                }
            }
            true
        }
        try {
            wm.addView(v, p)
            orb = v; params = p
        } catch (_: Throwable) {
            orb = null
        }
    }

    private fun removeOrb() {
        val v = orb ?: return
        try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
        orb = null; params = null
    }
}
