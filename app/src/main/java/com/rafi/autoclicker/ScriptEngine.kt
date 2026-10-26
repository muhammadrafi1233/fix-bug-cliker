package com.rafi.autoclicker

import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Cfg(
    val refW: Int = 2400,      // lebar layar saat script direkam
    val refH: Int = 1080,      // tinggi layar saat script direkam
    val floor: Double = 0.70,  // kemiripan bawaan, hanya untuk langkah yang tidak punya nilai "similar" sendiri
    val loops: Int = 0,        // 0 = ulang terus sampai Stop
    val timeoutSec: Int = 0,   // 0 = tunggu tanpa batas pada langkah "Repeat"
    val clickMs: Int = 300,    // durasi total per klik (tap + tunda); 0 = ikut tunda di script
    val searchMs: Int = 300,   // waktu mencari gambar per langkah sebelum Next/Action; 0 = cek sekali
    val stepGapMs: Int = 300,  // jeda tetap di akhir SETIAP langkah (ketemu / tidak ketemu / klik koordinat); 0 = tanpa jeda
    val sameGapMs: Int = 700,  // total jeda setelah klik bila langkah berikutnya mencari area gambar yang sama (sudah termasuk stepGapMs); 0 = ikut stepGapMs
    val startStep: Int = 1,    // putaran pertama mulai dari langkah ke-N (mode uji / lewati langkah awal)
    val endStep: Int = 0,      // berhenti setelah langkah ke-N; 0 = sampai langkah terakhir
    val marker: Boolean = true, // tampilkan nomor langkah di layar
    val repeatRange: Boolean = false // true = setiap putaran mengulang dari startStep (rentang dari script), bukan dari langkah 1
)

class ScriptEngine(
    private val store: ScriptStore,
    private val slug: String,
    private val capturer: ScreenCapturer,
    cfg0: Cfg,
    private val onTap: ((Float, Float, Int) -> Unit)? = null,
    private val onStep: ((Int, Int) -> Unit)? = null   // (nomor langkah, total langkah), dipanggil tiap langkah dimulai
) {
    /** Pengaturan akhir = pengaturan aplikasi, ditimpa oleh isi script bila script menentukannya sendiri. */
    private val cfg: Cfg = effectiveCfg(cfg0)

    /**
     * Script boleh membawa: "own_timing" (pakai jeda per langkah dari script, abaikan jeda tetap aplikasi),
     * "ref_w"/"ref_h" (ukuran layar rekaman sendiri), dan "cycles" (jumlah putaran bawaan, dipakai bila
     * "Jumlah putaran" di pengaturan 0).
     */
    private fun effectiveCfg(base: Cfg): Cfg {
        return try {
            val root = store.load(slug)
            var c = base
            if (root.optBoolean("own_timing", false)) c = c.copy(clickMs = 0, stepGapMs = 0, sameGapMs = 0)
            val w = root.optInt("ref_w", 0)
            val h = root.optInt("ref_h", 0)
            if (w > 0 && h > 0) c = c.copy(refW = w, refH = h)
            // Rentang langkah dari script ("from_step"/"to_step"); tidak dipakai saat mode uji dari editor langkah.
            if (base.startStep == 1 && base.endStep == 0) {
                val f = root.optInt("from_step", 0)
                val t = root.optInt("to_step", 0)
                if (f > 1 || t > 0) c = c.copy(startStep = max(1, f), endStep = max(0, t), repeatRange = true)
            }
            val cyc = root.optInt("cycles", 0)
            if (cyc > 0 && c.loops == 0) c = c.copy(loops = cyc)
            // Pengulangan yang diatur per script (tab Script): 0 = tanpa batas, 1 = sekali sampai akhir, N = N kali.
            if (root.has("repeat")) c = c.copy(loops = max(0, root.optInt("repeat", 0)))
            c
        } catch (e: Exception) {
            base
        }
    }

    @Volatile private var running = false
    /** Semua log dari engine ini ditandai nama scriptnya, supaya log 3 script yang berjalan sekaligus tidak tercampur. */
    private fun log(s: String) = Bus.log("[$slug] $s")
    private val tplCache = HashMap<String, Mat>()
    private var lastFrame: Mat? = null
    private var lastBest = 0.0
    private var lastNeed = 0.0
    private var lastMissLog = 0L
    private var actsRef: JSONArray? = null

    fun stop() { running = false }

    fun run() {
        running = true
        val root = store.load(slug)
        val acts: JSONArray = root.getJSONArray("actions")
        actsRef = acts
        val ids = (0 until acts.length()).map { acts.getJSONObject(it).optString("id") }
        var loop = 0
        val startIdx = (cfg.startStep - 1).coerceIn(0, max(0, acts.length() - 1))
        var endIdx = if (cfg.endStep > 0) min(cfg.endStep, acts.length()) else acts.length()
        if (endIdx <= startIdx) endIdx = acts.length()   // rentang tidak masuk akal: pakai sampai langkah terakhir
        log("Mulai \"${root.optString("name")}\": ${acts.length()} langkah")
        if (startIdx > 0 || endIdx < acts.length())
            log((if (cfg.repeatRange) "Rentang langkah" else "Mode uji") + ": langkah ${startIdx + 1} sampai $endIdx")
        while (running) {
            var i = if (loop == 0 || cfg.repeatRange) startIdx else 0
            while (running && i < endIdx) {
                i = step(acts.getJSONObject(i), i, ids)
                // Jeda tetap setelah tiap langkah, ada gambar atau tidak, supaya langkah berjalan satu per satu.
                if (running && cfg.stepGapMs > 0) {
                    sleep(cfg.stepGapMs.toLong())
                    capturer.invalidate()   // langkah berikutnya memakai frame layar baru
                }
            }
            loop++
            log("Putaran $loop selesai")
            if (cfg.loops > 0 && loop >= cfg.loops) break
        }
        running = false
        tplCache.values.forEach { it.release() }
        tplCache.clear()
        log("Berhenti")
    }

    private fun jumpIndex(code: String, ids: List<String>, fallback: Int): Int {
        val m = Regex("\\((\\w+)\\)").find(code) ?: return fallback
        val idx = ids.indexOf(m.groupValues[1])
        return if (idx >= 0) idx else fallback
    }

    /** Tidur dalam potongan 25 ms dan berhenti seketika begitu stop() dipanggil (tidak menunggu jeda selesai). */
    private fun sleep(ms: Long) {
        if (ms <= 0) return
        val end = System.currentTimeMillis() + ms
        try {
            while (running) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                Thread.sleep(minOf(left, 25L))
            }
        } catch (_: InterruptedException) { running = false }
    }

    private fun rectKey(a: JSONObject?): String? {
        val r = a?.optJSONObject("rect") ?: return null
        if (r.optInt("w") <= 0 || r.optInt("h") <= 0) return null
        return "${r.optInt("x")},${r.optInt("y")},${r.optInt("w")},${r.optInt("h")}"
    }

    /** True bila langkah urutan berikutnya (i + 1) juga "Search Image" dengan area pencarian yang sama persis. */
    private fun nextSameArea(a: JSONObject, i: Int): Boolean {
        val list = actsRef ?: return false
        if (i + 1 >= list.length()) return false
        val nxt = list.optJSONObject(i + 1) ?: return false
        if (nxt.optString("type") != "Search Image") return false
        val k = rectKey(a) ?: return false
        return k == rectKey(nxt)
    }

    private fun step(a: JSONObject, i: Int, ids: List<String>): Int {
        val svc = ClickerAccessibilityService.instance
        if (svc == null) { log("Layanan Aksesibilitas belum aktif"); running = false; return i }
        val type = a.optString("type")
        onStep?.invoke(i + 1, ids.size)

        if (type == "Search Image") {
            val start = System.currentTimeMillis()
            while (running) {
                val hit = find(a)
                if (hit != null) {
                    if (!running) return i   // dihentikan saat mencari: jangan klik lagi
                    val act = a.optString("found_action")
                    var clicked = false
                    if (act == "Click" || act == "DoubleClick" || act == "LongClick") {
                        log("#${i + 1} ${a.optString("id")} ketemu (${"%.2f".format(hit.third)}) -> $act")
                        val hold = if (act == "LongClick") a.optLong("end_long_click_duration", 500) else a.optLong("press_ms", 50)
                        val atPos = a.optString("found_action_click") == "Position"
                        val cx = if (atPos) a.optDouble("x") * capturerScaleX() else hit.first
                        val cy = if (atPos) a.optDouble("y") * capturerScaleY() else hit.second
                        val t0 = System.currentTimeMillis()
                        onTap?.invoke(cx.toFloat(), cy.toFloat(), i + 1)
                        svc.tap(cx.toFloat(), cy.toFloat(), act == "DoubleClick", hold, a.optLong("double_gap", 300))
                        capturer.invalidate()
                        clicked = true
                        if (cfg.clickMs > 0) sleep(cfg.clickMs - (System.currentTimeMillis() - t0))
                    }
                    if (!(clicked && cfg.clickMs > 0)) sleep(a.optLong("delay", 0))
                    // Langkah berikutnya mencari gambar yang sama: beri waktu agar klik ini benar-benar selesai
                    // (layar/game sempat bereaksi) sebelum langkah berikutnya mulai, lalu pakai frame baru.
                    if (clicked && cfg.sameGapMs > 0 && a.optString("found_more_action") != "Action" && nextSameArea(a, i)) {
                        log("#${i + 1} selesai, jeda ${cfg.sameGapMs} ms sebelum #${i + 2} (gambar sama)")
                        // stepGapMs sudah dijalankan oleh run() setelah langkah ini, jadi di sini hanya selisihnya.
                        sleep((cfg.sameGapMs - cfg.stepGapMs).toLong())
                        capturer.invalidate()
                    }
                    return if (a.optString("found_more_action") == "Action")
                        jumpIndex(a.optString("found_more_action_code"), ids, i + 1) else i + 1
                }
                val nf = a.optString("not_found_action")
                if (nf != "Repeat" && cfg.searchMs > 0 && !a.optBoolean("use_pre_search_frame", false) && System.currentTimeMillis() - start < cfg.searchMs) {
                    sleep(30); continue
                }
                if (nf != "Repeat" || System.currentTimeMillis() - lastMissLog > 2000) {
                    lastMissLog = System.currentTimeMillis()
                    log("#${i + 1} ${a.optString("id")} tidak ketemu: skor ${"%.2f".format(lastBest)} (butuh ${"%.2f".format(lastNeed)})")
                }
                when (nf) {
                    "Repeat" -> {
                        if (cfg.timeoutSec > 0 && System.currentTimeMillis() - start > cfg.timeoutSec * 1000L) return i + 1
                        sleep(max(100L, a.optLong("not_found_action_repeat", 500)))
                    }
                    "Action" -> return jumpIndex(a.optString("not_found_action_code"), ids, i + 1)
                    else -> return i + 1   // "Next"
                }
            }
            return i
        }

        // Single Click / Double Click koordinat
        val sx = capturerScaleX(); val sy = capturerScaleY()
        val x = a.optDouble("x") * sx; val y = a.optDouble("y") * sy
        if (!running) return i
        log("#${i + 1} klik ($x, $y)")
        val t0 = System.currentTimeMillis()
        onTap?.invoke(x.toFloat(), y.toFloat(), i + 1)
        svc.tap(x.toFloat(), y.toFloat(), type.contains("Double"),
            if (type.contains("Long")) a.optLong("end_long_click_duration", 500) else a.optLong("press_ms", 50),
            a.optLong("double_gap", 300))
        capturer.invalidate()
        if (cfg.clickMs > 0) sleep(cfg.clickMs - (System.currentTimeMillis() - t0)) else sleep(a.optLong("delay", 0))
        return i + 1
    }

    private var curW = cfg.refW.toDouble()
    private var curH = cfg.refH.toDouble()
    private fun capturerScaleX() = curW / cfg.refW
    private fun capturerScaleY() = curH / cfg.refH

    private fun loadTemplate(name: String): Mat? {
        tplCache[name]?.let { return it }
        val f = store.imageFile(name) ?: return null
        val m = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_COLOR)
        if (m.empty()) return null
        tplCache[name] = m
        return m
    }

    /** Mengembalikan (x, y, skor) titik tengah hasil match dalam piksel layar, atau null. */
    private fun find(a: JSONObject): Triple<Double, Double, Double>? {
        val imgs = a.optJSONArray("images")
        val names = ArrayList<String>()
        if (imgs != null) for (k in 0 until imgs.length()) imgs.optString(k).takeIf { it.isNotEmpty() }?.let { names.add(it) }
        else a.optString("image").takeIf { it.isNotEmpty() }?.let { names.add(it) }
        lastBest = 0.0
        if (names.isEmpty()) return null

        val frame: Mat = if (a.optBoolean("use_pre_search_frame", false) && lastFrame != null) lastFrame!!
        else {
            val g = capturer.grab() ?: return null
            lastFrame?.release(); lastFrame = g; g
        }
        curW = frame.cols().toDouble(); curH = frame.rows().toDouble()
        val sx = curW / cfg.refW; val sy = curH / cfg.refH
        // Tiap gambar memakai tingkat kemiripannya sendiri (kolom "similar" di langkah). Batas bawaan dari Pengaturan
        // hanya dipakai bila langkah tidak punya nilai sendiri.
        val own = a.optInt("similar", -1)
        val threshold = if (own >= 0) max(own, 1) / 100.0 else cfg.floor
        lastNeed = threshold

        // Area pencarian dari "rect" (+ margin kecil)
        var rx = 0; var ry = 0; var rw = frame.cols(); var rh = frame.rows()
        a.optJSONObject("rect")?.let { r ->
            if (r.optInt("w") > 0 && r.optInt("h") > 0) {
                val m = 20
                rx = ((r.optInt("x") * sx).toInt() - m).coerceAtLeast(0)
                ry = ((r.optInt("y") * sy).toInt() - m).coerceAtLeast(0)
                rw = min((r.optInt("w") * sx).toInt() + 2 * m, frame.cols() - rx)
                rh = min((r.optInt("h") * sy).toInt() + 2 * m, frame.rows() - ry)
            }
        }

        var best: Triple<Double, Double, Double>? = null
        for (n in names) {
            val tpl = loadTemplate(n) ?: continue
            var t = tpl
            var scaled = false
            if (abs(sx - 1.0) > 0.01 || abs(sy - 1.0) > 0.01) {
                t = Mat(); Imgproc.resize(tpl, t, Size(), sx, sy, Imgproc.INTER_AREA); scaled = true
            }
            var region = Rect(rx, ry, rw, rh)
            if (rw < t.cols() || rh < t.rows()) region = Rect(0, 0, frame.cols(), frame.rows())
            if (t.cols() > region.width || t.rows() > region.height) { if (scaled) t.release(); continue }
            val sub = Mat(frame, region)
            val res = Mat()
            Imgproc.matchTemplate(sub, t, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            val score = mm.maxVal
            if (!score.isNaN() && score > lastBest) lastBest = score
            if (!score.isNaN() && score >= threshold && (best == null || score > best.third)) {
                best = Triple(region.x + mm.maxLoc.x + t.cols() / 2.0, region.y + mm.maxLoc.y + t.rows() / 2.0, score)
            }
            res.release(); sub.release(); if (scaled) t.release()
        }
        return best
    }
}
