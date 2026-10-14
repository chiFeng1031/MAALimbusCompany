package io.github.chifeng1031.limbushelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Performs taps and swipes with AccessibilityService.dispatchGesture, and launches the game.
 * Coordinates come in frame space (frame height = 720) and are scaled to the real screen here.
 */
class TapService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        LogBus.log("無障礙服務已連線")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun screenSize(): Point {
        val dm = getSystemService(DisplayManager::class.java)
        val p = Point()
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }

    /** Frame -> screen. The game runs in landscape, so the frame height maps to the short side. */
    private fun toScreen(x: Int, y: Int, frameH: Int): Pair<Float, Float> {
        val s = screenSize()
        if (s.x < s.y) LogBus.log("警告：目前是直向畫面，點擊位置可能不正確")
        val scale = minOf(s.x, s.y).toFloat() / frameH
        return x * scale to y * scale
    }

    fun tap(x: Int, y: Int, frameH: Int) {
        val (sx, sy) = toScreen(x, y, frameH)
        val path = Path().apply { moveTo(sx, sy) }
        perform(GestureDescription.StrokeDescription(path, 0, 60))
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, frameH: Int) {
        val (ax, ay) = toScreen(x1, y1, frameH)
        val (bx, by) = toScreen(x2, y2, frameH)
        val path = Path().apply {
            moveTo(ax, ay)
            lineTo(bx, by)
        }
        perform(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50)))
    }

    private fun perform(stroke: GestureDescription.StrokeDescription) {
        val done = CountDownLatch(1)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = done.countDown()
            override fun onCancelled(g: GestureDescription?) {
                LogBus.log("手勢被系統取消")
                done.countDown()
            }
        }, null)
        if (!accepted) {
            LogBus.log("手勢送出失敗")
            return
        }
        done.await(stroke.duration + 3000, TimeUnit.MILLISECONDS)
    }

    /** An enabled accessibility service may start activities from the background */
    fun startApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            LogBus.log("找不到應用：$pkg（是否已安裝？）")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    companion object {
        @Volatile
        var instance: TapService? = null
    }
}
