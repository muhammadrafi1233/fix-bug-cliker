package com.rafi.autoclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pil kecil di atas layar: "Ubah gambar langkah #3 · Ambil layar · ✕".
 * Pengguna membuka game ke layar yang diinginkan, lalu mengetuk Ambil layar untuk lanjut ke [CropOverlay].
 */
class CapturePrompt(
    private val ctx: Context,
    private val title: String,
    private val onCapture: () -> Unit,
    private val onCancel: () -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (view != null) return
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat(); setColor(0xF01E4A18.toInt()); setStroke(dp(1), 0xFFF4C542.toInt())
            }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = title; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.DEFAULT_BOLD
        })
        texts.addView(TextView(ctx).apply {
            text = "Buka layar game yang dituju, lalu ketuk Ambil"
            setTextColor(0xCCFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        row.addView(texts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(10) })
        val take = TextView(ctx).apply {
            text = "Ambil layar"; gravity = Gravity.CENTER
            setTextColor(0xFF2A3B24.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xFFF5C637.toInt()) }
            isClickable = true
            setOnClickListener { onCapture() }
        }
        row.addView(take, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val x = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(Icon.CLOSE, Color.WHITE, dp(20)))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Batal"
            setOnClickListener { onCancel() }
        }
        row.addView(x, LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(4) })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(10)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { wm.addView(row, lp); view = row } catch (_: Exception) { view = null }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }
}

/**
 * Alat potong gambar dari layar (seperti di Image Clicker): tangkapan layar dibekukan, lalu pengguna menggeser dan
 * menarik sudut kotak ke gambar yang ingin dipakai. Tombol: ✂ potong & simpan, ✕ batal, ＋ perbesar kotak,
 * － perkecil kotak, ⟲ kembalikan kotak awal. [onDone] menerima kotak dalam piksel [shot], atau null bila dibatalkan.
 */
class CropOverlay(
    private val ctx: Context,
    private val shot: Bitmap,
    initial: Rect?,
    private val onDone: (Rect?) -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var cv: CropView? = null
    private var bar: LinearLayout? = null

    private val startNorm: RectF =
        if (initial != null && initial.width() > 0 && initial.height() > 0)
            RectF(
                initial.left.toFloat() / shot.width, initial.top.toFloat() / shot.height,
                initial.right.toFloat() / shot.width, initial.bottom.toFloat() / shot.height
            )
        else RectF(0.42f, 0.38f, 0.58f, 0.62f)

    private fun px(v: Int) = (v * d).toInt()

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
        val view = CropView(ctx, shot, startNorm) { place() }
        cv = view
        frame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))

        val hint = TextView(ctx).apply {
            text = "Seret kotak ke gambar yang dipakai, tarik sudutnya untuk mengubah ukuran, lalu ketuk ✂"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); gravity = Gravity.CENTER
            setPadding(px(12), px(6), px(12), px(6))
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(0xB3000000.toInt()) }
        }
        frame.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(10) })

        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true   // sentuhan di sela tombol tidak ikut menggeser kotak
            setPadding(px(4), px(3), px(4), px(3))
            background = GradientDrawable().apply { cornerRadius = px(30).toFloat(); setColor(0xE6707070.toInt()) }
        }
        val grey = 0xFF8C8C8C.toInt()
        b.addView(button(Icon.CUT, Palette.green, "Potong dan simpan") { confirm() })
        b.addView(button(Icon.CLOSE, grey, "Batal") { finish(null) })
        b.addView(button(Icon.PLUS, grey, "Perbesar kotak") { view.scaleBy(1.15f) })
        b.addView(button(Icon.MINUS, grey, "Perkecil kotak") { view.scaleBy(0.87f) })
        b.addView(button(Icon.RESET, grey, "Kembalikan kotak awal") { view.reset() })
        bar = b
        frame.addView(b, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))

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
        try { wm.addView(frame, lp) } catch (_: Exception) { root = null; cv = null; bar = null; onDone(null); return }
        frame.post { place() }
    }

    /** Taruh bilah tombol di bawah kotak (atau di atasnya bila tidak muat). */
    private fun place() {
        val v = cv ?: return
        val b = bar ?: return
        val r = root ?: return
        val w = r.width.toFloat()
        val h = r.height.toFloat()
        if (w <= 0f || h <= 0f) return
        b.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bw = b.measuredWidth.toFloat()
        val bh = b.measuredHeight.toFloat()
        val rc = v.rectPx()
        val gap = 12f * d
        val edge = 8f * d
        var y = rc.bottom + gap
        if (y + bh > h - edge) y = rc.top - bh - gap
        if (y < edge) y = h - bh - edge
        val x = (rc.centerX() - bw / 2f).coerceIn(edge, max(edge, w - bw - edge))
        b.translationX = x
        b.translationY = y
    }

    private fun confirm() {
        val rc = cv?.pixelRect() ?: return
        if (rc.width() < 8 || rc.height() < 8) {
            Toast.makeText(ctx, "Kotak terlalu kecil, perbesar dulu", Toast.LENGTH_SHORT).show()
            return
        }
        finish(rc)
    }

    private fun finish(rc: Rect?) {
        removeView()
        onDone(rc)
    }

    /** Tutup tanpa memanggil onDone (dipakai saat layanan dimatikan). */
    fun hide() = removeView()

    private fun removeView() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null; cv = null; bar = null
    }
}

/** Tangkapan layar beku + kotak seleksi. Kotak disimpan sebagai pecahan 0..1 dari ukuran layar. */
private class CropView(
    c0: Context,
    private val shot: Bitmap,
    start: RectF,
    private val onChange: () -> Unit
) : View(c0) {
    private val d = c0.resources.displayMetrics.density
    private val rect = RectF(start)
    private val first = RectF(start)
    private val src = Rect(0, 0, shot.width, shot.height)
    private val dst = RectF()
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xFFE91E8C.toInt()
        pathEffect = DashPathEffect(floatArrayOf(9f * d, 6f * d), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFE91E8C.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11f * d; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f * d, 0f, 0f, 0xFF000000.toInt())
    }

    private val eL = 1
    private val eT = 2
    private val eR = 4
    private val eB = 8
    private var mode = 0   // 0 diam, 1 geser kotak, 2 ubah ukuran
    private var mask = 0
    private var lastX = 0f
    private var lastY = 0f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        dst.set(0f, 0f, w, h)
        c.drawBitmap(shot, src, dst, null)
        val l = rect.left * w
        val t = rect.top * h
        val r = rect.right * w
        val b = rect.bottom * h
        c.drawRect(0f, 0f, w, t, dim)
        c.drawRect(0f, b, w, h, dim)
        c.drawRect(0f, t, l, b, dim)
        c.drawRect(r, t, w, b, dim)
        c.drawRect(l, t, r, b, border)
        val rd = 5f * d
        c.drawCircle(l, t, rd, dot); c.drawCircle(r, t, rd, dot)
        c.drawCircle(l, b, rd, dot); c.drawCircle(r, b, rd, dot)
        val pw = ((rect.right - rect.left) * shot.width).roundToInt()
        val ph = ((rect.bottom - rect.top) * shot.height).roundToInt()
        c.drawText("$pw x $ph px", l, if (t > 20f * d) t - 8f * d else b + 18f * d, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val l = rect.left * w
                val t = rect.top * h
                val r = rect.right * w
                val b = rect.bottom * h
                val slop = 30f * d
                val sx = minOf(slop, (r - l) * 0.35f)
                val sy = minOf(slop, (b - t) * 0.35f)
                var m = 0
                val inY = e.y >= t - slop && e.y <= b + slop
                val inX = e.x >= l - slop && e.x <= r + slop
                if (inY) { if (abs(e.x - l) < sx) m = m or eL else if (abs(e.x - r) < sx) m = m or eR }
                if (inX) { if (abs(e.y - t) < sy) m = m or eT else if (abs(e.y - b) < sy) m = m or eB }
                if (m != 0) {
                    mode = 2; mask = m
                } else if (e.x > l && e.x < r && e.y > t && e.y < b) {
                    mode = 1
                } else {
                    // Sentuh di luar kotak: mulai kotak baru dari titik ini.
                    val nx = (e.x / w).coerceIn(0f, 1f)
                    val ny = (e.y / h).coerceIn(0f, 1f)
                    rect.set(nx, ny, nx, ny)
                    mode = 2; mask = eR or eB
                }
                lastX = e.x; lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate(); onChange()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / w
                val dy = (e.y - lastY) / h
                lastX = e.x; lastY = e.y
                if (mode == 1) {
                    val rw = rect.width()
                    val rh = rect.height()
                    val nl = (rect.left + dx).coerceIn(0f, 1f - rw)
                    val nt = (rect.top + dy).coerceIn(0f, 1f - rh)
                    rect.set(nl, nt, nl + rw, nt + rh)
                } else if (mode == 2) {
                    if ((mask and eL) != 0) rect.left = (rect.left + dx).coerceIn(0f, 1f)
                    if ((mask and eR) != 0) rect.right = (rect.right + dx).coerceIn(0f, 1f)
                    if ((mask and eT) != 0) rect.top = (rect.top + dy).coerceIn(0f, 1f)
                    if ((mask and eB) != 0) rect.bottom = (rect.bottom + dy).coerceIn(0f, 1f)
                    // Sisi yang ditarik melewati sisi seberangnya: tukar supaya kotak tetap valid.
                    if (rect.left > rect.right) { val x = rect.left; rect.left = rect.right; rect.right = x; mask = flip(mask, eL, eR) }
                    if (rect.top > rect.bottom) { val x = rect.top; rect.top = rect.bottom; rect.bottom = x; mask = flip(mask, eT, eB) }
                }
                invalidate(); onChange()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != 0) { ensureMin(); invalidate(); onChange() }
                mode = 0
            }
        }
        return true
    }

    private fun flip(m: Int, a: Int, b: Int): Int {
        val hasA = (m and a) != 0
        val hasB = (m and b) != 0
        var o = m and (a or b).inv()
        if (hasA) o = o or b
        if (hasB) o = o or a
        return o
    }

    /** Kotak tidak boleh lebih kecil dari 24 dp. */
    private fun ensureMin() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val minW = minOf(24f * d / w, 1f)
        val minH = minOf(24f * d / h, 1f)
        if (rect.width() < minW) {
            val l = ((rect.left + rect.right) / 2f - minW / 2f).coerceIn(0f, 1f - minW)
            rect.left = l; rect.right = l + minW
        }
        if (rect.height() < minH) {
            val t = ((rect.top + rect.bottom) / 2f - minH / 2f).coerceIn(0f, 1f - minH)
            rect.top = t; rect.bottom = t + minH
        }
    }

    fun scaleBy(f: Float) {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val hw = (rect.width() * f / 2f).coerceAtMost(0.5f)
        val hh = (rect.height() * f / 2f).coerceAtMost(0.5f)
        val l = (cx - hw).coerceIn(0f, 1f - 2f * hw)
        val t = (cy - hh).coerceIn(0f, 1f - 2f * hh)
        rect.set(l, t, l + 2f * hw, t + 2f * hh)
        ensureMin()
        invalidate(); onChange()
    }

    fun reset() {
        rect.set(first)
        invalidate(); onChange()
    }

    /** Kotak dalam piksel tampilan (untuk menaruh bilah tombol). */
    fun rectPx() = RectF(rect.left * width, rect.top * height, rect.right * width, rect.bottom * height)

    /** Kotak dalam piksel tangkapan layar. */
    fun pixelRect(): Rect {
        val l = (rect.left * shot.width).roundToInt().coerceIn(0, shot.width - 1)
        val t = (rect.top * shot.height).roundToInt().coerceIn(0, shot.height - 1)
        val r = (rect.right * shot.width).roundToInt().coerceIn(l + 1, shot.width)
        val b = (rect.bottom * shot.height).roundToInt().coerceIn(t + 1, shot.height)
        return Rect(l, t, r, b)
    }
}
