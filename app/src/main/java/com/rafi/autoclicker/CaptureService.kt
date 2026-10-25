package com.rafi.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
        private const val CH = "clicker"
    }

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    /** Hingga 3 engine independen, satu per tombol ▶ (indeks 0,1,2); yang terpakai sebanyak script yang dipilih. */
    private val engines = arrayOfNulls<ScriptEngine>(3)
    private var bubble: FloatingBubble? = null
    private var marker: TapMarker? = null
    private var badge: StepBadge? = null
    private val main = Handler(Looper.getMainLooper())
    private var active = false

    override fun onBind(i: Intent?): IBinder? = null

    private fun toast(s: String) = main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
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
                    }
                }).also { it.show() }
            toast(if (count == 1) "Buka game, lalu tekan tombol ▶" else "Buka game, lalu tekan salah satu dari $count tombol ▶")
        } else toast("Izin 'tampil di atas aplikasi lain' belum diberikan")
        return START_NOT_STICKY
    }

    private fun toggle(slot: Int) { if (Bus.runningSlots[slot]) stopEngine(slot) else startEngine(slot) }

    /** Berhenti: ikon dan status langsung kembali ke ▶ (tidak menunggu thread script selesai), engine menyusul berhenti. */
    private fun stopEngine(slot: Int) {
        val e = engines[slot]
        engines[slot] = null
        e?.stop()
        Bus.setRunning(slot, false)
        bubble?.setRunning(slot, false)
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
        val eng = ScriptEngine(store, slug, cap, cfg,
            if (mk != null) { x, y, n -> main.post { mk.show(x, y, n) } } else null,
            if (bd != null) { n, total -> main.post { bd.show(n, total) } } else null)
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
                        Bus.ping()
                    }
                }
            }
        }.start()
    }

    private fun shutdown() {
        for (e in engines) e?.stop()
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
        bubble?.hide(); badge?.hide(); Bus.serviceActive = false; super.onDestroy()
    }
}
