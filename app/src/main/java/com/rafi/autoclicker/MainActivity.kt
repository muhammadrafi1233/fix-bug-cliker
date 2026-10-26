package com.rafi.autoclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Halaman utama Rafi Automatic: sapaan + tombol Mulai, ringkasan status sistem, ringkasan tugas,
 * aktivitas terbaru, dan pengaturan. Seluruh logika (preferensi, impor, MediaProjection, service)
 * sama seperti sebelumnya; yang berubah hanya tampilan dan umpan balik ke pengguna.
 */
class MainActivity : Activity() {
    private lateinit var store: ScriptStore
    private lateinit var scroll: ScrollView

    private lateinit var banner: LinearLayout
    private lateinit var bannerIconHolder: LinearLayout
    private lateinit var bannerText: TextView

    private lateinit var overallChip: StatusChip
    private lateinit var overallText: TextView
    private lateinit var btnStart: AppButton
    private lateinit var btnStop: AppButton
    private lateinit var btnImport: AppButton

    private lateinit var rowImages: StatusRow
    private lateinit var rowA11y: StatusRow
    private lateinit var rowOverlay: StatusRow

    private lateinit var statTotal: TextView
    private lateinit var statImg: TextView
    private lateinit var statClick: TextView
    private lateinit var statCaption: TextView

    private lateinit var logBox: LinearLayout
    private lateinit var scriptBox: LinearLayout
    private lateinit var nav: BottomNav
    private lateinit var pageHome: LinearLayout
    private lateinit var pageScripts: LinearLayout
    private lateinit var pageLog: LinearLayout
    private lateinit var pageSettings: LinearLayout
    private lateinit var activeText: TextView
    private var currentTab = 0
    private lateinit var fFrom: FormField
    private lateinit var fTo: FormField
    private lateinit var rangeHint: TextView
    private var rangeSlug: String? = null   // script yang sedang tampil di isian rentang
    private lateinit var fRepeatN: FormField
    private lateinit var repeatHint: TextView
    private val repeatPills = arrayOfNulls<TextView>(3)
    private var repeatMode = 2              // 0 = sekali, 1 = N kali, 2 = tanpa batas
    private var repeatSlug: String? = null  // script yang sedang tampil di kartu pengulangan

    private lateinit var fRefW: FormField
    private lateinit var fRefH: FormField
    private lateinit var fFloor: FormField
    private lateinit var fLoops: FormField
    private lateinit var fTimeout: FormField
    private lateinit var fClick: FormField
    private lateinit var fSearch: FormField
    private lateinit var fStepGap: FormField
    private lateinit var fSameGap: FormField
    private lateinit var markerSw: Switch

    private var scriptOk = true
    private var missingCount = 0
    private var starting = false
    private var importing = false

    private val REQ_IMPORT = 1
    private val REQ_PROJ = 2

    // ------------------------------------------------------------------------------------------
    // Siklus hidup
    // ------------------------------------------------------------------------------------------

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = ScriptStore(this)
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setBackgroundColor(Palette.cream)
        val col = MaxWidthLayout(this, 640)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(12), dp(16), dp(32))
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))

        // Halaman dipisah per tab; bilah navigasi di bawah (gaya aplikasi) memilih halaman yang tampil.
        val rootV = LinearLayout(this)
        rootV.orientation = LinearLayout.VERTICAL
        rootV.setBackgroundColor(Palette.cream)
        rootV.addView(scroll, lp(MATCH, 0, 1f))
        nav = BottomNav(
            this,
            listOf(Icon.HOME to "Beranda", Icon.LIST to "Script", Icon.CLOCK to "Aktivitas", Icon.GEAR to "Pengaturan"),
            2,
            { showTab(it) },
            { pickFiles() },
            "Tambah script dari file"
        )
        rootV.addView(nav, lp(MATCH, WRAP))
        setContentView(rootV)

        // ---- bilah atas hijau (gaya RAFI HAYDAY): logo + nama, garis bawah daun tua
        val topbar = LinearLayout(this)
        topbar.orientation = LinearLayout.HORIZONTAL
        topbar.gravity = Gravity.CENTER_VERTICAL
        topbar.setPadding(dp(14), dp(10), dp(10), dp(10 + 3))
        topbar.background = topBarBackground()
        topbar.addView(LogoView(this, false), lp(dp(34), dp(34), 0f, 0, 0, dp(10), 0))
        val tbTitle = text("RAFI HAYDAY", 22f, android.graphics.Color.WHITE, true)
        tbTitle.setShadowLayer(0.01f, 0f, dpf(2f), 0x8C1E4A18.toInt())
        tbTitle.letterSpacing = 0.02f
        topbar.addView(tbTitle, lp(0, WRAP, 1f))
        rootV.addView(topbar, 0, lp(MATCH, WRAP))

        // ---- banner pesan (tersembunyi sampai dibutuhkan)
        banner = LinearLayout(this)
        banner.orientation = LinearLayout.HORIZONTAL
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.setPadding(dp(14), dp(10), dp(6), dp(10))
        banner.visibility = View.GONE
        bannerIconHolder = LinearLayout(this)
        banner.addView(bannerIconHolder, lp(WRAP, WRAP, 0f, 0, 0, dp(10), 0))
        bannerText = text("", 13.5f, Palette.ink)
        banner.addView(bannerText, lp(0, WRAP, 1f))
        val closeIv = iconView(Icon.CLOSE, Palette.inkSoft, 18)
        closeIv.scaleType = ImageView.ScaleType.CENTER
        closeIv.isClickable = true
        closeIv.contentDescription = "Tutup pesan"
        closeIv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        closeIv.setOnClickListener { banner.visibility = View.GONE }
        banner.addView(closeIv, lp(dp(40), dp(40)))
        col.addView(banner, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(12)))

        pageHome = pageBox(); pageScripts = pageBox(); pageLog = pageBox(); pageSettings = pageBox()
        for (pg in listOf(pageHome, pageScripts, pageLog, pageSettings)) col.addView(pg, lp(MATCH, WRAP))

        // ---- kartu sambutan + aksi utama
        val welcome = card(18)
        welcome.addView(text("Welcome to Rafi Automatic", 19f, Palette.ink, true))
        welcome.addView(
            text("Mencari gambar di layar lalu mengklik otomatis sesuai script. Pastikan semua status di bawah sudah siap, lalu tekan Mulai.", 13.5f, Palette.inkSoft),
            lp(MATCH, WRAP, 0f, 0, dp(6), 0, dp(14))
        )
        overallChip = StatusChip(this)
        welcome.addView(overallChip, lp(WRAP, WRAP))
        overallText = text("", 13f, Palette.inkSoft)
        welcome.addView(overallText, lp(MATCH, WRAP, 0f, 0, dp(6), 0, dp(14)))
        activeText = text("", 13.5f, Palette.greenDark, true)
        welcome.addView(activeText, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(10)))
        btnStart = AppButton(this, "Mulai", BtnStyle.PRIMARY, Icon.PLAY)
        btnStart.setOnClickListener { start() }
        welcome.addView(btnStart, lp(MATCH, dp(52)))
        welcome.addView(
            text("Menampilkan tombol melayang. Jumlah tombol ▶ sama dengan jumlah script yang kamu pilih di tab Script (maksimal 3): pilih 2 script = 2 tombol ▶, pilih 3 = 3 tombol ▶. Tiap tombol bisa dijalankan sendiri-sendiri atau sekaligus.", 12f, Palette.inkSoft),
            lp(MATCH, WRAP, 0f, 0, dp(8), 0, 0)
        )
        btnStop = AppButton(this, "Tutup tombol melayang", BtnStyle.DANGER, Icon.STOP)
        btnStop.setOnClickListener { confirmStop() }
        btnStop.visibility = View.GONE
        welcome.addView(btnStop, lp(MATCH, WRAP, 0f, 0, dp(12), 0, 0))
        pageHome.addView(welcome, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(20)))

        // ---- daftar script
        pageScripts.addView(sectionTitle("Script"), lp(MATCH, WRAP, 0f, 0, 0, 0, dp(10)))
        val scripts = card()
        scriptBox = LinearLayout(this)
        scriptBox.orientation = LinearLayout.VERTICAL
        scripts.addView(scriptBox, lp(MATCH, WRAP))
        val btnAddScript = AppButton(this, "Tambah script dari file", BtnStyle.SECONDARY, Icon.PLUS)
        btnAddScript.setOnClickListener { pickFiles() }
        scripts.addView(btnAddScript, lp(MATCH, WRAP, 0f, 0, dp(10), 0, 0))
        scripts.addView(
            text("Ketuk script untuk mengaktifkannya (untuk Atur langkah & rentang). Ketuk pil ▶ di kanan untuk memilih script yang dimasukkan ke tombol melayang (maks 3); jumlah tombol ▶ = jumlah yang dipilih, nomornya mengikuti urutan pilih. File .json / .txt ditambahkan sebagai script baru tanpa menimpa yang lama. Dikenali otomatis: script Image Clicker (pakai gambar), macro seperti min_n_max.txt, atau teks Notepad biasa satu klik per baris \"x,y,jeda\".", 12f, Palette.inkSoft),
            lp(MATCH, WRAP, 0f, 0, dp(8), 0, 0)
        )
        pageScripts.addView(scripts, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(12)))

        // ---- rentang langkah script aktif
        val cRange = card()
        cRange.addView(text("Rentang langkah (script aktif)", 15f, Palette.ink, true))
        rangeHint = text("", 12.5f, Palette.inkSoft)
        cRange.addView(rangeHint, lp(MATCH, WRAP, 0f, 0, dp(2), 0, dp(6)))
        fFrom = formField("Mulai dari langkah", null, "1", false, 0x5A1A)
        fTo = formField("Sampai langkah", "0 = sampai langkah terakhir.", "0", false, 0x5A1B)
        val rangeRow = LinearLayout(this)
        rangeRow.orientation = LinearLayout.HORIZONTAL
        rangeRow.addView(fFrom.container, lp(0, WRAP, 1f, 0, 0, dp(6), 0))
        rangeRow.addView(fTo.container, lp(0, WRAP, 1f, dp(6), 0, 0, 0))
        cRange.addView(rangeRow, lp(MATCH, WRAP))
        pageScripts.addView(cRange, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(12)))

        // ---- pengulangan script aktif
        val cRep = card()
        cRep.addView(text("Pengulangan (script aktif)", 15f, Palette.ink, true))
        repeatHint = text("", 12.5f, Palette.inkSoft)
        cRep.addView(repeatHint, lp(MATCH, WRAP, 0f, 0, dp(2), 0, dp(8)))
        val repRow = LinearLayout(this)
        repRow.orientation = LinearLayout.HORIZONTAL
        for ((k, l) in listOf("Sekali", "N kali", "Tanpa batas").withIndex()) {
            val p = pill(l, Palette.creamDeep, Palette.inkSoft)
            p.gravity = Gravity.CENTER
            p.setPadding(dp(8), dp(10), dp(8), dp(10))
            p.isClickable = true
            p.setOnClickListener { setRepeatMode(k) }
            repeatPills[k] = p
            repRow.addView(p, lp(0, WRAP, 1f, if (k == 0) 0 else dp(4), 0, if (k == 2) 0 else dp(4), 0))
        }
        cRep.addView(repRow, lp(MATCH, WRAP))
        fRepeatN = formField("Jumlah pengulangan", "Script diulang dari awal sebanyak ini, lalu berhenti sendiri.", "5", false, 0x5A1C)
        fRepeatN.edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { saveRepeat() }
        })
        cRep.addView(fRepeatN.container)
        pageScripts.addView(cRep, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(20)))

        // ---- ringkasan sistem
        pageHome.addView(sectionTitle("Ringkasan sistem"), lp(MATCH, WRAP, 0f, 0, 0, 0, dp(10)))
        val sys = card()
        rowImages = StatusRow(this, Icon.IMAGE, "Gambar script")
        rowA11y = StatusRow(this, Icon.ACCESS, "Layanan Aksesibilitas")
        rowOverlay = StatusRow(this, Icon.LAYERS, "Tampil di atas aplikasi lain")
        sys.addView(rowImages)
        sys.addView(divider())
        sys.addView(rowA11y)
        sys.addView(divider())
        sys.addView(rowOverlay)
        pageHome.addView(sys, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(20)))

        // ---- tugas otomatisasi
        pageScripts.addView(sectionTitle("Tugas otomatisasi"), lp(MATCH, WRAP, 0f, 0, 0, 0, dp(10)))
        val tasks = card()
        val tiles = LinearLayout(this)
        tiles.orientation = LinearLayout.HORIZONTAL
        statTotal = tile(tiles, "Langkah", 0)
        statImg = tile(tiles, "Cari gambar", 1)
        statClick = tile(tiles, "Klik koordinat", 2)
        tasks.addView(tiles, lp(MATCH, WRAP))
        statCaption = text("", 12.5f, Palette.inkSoft)
        tasks.addView(statCaption, lp(MATCH, WRAP, 0f, 0, dp(10), 0, dp(14)))
        val taskBtns = LinearLayout(this)
        taskBtns.orientation = LinearLayout.HORIZONTAL
        val btnSteps = AppButton(this, "Atur langkah", BtnStyle.SECONDARY, Icon.LIST)
        btnSteps.setOnClickListener { startActivity(Intent(this, ActionsActivity::class.java)) }
        btnImport = AppButton(this, "Impor file", BtnStyle.SECONDARY, Icon.UPLOAD)
        btnImport.setOnClickListener { pickFiles() }
        taskBtns.addView(btnSteps, lp(0, WRAP, 1f, 0, 0, dp(6), 0))
        taskBtns.addView(btnImport, lp(0, WRAP, 1f, dp(6), 0, 0, 0))
        tasks.addView(taskBtns, lp(MATCH, WRAP))
        tasks.addView(
            text("Impor menerima PNG gambar, script (.json / .txt), atau ZIP hasil export. Script baru ditambahkan ke daftar.", 12f, Palette.inkSoft),
            lp(MATCH, WRAP, 0f, 0, dp(8), 0, 0)
        )
        pageScripts.addView(tasks, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(20)))

        // ---- aktivitas terbaru
        val actHead = LinearLayout(this)
        actHead.orientation = LinearLayout.HORIZONTAL
        actHead.gravity = Gravity.CENTER_VERTICAL
        actHead.addView(sectionTitle("Aktivitas terbaru"), lp(0, WRAP, 1f))
        val clear = text("Bersihkan", 13.5f, Palette.green, true)
        clear.setPadding(dp(12), dp(12), dp(12), dp(12))
        clear.background = ripple(0x00000000, 12f, 0, 0f, 0x222F7D3A)
        clear.isClickable = true
        clear.setOnClickListener { Bus.clearHistory(); renderLog() }
        actHead.addView(clear, lp(WRAP, WRAP))
        pageLog.addView(actHead, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(4)))
        val logCard = card()
        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        logCard.addView(logBox, lp(MATCH, WRAP))
        pageLog.addView(logCard, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(20)))

        // ---- pengaturan
        pageSettings.addView(sectionTitle("Pengaturan"), lp(MATCH, WRAP, 0f, 0, 0, 0, dp(10)))

        val cScreen = card()
        cScreen.addView(text("Layar rekaman script", 15f, Palette.ink, true))
        cScreen.addView(
            text("Ukuran layar saat script direkam. Koordinat dan gambar diskalakan otomatis bila layar HP berbeda. Script yang membawa ukuran layar sendiri (mis. min/max) mengabaikan isian ini.", 12.5f, Palette.inkSoft),
            lp(MATCH, WRAP, 0f, 0, dp(2), 0, dp(6))
        )
        fRefW = formField("Lebar (px)", null, p.getString("refW", "2400")!!, false, 0x5A10)
        fRefH = formField("Tinggi (px)", null, p.getString("refH", "1080")!!, false, 0x5A11)
        val screenRow = LinearLayout(this)
        screenRow.orientation = LinearLayout.HORIZONTAL
        screenRow.addView(fRefW.container, lp(0, WRAP, 1f, 0, 0, dp(6), 0))
        screenRow.addView(fRefH.container, lp(0, WRAP, 1f, dp(6), 0, 0, 0))
        cScreen.addView(screenRow, lp(MATCH, WRAP))
        pageSettings.addView(cScreen, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(12)))

        val cMatch = card()
        cMatch.addView(text("Pencocokan & putaran", 15f, Palette.ink, true))
        fFloor = formField("Kemiripan bawaan", "Hanya dipakai untuk langkah yang tidak punya nilai kemiripan sendiri. Nilai tiap gambar diatur di Atur langkah (Tingkat kemiripan).", p.getString("floor", "0.70")!!, true, 0x5A12)
        fLoops = formField("Jumlah putaran", "0 = berulang terus sampai dihentikan. Hanya dipakai untuk script yang belum diatur di kartu Pengulangan (tab Script).", p.getString("loops", "0")!!, false, 0x5A13)
        fTimeout = formField("Timeout langkah 'Repeat' (detik)", "0 = menunggu tanpa batas.", p.getString("timeout", "0")!!, false, 0x5A14)
        cMatch.addView(fFloor.container); cMatch.addView(fLoops.container); cMatch.addView(fTimeout.container)
        pageSettings.addView(cMatch, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(12)))

        val cTime = card()
        cTime.addView(text("Waktu & jeda", 15f, Palette.ink, true))
        fClick = formField("Durasi total per klik (ms)", "0 = ikut tunda di script.", p.getString("clickMs", "300")!!, false, 0x5A15)
        fSearch = formField("Waktu cari per langkah (ms)", "Lama mencari gambar sebelum lanjut. 0 = cek sekali.", p.getString("searchMs", "300")!!, false, 0x5A16)
        fStepGap = formField("Jeda tetap tiap langkah (ms)", "Berlaku di akhir setiap langkah, ada gambar atau tidak. 0 = tanpa jeda.", p.getString("stepGap", "300")!!, false, 0x5A17)
        fSameGap = formField("Jeda bila gambar berikutnya sama (ms)", "Total jeda setelah klik bila langkah berikutnya mencari gambar yang sama (sudah termasuk jeda di atas).", p.getString("sameGap", "700")!!, false, 0x5A18)
        cTime.addView(fClick.container); cTime.addView(fSearch.container)
        cTime.addView(fStepGap.container); cTime.addView(fSameGap.container)
        pageSettings.addView(cTime, lp(MATCH, WRAP, 0f, 0, 0, 0, dp(12)))

        val cView = card()
        val viewRow = LinearLayout(this)
        viewRow.orientation = LinearLayout.HORIZONTAL
        viewRow.gravity = Gravity.CENTER_VERTICAL
        val viewTexts = LinearLayout(this)
        viewTexts.orientation = LinearLayout.VERTICAL
        viewTexts.addView(text("Nomor langkah di layar", 15f, Palette.ink, true))
        viewTexts.addView(text("Menampilkan penanda nomor tiap langkah di titik kerjanya (klik / gambar), walau belum mengklik. Tombol mata di tombol melayang untuk menyembunyikannya sementara.", 12.5f, Palette.inkSoft))
        viewRow.addView(viewTexts, lp(0, WRAP, 1f, 0, 0, dp(12), 0))
        markerSw = Switch(this)
        markerSw.id = 0x5A19
        markerSw.isChecked = p.getBoolean("marker", true)
        markerSw.contentDescription = "Tampilkan nomor langkah di layar"
        val checked = intArrayOf(android.R.attr.state_checked)
        markerSw.thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Palette.green, 0xFFBDBDBD.toInt()))
        markerSw.trackTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Palette.greenPastel, 0xFFE0E0E0.toInt()))
        viewRow.addView(markerSw, lp(WRAP, WRAP))
        cView.addView(viewRow, lp(MATCH, WRAP))
        pageSettings.addView(cView, lp(MATCH, WRAP))

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)

        showTab(0)
        refreshStatus()
        renderLog()
    }

    private fun pageBox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    /** Menampilkan satu halaman (0 Beranda, 1 Script, 2 Aktivitas, 3 Pengaturan) dan menandai tab-nya. */
    private fun showTab(i: Int) {
        currentTab = i
        val pages = listOf(pageHome, pageScripts, pageLog, pageSettings)
        for ((k, pg) in pages.withIndex()) pg.visibility = if (k == i) View.VISIBLE else View.GONE
        nav.select(i)
        scroll.post { scroll.scrollTo(0, 0) }
    }

    override fun onStart() {
        super.onStart()
        // Hanya mendengarkan saat layar terlihat, supaya tidak membebani proses otomatisasi di latar.
        Bus.listener = { _ -> renderLog(); updateRunState() }
        renderLog()
        updateRunState()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    override fun onPause() {
        // Simpan isian yang valid agar perubahan lewat tombol ⚙ langsung dipakai pada ▶ berikutnya.
        saveSettings(true)
        saveRange()
        super.onPause()
    }

    override fun onStop() { Bus.listener = null; super.onStop() }
    override fun onDestroy() { Bus.listener = null; super.onDestroy() }

    // ------------------------------------------------------------------------------------------
    // Komponen kecil
    // ------------------------------------------------------------------------------------------

    private fun tile(parent: LinearLayout, label: String, idx: Int): TextView {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.background = shape(Palette.greenSoft, 14f)
        box.setPadding(dp(6), dp(12), dp(6), dp(12))
        val n = text("–", 24f, Palette.greenDark, true)
        n.gravity = Gravity.CENTER
        val l = text(label, 12f, Palette.inkSoft)
        l.gravity = Gravity.CENTER
        box.addView(n)
        box.addView(l)
        val left = if (idx == 0) 0 else dp(4)
        val right = if (idx == 2) 0 else dp(4)
        parent.addView(box, lp(0, WRAP, 1f, left, 0, right, 0))
        return n
    }

    private fun showBanner(tone: Tone, msg: String) {
        val c = when (tone) {
            Tone.OK, Tone.RUN -> intArrayOf(Palette.greenSoft, Palette.green)
            Tone.WARN, Tone.IDLE -> intArrayOf(Palette.yellowSoft, Palette.amberText)
            Tone.ERROR -> intArrayOf(Palette.redSoft, Palette.red)
        }
        val ic = when (tone) {
            Tone.OK, Tone.RUN -> Icon.CHECK
            Tone.WARN, Tone.IDLE -> Icon.INFO
            Tone.ERROR -> Icon.ALERT
        }
        banner.background = shape(c[0], 14f)
        bannerIconHolder.removeAllViews()
        bannerIconHolder.addView(iconView(ic, c[1], 22))
        bannerText.setTextColor(c[1])
        bannerText.text = msg
        banner.visibility = View.VISIBLE
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------------------------------

    /** Membaca ulang script, gambar, dan izin. Dipanggil saat kembali ke layar dan setelah impor. */
    private fun refreshStatus() {
        saveRange()
        renderScripts()
        loadRange()
        loadRepeat()
        var need = emptyList<String>()
        var miss = emptyList<String>()
        var total = 0
        var imgSteps = 0
        var clickSteps = 0
        var cycles = 0
        var activeName = ""
        scriptOk = true
        try {
            need = store.requiredImages()
            miss = store.missingImages()
            val root = store.load()
            cycles = root.optInt("cycles", 0)
            activeName = root.optString("name")
            val acts = root.getJSONArray("actions")
            total = acts.length()
            for (i in 0 until total) {
                if (acts.getJSONObject(i).optString("type") == "Search Image") imgSteps++ else clickSteps++
            }
        } catch (e: Exception) {
            scriptOk = false
        }
        missingCount = miss.size

        if (!scriptOk) {
            rowImages.update(
                "File script tidak bisa dibaca. Impor ulang script.json, atau pakai Reset bawaan di Atur langkah.",
                Tone.ERROR, "Rusak", "Impor file"
            ) { pickFiles() }
        } else if (miss.isNotEmpty()) {
            val shown = miss.take(3).joinToString { "$it.png" }
            val more = if (miss.size > 3) " +${miss.size - 3} lagi" else ""
            rowImages.update(
                "Kurang: $shown$more",
                Tone.WARN, "${need.size - miss.size}/${need.size}", "Impor gambar"
            ) { pickFiles() }
        } else if (need.isEmpty()) {
            rowImages.update("Script ini tidak memakai gambar.", Tone.OK, "Lengkap")
        } else {
            rowImages.update("${need.size} gambar terpasang.", Tone.OK, "Lengkap")
        }

        if (ClickerAccessibilityService.instance != null) {
            rowA11y.update("Layanan siap melakukan klik di layar.", Tone.OK, "Aktif")
        } else {
            rowA11y.update("Dibutuhkan agar aplikasi bisa mengetuk layar.", Tone.WARN, "Belum aktif", "Buka pengaturan aksesibilitas") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        if (Settings.canDrawOverlays(this)) {
            rowOverlay.update("Tombol melayang bisa tampil di atas game.", Tone.OK, "Diizinkan")
        } else {
            rowOverlay.update("Dibutuhkan untuk menampilkan tombol melayang.", Tone.WARN, "Belum diizinkan", "Izinkan tampil di atas aplikasi lain") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        activeText.text = if (scriptOk) "Script aktif: $activeName" else "Script aktif: tidak terbaca"
        statTotal.text = if (scriptOk) total.toString() else "–"
        statImg.text = if (scriptOk) imgSteps.toString() else "–"
        statClick.text = if (scriptOk) clickSteps.toString() else "–"
        val loops = Prefs.cfg(this).loops
        val rep = if (scriptOk) store.repeatOf(store.activeSlug, loops) else loops
        statCaption.text = when {
            rep == 0 -> "Putaran: berulang terus sampai dihentikan."
            rep == 1 -> "Putaran: sekali sampai langkah terakhir, lalu berhenti."
            else -> "Putaran: $rep kali, lalu berhenti."
        }

        updateRunState()
    }

    /** Ringan (tanpa baca file): memperbarui chip status dan tombol sesuai kondisi proses. */
    private fun updateRunState() {
        val a11y = ClickerAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val pending = listOf(scriptOk && missingCount == 0, a11y, overlay).count { !it }
        when {
            Bus.running -> {
                overallChip.set("Sedang berjalan", Tone.RUN)
                overallText.text = "Script sedang dijalankan. Hentikan lewat tombol ■ melayang."
            }
            Bus.serviceActive -> {
                overallChip.set("Tombol melayang aktif", Tone.OK)
                overallText.text = "Buka game, lalu tekan ▶ pada tombol melayang."
            }
            !scriptOk -> {
                overallChip.set("Script bermasalah", Tone.ERROR)
                overallText.text = "Script tidak bisa dibaca. Lihat Ringkasan sistem di bawah."
            }
            pending == 0 -> {
                overallChip.set("Siap dijalankan", Tone.OK)
                overallText.text = "Semua persiapan lengkap. Tekan Mulai."
            }
            else -> {
                overallChip.set("Perlu persiapan", Tone.WARN)
                overallText.text = "$pending hal perlu diselesaikan di Ringkasan sistem."
            }
        }
        if (!starting) {
            btnStart.setLabel(if (Bus.serviceActive) "Tombol melayang aktif" else "Mulai")
            btnStart.isEnabled = !Bus.serviceActive
        }
        btnStop.visibility = if (Bus.serviceActive) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------------------------------
    // Daftar script
    // ------------------------------------------------------------------------------------------

    private fun renderScripts() {
        scriptBox.removeAllViews()
        val items = store.list()
        val active = store.activeSlug
        val bubble = store.bubbleSlugs()
        val globalLoops = Prefs.cfg(this).loops
        for ((i, s) in items.withIndex()) {
            val isActive = s.slug == active
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(6), dp(8), 0, dp(8))
            row.background = ripple(if (isActive) Palette.greenSoft else 0x00000000, 12f, 0, 0f, 0x222F7D3A)
            row.isClickable = true
            row.setOnClickListener { activateScript(s) }
            row.addView(
                iconBadge(
                    if (isActive) Icon.CHECK else Icon.LIST,
                    if (isActive) Palette.green else Palette.inkSoft,
                    if (isActive) Palette.greenPastel else Palette.creamDeep, 36
                ),
                lp(dp(36), dp(36), 0f, 0, 0, dp(12), 0)
            )
            val tc = LinearLayout(this)
            tc.orientation = LinearLayout.VERTICAL
            tc.addView(text(s.name, 15f, Palette.ink, true))
            val sub = if (!s.ok) "Rusak, tidak terbaca"
                else "${s.steps} langkah · ${repeatLabel(store.repeatOf(s.slug, globalLoops))}" + if (isActive) " · aktif" else ""
            tc.addView(text(sub, 12f, if (s.ok) Palette.inkSoft else Palette.red))
            row.addView(tc, lp(0, WRAP, 1f))

            val pos = bubble.indexOf(s.slug)
            val sel = pos >= 0
            val sp = pill(if (sel) "▶ ${pos + 1}" else "▶ +", if (sel) Palette.green else Palette.creamDeep,
                if (sel) 0xFFFFFFFF.toInt() else Palette.inkSoft)
            sp.isClickable = true
            sp.contentDescription = if (sel) "Keluarkan ${s.name} dari tombol melayang" else "Masukkan ${s.name} ke tombol melayang"
            sp.setOnClickListener { toggleBubble(s) }
            row.addView(sp, lp(WRAP, WRAP, 0f, 0, 0, dp(2), 0))

            val ren = text("Ubah", 13f, Palette.green, true)
            ren.setPadding(dp(10), dp(12), dp(10), dp(12))
            ren.isClickable = true
            ren.setOnClickListener { renameScript(s) }
            row.addView(ren, lp(WRAP, WRAP))
            if (items.size > 1) {
                val del = iconView(Icon.TRASH, Palette.red, 20)
                del.scaleType = ImageView.ScaleType.CENTER
                del.isClickable = true
                del.contentDescription = "Hapus script ${s.name}"
                del.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                del.setOnClickListener { deleteScript(s) }
                row.addView(del, lp(dp(40), dp(40)))
            }
            scriptBox.addView(row, lp(MATCH, WRAP))
            if (i < items.size - 1) scriptBox.addView(divider())
        }
    }

    /** Menyimpan isian rentang ke script yang isiannya sedang ditampilkan (bukan ke script aktif yang baru). */
    private fun saveRange() {
        val slug = rangeSlug ?: return
        val from = (fFrom.edit.text.toString().trim().toIntOrNull() ?: 1).coerceAtLeast(1)
        var to = (fTo.edit.text.toString().trim().toIntOrNull() ?: 0).coerceAtLeast(0)
        if (to in 1 until from) to = 0
        try { store.setRange(slug, from, to) } catch (_: Exception) {}
    }

    private fun loadRange() {
        val slug = store.activeSlug
        rangeSlug = null
        try {
            val o = store.load()
            val total = o.getJSONArray("actions").length()
            fFrom.edit.setText(maxOf(1, o.optInt("from_step", 1)).toString())
            fTo.edit.setText(o.optInt("to_step", 0).toString())
            rangeHint.text = "Script punya $total langkah. Contoh: mulai 1, sampai 30 = jalankan langkah 1-30, lalu ulang lagi dari awal."
            rangeSlug = slug
        } catch (e: Exception) {
            rangeHint.text = "Script tidak terbaca."
        }
    }

    private fun repeatLabel(n: Int) = when (n) { 0 -> "ulang terus"; 1 -> "sekali"; else -> "${n}x" }

    private fun loadRepeat() {
        val slug = store.activeSlug
        repeatSlug = null
        try {
            val n = store.repeatOf(slug, Prefs.cfg(this).loops)
            repeatMode = when { n == 0 -> 2; n == 1 -> 0; else -> 1 }
            if (n >= 2 && fRepeatN.edit.text.toString() != n.toString()) fRepeatN.edit.setText(n.toString())
            store.load(slug)   // pastikan script terbaca
            repeatSlug = slug
        } catch (_: Exception) {}
        paintRepeat()
    }

    private fun paintRepeat() {
        for (k in 0..2) {
            val on = k == repeatMode
            repeatPills[k]?.let {
                it.background = shape(if (on) Palette.green else Palette.creamDeep, 20f)
                it.setTextColor(if (on) 0xFFFFFFFF.toInt() else Palette.inkSoft)
            }
        }
        fRepeatN.container.visibility = if (repeatMode == 1) View.VISIBLE else View.GONE
        repeatHint.text = if (repeatSlug == null) "Script tidak terbaca." else when (repeatMode) {
            0 -> "Script berjalan sekali sampai langkah terakhir, lalu berhenti."
            1 -> "Script diulang beberapa kali, lalu berhenti sendiri."
            else -> "Script berulang terus sampai kamu menekan ■ di tombol melayang."
        }
    }

    private fun setRepeatMode(k: Int) {
        if (repeatSlug == null) return
        repeatMode = k
        if (k == 1 && (fRepeatN.edit.text.toString().trim().toIntOrNull() ?: 0) < 1) fRepeatN.edit.setText("5")
        paintRepeat()
        saveRepeat()
    }

    /** Menyimpan pengulangan ke script yang sedang tampil di kartu. Berlaku pada ▶ berikutnya. */
    private fun saveRepeat() {
        val slug = repeatSlug ?: return
        val n = when (repeatMode) {
            0 -> 1
            2 -> 0
            else -> fRepeatN.edit.text.toString().trim().toIntOrNull()?.takeIf { it >= 1 } ?: return
        }
        try { store.setRepeat(slug, n) } catch (_: Exception) {}
        renderScripts()
    }

    /** Pilih / batalkan script untuk tombol melayang. Jumlah tombol ▶ mengikuti jumlah script terpilih. */
    private fun toggleBubble(s: ScriptInfo) {
        if (!s.ok) { toast("Script ini rusak, tidak bisa dipilih."); return }
        if (!store.toggleBubble(s.slug)) { toast("Maksimal ${ScriptStore.MAX_SLOTS} script. Batalkan salah satu dulu."); return }
        renderScripts()
        val n = store.bubbleSlugs().size
        toast(if (n == 0) "Belum ada script dipilih" else "Tombol melayang: $n tombol ▶")
        if (Bus.serviceActive) showBanner(Tone.WARN, "Pilihan baru berlaku setelah tombol melayang ditutup lalu Mulai lagi.")
    }

    private fun activateScript(s: ScriptInfo) {
        if (s.slug == store.activeSlug) return
        if (Bus.running) { toast("Hentikan script yang sedang berjalan dulu (tombol ■)."); return }
        saveRange()
        store.activeSlug = s.slug
        refreshStatus()
        showBanner(Tone.OK, "Script aktif: ${s.name}. Tekan ▶ untuk menjalankannya.")
    }

    private fun renameScript(s: ScriptInfo) {
        val et = android.widget.EditText(this)
        et.setText(s.name)
        et.setSelection(et.text.length)
        et.setSingleLine(true)
        val wrap = FrameLayout(this)
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(et, FrameLayout.LayoutParams(MATCH, WRAP))
        val d = android.app.AlertDialog.Builder(this)
            .setTitle("Ubah nama script")
            .setView(wrap)
            .setPositiveButton("Simpan") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    try { store.rename(s.slug, n) } catch (e: Exception) { toast("Gagal mengubah nama: ${e.message}") }
                    refreshStatus()
                }
            }
            .setNegativeButton("Batal", null)
            .create()
        d.show()
        styleDialog(d)
    }

    private fun deleteScript(s: ScriptInfo) {
        if (Bus.running) { toast("Hentikan script yang sedang berjalan dulu (tombol ■)."); return }
        confirm(
            "Hapus script?",
            "Script \"${s.name}\" akan dihapus dari daftar. Gambar yang sudah diimpor tidak dihapus. Script bawaan bisa dipasang kembali dengan menginstal ulang aplikasi.",
            "Hapus", true
        ) {
            store.delete(s.slug)
            refreshStatus()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Riwayat aktivitas
    // ------------------------------------------------------------------------------------------

    private fun toneColor(s: String): Int = when {
        s.startsWith("Error") || s.contains("belum aktif") -> Palette.red
        s.contains("tidak ketemu") -> Palette.yellow
        s.contains("ketemu") || s.startsWith("Mulai") || s.contains("selesai") -> Palette.green
        else -> Palette.greenPastel
    }

    private fun renderLog() {
        logBox.removeAllViews()
        val items = Bus.history().takeLast(12).reversed()
        if (items.isEmpty()) {
            val empty = LinearLayout(this)
            empty.orientation = LinearLayout.VERTICAL
            empty.gravity = Gravity.CENTER_HORIZONTAL
            empty.setPadding(0, dp(8), 0, dp(8))
            empty.addView(iconBadge(Icon.LIST, Palette.inkSoft, Palette.creamDeep, 48))
            val t = text("Belum ada aktivitas", 15f, Palette.ink, true)
            t.gravity = Gravity.CENTER
            empty.addView(t, lp(WRAP, WRAP, 0f, 0, dp(10), 0, 0).also { it.gravity = Gravity.CENTER_HORIZONTAL })
            val d = text("Riwayat proses akan muncul di sini setelah script dijalankan.", 12.5f, Palette.inkSoft)
            d.gravity = Gravity.CENTER
            empty.addView(d, lp(WRAP, WRAP, 0f, 0, dp(2), 0, 0).also { it.gravity = Gravity.CENTER_HORIZONTAL })
            logBox.addView(empty, lp(MATCH, WRAP))
            return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for ((i, e) in items.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, dp(6), 0, dp(6))
            val dot = View(this)
            val dotBg = GradientDrawable()
            dotBg.shape = GradientDrawable.OVAL
            dotBg.setColor(toneColor(e.text))
            dot.background = dotBg
            row.addView(dot, lp(dp(8), dp(8), 0f, 0, dp(6), dp(10), 0))
            val tc = LinearLayout(this)
            tc.orientation = LinearLayout.VERTICAL
            tc.addView(text(e.text, 13.5f, Palette.ink))
            tc.addView(text(fmt.format(Date(e.time)), 11.5f, Palette.inkSoft))
            row.addView(tc, lp(0, WRAP, 1f))
            logBox.addView(row, lp(MATCH, WRAP))
            if (i < items.size - 1) {
                val ln = View(this)
                ln.setBackgroundColor(Palette.line)
                logBox.addView(ln, lp(MATCH, maxOf(1, dp(1)), 0f, dp(18), 0, 0, 0))
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Aksi
    // ------------------------------------------------------------------------------------------

    private fun pickFiles() {
        if (importing) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun doImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importing = true
        btnImport.setBusy(true, "Mengimpor…")
        Thread {
            var ok = 0
            val errors = ArrayList<String>()
            val added = ArrayList<String>()
            store.ignored.clear()
            for (u in uris) {
                try { added.addAll(store.import(u)); ok++ } catch (e: Exception) { errors.add(e.message ?: "alasan tidak diketahui") }
            }
            if (added.isNotEmpty()) store.activeSlug = added.last()
            runOnUiThread {
                importing = false
                if (!isDestroyed) {
                    btnImport.setBusy(false)
                    refreshStatus()
                    if (added.isNotEmpty()) showTab(1)
                    val skipped = if (store.ignored.isEmpty()) "" else " Dilewati (ekstensi tidak dikenal): ${store.ignored.joinToString()}."
                    if (errors.isEmpty()) showBanner(
                        if (store.ignored.isEmpty()) Tone.OK else Tone.WARN,
                        if (added.isNotEmpty()) "${added.size} script ditambahkan dan dipilih sebagai script aktif.$skipped"
                        else "$ok file diproses, tidak ada script baru.$skipped")
                    else showBanner(Tone.ERROR, "Impor selesai sebagian: $ok berhasil, ${errors.size} gagal (${errors.first()}).")
                }
            }
        }.start()
    }

    private fun confirmStop() {
        if (!Bus.serviceActive) { toast("Tidak ada tombol melayang yang aktif."); return }
        confirm(
            "Tutup tombol melayang?",
            "Tombol melayang akan hilang dan proses otomatis yang sedang berjalan dihentikan.",
            "Tutup", true
        ) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            showBanner(Tone.OK, "Tombol melayang ditutup.")
        }
    }

    /**
     * Menyimpan isian ke SharedPreferences "cfg" (kunci dan format sama seperti sebelumnya).
     * [quiet] = true: dipakai saat layar ditinggalkan; tidak menampilkan pesan dan tidak menyimpan
     * bila ada isian yang tidak valid.
     */
    private fun saveSettings(quiet: Boolean = false): Boolean {
        val bad = ArrayList<String>()
        fun numOf(f: FormField, name: String, min: Int): String {
            val raw = f.edit.text.toString().trim()
            val v = raw.toIntOrNull()
            if (v == null || v < min) {
                if (!quiet) setEditError(f.edit, true)
                bad.add(name)
                return raw
            }
            return v.toString()
        }
        val w = numOf(fRefW, "lebar layar", 1)
        val h = numOf(fRefH, "tinggi layar", 1)
        val floorRaw = fFloor.edit.text.toString().trim().replace(',', '.')
        val fl = floorRaw.toDoubleOrNull()
        if (fl == null || fl < 0.1 || fl > 1.0) {
            if (!quiet) setEditError(fFloor.edit, true)
            bad.add("batas kemiripan")
        }
        val loops = numOf(fLoops, "jumlah putaran", 0)
        val timeout = numOf(fTimeout, "timeout", 0)
        val clickMs = numOf(fClick, "durasi klik", 0)
        val searchMs = numOf(fSearch, "waktu cari", 0)
        val stepGap = numOf(fStepGap, "jeda tiap langkah", 0)
        val sameGap = numOf(fSameGap, "jeda gambar sama", 0)

        if (bad.isNotEmpty()) {
            if (!quiet) showBanner(Tone.ERROR, "Periksa isian: ${bad.joinToString(", ")}. Isi dengan angka yang valid (tanpa huruf atau tanda minus).")
            return false
        }
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("refW", w).putString("refH", h)
            .putString("floor", floorRaw).putString("loops", loops)
            .putString("timeout", timeout)
            .putString("clickMs", clickMs)
            .putString("searchMs", searchMs)
            .putString("stepGap", stepGap)
            .putString("sameGap", sameGap)
            .putBoolean("marker", markerSw.isChecked).apply()
        return true
    }

    private fun start() {
        if (starting) return
        if (ClickerAccessibilityService.instance == null) {
            showBanner(Tone.WARN, "Layanan Aksesibilitas belum aktif. Aktifkan dulu lewat Ringkasan sistem agar aplikasi bisa mengetuk layar.")
            return
        }
        val chosen = store.bubbleSlugs()
        if (chosen.isEmpty()) {
            showBanner(Tone.WARN, "Pilih dulu script untuk tombol melayang di tab Script (ketuk pil ▶ di samping script).")
            return
        }
        val miss: List<String>
        try {
            miss = chosen.flatMap { store.missingImages(it) }.distinct()
        } catch (e: Exception) {
            showBanner(Tone.ERROR, "Script tidak bisa dibaca. Impor ulang script.json atau pakai Reset bawaan di Atur langkah.")
            return
        }
        if (miss.isNotEmpty()) {
            showBanner(Tone.WARN, "Gambar belum lengkap (${miss.size} kurang). Impor dulu lewat tombol Impor file.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showBanner(Tone.WARN, "Izinkan 'Tampil di atas aplikasi lain' untuk Rafi Automatic, lalu tekan Mulai lagi.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!saveSettings()) return
        saveRange()
        starting = true
        btnStart.setBusy(true, "Menunggu izin rekam layar…")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_PROJ) {
            starting = false
            btnStart.setBusy(false)
            if (res != RESULT_OK || data == null) {
                showBanner(Tone.WARN, "Izin rekam layar tidak diberikan. Tanpa izin ini aplikasi tidak bisa melihat layar, jadi tombol melayang tidak ditampilkan.")
            } else {
                val i = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                    .putExtra("code", res).putExtra("data", data)
                startForegroundService(i)
                showBanner(Tone.OK, "Tombol melayang muncul. Buka game, lalu tekan salah satu dari 3 tombol ▶.")
            }
            updateRunState()
            return
        }
        if (req == REQ_IMPORT) {
            if (res != RESULT_OK || data == null) return
            val uris = ArrayList<Uri>()
            data.clipData?.let { for (k in 0 until it.itemCount) uris.add(it.getItemAt(k).uri) }
            data.data?.let { uris.add(it) }
            doImport(uris)
        }
    }
}
