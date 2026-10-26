package com.rafi.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        /** Dari editor langkah: ambil gambar baru dari layar. Extra: "slug" (script), "id" (langkah; kosong = langkah baru). */
        const val ACTION_RECAPTURE = "recapture"
        /** Dari editor langkah: script berubah, baca ulang penanda langkah. Extra opsional: "slug". */
        const val ACTION_REFRESH = "refresh"
        private const val CH = "clicker"
    }

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    /** Hingga 3 engine independen, satu per tombol ▶ (indeks 0,1,2); yang terpakai sebanyak script yang dipilih. */
    private val engines = arrayOfNulls<ScriptEngine>(3)
    private var bubble: FloatingBubble? = null
    private var marker: TapMarker? = null
    private var badge: StepBadge? = null
    private var overlay: StepOverlay? = null       // penanda langkah yang selalu tampil
    private var prompt: CapturePrompt? = null      // pil "Ambil layar" saat mengganti gambar
    private var cropper: CropOverlay? = null       // alat potong gambar
    private val main = Handler(Looper.getMainLooper())
    private var active = false

    override fun onBind(i: Intent?): IBinder? = null

    private fun toast(s: String) = main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        val act = intent?.action
        if (act == ACTION_REFRESH || act == ACTION_RECAPTURE) {
            // Layanan belum jalan (instance baru tanpa tombol melayang): tidak ada yang bisa dikerjakan.
            if (!active) { stopSelf(); return START_NOT_STICKY }
            val slug = intent?.getStringExtra("slug").orEmpty()
            val stepId = intent?.getStringExtra("id").orEmpty()
            if (act == ACTION_REFRESH) main.post { if (slug.isNotEmpty()) showOverlay(slug) else overlay?.reload() }
            else main.post { beginRecapture(slug, stepId) }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || active) return START_NOT_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Rafi Automatic", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setContentTitle("Rafi Automatic aktif")
            .setContentText("Tekan TUTUP untuk menghentikan")
            .setSmallIcon(R.drawable.ic_stat_leaf)
            .addAction(Notification.Action.Builder(null, "TUTUP", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { shutdown(); return START_NOT_STICKY }
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        capturer = ScreenCapturer(this, projection!!)
        active = true
        Bus.serviceActive = true
        Bus.log("Tombol melayang aktif")

        if (Settings.canDrawOverlays(this)) {
            val scriptStore = ScriptStore(this)
            val count = scriptStore.bubbleSlugs().size.coerceIn(1, ScriptStore.MAX_SLOTS)
            bubble = FloatingBubble(this, slotCount = count,
                onToggle = { slot -> toggle(slot) },
                onSettings = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onClose = { shutdown() },
                listScripts = { scriptStore.list() },
                activeSlugFor = { slot -> scriptStore.activeSlugSlot(slot) },
                onPickScript = { slot, s ->
                    if (Bus.runningSlots[slot]) toast("Hentikan dulu tombol ${slot + 1} (tekan lagi untuk stop)")
                    else {
                        scriptStore.setActiveSlugSlot(slot, s.slug)
                        toast("Tombol ${slot + 1}: ${s.name}")
                        Bus.log("Tombol ${slot + 1} dipasangi script: ${s.name}")
                        showOverlay(s.slug)
                    }
                },
                onSteps = { on -> if (on) showOverlay(scriptStore.activeSlugSlot(0)) else overlay?.hide() }).also { it.show() }
            overlay = StepOverlay(this, scriptStore)
            showOverlay(scriptStore.activeSlugSlot(0))
            toast(if (count == 1) "Buka game, lalu tekan tombol ▶" else "Buka game, lalu tekan salah satu dari $count tombol ▶")
        } else toast("Izin 'tampil di atas aplikasi lain' belum diberikan")
        return START_NOT_STICKY
    }

    /** Penanda langkah dipakai bila tombol mata di tombol melayang menyala DAN "Nomor langkah di layar" di pengaturan aktif. */
    private fun overlayWanted() = (bubble?.stepsOn ?: false) && Prefs.cfg(this).marker

    private fun showOverlay(slug: String) {
        val o = overlay ?: return
        if (overlayWanted()) o.show(slug) else o.hide()
    }

    private fun toggle(slot: Int) { if (Bus.runningSlots[slot]) stopEngine(slot) else startEngine(slot) }

    /** Berhenti: ikon dan status langsung kembali ke ▶ (tidak menunggu thread script selesai), engine menyusul berhenti. */
    private fun stopEngine(slot: Int) {
        val e = engines[slot]
        engines[slot] = null
        e?.stop()
        Bus.setRunning(slot, false)
        bubble?.setRunning(slot, false)
        if (!Bus.running) overlay?.setCurrent(0)
        Bus.ping()
    }

    /** Menjalankan script yang ditempel ke tombol ▶ nomor [slot] (0,1,2). Tiap slot punya engine sendiri,
     *  jadi 3 slot bisa jalan bersamaan; satu kapturer layar (capturer) dipakai bersama karena sama-sama
     *  membaca layar yang sama, dan ketukannya sudah diantre lewat ClickerAccessibilityService.tap(). */
    private fun startEngine(slot: Int) {
        val cap = capturer ?: return
        if (Bus.runningSlots[slot]) return
        if (ClickerAccessibilityService.instance == null) { toast("Aktifkan Layanan Aksesibilitas dulu"); return }
        val store = ScriptStore(this)
        val slug = store.activeSlugSlot(slot)
        if (store.missingImages(slug).isNotEmpty()) { toast("Gambar tombol ${slot + 1} belum lengkap, impor dulu"); return }
        val base = Prefs.cfg(this)
        // Mode uji sekali pakai dari editor langkah hanya berlaku bila tombol ini sedang menjalankan script aktif lama.
        val test = if (slug == store.activeSlug) Prefs.takeTest(this) else null
        val cfg = if (test != null) base.copy(startStep = test.first, endStep = test.second,
            loops = if (test.second > 0) 1 else base.loops) else base
        if (cfg.marker && Settings.canDrawOverlays(this) && marker == null) marker = TapMarker(this)
        if (!cfg.marker) { marker?.hide(); marker = null }
        if (cfg.marker && Settings.canDrawOverlays(this) && badge == null) badge = StepBadge(this)
        if (!cfg.marker) { badge?.hide(); badge = null }
        val mk = marker
        val bd = badge
        val ov = overlay
        // Sorotan langkah aktif hanya mengikuti tombol ▶ dengan nomor terkecil yang berjalan (supaya tidak berkedip bila beberapa script jalan).
        val onStep: ((Int, Int) -> Unit)? = if (bd != null || ov != null) { n, total ->
            main.post {
                bd?.show(n, total)
                if (ov != null && overlayWanted() && Bus.runningSlots.indexOfFirst { it } == slot) ov.focus(slug, n)
            }
        } else null
        val eng = ScriptEngine(store, slug, cap, cfg,
            if (mk != null) { x, y, n -> main.post { mk.show(x, y, n) } } else null,
            onStep)
        engines[slot] = eng
        Bus.setRunning(slot, true)
        bubble?.setRunning(slot, true)   // ikon ■ langsung muncul; pemuatan script berjalan di thread terpisah
        Bus.ping()
        Thread {
            try {
                org.opencv.android.OpenCVLoader.initLocal()
                eng.run()
            } catch (e: Throwable) {
                Bus.log("[$slug] Error: ${e.message}")
                toast("Tombol ${slot + 1} error: ${e.message}")
            } finally {
                main.post {
                    // Selesai sendiri (putaran habis / error). Bila sudah dihentikan manual atau slot dipakai engine baru,
                    // status sudah diurus di stopEngine()/startEngine() dan tidak boleh ditimpa.
                    if (engines[slot] === eng) {
                        engines[slot] = null
                        Bus.setRunning(slot, false)
                        bubble?.setRunning(slot, false)
                        if (!Bus.running) overlay?.setCurrent(0)
                        Bus.ping()
                    }
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------------------------------
    // Ganti / tambah gambar langkah dari layar
    // ------------------------------------------------------------------------------------------

    /** Nomor langkah (mulai 1) dengan id [id] di script [slug]; 0 bila tidak ada. */
    private fun stepNumberOf(slug: String, id: String): Int = try {
        val acts = ScriptStore(this).load(slug).getJSONArray("actions")
        var n = 0
        for (i in 0 until acts.length()) if (acts.getJSONObject(i).optString("id") == id) { n = i + 1; break }
        n
    } catch (_: Exception) { 0 }

    /** Tahap 1: tampilkan pil "Ambil layar". Pengguna membuka game ke layar yang dituju, lalu mengetuknya. */
    private fun beginRecapture(slug: String, id: String) {
        if (!active || capturer == null) { toast("Tekan Mulai dulu agar bisa mengambil gambar dari layar"); return }
        if (!Settings.canDrawOverlays(this)) { toast("Izin 'tampil di atas aplikasi lain' belum diberikan"); return }
        closeCaptureUi()
        val n = if (id.isEmpty()) 0 else stepNumberOf(slug, id)
        val title = if (id.isEmpty()) "Tambah langkah cari gambar" else if (n > 0) "Ubah gambar langkah #$n" else "Ubah gambar langkah"
        prompt = CapturePrompt(this, title, onCapture = { startCrop(slug, id) }, onCancel = { closeCaptureUi() }).also { it.show() }
        toast("Buka layar game yang dituju, lalu ketuk \"Ambil layar\"")
    }

    /** Tahap 2: sembunyikan semua overlay kita, ambil tangkapan layar bersih, lalu buka alat potong. */
    private fun startCrop(slug: String, id: String) {
        val cap = capturer ?: return
        prompt?.hide(); prompt = null
        setOverlaysHidden(true)
        Thread {
            try { Thread.sleep(450) } catch (_: InterruptedException) {}   // beri waktu layar menggambar ulang tanpa overlay
            val shot = try { cap.grabBitmap() } catch (_: Throwable) { null }
            main.post {
                if (shot == null || !active) {
                    setOverlaysHidden(false)
                    toast("Gagal mengambil layar, coba lagi")
                    return@post
                }
                cropper = CropOverlay(this, shot, initialRegion(slug, id, shot.width, shot.height)) { r -> finishCrop(slug, id, shot, r) }
                    .also { it.show() }
            }
        }.start()
    }

    /** Kotak awal alat potong: posisi gambar lama (bila ada), dalam piksel tangkapan layar. */
    private fun initialRegion(slug: String, id: String, sw: Int, sh: Int): Rect? {
        if (id.isEmpty()) return null
        return try {
            val st = ScriptStore(this)
            val a = st.load(slug).getJSONArray("actions").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
            } ?: return null
            val r = a.optJSONArray("origin_rect")?.optJSONObject(0) ?: a.optJSONObject("rect") ?: return null
            if (r.optInt("w") <= 0 || r.optInt("h") <= 0) return null
            val ref = st.refSize(slug)
            val fx = sw.toDouble() / ref.first
            val fy = sh.toDouble() / ref.second
            Rect(
                (r.optInt("x") * fx).toInt().coerceIn(0, sw - 1), (r.optInt("y") * fy).toInt().coerceIn(0, sh - 1),
                ((r.optInt("x") + r.optInt("w")) * fx).toInt().coerceIn(1, sw), ((r.optInt("y") + r.optInt("h")) * fy).toInt().coerceIn(1, sh)
            )
        } catch (_: Exception) { null }
    }

    /** Tahap 3: simpan potongan ke script, lalu pulihkan tombol melayang dan penanda. */
    private fun finishCrop(slug: String, id: String, shot: Bitmap, r: Rect?) {
        cropper = null
        try {
            if (r == null) { toast("Dibatalkan"); return }
            val l = r.left.coerceIn(0, shot.width - 1)
            val t = r.top.coerceIn(0, shot.height - 1)
            val w = r.width().coerceIn(1, shot.width - l)
            val h = r.height().coerceIn(1, shot.height - t)
            val crop = Bitmap.createBitmap(shot, l, t, w, h)
            val msg = ScriptStore(this).saveCapture(slug, id, crop, Rect(l, t, l + w, t + h), shot.width, shot.height)
            Bus.log("[$slug] $msg")
            toast(msg)
        } catch (e: Throwable) {
            toast("Gagal menyimpan gambar: ${e.message}")
        } finally {
            setOverlaysHidden(false)
            showOverlay(slug)
            shot.recycle()
        }
    }

    private fun setOverlaysHidden(hidden: Boolean) {
        bubble?.setVisible(!hidden)
        overlay?.setVisible(!hidden)
        if (hidden) marker?.hide()
    }

    private fun closeCaptureUi() {
        prompt?.hide(); prompt = null
        cropper?.hide(); cropper = null
        setOverlaysHidden(false)
    }

    private fun shutdown() {
        for (e in engines) e?.stop()
        closeCaptureUi()
        overlay?.hide(); overlay = null
        bubble?.hide(); bubble = null
        marker?.hide(); marker = null
        badge?.hide(); badge = null
        try { capturer?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null; capturer = null
        for (i in engines.indices) { engines[i] = null; Bus.setRunning(i, false) }
        active = false
        if (Bus.serviceActive) Bus.log("Tombol melayang ditutup")
        Bus.serviceActive = false
        Bus.ping()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        for (e in engines) e?.stop()
        prompt?.hide(); cropper?.hide(); overlay?.hide()
        bubble?.hide(); badge?.hide(); Bus.serviceActive = false; super.onDestroy()
    }
}
