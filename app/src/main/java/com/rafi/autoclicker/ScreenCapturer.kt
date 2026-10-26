package com.rafi.autoclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class ScreenCapturer(private val ctx: Context, private val mp: MediaProjection) {
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var w = 0
    private var h = 0
    private var last: Bitmap? = null
    /** Nomor urut frame, naik tiap kali ada gambar BARU dari layar. Dipakai supaya tiap engine (slot) bisa
     *  minta "tunggu frame baru" masing-masing tanpa rebutan dengan engine slot lain yang berjalan bersamaan. */
    @Volatile private var frameSeq = 0L

    /** Dipanggil setelah klik: kembalikan token yang dipakai di grab(waitFor=...) supaya PEMANGGIL INI
     *  (bukan engine lain) menunggu frame baru (maks 250 ms) sebelum mencocokkan gambar lagi. */
    fun invalidate(): Long = frameSeq + 1

    init {
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { release() }
        }, Handler(Looper.getMainLooper()))
    }

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") d.getRealMetrics(dm)
        return dm
    }

    private fun setup(nw: Int, nh: Int, dpi: Int) {
        reader?.close()
        val r = ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 2)
        reader = r
        if (vd == null) {
            vd = mp.createVirtualDisplay("cap", nw, nh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        } else {
            vd!!.resize(nw, nh, dpi)
            vd!!.surface = r.surface
        }
        w = nw; h = nh; last = null
    }

    /** Mengembalikan frame layar terbaru (BGR) atau null. [waitFor] = token dari invalidate() milik PEMANGGIL
     *  INI (0 = tidak perlu menunggu frame baru, langsung ambil yang ada). */
    @Synchronized
    fun grab(waitFor: Long = 0): Mat? {
        val b = acquire(waitFor) ?: return null
        val m = Mat()
        Utils.bitmapToMat(b, m)
        Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2BGR)
        return m
    }

    /** Layar sekarang sebagai Bitmap salinan (menunggu frame baru, maks 250 ms). Tidak butuh OpenCV; dipakai alat potong gambar. */
    fun grabBitmap(): Bitmap? {
        val tok = invalidate()
        return acquire(tok)?.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Frame layar terbaru sebagai Bitmap (atau null). Bitmap ini milik capturer: jangan diubah.
     *  [waitFor] > 0 = tunggu sampai frameSeq mencapai token itu (frame genuinely baru), maks 250 ms. */
    @Synchronized
    private fun acquire(waitFor: Long = 0): Bitmap? {
        val dm = realMetrics()
        if (vd == null || dm.widthPixels != w || dm.heightPixels != h) setup(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 1500
        val freshDeadline = t0 + 250
        while (true) {
            val img = reader?.acquireLatestImage()
            if (img != null) {
                if (img.width == w && img.height == h) {
                    val p = img.planes[0]
                    val pad = p.rowStride - p.pixelStride * img.width
                    val bmp = Bitmap.createBitmap(img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(p.buffer)
                    last = if (pad == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
                    frameSeq++
                }
                img.close()
            }
            val now = System.currentTimeMillis()
            if (last != null && (waitFor <= 0 || frameSeq >= waitFor || now > freshDeadline)) break
            if (now > deadline) break
            Thread.sleep(15)
        }
        return last
    }

    fun release() {
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        vd = null; reader = null
    }
}
