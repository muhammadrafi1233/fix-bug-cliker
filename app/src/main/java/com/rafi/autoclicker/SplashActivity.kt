package com.rafi.autoclicker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.animation.OvershootInterpolator

/**
 * Opening screen "Rafi HayDay" (pemandangan ladang, logo, papan kayu). Progress bar mengikuti langkah inisialisasi yang sungguhan
 * (baca pengaturan, siapkan script, cek gambar), bukan timer buatan. Setelah selesai langsung
 * membuka halaman utama.
 */
class SplashActivity : Activity() {
    private lateinit var progress: RoundedProgress
    private var ready = false
    private var resumed = false
    private var gone = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        // Layar loading bergaya RAFI HAYDAY: pemandangan ladang, logo gandum, papan kayu, bar kemajuan.
        val root = FrameLayout(this)
        root.addView(FarmBackgroundView(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(20), dp(20), dp(20), dp(20))

        val logo = LogoView(this, false)
        col.addView(logo, lp(dp(88), dp(88), 0f, 0, 0, 0, dp(12)).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        val sign = WoodSign(this, "RAFI HAYDAY")
        col.addView(sign, lp(WRAP, WRAP).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        val tag = text("Rafi Automatic", 15f, Palette.leaf, true)
        tag.gravity = Gravity.CENTER
        col.addView(tag, lp(WRAP, WRAP, 0f, 0, dp(18), 0, dp(24)).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        progress = RoundedProgress(this)
        val maxW = minOf(dp(210), (resources.displayMetrics.widthPixels * 0.7f).toInt())
        col.addView(progress, lp(maxW, dp(18), 0f, 0, 0, 0, dp(24)).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        val label = text("Memuat aplikasi\u2026", 16f, Palette.leaf, true)
        label.gravity = Gravity.CENTER
        col.addView(label, lp(WRAP, WRAP).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        root.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        setContentView(root)

        // papan jatuh dari atas dan memantul sedikit (seperti animasi "drop" di HTML)
        sign.rotation = -6f
        sign.alpha = 0f
        sign.translationY = -dp(36).toFloat()
        sign.animate().alpha(1f).translationY(0f).rotation(-1.5f)
            .setDuration(700).setInterpolator(OvershootInterpolator(1.4f)).start()

        runInit()
    }

    private fun runInit() {
        val steps = listOf<Pair<String, () -> Unit>>(
            "Memuat pengaturan" to { Prefs.cfg(this) },
            "Menyiapkan script" to { ScriptStore(this).load() },
            "Memeriksa gambar" to { ScriptStore(this).let { it.requiredImages(); it.missingImages() } }
        )
        Thread {
            for ((i, step) in steps.withIndex()) {
                // Kegagalan tidak menghentikan pembukaan aplikasi: halaman utama menampilkan
                // pesan yang jelas bila script tidak bisa dibaca.
                try { step.second() } catch (_: Throwable) {}
                val last = i == steps.size - 1
                runOnUiThread {
                    progress.animateTo((i + 1f) / steps.size, 260) {
                        if (last) { ready = true; goNext() }
                    }
                }
                // tunggu animasi langkah ini selesai sebelum lanjut, supaya gerakannya halus
                try { Thread.sleep(260) } catch (_: InterruptedException) { return@Thread }
            }
        }.start()
    }

    private fun goNext() {
        if (!ready || !resumed || gone) return
        gone = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onResume() { super.onResume(); resumed = true; goNext() }
    override fun onPause() { resumed = false; super.onPause() }
}
