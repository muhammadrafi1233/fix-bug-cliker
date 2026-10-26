package com.rafi.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Satu penanda langkah, dalam koordinat layar acuan script. [area] = area cari (hanya langkah "Cari gambar"). */
private class Mark(val number: Int, val search: Boolean, val ax: Float, val ay: Float, val area: RectF?)

private fun realDisplayMetrics(ctx: Context): DisplayMetrics {
    val dm = DisplayMetrics()
    val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    @Suppress("DEPRECATION") d.getRealMetrics(dm)
    return dm
}

/**
 * Penanda nomor langkah yang SELALU tampil di layar (satu jendela layar penuh, tidak menghalangi sentuhan),
 * di titik tempat tiap langkah bekerja: lingkaran bernomor di titik klik / gambar, dan kotak putus-putus untuk
 * area cari gambar. Tampil walau script belum jalan atau langkah belum mengklik. Langkah yang sedang dikerjakan
 * berwarna kuning. Semua fungsi dipanggil di main thread.
 */
class StepOverlay(private val ctx: Context, private val store: ScriptStore) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: MarksView? = null
    private var slug = ""
    private var current = 0

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Android 12+ memblokir sentuhan yang menembus jendela overlay berlapis > 0,8; jendela layar penuh ini
        // harus tetap membiarkan jari pengguna sampai ke game.
        alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /** Tampilkan penanda untuk script [slug] (membaca ulang dari berkas). */
    fun show(slug: String) {
        this.slug = slug
        attach()
        load()
    }

    /** Tampilkan script [slug] dan sorot langkah [n] (mulai 1) sebagai langkah yang sedang dikerjakan. */
    fun focus(slug: String, n: Int) {
        if (slug != this.slug || view == null) show(slug)
        setCurrent(n)
    }

    /** Sorot langkah [n]; 0 = tanpa sorotan. */
    fun setCurrent(n: Int) {
        current = n
        view?.let { it.current = n; it.invalidate() }
    }

    /** Baca ulang script yang sedang ditampilkan (setelah langkah diedit atau gambar diganti). */
    fun reload() { if (view != null) load() }

    /** Sembunyikan sementara (mis. saat mengambil tangkapan layar) tanpa melepas jendelanya. */
    fun setVisible(v: Boolean) { view?.visibility = if (v) View.VISIBLE else View.INVISIBLE }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    private fun attach() {
        if (view != null) return
        val v = MarksView(ctx)
        view = v
        try { wm.addView(v, lp) } catch (_: Exception) { view = null }
    }

    private fun rectOf(o: JSONObject?): RectF? {
        if (o == null) return null
        val w = o.optInt("w")
        val h = o.optInt("h")
        if (w <= 0 || h <= 0) return null
        val x = o.optInt("x").toFloat()
        val y = o.optInt("y").toFloat()
        return RectF(x, y, x + w, y + h)
    }

    private fun load() {
        val v = view ?: return
        var out = ArrayList<Mark>()
        var rw = 2400
        var rh = 1080
        try {
            val acts = store.load(slug).getJSONArray("actions")
            val ref = store.refSize(slug)
            rw = ref.first; rh = ref.second
            for (i in 0 until acts.length()) {
                val a = acts.getJSONObject(i)
                val n = i + 1
                if (a.optString("type") == "Search Image") {
                    val orig = rectOf(a.optJSONArray("origin_rect")?.optJSONObject(0))
                    val area = rectOf(a.optJSONObject("rect")) ?: orig
                    val box = orig ?: area
                    val ax: Float
                    val ay: Float
                    if (a.optString("found_action_click") == "Position") {
                        ax = a.optDouble("x").toFloat(); ay = a.optDouble("y").toFloat()
                    } else if (box != null) {
                        ax = box.centerX(); ay = box.centerY()
                    } else continue
                    if (ax.isNaN() || ay.isNaN()) continue
                    out.add(Mark(n, true, ax, ay, area))
                } else {
                    val x = a.optDouble("x").toFloat()
                    val y = a.optDouble("y").toFloat()
                    if (x.isNaN() || y.isNaN()) continue
                    out.add(Mark(n, false, x, y, null))
                }
            }
        } catch (_: Exception) {
            out = ArrayList()
        }
        v.refW = rw; v.refH = rh; v.marks = out; v.current = current
        v.invalidate()
    }

    private class MarksView(private val c0: Context) : View(c0) {
        var marks: List<Mark> = emptyList()
        var refW = 2400
        var refH = 1080
        var current = 0

        private val d = c0.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 12f * d; color = Color.WHITE
        }
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * d
            pathEffect = DashPathEffect(floatArrayOf(10f * d, 7f * d), 0f)
        }

        override fun onDraw(c: Canvas) {
            if (marks.isEmpty()) return
            val dm = realDisplayMetrics(c0)
            // Script direkam landscape tapi layar sedang portrait (atau sebaliknya): posisi tidak bermakna, jangan digambar.
            if ((dm.widthPixels > dm.heightPixels) != (refW > refH)) return
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val ox = loc[0].toFloat()
            val oy = loc[1].toFloat()
            val sx = dm.widthPixels.toFloat() / refW
            val sy = dm.heightPixels.toFloat() / refH

            // 1) Kotak putus-putus area cari.
            for (m in marks) {
                val a = m.area ?: continue
                dash.color = if (m.number == current) 0xFFF5C637.toInt() else 0xB337872A.toInt()
                c.drawRect(a.left * sx - ox, a.top * sy - oy, a.right * sx - ox, a.bottom * sy - oy, dash)
            }

            // 2) Lingkaran bernomor; langkah yang berdekatan digabung jadi satu label ("4,5,6").
            val r = 13f * d
            val groups = ArrayList<ArrayList<Mark>>()
            for (m in marks) {
                val g = groups.firstOrNull { g0 ->
                    abs(g0[0].ax - m.ax) * sx < r * 1.6f && abs(g0[0].ay - m.ay) * sy < r * 1.6f
                }
                if (g != null) g.add(m) else groups.add(arrayListOf(m))
            }
            for (g in groups) {
                val cx = g[0].ax * sx - ox
                val cy = g[0].ay * sy - oy
                val isCur = g.any { it.number == current }
                val label = if (g.size <= 3) g.joinToString(",") { it.number.toString() }
                else g.take(3).joinToString(",") { it.number.toString() } + "+" + (g.size - 3)
                val half = max(r, txt.measureText(label) / 2f + 7f * d)
                val box = RectF(cx - half, cy - r, cx + half, cy + r)
                fill.color = when {
                    isCur -> 0xFFF5C637.toInt()
                    g.any { it.search } -> 0xE62F7D3A.toInt()
                    else -> 0xE6C77B1A.toInt()
                }
                c.drawRoundRect(box, r, r, fill)
                c.drawRoundRect(box, r, r, ring)
                txt.color = if (isCur) 0xFF2A3B24.toInt() else Color.WHITE
                c.drawText(label, cx, cy - (txt.descent() + txt.ascent()) / 2f, txt)
            }
        }
    }
}
