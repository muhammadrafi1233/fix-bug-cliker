package com.rafi.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClickerAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: ClickerAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Android hanya bisa mengirim satu gesture pada satu waktu; ini mengantre ketukan dari 3 script yang berjalan bersamaan. */
    private val tapLock = Any()

    /**
     * Dipanggil dari thread worker; menunggu semua gesture selesai.
     * holdMs = lama tiap ketukan (ms); nilai besar dipakai untuk tekan lama.
     * gapMs = jeda antara ketukan pertama dan kedua pada double klik (di luar waktu dispatch gesture).
     * Double klik = dua tap terpisah (masing-masing gesture sendiri), bukan dua stroke dalam satu gesture,
     * supaya game membacanya sebagai dua sentuhan sungguhan.
     */
    fun tap(x: Float, y: Float, double: Boolean, holdMs: Long = 50, gapMs: Long = 300) {
        synchronized(tapLock) {
            tapOnce(x, y, holdMs)
            if (double) {
                try { Thread.sleep(gapMs) } catch (_: InterruptedException) { return }
                tapOnce(x, y, holdMs)
            }
        }
    }

    private fun tapOnce(x: Float, y: Float, holdMs: Long): Boolean {
        val b = GestureDescription.Builder()
        b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, holdMs))
        val latch = CountDownLatch(1)
        var done = false
        val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        if (ok) latch.await(holdMs + 2500, TimeUnit.MILLISECONDS)
        return ok && done
    }
}
