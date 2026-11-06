package com.rafi.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Merekam script langsung dari layar: layar diberi lapisan tipis, setiap ketukan menambah satu titik klik bernomor.
 * Bilah di atas: ↶ hapus titik terakhir, ✓ simpan semua titik jadi langkah klik, ✕ batal.
 * Catatan: ketukan ditangkap lapisan ini, jadi game di bawahnya tidak ikut bereaksi; titik yang direkam adalah
 * posisi di layar yang sedang terlihat. [onDone] menerima daftar titik + ukuran layar, atau null bila dibatalkan.
 */
class TapRecorder(
    private val ctx: Context,
    private val defaultDelayMs: Long,
    private val onDone: (taps: List<ScriptStore.RecordedTap>?, screenW: Int, screenH: Int) -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var marks: MarksCanvas? = null
    private var counter: TextView? = null
    private val points = ArrayList<Pair<Float, Float>>()   // koordinat layar (piksel)

    private fun px(v: Int) = (v * d).toInt()

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val disp = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") disp.getRealMetrics(dm)
        return dm
    }

    private fun button(icon: Icon, bg: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageDrawable(IconDrawable(icon, Color.WHITE, px(22)))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(px(40), px(40)).apply { setMargins(px(3), px(3), px(3), px(3)) }
        setOnClickListener { onClick() }
    }

    fun show() {
        if (root != null) return
        val frame = FrameLayout(ctx)
        val canvas = MarksCanvas(ctx, points) { x, y -> addPoint(x, y) }
        marks = canvas
        frame.addView(canvas, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true   // sentuhan di bilah tidak dihitung sebagai titik
            setPadding(px(12), px(4), px(6), px(4))
            background = GradientDrawable().apply {
                cornerRadius = px(28).toFloat(); setColor(0xF01E4A18.toInt()); setStroke(px(1), 0xFFF4C542.toInt())
            }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = "Ketuk titik yang mau diklik"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.DEFAULT_BOLD
        })
        val cnt = TextView(ctx).apply {
            setTextColor(0xCCFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        counter = cnt
        texts.addView(cnt)
        bar.addView(texts, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = px(10) })
        bar.addView(button(Icon.RESET, 0xFF8C8C8C.toInt(), "Hapus titik terakhir") { undo() })
        bar.addView(button(Icon.CHECK, Palette.green, "Simpan titik jadi langkah") { finish(true) })
        bar.addView(button(Icon.CLOSE, 0xFF8C8C8C.toInt(), "Batal") { finish(false) })
        frame.addView(bar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(10) })
        updateCounter()

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        root = frame
        try { wm.addView(frame, lp) } catch (_: Exception) { root = null; marks = null; counter = null; onDone(null, 0, 0) }
    }

    private fun addPoint(x: Float, y: Float) {
        points.add(x to y)
        marks?.invalidate()
        updateCounter()
    }

    private fun undo() {
        if (points.isNotEmpty()) points.removeAt(points.size - 1)
        marks?.invalidate()
        updateCounter()
    }

    private fun updateCounter() {
        counter?.text = if (points.isEmpty()) "Belum ada titik" else "${points.size} titik · ✓ untuk simpan"
    }

    private fun finish(save: Boolean) {
        val dm = realMetrics()
        val taps = if (save) points.map { ScriptStore.RecordedTap(it.first.toInt(), it.second.toInt(), defaultDelayMs) } else null
        removeView()
        onDone(taps, dm.widthPixels, dm.heightPixels)
    }

    /** Tutup tanpa memanggil onDone (dipakai saat layanan dimatikan). */
    fun hide() = removeView()

    private fun removeView() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null; marks = null; counter = null
    }

    /** Lapisan penangkap ketukan + penggambar lingkaran bernomor. Koordinat dikonversi ke koordinat layar penuh. */
    private class MarksCanvas(
        c: Context,
        private val pts: List<Pair<Float, Float>>,
        private val onTap: (Float, Float) -> Unit
    ) : View(c) {
        private val dens = c.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCF5C637.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2f * dens }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2A3B24.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 13f * dens
        }
        private val loc = IntArray(2)
        private var downX = 0f
        private var downY = 0f

        init { setBackgroundColor(0x26000000) }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_UP -> {
                    // Hanya ketukan (bukan geseran) yang dihitung sebagai titik.
                    if (abs(e.rawX - downX) < 24f * dens && abs(e.rawY - downY) < 24f * dens) onTap(e.rawX, e.rawY)
                }
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            val r = 15f * dens
            for ((i, p) in pts.withIndex()) {
                val vx = p.first - loc[0]
                val vy = p.second - loc[1]
                c.drawCircle(vx, vy, r, fill)
                c.drawCircle(vx, vy, r, ring)
                c.drawText((i + 1).toString(), vx, vy + txt.textSize / 3f, txt)
            }
        }
    }
}
