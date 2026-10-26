package com.rafi.autoclicker

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tombol melayang dengan [slotCount] tombol ▶ (1..3, sesuai jumlah script yang dipilih pengguna), masing-masing
 * menjalankan script-nya sendiri secara independen — bisa ditekan satu-satu atau sekaligus.
 * Ketuk singkat tombol ▶ = jalankan/hentikan script di slot itu. Tekan agak lama (tahan) tombol ▶ =
 * buka daftar untuk memilih script yang ditempel ke slot itu.
 * Ketuk ✥ untuk melipat (tinggal ✥ + semua tombol ▶ yang dipakai: 1 script = ✥ + ▶1, 2 script = ✥ + ▶1 + ▶2, dst) atau membuka semua tombol (+ Pengaturan, Tutup);
 * geser ✥ untuk memindahkan. Tombol susunan (saat dibuka) mengganti tata letak: berjejer ke samping atau ke bawah.
 * Posisi dan susunan diingat untuk pemakaian berikutnya.
 */
class FloatingBubble(
    private val ctx: Context,
    private val slotCount: Int,                    // jumlah tombol ▶ yang ditampilkan (1..3)
    private val onToggle: (Int) -> Unit,            // slot 0,1,2 = tombol 1,2,3
    private val onSettings: () -> Unit,
    private val onClose: () -> Unit,
    private val listScripts: () -> List<ScriptInfo>,
    private val activeSlugFor: (Int) -> String,
    private val onPickScript: (Int, ScriptInfo) -> Unit,
    private val onSteps: (Boolean) -> Unit = {}     // tombol mata: tampil / sembunyikan penanda langkah di layar
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var box: LinearLayout? = null
    private val plays = arrayOfNulls<ImageView>(3)
    /** Status jalan/tidaknya tiap slot, dilacak di sini (bukan cuma lewat ikon) supaya bindPlay bisa langsung
     *  mengecek tanpa menunggu callback Handler yang bisa telat saat thread utama sibuk. */
    private val runningState = BooleanArray(3)
    private var panel: ScrollView? = null
    private val extras = ArrayList<View>()
    private var expanded = true

    private val ui = ctx.getSharedPreferences("bubble_ui", Context.MODE_PRIVATE)
    private var vertical = ui.getBoolean("vertical", false)
    private var orientBtn: ImageView? = null
    private var eyeBtn: ImageView? = null
    /** True = penanda langkah di layar ditampilkan (diingat untuk pemakaian berikutnya). */
    var stepsOn = ui.getBoolean("showSteps", true)
        private set

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = ui.getInt("x", 30); y = ui.getInt("y", 160) }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun circle(icon: Icon, color: Int, iconColor: Int, desc: String): ImageView = ImageView(ctx).apply {
        setImageDrawable(IconDrawable(icon, iconColor, dp(22)))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    }

    /** Lingkaran ▶ untuk slot [n] (0,1,2), dengan label angka kecil di pojok supaya tombol 1/2/3 mudah dibedakan. */
    private fun numberedPlay(n: Int): FrameLayout {
        val iv = circle(Icon.PLAY, Palette.green, 0xFFFFFFFF.toInt(), "Jalankan atau hentikan script tombol ${n + 1}")
        plays[n] = iv
        bindPlay(iv, n)
        if (slotCount <= 1) return wrapOnly(iv)
        val badge = TextView(ctx).apply {
            text = (n + 1).toString()
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF1E4A18.toInt()) }
        }
        val wrap = FrameLayout(ctx)
        // iv datang dari circle() dengan LinearLayout.LayoutParams; harus diganti FrameLayout.LayoutParams
        // di sini karena parent-nya sekarang FrameLayout (beda jenis, FrameLayout tidak toleran tipe lain).
        wrap.addView(iv, FrameLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        wrap.addView(badge, FrameLayout.LayoutParams(dp(16), dp(16)).apply { gravity = Gravity.TOP or Gravity.END })
        return wrap
    }

    /** Satu tombol ▶ saja: tanpa label angka. */
    private fun wrapOnly(iv: ImageView): FrameLayout {
        val wrap = FrameLayout(ctx)
        wrap.addView(iv, FrameLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        return wrap
    }

    /** Ketuk singkat = toggle start/stop slot [n]; tahan ~450ms = buka daftar pilih script untuk slot itu
     *  (hanya berlaku saat belum jalan — saat sedang jalan, satu sentuhan = stop, langsung, tanpa syarat). */
    private fun bindPlay(iv: ImageView, n: Int) {
        val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(450L)
        var longPressFired = false
        var stoppedOnDown = false
        val lpRunnable = Runnable { longPressFired = true; togglePanel(n) }
        iv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressFired = false
                    stoppedOnDown = false
                    // Umpan balik instan: tombol mengecil + getar ringan begitu disentuh.
                    iv.animate().cancel(); iv.animate().scaleX(0.86f).scaleY(0.86f).setDuration(50).start()
                    iv.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (runningState[n]) {
                        // Sedang jalan: stop langsung saat disentuh. Tidak menunggu lepas jari atau timer apa pun,
                        // supaya tombol Stop selalu responsif walau thread utama lagi sibuk menjalankan script.
                        stoppedOnDown = true
                        onToggle(n)
                    } else {
                        main.postDelayed(lpRunnable, longPressMs)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(lpRunnable)
                    iv.animate().cancel(); iv.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (!stoppedOnDown && !longPressFired) {
                        // Pakai durasi sentuhan ASLI dari sistem input (eventTime - downTime), bukan urutan
                        // Handler yang bisa telat diproses saat thread utama sibuk — supaya ketukan cepat
                        // tetap dikenali benar sebagai "ketuk", bukan ketuju jadi "tekan lama".
                        val held = e.eventTime - e.downTime
                        if (held < longPressMs) onToggle(n)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(lpRunnable)
                    iv.animate().cancel(); iv.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }
            }
            true
        }
    }

    fun show() {
        if (box != null) return
        val b = LinearLayout(ctx).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(0xE61E4A18.toInt()) }
        }
        val handle = circle(Icon.MOVE, 0xFF4F6B54.toInt(), 0xFFFFFFFF.toInt(), "Geser tombol melayang, ketuk untuk melipat atau membuka")
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        var moved = false
        val slop = dp(8)
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = startX + dx; lp.y = startY + dy
                        clamp()
                        wm.updateViewLayout(b, lp)
                        hidePanel()
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) setExpanded(!expanded) else savePos()
            }
            true
        }
        val pl = (0 until slotCount.coerceIn(1, plays.size)).map { numberedPlay(it) }
        val o = circle(if (vertical) Icon.ROW else Icon.COLUMN, 0xFF4F6B54.toInt(), 0xFFFFFFFF.toInt(), orientDesc())
            .also { it.setOnClickListener { toggleOrientation() } }
        orientBtn = o
        val eye = circle(Icon.EYE, 0xFF4F6B54.toInt(), 0xFFFFFFFF.toInt(), eyeDesc()).also { iv ->
            iv.setOnClickListener {
                stepsOn = !stepsOn
                ui.edit().putBoolean("showSteps", stepsOn).apply()
                refreshEye()
                onSteps(stepsOn)
            }
        }
        eyeBtn = eye
        refreshEye()
        val s = circle(Icon.GEAR, Palette.yellow, Palette.ink, "Buka Rafi Automatic").also { it.setOnClickListener { onSettings() } }
        val c = circle(Icon.CLOSE, Palette.red, 0xFFFFFFFF.toInt(), "Tutup tombol melayang").also { it.setOnClickListener { onClose() } }
        b.addView(handle); pl.forEach { b.addView(it) }; b.addView(o); b.addView(eye); b.addView(s); b.addView(c)
        extras.clear(); extras.add(o); extras.add(eye); extras.add(s); extras.add(c)   // saat diperkecil: ✥ + semua tombol ▶ tetap tampil
        box = b
        applyExpanded()
        wm.addView(b, lp)
    }

    private fun applyExpanded() {
        for (v in extras) v.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun setExpanded(e: Boolean) {
        expanded = e
        applyExpanded()
        if (!e) hidePanel()
        relayout()
    }

    private fun eyeDesc() = if (stepsOn) "Sembunyikan penanda langkah di layar" else "Tampilkan penanda langkah di layar"

    /** Tombol mata terang bila penanda langkah tampil, redup bila disembunyikan. */
    private fun refreshEye() {
        val iv = eyeBtn ?: return
        iv.alpha = if (stepsOn) 1f else 0.5f
        (iv.background as? GradientDrawable)?.setColor(if (stepsOn) Palette.green else 0xFF4F6B54.toInt())
        iv.contentDescription = eyeDesc()
    }

    /** Sembunyikan / tampilkan seluruh tombol melayang tanpa melepasnya (dipakai saat mengambil tangkapan layar). */
    fun setVisible(v: Boolean) {
        if (!v) hidePanel()
        box?.visibility = if (v) View.VISIBLE else View.INVISIBLE
    }

    private fun orientDesc() = if (vertical) "Susun tombol ke samping" else "Susun tombol ke bawah"

    /** Ganti susunan tombol: berjejer ke samping (horizontal) atau ke bawah (vertikal). Diingat untuk berikutnya. */
    private fun toggleOrientation() {
        vertical = !vertical
        ui.edit().putBoolean("vertical", vertical).apply()
        hidePanel()
        box?.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        orientBtn?.let {
            it.setImageDrawable(IconDrawable(if (vertical) Icon.ROW else Icon.COLUMN, 0xFFFFFFFF.toInt(), dp(22)))
            it.contentDescription = orientDesc()
        }
        relayout()
    }

    /** Menjaga tombol tetap di dalam layar supaya tidak hilang setelah digeser, dilipat, atau diputar susunannya. */
    private fun clamp() {
        val b = box ?: return
        val dm = ctx.resources.displayMetrics
        val w = if (b.width > 0) b.width else dp(56)
        val h = if (b.height > 0) b.height else dp(56)
        lp.x = lp.x.coerceIn(0, maxOf(0, dm.widthPixels - w))
        lp.y = lp.y.coerceIn(0, maxOf(0, dm.heightPixels - h))
    }

    private fun savePos() { ui.edit().putInt("x", lp.x).putInt("y", lp.y).apply() }

    /** Perbarui jendela, lalu setelah ukurannya berubah (lipat/buka, ganti susunan) rapikan posisinya. */
    private fun relayout() {
        val b = box ?: return
        try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
        b.postDelayed({
            if (box !== b) return@postDelayed
            clamp()
            try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
            savePos()
        }, 60)
    }

    // ------------------------------------------------------------------------------------------
    // Daftar pilih script
    // ------------------------------------------------------------------------------------------

    private var hiddenAt = 0L

    private fun togglePanel(slot: Int) {
        if (panel != null) { hidePanel(); return }
        // Ketukan di tombol ini saat daftar terbuka sudah menutupnya lewat "ketuk di luar"; jangan dibuka lagi.
        if (System.currentTimeMillis() - hiddenAt < 400) return
        showPanel(slot)
    }

    private fun showPanel(slot: Int) {
        val bar = box ?: return
        val items = listScripts()
        val active = activeSlugFor(slot)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        col.addView(label("Script untuk tombol ${slot + 1}", false).apply { alpha = 0.7f })
        if (items.isEmpty()) {
            col.addView(label("Belum ada script", false))
        }
        for (s in items) {
            val isActive = s.slug == active
            val row = label(s.name + if (s.ok) "  ·  ${s.steps}" else "  ·  rusak", isActive)
            row.setOnClickListener {
                hidePanel()
                onPickScript(slot, s)
            }
            col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(2), 0, dp(2)) })
        }
        val sv = ScrollView(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xF01E4A18.toInt()) }
            addView(col)
            // Ketuk di luar daftar menutupnya.
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { hidePanel(); true } else false }
        }
        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels
        col.measure(
            View.MeasureSpec.makeMeasureSpec(screenW - dp(24), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val ph = minOf(col.measuredHeight, minOf(dp(280), screenH - dp(80)))
        val pw = maxOf(col.measuredWidth, dp(180))
        val barH = if (bar.height > 0) bar.height else dp(56)
        val barW = if (bar.width > 0) bar.width else dp(56)
        val px: Int
        val y: Int
        if (vertical) {
            // Susunan ke bawah: daftar muncul di samping kanan (atau kiri bila tidak muat), sejajar dengan tombol.
            val right = lp.x + barW + dp(6)
            px = if (right + pw > screenW - dp(8)) maxOf(0, lp.x - pw - dp(6)) else right
            y = lp.y.coerceAtMost(maxOf(0, screenH - ph - dp(24)))
        } else {
            px = lp.x
            val below = lp.y + barH + dp(6)
            y = if (below + ph > screenH - dp(24)) maxOf(0, lp.y - ph - dp(6)) else below
        }
        val plp = WindowManager.LayoutParams(
            pw, ph,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = px; this.y = y }
        try { wm.addView(sv, plp); panel = sv } catch (_: Exception) { panel = null }
    }

    private fun label(t: String, active: Boolean): TextView = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(0xFFFFFFFF.toInt())
        typeface = if (active) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
        setPadding(dp(14), dp(10), dp(14), dp(10))
        minWidth = dp(168)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (active) Palette.green else 0x00000000)
        }
        isClickable = true
    }

    private fun hidePanel() {
        if (panel != null) hiddenAt = System.currentTimeMillis()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    // ------------------------------------------------------------------------------------------

    fun setRunning(slot: Int, r: Boolean) {
        if (slot in runningState.indices) runningState[slot] = r
        val iv = plays.getOrNull(slot) ?: return
        iv.setImageDrawable(IconDrawable(if (r) Icon.STOP else Icon.PLAY, if (r) Palette.ink else 0xFFFFFFFF.toInt(), dp(22)))
        (iv.background as? GradientDrawable)?.setColor(if (r) Palette.yellow else Palette.green)
    }

    fun hide() {
        hidePanel()
        box?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        box = null
        orientBtn = null
        eyeBtn = null
        for (i in plays.indices) plays[i] = null
        for (i in runningState.indices) runningState[i] = false
    }
}
