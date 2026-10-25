package com.rafi.autoclicker

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Editor manual untuk setiap langkah di script.json. */
class ActionsActivity : Activity() {
    private lateinit var store: ScriptStore
    private lateinit var list: ListView
    private lateinit var countText: TextView
    private lateinit var btnAdd: AppButton
    private var root = JSONObject()
    private val acts = ArrayList<JSONObject>()
    private var broken = false

    private val adapter = object : BaseAdapter() {
        override fun getCount() = acts.size
        override fun getItem(position: Int): Any = acts[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            rowView(position, acts[position])
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = ScriptStore(this)

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Palette.cream)
        val col = MaxWidthLayout(this, 640)
        col.orientation = LinearLayout.VERTICAL
        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER_HORIZONTAL))

        // ---- bilah atas
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(8), dp(8), dp(16), dp(8 + 3))
        top.background = topBarBackground()
        val back = iconView(Icon.BACK, android.graphics.Color.WHITE, 22)
        back.scaleType = ImageView.ScaleType.CENTER
        back.background = ripple(0x00000000, 24f, 0, 0f, 0x33FFFFFF)
        back.isClickable = true
        back.contentDescription = "Kembali"
        back.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        back.setOnClickListener { finish() }
        top.addView(back, lp(dp(48), dp(48), 0f, 0, 0, dp(4), 0))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(text("Atur langkah", 20f, android.graphics.Color.WHITE, true))
        countText = text("", 12.5f, Palette.greenSoft)
        titles.addView(countText)
        top.addView(titles, lp(0, WRAP, 1f))
        col.addView(top, lp(MATCH, WRAP))

        // ---- aksi
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(dp(16), dp(8), dp(16), dp(8))
        btnAdd = AppButton(this, "Klik koordinat", BtnStyle.PRIMARY, Icon.PLUS)
        btnAdd.setOnClickListener { addClick() }
        val btnReset = AppButton(this, "Reset bawaan", BtnStyle.SECONDARY, Icon.RESET)
        btnReset.setOnClickListener {
            confirm(
                "Reset script ke bawaan?",
                "Semua perubahan langkah pada script yang aktif akan hilang dan kembali ke versi bawaan aplikasi (hanya untuk script bawaan; script hasil impor tidak bisa direset). Gambar yang sudah diimpor tidak dihapus.",
                "Reset", true
            ) { reset() }
        }
        actions.addView(btnAdd, lp(0, WRAP, 1f, 0, 0, dp(6), 0))
        actions.addView(btnReset, lp(0, WRAP, 1f, dp(6), 0, 0, 0))
        col.addView(actions, lp(MATCH, WRAP))

        // ---- daftar + state kosong
        val listBox = FrameLayout(this)
        list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        list.selector = ColorDrawable(0)
        list.clipToPadding = false
        list.setPadding(0, dp(4), 0, dp(24))
        list.isVerticalScrollBarEnabled = false
        listBox.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))

        val empty = LinearLayout(this)
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(32), dp(32), dp(32), dp(32))
        empty.addView(iconBadge(Icon.LIST, Palette.inkSoft, Palette.creamDeep, 56))
        val et = text("Belum ada langkah", 16f, Palette.ink, true)
        et.gravity = Gravity.CENTER
        empty.addView(et, lp(WRAP, WRAP, 0f, 0, dp(12), 0, 0).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        val ed = text("Tambahkan klik koordinat, atau tekan Reset bawaan untuk memulihkan script asli.", 13f, Palette.inkSoft)
        ed.gravity = Gravity.CENTER
        empty.addView(ed, lp(WRAP, WRAP, 0f, 0, dp(4), 0, 0).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        listBox.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        list.emptyView = empty

        col.addView(listBox, lp(MATCH, 0, 1f))
        setContentView(frame)

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> edit(pos) }
        reload()
    }

    // ---------- data (logika tidak berubah) ----------

    private fun reload() {
        broken = false
        acts.clear()
        try {
            root = store.load()
            val arr = root.getJSONArray("actions")
            for (i in 0 until arr.length()) acts.add(arr.getJSONObject(i))
        } catch (e: Exception) {
            broken = true
            Toast.makeText(this, "Script tidak bisa dibaca. Tekan Reset bawaan untuk memulihkan.", Toast.LENGTH_LONG).show()
        }
        btnAdd.isEnabled = !broken
        refresh()
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        countText.text = if (broken) "Script tidak terbaca"
        else "${root.optString("name")} · ${acts.size} langkah · ketuk untuk mengedit"
    }

    private fun save() {
        if (broken) return
        root.put("actions", JSONArray(acts))
        store.scriptFile.writeText(root.toString(2))
        refresh()
    }

    private fun reset() {
        if (!store.resetActive()) {
            Toast.makeText(this, "Script ini hasil impor, tidak punya versi bawaan", Toast.LENGTH_LONG).show()
            return
        }
        reload()
        Toast.makeText(this, "Script dikembalikan ke bawaan", Toast.LENGTH_SHORT).show()
    }

    private fun addClick() {
        if (broken) return
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        acts.add(JSONObject().apply {
            put("id", id); put("type", "Single Click"); put("found_action", "Next")
            put("x", 500); put("y", 500); put("delay", 300); put("area", 1)
            put("click_loop_count", 1); put("r", 50.0); put("similar", 85)
            put("not_found_action_repeat_count", 1); put("end_long_click_duration", 500)
        })
        save()
        edit(acts.size - 1)
    }

    // ---------- baris daftar ----------

    private fun rowView(i: Int, a: JSONObject): View {
        val isSearch = a.optString("type") == "Search Image"
        val wrap = FrameLayout(this)
        wrap.setPadding(dp(16), 0, dp(16), dp(10))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = ripple(Palette.surface, 16f, Palette.line, 1f, 0x1A2F7D3A)
        card.elevation = dpf(1f)
        card.setPadding(dp(12), dp(12), dp(8), dp(12))

        val badge = text("${i + 1}", 14f, if (isSearch) Palette.greenDark else Palette.amberText, true)
        badge.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(if (isSearch) Palette.greenSoft else Palette.yellowSoft)
        badge.background = bg
        card.addView(badge, lp(dp(36), dp(36), 0f, 0, 0, dp(12), 0))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        if (isSearch) {
            body.addView(text("Cari gambar ${a.optString("id")}", 15f, Palette.ink, true))
            val found = a.optString("found_action") + (if (a.optString("found_more_action") == "Action") " → lompat" else "")
            body.addView(pill("Ketemu: $found", Palette.greenSoft, Palette.greenDark), lp(WRAP, WRAP, 0f, 0, dp(6), 0, 0))
            body.addView(pill("Tidak ketemu: ${a.optString("not_found_action")}", Palette.yellowSoft, Palette.amberText), lp(WRAP, WRAP, 0f, 0, dp(4), 0, 0))
            if (a.optBoolean("use_pre_search_frame", false)) {
                body.addView(pill("Pakai pencarian sebelumnya", Palette.creamDeep, Palette.inkSoft), lp(WRAP, WRAP, 0f, 0, dp(4), 0, 0))
            }
        } else {
            body.addView(text(a.optString("type"), 15f, Palette.ink, true))
            body.addView(
                text("(${a.optInt("x")}, ${a.optInt("y")}) · tunda ${a.optInt("delay")} ms", 12.5f, Palette.inkSoft),
                lp(WRAP, WRAP, 0f, 0, dp(2), 0, 0)
            )
        }
        card.addView(body, lp(0, WRAP, 1f))
        card.addView(iconView(Icon.CHEVRON, Palette.inkSoft, 20), lp(dp(20), dp(20), 0f, dp(4), 0, dp(4), 0))
        wrap.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        return wrap
    }

    // ---------- helper UI untuk dialog edit ----------

    private fun section(p: LinearLayout, t: String) {
        val v = text(t, 13f, Palette.greenDark, true)
        v.letterSpacing = 0.04f
        p.addView(v, lp(MATCH, WRAP, 0f, 0, dp(20), 0, dp(2)))
    }

    private fun label(p: LinearLayout, t: String) {
        p.addView(text(t, 13f, Palette.ink, true), lp(MATCH, WRAP, 0f, 0, dp(12), 0, dp(6)))
    }

    private fun spinner(p: LinearLayout, items: List<String>, sel: Int): Spinner {
        val s = Spinner(this)
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        s.adapter = ad
        s.setSelection(if (sel < 0) 0 else sel)
        s.background = null
        s.setPadding(dp(10), 0, dp(36), 0)
        val box = FrameLayout(this)
        box.background = inputBackground(false)
        box.addView(s, FrameLayout.LayoutParams(MATCH, dp(48)))
        val arrow = iconView(Icon.DOWN, Palette.inkSoft, 20)
        val al = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
        al.rightMargin = dp(12)
        box.addView(arrow, al)
        p.addView(box, lp(MATCH, WRAP))
        return s
    }

    private fun num(p: LinearLayout, v: Int): EditText {
        val e = styledEdit(v.toString(), false, 0)
        p.addView(e, lp(MATCH, WRAP))
        return e
    }

    private fun idxOf(code: String): Int {
        val m = Regex("\\((\\w+)\\)").find(code) ?: return -1
        return acts.indexOfFirst { it.optString("id") == m.groupValues[1] }
    }

    private fun codeOf(i: Int) = "${acts[i].optString("type")} (${acts[i].optString("id")})"

    private fun move(pos: Int, d: Int) {
        val n = pos + d
        if (n < 0 || n >= acts.size) return
        val t = acts.removeAt(pos); acts.add(n, t); save()
    }

    /** Simpan perubahan form, lalu atur mode uji sekali pakai untuk dijalankan lewat tombol ▶ melayang. */
    private fun queueTest(pos: Int, applyEdits: () -> Unit, only: Boolean) {
        applyEdits(); save()
        Prefs.setTest(this, pos + 1, if (only) pos + 1 else 0)
        val n = pos + 1
        val msg = (if (only) "Langkah $n siap diuji sekali. " else "Script akan mulai dari langkah $n. ") +
            "Buka game ke layar yang sesuai, lalu tekan ▶ pada tombol melayang (jika belum ada, tekan Mulai di layar utama)."
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun edit(pos: Int) {
        val a = acts[pos]
        val isSearch = a.optString("type") == "Search Image"
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(20), dp(8), dp(20), dp(8))
        val targets = acts.mapIndexed { i, x -> "#${i + 1} ${x.optString("type")} ${x.optString("id")}" }
        val dflt = minOf(pos + 1, acts.size - 1)
        var dlg: AlertDialog? = null

        val mv = LinearLayout(this)
        mv.orientation = LinearLayout.HORIZONTAL
        val bUp = AppButton(this, "Naik", BtnStyle.SECONDARY, Icon.UP)
        bUp.isEnabled = pos > 0
        bUp.setOnClickListener { move(pos, -1); dlg?.dismiss() }
        val bDown = AppButton(this, "Turun", BtnStyle.SECONDARY, Icon.DOWN)
        bDown.isEnabled = pos < acts.size - 1
        bDown.setOnClickListener { move(pos, 1); dlg?.dismiss() }
        mv.addView(bUp, lp(0, WRAP, 1f, 0, 0, dp(6), 0))
        mv.addView(bDown, lp(0, WRAP, 1f, dp(6), 0, 0, 0))
        form.addView(mv, lp(MATCH, WRAP))

        val applyEdits: () -> Unit
        if (isSearch) {
            val foundOpts = listOf("Click", "DoubleClick", "LongClick", "Next")
            val nfOpts = listOf("Repeat", "Next", "Action")
            section(form, "Jika gambar DITEMUKAN")
            val sFound = spinner(form, foundOpts, foundOpts.indexOf(a.optString("found_action")))
            label(form, "Klik di")
            val sAt = spinner(form, listOf("Image", "Position"), if (a.optString("found_action_click") == "Position") 1 else 0)
            label(form, "Posisi x (dipakai bila Position)"); val eX = num(form, a.optInt("x"))
            label(form, "Posisi y (dipakai bila Position)"); val eY = num(form, a.optInt("y"))
            label(form, "Lama tahan untuk LongClick (ms)"); val eHold = num(form, a.optInt("end_long_click_duration", 500))
            label(form, "Lama tiap ketukan untuk Click/DoubleClick (ms)"); val ePress = num(form, a.optInt("press_ms", 50))
            label(form, "Jeda antara 2 ketukan pada DoubleClick (ms)"); val eDGap = num(form, a.optInt("double_gap", 300))
            label(form, "Setelah ditemukan, lanjut ke")
            val sAfter = spinner(form, listOf("Next", "Action"), if (a.optString("found_more_action") == "Action") 1 else 0)
            label(form, "Langkah tujuan (bila Action)")
            val i1 = idxOf(a.optString("found_more_action_code"))
            val sFoundT = spinner(form, targets, if (i1 < 0) dflt else i1)
            section(form, "Jika gambar TIDAK ditemukan")
            val sNf = spinner(form, nfOpts, nfOpts.indexOf(a.optString("not_found_action")))
            label(form, "Langkah tujuan (bila Action)")
            val i2 = idxOf(a.optString("not_found_action_code"))
            val sNfT = spinner(form, targets, if (i2 < 0) dflt else i2)
            label(form, "Jeda antar pencarian ulang (ms)"); val eRep = num(form, a.optInt("not_found_action_repeat", 500))
            section(form, "Kemiripan & waktu")
            label(form, "Tingkat kemiripan (%)"); val eSim = num(form, a.optInt("similar", 85))
            label(form, "Tunda setelah aksi (ms)"); val eDelay = num(form, a.optInt("delay", 0))
            val cPre = Switch(this)
            cPre.text = "Pakai tangkapan layar pencarian sebelumnya (Use the previous search area)"
            cPre.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            cPre.setTextColor(Palette.ink)
            cPre.isChecked = a.optBoolean("use_pre_search_frame", false)
            val checked = intArrayOf(android.R.attr.state_checked)
            cPre.thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Palette.green, 0xFFBDBDBD.toInt()))
            cPre.trackTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Palette.greenPastel, 0xFFE0E0E0.toInt()))
            form.addView(cPre, lp(MATCH, WRAP, 0f, 0, dp(16), 0, dp(4)))
            applyEdits = {
                a.put("use_pre_search_frame", cPre.isChecked)
                a.put("found_action", foundOpts[sFound.selectedItemPosition])
                a.put("found_action_click", if (sAt.selectedItemPosition == 1) "Position" else "Image")
                a.put("x", eX.text.toString().toIntOrNull() ?: a.optInt("x"))
                a.put("y", eY.text.toString().toIntOrNull() ?: a.optInt("y"))
                a.put("end_long_click_duration", eHold.text.toString().toIntOrNull() ?: 500)
                a.put("press_ms", ePress.text.toString().toIntOrNull() ?: 50)
                a.put("double_gap", eDGap.text.toString().toIntOrNull() ?: 300)
                a.put("found_more_action", if (sAfter.selectedItemPosition == 1) "Action" else "Next")
                a.put("found_more_action_code", codeOf(sFoundT.selectedItemPosition))
                a.put("not_found_action", nfOpts[sNf.selectedItemPosition])
                a.put("not_found_action_code", codeOf(sNfT.selectedItemPosition))
                a.put("not_found_action_repeat", eRep.text.toString().toIntOrNull() ?: 500)
                a.put("similar", eSim.text.toString().toIntOrNull() ?: 85)
                a.put("delay", eDelay.text.toString().toIntOrNull() ?: 0)
            }
        } else {
            val types = listOf("Single Click", "Double Click", "Long Click")
            section(form, "Klik koordinat")
            label(form, "Jenis klik")
            val sType = spinner(form, types, types.indexOf(a.optString("type")))
            label(form, "Posisi x"); val eX = num(form, a.optInt("x"))
            label(form, "Posisi y"); val eY = num(form, a.optInt("y"))
            label(form, "Lama tahan untuk Long Click (ms)"); val eHold = num(form, a.optInt("end_long_click_duration", 500))
            label(form, "Lama tiap ketukan untuk Single/Double Click (ms)"); val ePress = num(form, a.optInt("press_ms", 50))
            label(form, "Jeda antara 2 ketukan pada Double Click (ms)"); val eDGap = num(form, a.optInt("double_gap", 300))
            label(form, "Tunda setelah klik (ms)"); val eDelay = num(form, a.optInt("delay", 0))
            applyEdits = {
                a.put("type", types[sType.selectedItemPosition])
                a.put("x", eX.text.toString().toIntOrNull() ?: a.optInt("x"))
                a.put("y", eY.text.toString().toIntOrNull() ?: a.optInt("y"))
                a.put("end_long_click_duration", eHold.text.toString().toIntOrNull() ?: 500)
                a.put("press_ms", ePress.text.toString().toIntOrNull() ?: 50)
                a.put("double_gap", eDGap.text.toString().toIntOrNull() ?: 300)
                a.put("delay", eDelay.text.toString().toIntOrNull() ?: 0)
            }
        }

        // Uji langkah ini / mulai dari sini: sekali pakai, dijalankan saat tombol ▶ melayang ditekan.
        val tr = LinearLayout(this)
        tr.orientation = LinearLayout.HORIZONTAL
        val bTest = AppButton(this, "Uji langkah ini", BtnStyle.PRIMARY, Icon.PLAY)
        bTest.setOnClickListener { queueTest(pos, applyEdits, true); dlg?.dismiss() }
        val bFrom = AppButton(this, "Mulai dari sini", BtnStyle.SECONDARY, Icon.CHEVRON)
        bFrom.setOnClickListener { queueTest(pos, applyEdits, false); dlg?.dismiss() }
        tr.addView(bTest, lp(0, WRAP, 1f, 0, 0, dp(6), 0))
        tr.addView(bFrom, lp(0, WRAP, 1f, dp(6), 0, 0, 0))
        form.addView(tr, 1, lp(MATCH, WRAP, 0f, 0, dp(8), 0, 0))
        form.addView(
            text("Uji berjalan sekali saat tombol ▶ melayang ditekan.", 12f, Palette.inkSoft),
            2, lp(MATCH, WRAP, 0f, dp(2), dp(6), 0, 0)
        )

        val d = AlertDialog.Builder(this)
            .setTitle("Langkah #${pos + 1}")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Simpan") { _, _ -> applyEdits(); save() }
            .setNegativeButton("Batal", null)
            .setNeutralButton("Hapus") { _, _ ->
                confirm("Hapus langkah #${pos + 1}?", "Langkah ini akan dihapus dari script. Tindakan ini tidak bisa dibatalkan, kecuali dengan Reset bawaan.", "Hapus", true) {
                    acts.removeAt(pos); save()
                }
            }
            .create()
        dlg = d
        d.show()
        styleDialog(d)
        d.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Palette.red)
    }
}
