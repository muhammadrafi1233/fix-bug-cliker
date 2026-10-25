package com.rafi.autoclicker

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/*
 * Kit komponen UI "Rafi Automatic" bergaya RAFI HAYDAY: ladang hijau, krem, gandum kuning, papan kayu,
 * tombol/kartu "tebal" dengan tepi bawah solid.
 * Semua dibuat lewat kode (project ini tidak memakai layout XML) dan tanpa library tambahan.
 */

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Palette {
    val cream = 0xFFFFF6DA.toInt()
    val creamDeep = 0xFFF6E8B8.toInt()
    val surface = 0xFFFFFEF8.toInt()        // "paper"
    val inputBg = 0xFFFFFFFF.toInt()
    val green = 0xFF37872A.toInt()          // "grass"
    val greenDark = 0xFF25601C.toInt()      // "grass-d"
    val greenPastel = 0xFFB4DB8C.toInt()
    val greenSoft = 0xFFDFF1D2.toInt()
    val yellow = 0xFFF5C637.toInt()         // "wheat"
    val yellowDark = 0xFFC9971A.toInt()     // "wheat-d"
    val yellowSoft = 0xFFFFF3C2.toInt()
    val amberText = 0xFF8A6100.toInt()
    val red = 0xFFB23A28.toInt()
    val redSoft = 0xFFFADAD2.toInt()
    val redLine = 0xFFE9B3A8.toInt()
    val ink = 0xFF2A3B24.toInt()
    val inkSoft = 0xFF5D6B52.toInt()
    val line = 0xFFE6DAB0.toInt()
    val leaf = 0xFF1E4A18.toInt()
    val wood = 0xFFA8713A.toInt()
    val woodLight = 0xFFBC8449.toInt()
    val woodDark = 0xFF6B4220.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.shape(fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(fill)
    g.cornerRadius = dpf(radiusDp)
    if (strokeDp > 0f) g.setStroke(maxOf(1, dpf(strokeDp).toInt()), strokeColor)
    return g
}

fun Context.ripple(
    fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f, rippleColor: Int = 0x22000000
): Drawable {
    val content = shape(fill, radiusDp, strokeColor, strokeDp)
    val mask = shape(Color.WHITE, radiusDp)
    return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
}

/**
 * Kotak "tebal" ala HTML (box-shadow 0 Npx 0): [face] di atas, tepi bawah solid [edgeColor] setebal [edgeDp].
 * Bbila [faceTopDp] > 0 muka turun sebesar itu (keadaan ditekan), tepi bawah makin tipis.
 * Isi View harus diberi padding bawah tambahan sebesar [edgeDp].
 */
fun Context.chunk(face: Drawable, edgeColor: Int, radiusDp: Float, edgeDp: Int, faceTopDp: Int = 0): Drawable {
    val ld = LayerDrawable(arrayOf(shape(edgeColor, radiusDp), face))
    ld.setLayerInset(0, 0, dp(faceTopDp), 0, 0)
    ld.setLayerInset(1, 0, dp(faceTopDp), 0, dp(edgeDp - faceTopDp))
    return ld
}

/** Latar bilah atas: hijau rumput dengan garis bawah daun tua 3dp. */
fun Context.topBarBackground(): Drawable {
    val ld = LayerDrawable(arrayOf<Drawable>(ColorDrawable(Palette.leaf), ColorDrawable(Palette.green)))
    ld.setLayerInset(1, 0, 0, 0, dp(3))
    return ld
}

fun lp(
    w: Int = MATCH, h: Int = WRAP, weight: Float = 0f,
    l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(l, t, r, b)
    return p
}

// ---------------------------------------------------------------------------------------------
// Teks
// ---------------------------------------------------------------------------------------------

fun Context.text(t: CharSequence, sp: Float = 14f, color: Int = Palette.ink, strong: Boolean = false): TextView {
    val v = TextView(this)
    v.text = t
    v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    v.setTextColor(color)
    if (strong) v.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    v.setLineSpacing(0f, 1.12f)
    return v
}

fun Context.sectionTitle(t: String): TextView {
    val v = text(t, 17f, Palette.ink, true)
    v.setPadding(dp(4), 0, 0, 0)
    return v
}

// ---------------------------------------------------------------------------------------------
// Ikon (digambar dengan Canvas supaya seragam: garis 2 px, ujung bulat, grid 24)
// ---------------------------------------------------------------------------------------------

enum class Icon {
    PLAY, STOP, GEAR, IMAGE, UPLOAD, ACCESS, LAYERS, LIST, CHECK, ALERT, CLOSE, TRASH,
    UP, DOWN, MOVE, PLUS, RESET, LEAF, CLOCK, CHEVRON, INFO, BACK, TARGET, HOME,
    ROW, COLUMN   // susunan tombol melayang: ke samping / ke bawah
}

class IconDrawable(private val kind: Icon, tint: Int, private val px: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        color = tint
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tint
    }
    private val path = Path()

    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px

    fun setTint2(c: Int) { stroke.color = c; fill.color = c; invalidateSelf() }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, stroke)

    private fun poly(c: Canvas, close: Boolean, filled: Boolean, vararg pts: Float) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        if (close) path.close()
        if (filled) c.drawPath(path, fill)
        c.drawPath(path, stroke)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        c.scale(s, s)
        when (kind) {
            Icon.PLAY -> poly(c, true, true, 8f, 5.5f, 18.5f, 12f, 8f, 18.5f)
            Icon.ROW -> for (k in 0 until 3) {
                val r = RectF(2.5f + k * 7f, 8.5f, 7.5f + k * 7f, 15.5f)
                c.drawRoundRect(r, 1.5f, 1.5f, fill); c.drawRoundRect(r, 1.5f, 1.5f, stroke)
            }
            Icon.COLUMN -> for (k in 0 until 3) {
                val r = RectF(8.5f, 2.5f + k * 7f, 15.5f, 7.5f + k * 7f)
                c.drawRoundRect(r, 1.5f, 1.5f, fill); c.drawRoundRect(r, 1.5f, 1.5f, stroke)
            }
            Icon.STOP -> {
                val r = RectF(6f, 6f, 18f, 18f)
                c.drawRoundRect(r, 2f, 2f, fill)
                c.drawRoundRect(r, 2f, 2f, stroke)
            }
            Icon.GEAR -> {
                c.drawCircle(12f, 12f, 5.5f, stroke)
                c.drawCircle(12f, 12f, 1.8f, fill)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val cs = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                    line(c, 12f + cs * 8f, 12f + sn * 8f, 12f + cs * 10.2f, 12f + sn * 10.2f)
                }
            }
            Icon.IMAGE -> {
                c.drawRoundRect(RectF(3f, 4f, 21f, 20f), 3f, 3f, stroke)
                c.drawCircle(8.5f, 9.5f, 1.6f, fill)
                poly(c, false, false, 3.5f, 17f, 9f, 12.5f, 14f, 17f, 16.5f, 14.8f, 20.5f, 18f)
            }
            Icon.UPLOAD -> {
                line(c, 12f, 15f, 12f, 4.5f)
                poly(c, false, false, 7.5f, 9f, 12f, 4.5f, 16.5f, 9f)
                poly(c, false, false, 4f, 15f, 4f, 19.5f, 20f, 19.5f, 20f, 15f)
            }
            Icon.ACCESS -> {
                c.drawCircle(12f, 5f, 1.9f, fill)
                line(c, 5f, 9f, 19f, 9f)
                line(c, 12f, 9f, 12f, 14f)
                line(c, 12f, 14f, 8.5f, 20f)
                line(c, 12f, 14f, 15.5f, 20f)
            }
            Icon.LAYERS -> {
                poly(c, true, false, 12f, 3.5f, 20.5f, 8f, 12f, 12.5f, 3.5f, 8f)
                poly(c, false, false, 3.5f, 12f, 12f, 16.5f, 20.5f, 12f)
                poly(c, false, false, 3.5f, 16f, 12f, 20.5f, 20.5f, 16f)
            }
            Icon.LIST -> {
                for (k in 0 until 3) {
                    val y = 6f + k * 6f
                    c.drawCircle(5f, y, 1.2f, fill)
                    line(c, 9f, y, 20f, y)
                }
            }
            Icon.CHECK -> poly(c, false, false, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Icon.ALERT -> {
                poly(c, true, false, 12f, 4f, 21f, 19.5f, 3f, 19.5f)
                line(c, 12f, 10f, 12f, 14f)
                c.drawCircle(12f, 16.6f, 1f, fill)
            }
            Icon.CLOSE -> { line(c, 6f, 6f, 18f, 18f); line(c, 18f, 6f, 6f, 18f) }
            Icon.TRASH -> {
                line(c, 4f, 7f, 20f, 7f)
                poly(c, false, false, 6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f)
                poly(c, false, false, 9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f)
            }
            Icon.UP -> poly(c, false, false, 6f, 14.5f, 12f, 8.5f, 18f, 14.5f)
            Icon.DOWN -> poly(c, false, false, 6f, 9.5f, 12f, 15.5f, 18f, 9.5f)
            Icon.MOVE -> {
                line(c, 12f, 3.5f, 12f, 20.5f); line(c, 3.5f, 12f, 20.5f, 12f)
                poly(c, false, false, 9f, 6.5f, 12f, 3.5f, 15f, 6.5f)
                poly(c, false, false, 9f, 17.5f, 12f, 20.5f, 15f, 17.5f)
                poly(c, false, false, 6.5f, 9f, 3.5f, 12f, 6.5f, 15f)
                poly(c, false, false, 17.5f, 9f, 20.5f, 12f, 17.5f, 15f)
            }
            Icon.PLUS -> { line(c, 12f, 5f, 12f, 19f); line(c, 5f, 12f, 19f, 12f) }
            Icon.RESET -> {
                c.drawArc(RectF(5f, 5f, 19f, 19f), 300f, 300f, false, stroke)
                poly(c, false, false, 19.5f, 2.5f, 15.5f, 5.9f, 19.7f, 7.6f)
            }
            Icon.LEAF -> {
                path.reset()
                path.moveTo(5f, 19f)
                path.cubicTo(5f, 9f, 11f, 4f, 20f, 4f)
                path.cubicTo(20f, 13f, 15f, 19f, 5f, 19f)
                path.close()
                c.drawPath(path, stroke)
                line(c, 4f, 20f, 13f, 11f)
            }
            Icon.CLOCK -> {
                c.drawCircle(12f, 12f, 9f, stroke)
                poly(c, false, false, 12f, 7f, 12f, 12f, 15.5f, 14f)
            }
            Icon.CHEVRON -> poly(c, false, false, 9f, 6f, 15f, 12f, 9f, 18f)
            Icon.INFO -> {
                c.drawCircle(12f, 12f, 9f, stroke)
                line(c, 12f, 11f, 12f, 16.5f)
                c.drawCircle(12f, 7.6f, 1f, fill)
            }
            Icon.BACK -> {
                line(c, 19f, 12f, 5f, 12f)
                poly(c, false, false, 11f, 6f, 5f, 12f, 11f, 18f)
            }
            Icon.TARGET -> {
                c.drawCircle(12f, 12f, 8f, stroke)
                c.drawCircle(12f, 12f, 2f, fill)
            }
            Icon.HOME -> {
                poly(c, false, false, 3f, 11f, 12f, 3.5f, 21f, 11f)
                poly(c, false, false, 5.5f, 9.5f, 5.5f, 20.5f, 18.5f, 20.5f, 18.5f, 9.5f)
                poly(c, false, false, 10f, 20.5f, 10f, 14.5f, 14f, 14.5f, 14f, 20.5f)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { stroke.colorFilter = cf; fill.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun Context.iconView(kind: Icon, tint: Int, sizeDp: Int = 22): ImageView {
    val v = ImageView(this)
    v.setImageDrawable(IconDrawable(kind, tint, dp(sizeDp)))
    v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    return v
}

/** Lingkaran lembut berisi ikon (dipakai di baris status dan state kosong). */
fun Context.iconBadge(kind: Icon, tint: Int, bg: Int, sizeDp: Int = 40): FrameLayout {
    val box = FrameLayout(this)
    box.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
    val iv = iconView(kind, tint, (sizeDp * 0.55f).toInt())
    box.addView(iv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    return box
}

// ---------------------------------------------------------------------------------------------
// Logo RAFI HAYDAY: lingkaran krem berbingkai hijau dengan gandum kuning
// ---------------------------------------------------------------------------------------------

class LogoView(ctx: Context, private val animated: Boolean = true) : View(ctx) {
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4025501F }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.cream }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.green; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.greenDark; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3.5f
    }
    private val grainFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.yellow }
    private val grainEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.yellowDark; style = Paint.Style.STROKE; strokeWidth = 1.2f
    }
    private var sway = 0f
    private var anim: ValueAnimator? = null

    private fun startAnim() {
        if (!animated || anim != null) return
        anim = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 1900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { sway = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); startAnim() }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        // Gambar logo 64x64 (sama dengan logo di HTML), diberi ruang 4 satuan di kanan/bawah untuk bayangan.
        val s = minOf(width, height) / 68f
        c.save()
        c.scale(s, s)
        c.translate(2f, 0f)
        c.drawCircle(32f, 35.5f, 30f, shadow)
        c.drawCircle(32f, 32f, 30f, disc)
        c.drawCircle(32f, 32f, 30f, ring)
        c.save()
        c.rotate(sway * 3f, 32f, 53f)
        c.drawLine(32f, 53f, 32f, 24f, stem)
        grain(c, 32f, 15f, 0f)
        grain(c, 24.5f, 24f, -35f); grain(c, 39.5f, 24f, 35f)
        grain(c, 25f, 34f, -35f); grain(c, 39f, 34f, 35f)
        c.restore()
        c.restore()
    }

    private fun grain(c: Canvas, x: Float, y: Float, angle: Float) {
        c.save()
        c.rotate(angle, x, y)
        val r = RectF(x - 4f, y - 7f, x + 4f, y + 7f)
        c.drawOval(r, grainFill)
        c.drawOval(r, grainEdge)
        c.restore()
    }
}

// ---------------------------------------------------------------------------------------------
// Latar ladang (pemandangan layar loading): langit, matahari, awan, lumbung, bukit, pagar
// ---------------------------------------------------------------------------------------------

class FarmBackgroundView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun fillPath(c: Canvas, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color
        c.drawPath(path, p)
    }

    private fun strokePath(c: Canvas, color: Int, w: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND
        c.drawPath(path, p)
    }

    private fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color
        c.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), p)
    }

    private fun hill(c: Canvas, color: Int, y0: Float, cx1: Float, cy1: Float, x1: Float, y1: Float,
                     cx2: Float, cy2: Float, x2: Float, y2: Float) {
        path.reset()
        path.moveTo(0f, y0); path.quadTo(cx1, cy1, x1, y1); path.quadTo(cx2, cy2, x2, y2)
        path.lineTo(400f, 800f); path.lineTo(0f, 800f); path.close()
        fillPath(c, color)
    }

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        // sama seperti SVG viewBox 400x800 dengan preserveAspectRatio "xMidYMax slice"
        val s = maxOf(width / 400f, height / 800f)
        c.save()
        c.translate((width - 400f * s) / 2f, height - 800f * s)
        c.scale(s, s)

        // langit
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, 800f,
            intArrayOf(0xFF8FD3F0.toInt(), 0xFFD9F1E4.toInt(), 0xFFFFF1BC.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 400f, 800f, p)
        p.shader = null

        // matahari + awan
        oval(c, 312f, 150f, 66f, 66f, 0x59FFE27A)
        oval(c, 312f, 150f, 46f, 46f, 0xFFFFE27A.toInt())
        oval(c, 86f, 140f, 46f, 15f, 0xEBFFFFFF.toInt()); oval(c, 116f, 128f, 30f, 14f, 0xEBFFFFFF.toInt())
        oval(c, 250f, 250f, 40f, 12f, 0xEBFFFFFF.toInt()); oval(c, 272f, 240f, 24f, 11f, 0xEBFFFFFF.toInt())

        // lumbung
        p.style = Paint.Style.FILL; p.color = 0xFFC2432F.toInt(); c.drawRect(40f, 452f, 100f, 498f, p)
        path.reset(); path.moveTo(34f, 452f); path.lineTo(70f, 424f); path.lineTo(106f, 452f); path.close()
        fillPath(c, 0xFF8A3325.toInt())
        p.color = 0xFFFFF6DA.toInt(); c.drawRect(62f, 470f, 78f, 498f, p)

        // bukit belakang + tengah
        hill(c, 0xFF8FCB5B.toInt(), 520f, 100f, 440f, 200f, 500f, 300f, 560f, 400f, 470f)
        hill(c, 0xFF6DB33F.toInt(), 590f, 120f, 520f, 240f, 580f, 360f, 640f, 400f, 560f)

        // pagar kayu
        val fence = 0xFF8A5A2B.toInt()
        path.reset(); path.moveTo(250f, 596f); path.quadTo(320f, 588f, 400f, 594f); strokePath(c, fence, 5f)
        path.reset(); path.moveTo(250f, 610f); path.quadTo(320f, 602f, 400f, 608f); strokePath(c, fence, 5f)
        for ((x, y0, y1) in listOf(Triple(262f, 586f, 622f), Triple(296f, 582f, 618f), Triple(330f, 582f, 618f),
            Triple(364f, 584f, 620f), Triple(396f, 586f, 622f))) {
            path.reset(); path.moveTo(x, y0); path.lineTo(x, y1); strokePath(c, fence, 5f)
        }

        // bukit depan + garis ladang
        hill(c, 0xFF4E9A32.toInt(), 680f, 140f, 620f, 260f, 670f, 380f, 720f, 400f, 650f)
        val row = 0xBFF0C94A.toInt()
        path.reset(); path.moveTo(0f, 724f); path.quadTo(200f, 690f, 400f, 722f); strokePath(c, row, 3f)
        path.reset(); path.moveTo(0f, 754f); path.quadTo(200f, 720f, 400f, 752f); strokePath(c, row, 3f)
        path.reset(); path.moveTo(0f, 784f); path.quadTo(200f, 750f, 400f, 782f); strokePath(c, row, 3f)
        c.restore()
    }
}

// ---------------------------------------------------------------------------------------------
// Papan nama kayu ("sign") dengan dua paku
// ---------------------------------------------------------------------------------------------

class WoodSign(ctx: Context, label: String) : TextView(ctx) {
    private val nail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B3818.toInt() }

    init {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setTextColor(Palette.cream)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.03f
        setShadowLayer(0.01f, 0f, ctx.dpf(3f), 0xB34B2C12.toInt())
        gravity = Gravity.CENTER
        maxLines = 1
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.woodLight, Palette.wood)).apply {
            cornerRadius = ctx.dpf(14f)
            setStroke(ctx.dp(4), Palette.woodDark)
        }
        background = ctx.chunk(face, Palette.woodDark, 14f, 6)
        setPadding(ctx.dp(30), ctx.dp(10), ctx.dp(30), ctx.dp(12 + 6))
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cy = (height - context.dp(6)) / 2f
        c.drawCircle(context.dpf(13f), cy, context.dpf(4f), nail)
        c.drawCircle(width - context.dpf(13f), cy, context.dpf(4f), nail)
    }
}

// ---------------------------------------------------------------------------------------------
// Progress bar bulat yang bergerak halus
// ---------------------------------------------------------------------------------------------

class RoundedProgress(ctx: Context) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xBFFFFFFF.toInt() }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.green }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.woodDark; style = Paint.Style.STROKE; strokeWidth = ctx.dpf(3f)
    }
    private var value = 0f
    private var anim: ValueAnimator? = null

    fun animateTo(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(value, target.coerceIn(0f, 1f))
        a.duration = durationMs
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { value = it.animatedValue as Float; invalidate() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) onEnd?.invoke() }
        })
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bw = border.strokeWidth
        val r = minOf(context.dpf(10f), h / 2)
        val outer = RectF(bw / 2, bw / 2, w - bw / 2, h - bw / 2)
        c.drawRoundRect(outer, r, r, track)
        if (value > 0f) {
            c.save()
            c.clipPath(Path().apply { addRoundRect(RectF(bw, bw, w - bw, h - bw), r - bw / 2, r - bw / 2, Path.Direction.CW) })
            c.drawRect(bw, bw, bw + (w - 2 * bw) * value, h - bw, bar)
            c.restore()
        }
        c.drawRoundRect(outer, r, r, border)
    }
}

// ---------------------------------------------------------------------------------------------
// Chip status
// ---------------------------------------------------------------------------------------------

enum class Tone { OK, RUN, WARN, ERROR, IDLE }

class PulseDot(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulse = false
    private var anim: ValueAnimator? = null

    fun set(color: Int, pulsing: Boolean) {
        p.color = color
        pulse = pulsing
        if (!pulsing) { anim?.cancel(); anim = null; alpha = 1f } else if (isAttachedToWindow) startPulse()
        invalidate()
    }

    private fun startPulse() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(1f, 0.3f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (pulse) startPulse() }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) { c.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, p) }
}

class StatusChip(ctx: Context) : LinearLayout(ctx) {
    private val dot = PulseDot(ctx)
    private val tv = TextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(10), ctx.dp(5), ctx.dp(12), ctx.dp(5))
        val dl = LayoutParams(ctx.dp(8), ctx.dp(8))
        dl.rightMargin = ctx.dp(6)
        addView(dot, dl)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tv.maxLines = 1
        addView(tv, LayoutParams(WRAP, WRAP))
    }

    fun set(label: String, tone: Tone) {
        val c = when (tone) {
            Tone.OK -> intArrayOf(Palette.greenSoft, Palette.green)
            Tone.RUN -> intArrayOf(Palette.greenPastel, Palette.greenDark)
            Tone.WARN -> intArrayOf(Palette.yellowSoft, Palette.amberText)
            Tone.ERROR -> intArrayOf(Palette.redSoft, Palette.red)
            Tone.IDLE -> intArrayOf(Palette.creamDeep, Palette.inkSoft)
        }
        val bg = c[0]
        val fg = c[1]
        background = context.shape(bg, 20f)
        tv.setTextColor(fg)
        tv.text = label
        dot.set(fg, tone == Tone.RUN)
    }
}

/** Pil kecil serbaguna (mis. ringkasan aksi langkah). */
fun Context.pill(t: String, bg: Int, fg: Int): TextView {
    val v = text(t, 12f, fg, true)
    v.setPadding(dp(9), dp(3), dp(9), dp(3))
    v.background = shape(bg, 20f)
    v.maxLines = 2
    return v
}

// ---------------------------------------------------------------------------------------------
// Tombol
// ---------------------------------------------------------------------------------------------

enum class BtnStyle { PRIMARY, YELLOW, SECONDARY, DANGER }

class AppButton(
    ctx: Context, label: String, style: BtnStyle = BtnStyle.PRIMARY, private val icon: Icon? = null
) : LinearLayout(ctx) {
    private val iconView = ImageView(ctx)
    private val spinner = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall)
    private val labelView = TextView(ctx)
    private var idleLabel = label
    private var busy = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = ctx.dp(52)
        isClickable = true
        isFocusable = true
        setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8 + 4))   // +4dp = tepi bawah tebal

        // [muka, teks, garis tepi muka, tepi bawah solid]
        val col = when (style) {
            BtnStyle.PRIMARY -> intArrayOf(Palette.green, Color.WHITE, Palette.greenDark, Palette.greenDark)
            BtnStyle.YELLOW -> intArrayOf(Palette.yellow, Palette.leaf, Palette.yellowDark, Palette.yellowDark)
            BtnStyle.SECONDARY -> intArrayOf(Palette.surface, Palette.ink, Palette.line, Palette.line)
            BtnStyle.DANGER -> intArrayOf(Palette.surface, Palette.red, Palette.redLine, Palette.redLine)
        }
        val fill = col[0]
        val fg = col[1]
        val stroke = col[2]
        val edge = col[3]
        fun face() = ctx.shape(fill, 14f, stroke, 2f)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ctx.chunk(face(), edge, 14f, 4, 3))
            addState(intArrayOf(), ctx.chunk(face(), edge, 14f, 4))
        }

        if (icon != null) {
            iconView.setImageDrawable(IconDrawable(icon, fg, ctx.dp(20)))
            iconView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val il = LayoutParams(ctx.dp(20), ctx.dp(20))
            il.rightMargin = ctx.dp(8)
            addView(iconView, il)
        }
        spinner.indeterminateTintList = ColorStateList.valueOf(fg)
        spinner.visibility = View.GONE
        val sl = LayoutParams(ctx.dp(18), ctx.dp(18))
        sl.rightMargin = ctx.dp(8)
        addView(spinner, sl)

        labelView.text = label
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        labelView.setTextColor(fg)
        labelView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        labelView.gravity = Gravity.CENTER
        labelView.maxLines = 2
        addView(labelView, LayoutParams(WRAP, WRAP))
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.55f
    }

    /** Saat ditekan, isi tombol ikut turun 3dp (muka tombol turun menutupi tepi bawah). */
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val ty = if (pressed && isEnabled) context.dp(3).toFloat() else 0f
        for (k in 0 until childCount) getChildAt(k).translationY = ty
    }

    fun setLabel(t: String) {
        idleLabel = t
        if (!busy) labelView.text = t
    }

    /** Tampilkan status "sedang memproses" (spinner + teks), tombol tidak bisa ditekan lagi. */
    fun setBusy(b: Boolean, busyText: String? = null) {
        busy = b
        spinner.visibility = if (b) View.VISIBLE else View.GONE
        if (icon != null) iconView.visibility = if (b) View.GONE else View.VISIBLE
        labelView.text = if (b && busyText != null) busyText else idleLabel
        isEnabled = !b
    }
}

// ---------------------------------------------------------------------------------------------
// Kartu & baris status
// ---------------------------------------------------------------------------------------------

fun Context.card(padDp: Int = 16): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.background = chunk(shape(Palette.surface, 18f, Palette.line, 2f), Palette.line, 18f, 4)
    c.elevation = 0f
    val p = dp(padDp)
    c.setPadding(p, p, p, p + dp(4))   // +4dp = tepi bawah tebal
    return c
}

fun Context.divider(): View {
    val v = View(this)
    v.setBackgroundColor(Palette.line)
    v.layoutParams = lp(MATCH, maxOf(1, dp(1)), 0f, 0, dp(12), 0, dp(12))
    return v
}

/** Baris status: ikon, judul, keterangan, chip, dan (opsional) tombol aksi bila belum siap. */
class StatusRow(ctx: Context, private val kind: Icon, title: String) : LinearLayout(ctx) {
    private val chip = StatusChip(ctx)
    private val detail = ctx.text("", 13f, Palette.inkSoft)
    private val action = AppButton(ctx, "", BtnStyle.SECONDARY)
    private val badgeHolder = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        val row = LinearLayout(ctx)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(badgeHolder, LayoutParams(WRAP, WRAP))
        val col = LinearLayout(ctx)
        col.orientation = VERTICAL
        col.addView(ctx.text(title, 15f, Palette.ink, true))
        col.addView(detail)
        row.addView(col, LayoutParams(0, WRAP, 1f).apply { leftMargin = ctx.dp(12); rightMargin = ctx.dp(8) })
        row.addView(chip, LayoutParams(WRAP, WRAP))
        addView(row, LayoutParams(MATCH, WRAP))
        action.visibility = View.GONE
        addView(action, LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(10) })
        setBadge(Palette.green, Palette.greenSoft)
    }

    private fun setBadge(fg: Int, bg: Int) {
        badgeHolder.removeAllViews()
        badgeHolder.addView(context.iconBadge(kind, fg, bg, 40))
    }

    fun update(detailText: String, tone: Tone, chipText: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        detail.text = detailText
        chip.set(chipText, tone)
        when (tone) {
            Tone.OK, Tone.RUN -> setBadge(Palette.green, Palette.greenSoft)
            Tone.WARN -> setBadge(Palette.amberText, Palette.yellowSoft)
            Tone.ERROR -> setBadge(Palette.red, Palette.redSoft)
            Tone.IDLE -> setBadge(Palette.inkSoft, Palette.creamDeep)
        }
        if (actionLabel != null && onAction != null && tone != Tone.OK) {
            action.setLabel(actionLabel)
            action.setOnClickListener { onAction() }
            action.visibility = View.VISIBLE
        } else {
            action.visibility = View.GONE
        }
    }
}

/** Layout yang lebarnya dibatasi (tablet / layar lebar) dan tetap rata tengah. */
class MaxWidthLayout(ctx: Context, private val maxDp: Int) : LinearLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = context.dp(maxDp)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (size > max) MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}

// ---------------------------------------------------------------------------------------------
// Form
// ---------------------------------------------------------------------------------------------

fun Context.inputBackground(error: Boolean): Drawable {
    val sl = StateListDrawable()
    sl.addState(intArrayOf(android.R.attr.state_focused), shape(Palette.inputBg, 12f, if (error) Palette.red else Palette.green, 2f))
    sl.addState(intArrayOf(), shape(Palette.inputBg, 12f, if (error) Palette.red else Palette.line, 2f))
    return sl
}

fun Context.styledEdit(value: String, decimal: Boolean, fid: Int): EditText {
    val e = EditText(this)
    if (fid != 0) e.id = fid
    e.setText(value)
    e.inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
    e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    e.setTextColor(Palette.ink)
    e.isSingleLine = true
    e.setMinHeight(dp(48))
    e.setPadding(dp(14), dp(8), dp(14), dp(8))
    e.background = inputBackground(false)
    return e
}

fun Context.setEditError(e: EditText, error: Boolean) {
    e.background = inputBackground(error)
}

class FormField(val container: LinearLayout, val edit: EditText)

/** Label + kolom isian + teks bantuan. Warna merah ditampilkan saat isian tidak valid dan hilang saat diubah. */
fun Context.formField(title: String, helper: String?, value: String, decimal: Boolean, fid: Int): FormField {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(0, dp(8), 0, dp(6))
    box.addView(text(title, 13.5f, Palette.ink, true), lp(MATCH, WRAP, 0f, 0, 0, 0, dp(6)))
    val e = styledEdit(value, decimal, fid)
    box.addView(e, lp(MATCH, WRAP))
    if (helper != null) box.addView(text(helper, 12f, Palette.inkSoft), lp(MATCH, WRAP, 0f, dp(2), dp(5), 0, 0))
    e.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { setEditError(e, false) }
    })
    return FormField(box, e)
}

// ---------------------------------------------------------------------------------------------
// Dialog
// ---------------------------------------------------------------------------------------------

fun Context.styleDialog(d: AlertDialog) {
    d.window?.setBackgroundDrawable(InsetDrawable(shape(Palette.surface, 22f, Palette.line, 2f), dp(14)))
    d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Palette.green)
    d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Palette.inkSoft)
}

/** Konfirmasi untuk tindakan penting. [danger] mewarnai tombol utama merah. */
fun Context.confirm(title: String, message: String, positive: String, danger: Boolean = false, onYes: () -> Unit) {
    val d = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positive) { _, _ -> onYes() }
        .setNegativeButton("Batal", null)
        .create()
    d.show()
    styleDialog(d)
    if (danger) d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Palette.red)
}


// ---------------------------------------------------------------------------------------------
// Navigasi bawah
// ---------------------------------------------------------------------------------------------

/**
 * Bilah navigasi bawah: tab di kiri dan kanan, tombol bulat kuning di tengah yang sedikit menonjol.
 * [centerAfter] = jumlah tab di sebelah kiri tombol tengah.
 */
class BottomNav(
    ctx: Context,
    private val tabs: List<Pair<Icon, String>>,
    centerAfter: Int,
    private val onTab: (Int) -> Unit,
    onCenter: () -> Unit,
    centerDesc: String
) : FrameLayout(ctx) {
    private val icons = ArrayList<ImageView>()
    private val labels = ArrayList<TextView>()
    private val items = ArrayList<LinearLayout>()

    private fun d(v: Int) = context.dp(v)

    init {
        clipChildren = false
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(d(6), d(10), d(6), d(8))
        // kertas krem muda dengan garis atas 2dp
        bar.background = LayerDrawable(arrayOf<Drawable>(ColorDrawable(Palette.line), ColorDrawable(Palette.surface)))
            .also { it.setLayerInset(1, 0, d(2), 0, 0) }
        bar.elevation = 0f

        for ((i, t) in tabs.withIndex()) {
            if (i == centerAfter) bar.addView(View(ctx), LinearLayout.LayoutParams(d(76), 1))
            val item = LinearLayout(ctx)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.setPadding(0, d(6), 0, d(6))
            item.background = context.ripple(0x00000000, 14f, 0, 0f, 0x222F7D3A)
            item.isClickable = true
            item.contentDescription = t.second
            item.setOnClickListener { onTab(i) }
            val iv = ImageView(ctx)
            iv.setImageDrawable(IconDrawable(t.first, Palette.inkSoft, d(24)))
            iv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            item.addView(iv, LinearLayout.LayoutParams(d(24), d(24)))
            val tv = context.text(t.second, 11.5f, Palette.inkSoft, true)
            tv.gravity = Gravity.CENTER
            item.addView(tv, lp(WRAP, WRAP, 0f, 0, d(2), 0, 0))
            bar.addView(item, lp(0, WRAP, 1f))
            icons.add(iv); labels.add(tv); items.add(item)
        }
        addView(bar, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(14) })

        fun fabLayer(pressed: Boolean): Drawable {
            val t = if (pressed) d(3) else 0
            val edge = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.yellowDark) }
            val face = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Palette.yellow); setStroke(d(3), Palette.yellowDark)
            }
            return LayerDrawable(arrayOf<Drawable>(edge, face)).also {
                it.setLayerInset(0, 0, t, 0, 0)
                it.setLayerInset(1, 0, t, 0, d(4) - t)
            }
        }
        val fab = FrameLayout(ctx)
        fab.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), fabLayer(true))
            addState(intArrayOf(), fabLayer(false))
        }
        fab.elevation = 0f
        fab.isClickable = true
        fab.contentDescription = centerDesc
        fab.setOnClickListener { onCenter() }
        val plus = ImageView(ctx)
        plus.setImageDrawable(IconDrawable(Icon.PLUS, Palette.leaf, d(30)))
        plus.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        fab.addView(plus, FrameLayout.LayoutParams(d(30), d(30), Gravity.CENTER).apply { bottomMargin = d(4) })
        fab.setOnTouchListener { _, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> plus.translationY = d(3).toFloat()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> plus.translationY = 0f
            }
            false
        }
        addView(fab, FrameLayout.LayoutParams(d(58), d(62), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        select(0)
    }

    fun select(i: Int) {
        for (k in icons.indices) {
            val on = k == i
            icons[k].setImageDrawable(IconDrawable(tabs[k].first, if (on) Palette.green else Palette.inkSoft, d(24)))
            labels[k].setTextColor(if (on) Palette.greenDark else Palette.inkSoft)
            labels[k].typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
            items[k].isSelected = on
        }
    }
}
