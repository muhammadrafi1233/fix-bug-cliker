package com.rafi.autoclicker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.max

/** Ringkasan satu script untuk daftar di dashboard. */
data class ScriptInfo(val slug: String, val name: String, val steps: Int, val ok: Boolean)

/**
 * Menyimpan banyak script. Tiap script = satu file `scripts/<slug>.json`; script yang aktif dipilih lewat
 * preferensi "activeScript". Gambar tetap satu folder bersama (`script/images`) karena namanya `<id>_0.png`.
 */
class ScriptStore(private val ctx: Context) {
    companion object {
        /** Jumlah maksimum tombol ▶ di tombol melayang (dan jumlah script yang bisa dipilih untuknya). */
        const val MAX_SLOTS = 3
    }

    private val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** Folder lama (versi satu script). Gambar tetap di sini supaya gambar yang sudah diimpor tidak hilang. */
    val dir = File(ctx.filesDir, "script").apply { mkdirs() }
    val imgDir = File(dir, "images").apply { mkdirs() }
    private val scriptsDir = File(ctx.filesDir, "scripts").apply { mkdirs() }

    /** Script bawaan: slug -> path di assets. Dipakai untuk pemasangan awal dan "Reset bawaan". */
    private val bundled = linkedMapOf(
        "lvl-7-1-farm" to "script.json",
        "min" to "scripts/min.json",
        "max" to "scripts/max.json"
    )

    init { migrate() }

    // ------------------------------------------------------------------------------------------
    // Daftar & pilihan script
    // ------------------------------------------------------------------------------------------

    private fun fileOf(slug: String) = File(scriptsDir, "$slug.json")

    private fun order(slug: String): Int {
        val i = bundled.keys.indexOf(slug)
        return if (i < 0) 999 else i
    }

    private fun slugs(): List<String> =
        (scriptsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "json" }
            .map { it.nameWithoutExtension }
            .sortedWith(compareBy<String>({ order(it) }, { it }))

    private fun copyAsset(path: String, to: File) {
        ctx.assets.open(path).use { i -> to.outputStream().use { o -> i.copyTo(o) } }
    }

    /** Pindahkan script lama (script/script.json) bila ada, lalu pasang script bawaan yang belum pernah dipasang. */
    private fun migrate() {
        val seeded = (prefs.getStringSet("seededScripts", emptySet()) ?: emptySet()).toMutableSet()
        val old = File(dir, "script.json")
        if (old.exists()) {
            val target = fileOf("lvl-7-1-farm")
            if (!target.exists()) old.copyTo(target)
            old.delete()
            seeded.add("lvl-7-1-farm")
        }
        for ((slug, asset) in bundled) {
            if (slug in seeded) continue
            val f = fileOf(slug)
            if (!f.exists()) copyAsset(asset, f)
            seeded.add(slug)
        }
        prefs.edit().putStringSet("seededScripts", seeded).apply()
    }

    var activeSlug: String
        get() {
            val s = prefs.getString("activeScript", null)
            if (s != null && fileOf(s).exists()) return s
            return slugs().firstOrNull() ?: "lvl-7-1-farm"
        }
        set(v) { prefs.edit().putString("activeScript", v).apply() }

    /** File script yang sedang aktif. */
    val scriptFile: File get() = fileOf(activeSlug)

    /**
     * Script yang DIPILIH pengguna untuk tombol melayang, berurutan: elemen ke-0 = tombol ▶ 1, dst (maks [MAX_SLOTS]).
     * Jumlah tombol ▶ di tombol melayang = jumlah script di daftar ini. Belum pernah memilih -> hanya script aktif.
     * Script yang sudah dihapus otomatis hilang dari daftar. Bisa kosong bila pengguna membatalkan semua pilihan.
     */
    fun bubbleSlugs(): List<String> {
        val raw = prefs.getString("bubbleScripts", null) ?: return listOf(activeSlug)
        return raw.split(",").filter { it.isNotBlank() && fileOf(it).exists() }.distinct().take(MAX_SLOTS)
    }

    private fun saveBubble(list: List<String>) {
        prefs.edit().putString("bubbleScripts", list.joinToString(",")).apply()
    }

    fun isInBubble(slug: String) = slug in bubbleSlugs()

    /**
     * Pilih / batalkan script [slug] untuk tombol melayang. Mengembalikan false bila sudah penuh ([MAX_SLOTS]).
     * Script yang baru dipilih mendapat tombol ▶ di urutan paling belakang.
     */
    fun toggleBubble(slug: String): Boolean {
        val list = bubbleSlugs().toMutableList()
        if (!list.remove(slug)) {
            if (list.size >= MAX_SLOTS) return false
            list.add(slug)
        }
        saveBubble(list)
        return true
    }

    /** Script untuk tombol ▶ nomor [slot] (0,1,2). */
    fun activeSlugSlot(slot: Int): String = bubbleSlugs().getOrNull(slot) ?: activeSlug

    /** Ganti script tombol [slot] dengan [slug] (dari daftar tahan-lama di tombol melayang). Bila [slug] sudah dipakai tombol lain, keduanya bertukar. */
    fun setActiveSlugSlot(slot: Int, slug: String) {
        val list = bubbleSlugs().toMutableList()
        if (slot !in list.indices) return
        val other = list.indexOf(slug)
        if (other >= 0) list[other] = list[slot]
        list[slot] = slug
        saveBubble(list)
    }

    fun list(): List<ScriptInfo> = slugs().map { s ->
        try {
            val o = JSONObject(fileOf(s).readText())
            ScriptInfo(s, o.optString("name", s).ifBlank { s }, o.getJSONArray("actions").length(), true)
        } catch (e: Exception) {
            ScriptInfo(s, s, 0, false)
        }
    }

    fun load(slug: String = activeSlug): JSONObject = JSONObject(fileOf(slug).readText())

    fun rename(slug: String, name: String) {
        val f = fileOf(slug)
        val o = JSONObject(f.readText())
        o.put("name", name)
        f.writeText(o.toString(2))
    }

    /** Rentang langkah per script: dari langkah [from] sampai [to] (0 = sampai terakhir), lalu ulang dari [from]. */
    fun setRange(slug: String, from: Int, to: Int) {
        val f = fileOf(slug)
        if (!f.exists()) return
        val o = JSONObject(f.readText())
        o.put("from_step", from)
        o.put("to_step", to)
        f.writeText(o.toString(2))
    }

    /**
     * Pengulangan script: 0 = tanpa batas (sampai dihentikan), 1 = sekali sampai langkah terakhir, N = N kali.
     * Bila script belum diatur sendiri: ikut "Jumlah putaran" di Pengaturan ([globalLoops]), lalu "cycles" bawaan script.
     */
    fun repeatOf(slug: String, globalLoops: Int): Int {
        val o = try { load(slug) } catch (_: Exception) { return globalLoops }
        if (o.has("repeat")) return max(0, o.optInt("repeat", 0))
        if (globalLoops > 0) return globalLoops
        return max(0, o.optInt("cycles", 0))
    }

    fun setRepeat(slug: String, n: Int) {
        val f = fileOf(slug)
        if (!f.exists()) return
        val o = JSONObject(f.readText())
        o.put("repeat", max(0, n))
        f.writeText(o.toString(2))
    }

    fun delete(slug: String) {
        fileOf(slug).delete()
        if (prefs.getString("activeScript", null) == slug) activeSlug = slugs().firstOrNull() ?: ""
    }

    /** Mengembalikan script aktif ke versi bawaan aplikasi. False bila script ini bukan bawaan. */
    fun resetActive(): Boolean {
        val asset = bundled[activeSlug] ?: return false
        copyAsset(asset, scriptFile)
        return true
    }

    private fun uniqueSlug(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "script" }
        var s = base
        var n = 2
        while (fileOf(s).exists()) { s = "$base-$n"; n++ }
        return s
    }

    private fun addScript(o: JSONObject, fallbackName: String): String {
        if (o.optString("name").isBlank()) o.put("name", fallbackName)
        val slug = uniqueSlug(o.optString("name"))
        fileOf(slug).writeText(o.toString(2))
        return slug
    }

    // ------------------------------------------------------------------------------------------
    // Gambar
    // ------------------------------------------------------------------------------------------

    fun imageFile(name: String): File? {
        for (ext in listOf("png", "jpg", "jpeg", "webp")) {
            val f = File(imgDir, "$name.$ext"); if (f.exists()) return f
        }
        return null
    }

    /** Daftar gambar yang dibutuhkan script [slug] (bawaan: script aktif lama): id_0, dst. */
    fun requiredImages(slug: String = activeSlug): List<String> {
        val acts = load(slug).getJSONArray("actions")
        val out = LinkedHashSet<String>()
        for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            if (a.optString("type") != "Search Image") continue
            val arr = a.optJSONArray("images")
            if (arr != null) {
                for (k in 0 until arr.length()) arr.optString(k).takeIf { it.isNotEmpty() }?.let { out.add(it) }
            } else a.optString("image").takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.toList()
    }

    fun missingImages(slug: String = activeSlug) = requiredImages(slug).filter { imageFile(it) == null }

    // ------------------------------------------------------------------------------------------
    // Impor
    // ------------------------------------------------------------------------------------------

    private fun isImage(lower: String) =
        lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")

    /** File yang dilewati pada impor terakhir karena ekstensinya tidak dikenal (dikosongkan oleh pemanggil sebelum impor). */
    val ignored = ArrayList<String>()

    private fun store(name: String, input: InputStream, added: MutableList<String>) {
        val lower = name.lowercase()
        when {
            lower.endsWith(".json") || lower.endsWith(".txt") || lower.endsWith(".bak") ->
                importScriptText(name, input.readBytes().toString(Charsets.UTF_8), added)
            isImage(lower) -> File(imgDir, name).outputStream().use { input.copyTo(it) }
            else -> ignored.add(name)
        }
    }

    /**
     * Script dikenali dari isinya:
     *  - objek dengan "actions" = script Image Clicker (ditambahkan sebagai script baru),
     *  - larik / objek dengan "targets" = macro klik koordinat (mis. min_n_max.txt), diubah jadi langkah "Single Click".
     */
    private fun importScriptText(file: String, text: String, added: MutableList<String>) {
        val t = text.trim().removePrefix("﻿")
        val base = file.removeSuffix(".bak").removeSuffix(".BAK").substringBeforeLast('.')
        if (!t.startsWith("[") && !t.startsWith("{")) {
            // Teks biasa (Notepad): satu klik per baris "x,y,jeda".
            added.add(addScript(macroToScript(simpleTextToMacro(file, t), base), base))
            return
        }
        if (t.startsWith("[")) {
            val arr = JSONArray(t)
            var n = 0
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                if (!m.has("targets")) continue
                added.add(addScript(macroToScript(m, "$base ${i + 1}"), base))
                n++
            }
            if (n == 0) throw IllegalArgumentException("$file bukan script yang dikenali")
        } else {
            val o = JSONObject(t)
            when {
                o.has("targets") -> added.add(addScript(macroToScript(o, base), base))
                o.optJSONArray("actions") != null -> added.add(addScript(o, base))
                else -> throw IllegalArgumentException("$file bukan script yang dikenali")
            }
        }
    }

    /**
     * Script teks sederhana, ditulis sendiri di Notepad. Satu klik per baris: "x,y,jeda" (jeda ms, boleh dikosongkan).
     * Baris pengaturan (opsional): "nama: ...", "putaran: 10", "jeda: 50" (jeda bawaan), "layar: 2560x1440".
     * Baris yang diawali # atau // diabaikan.
     */
    private fun simpleTextToMacro(file: String, text: String): JSONObject {
        var name = ""
        var cycles = 1
        var defDelay = 300L
        var w = 0
        var h = 0
        val targets = JSONArray()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val kv = Regex("^([A-Za-z ]+):\\s*(.*)$").find(line)
            if (kv != null) {
                val key = kv.groupValues[1].trim().lowercase()
                val v = kv.groupValues[2].trim()
                when (key) {
                    "nama", "name" -> name = v
                    "putaran", "cycles" -> cycles = max(1, v.toIntOrNull() ?: 1)
                    "jeda", "delay" -> defDelay = v.toLongOrNull() ?: defDelay
                    "layar", "screen" -> {
                        val m = Regex("(\\d+)\\s*[xX\\u00D7]\\s*(\\d+)").find(v)
                            ?: throw IllegalArgumentException("$file: format layar harus 2560x1440")
                        w = m.groupValues[1].toInt(); h = m.groupValues[2].toInt()
                    }
                    else -> throw IllegalArgumentException("$file: pengaturan tidak dikenal \"$key\"")
                }
                continue
            }
            val nums = line.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
                .map { it.toLongOrNull() ?: throw IllegalArgumentException("$file: baris tidak valid \"$line\"") }
            if (nums.size !in 2..3) throw IllegalArgumentException("$file: baris harus x,y atau x,y,jeda: \"$line\"")
            val t = JSONObject()
            t.put("xPos", nums[0].toInt())
            t.put("yPos", nums[1].toInt())
            t.put("delayValue", if (nums.size == 3) nums[2] else defDelay)
            t.put("delayUnit", 0)
            t.put("duration", 0)
            targets.put(t)
        }
        if (targets.length() == 0) throw IllegalArgumentException("$file: tidak ada baris klik")
        val m = JSONObject()
        m.put("name", name)
        m.put("targets", targets)
        m.put("numberOfCycles", cycles)
        if (w > 0 && h > 0) { m.put("refW", w); m.put("refH", h) }
        return m
    }

    /** Macro {name, targets:[{xPos,yPos,delayValue,delayUnit,duration}], numberOfCycles} -> script aplikasi ini. */
    private fun macroToScript(m: JSONObject, fallback: String): JSONObject {
        val name = m.optString("name").ifBlank { fallback }
        val targets = m.optJSONArray("targets") ?: JSONArray()
        val prefix = name.lowercase().replace(Regex("[^a-z0-9]+"), "").take(6).ifEmpty { "m" }
        val acts = JSONArray()
        var maxX = 0
        var maxY = 0
        for (i in 0 until targets.length()) {
            val t = targets.getJSONObject(i)
            val x = t.optInt("xPos")
            val y = t.optInt("yPos")
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            val unit = if (t.optInt("delayUnit") == 1) 1000L else 1L
            val a = JSONObject()
            a.put("id", "%s%03d".format(prefix, i + 1))
            a.put("type", "Single Click")
            a.put("found_action", "Next")
            a.put("x", x)
            a.put("y", y)
            a.put("delay", t.optLong("delayValue", 0) * unit)
            a.put("area", 1)
            a.put("click_loop_count", 1)
            a.put("r", 50.0)
            a.put("similar", 85)
            a.put("not_found_action_repeat_count", 1)
            a.put("end_long_click_duration", 500)
            if (t.optInt("duration") > 0) a.put("press_ms", t.optInt("duration"))
            acts.put(a)
        }
        // Ukuran layar rekaman tidak ada di file macro: ambil resolusi umum terkecil yang memuat semua titik.
        val sizes = listOf(
            1920 to 1080, 2160 to 1080, 2280 to 1080, 2340 to 1080, 2400 to 1080,
            2560 to 1440, 2960 to 1440, 3040 to 1440, 3200 to 1440, 3840 to 2160
        )
        val size = if (m.optInt("refW") > 0 && m.optInt("refH") > 0) m.optInt("refW") to m.optInt("refH")
            else sizes.firstOrNull { it.first > maxX && it.second > maxY } ?: ((maxX + 1) to (maxY + 1))
        val o = JSONObject()
        o.put("name", name)
        o.put("app_name", "Macro")
        o.put("version", "v0.0.1")
        o.put("description", "Macro klik koordinat (diimpor)")
        o.put("own_timing", true)
        o.put("cycles", max(1, m.optInt("numberOfCycles", 1)))
        o.put("ref_w", size.first)
        o.put("ref_h", size.second)
        o.put("actions", acts)
        return o
    }

    /**
     * Impor file .json / .txt (script) / .png (gambar) / .zip (hasil export Image Clicker) dari picker.
     * Script yang diimpor ditambahkan sebagai script baru (tidak menimpa). Mengembalikan slug script yang ditambahkan.
     */
    fun import(uri: Uri): List<String> {
        var name = "file"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
        }
        val added = ArrayList<String>()
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("gagal buka $name")
        ins.use {
            if (name.lowercase().endsWith(".zip")) {
                val zis = ZipInputStream(it)
                var e = zis.nextEntry
                while (e != null) {
                    // File cadangan (.bak) di dalam ZIP dilewati supaya script tidak terpasang dobel.
                    if (!e.isDirectory && !e.name.lowercase().endsWith(".bak")) {
                        // File lain di dalam ZIP (mis. README) yang bukan script tidak boleh menggagalkan seluruh impor.
                        try { store(File(e.name).name, zis, added) } catch (_: Exception) {}
                    }
                    e = zis.nextEntry
                }
            } else store(name, it, added)
        }
        return added
    }
}
