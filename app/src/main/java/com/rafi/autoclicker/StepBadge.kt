package com.rafi.autoclicker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Badge kecil di tengah atas layar yang selalu menampilkan nomor langkah aktif
 * ("13 / 28"), baik langkah itu mengklik maupun tidak. Tidak menghalangi sentuhan.
 * Semua fungsi harus dipanggil di main thread.
 */
class StepBadge(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private val density = ctx.resources.displayMetrics.density
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = (6 * density).toInt()
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    fun show(number: Int, total: Int) {
        val label = if (total > 0) "$number / $total" else "$number"
        val v = view ?: TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            val h = (10 * density).toInt(); val vv = (4 * density).toInt()
            setPadding(h, vv, h, vv)
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(0xE61E4A18.toInt())
                setStroke((1.5f * density).toInt(), 0xFFF4C542.toInt())
            }
        }.also {
            view = it
            try { wm.addView(it, lp) } catch (_: Exception) {}
        }
        v.text = label
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }
}
