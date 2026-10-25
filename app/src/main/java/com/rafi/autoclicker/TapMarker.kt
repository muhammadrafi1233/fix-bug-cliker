package com.rafi.autoclicker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Lingkaran bernomor yang muncul sebentar di titik klik. Tidak menghalangi sentuhan. */
class TapMarker(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val size = (52 * ctx.resources.displayMetrics.density).toInt()
    private val lp = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    private val hideRunnable = Runnable { hideView() }

    /** Harus dipanggil di main thread. Tampil 250 ms (lebih singkat dari durasi klik default 300 ms). */
    fun show(x: Float, y: Float, number: Int) {
        main.removeCallbacks(hideRunnable)
        val v = view ?: TextView(ctx).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xDD2F7D3A.toInt()); setStroke(4, Palette.yellow)
            }
        }.also { view = it; lp.x = (x - size / 2).toInt(); lp.y = (y - size / 2).toInt(); try { wm.addView(it, lp) } catch (_: Exception) {} }
        v.text = number.toString()
        lp.x = (x - size / 2).toInt(); lp.y = (y - size / 2).toInt()
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
        main.postDelayed(hideRunnable, 250)
    }

    private fun hideView() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    fun hide() { main.removeCallbacks(hideRunnable); hideView() }
}
