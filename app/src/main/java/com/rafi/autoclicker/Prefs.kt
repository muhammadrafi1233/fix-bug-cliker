package com.rafi.autoclicker

import android.content.Context

object Prefs {
    fun cfg(c: Context): Cfg {
        val p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        return Cfg(
            refW = p.getString("refW", "2400")!!.toIntOrNull() ?: 2400,
            refH = p.getString("refH", "1080")!!.toIntOrNull() ?: 1080,
            floor = p.getString("floor", "0.70")!!.toDoubleOrNull() ?: 0.70,
            loops = p.getString("loops", "0")!!.toIntOrNull() ?: 0,
            timeoutSec = p.getString("timeout", "0")!!.toIntOrNull() ?: 0,
            clickMs = p.getString("clickMs", "300")!!.toIntOrNull() ?: 300,
            searchMs = p.getString("searchMs", "300")!!.toIntOrNull() ?: 300,
            stepGapMs = p.getString("stepGap", "300")!!.toIntOrNull() ?: 300,
            sameGapMs = p.getString("sameGap", "700")!!.toIntOrNull() ?: 700,
            marker = p.getBoolean("marker", true)
        )
    }

    /** Mode uji sekali pakai: jalankan mulai langkah [from]; bila [to] > 0 berhenti setelah langkah [to]. */
    fun setTest(c: Context, from: Int, to: Int) {
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putInt("testFrom", from).putInt("testTo", to).apply()
    }

    /** Mengambil mode uji (sekali pakai) lalu menghapusnya. Null bila tidak ada. */
    fun takeTest(c: Context): Pair<Int, Int>? {
        val p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val from = p.getInt("testFrom", 0)
        if (from <= 0) return null
        val to = p.getInt("testTo", 0)
        p.edit().remove("testFrom").remove("testTo").apply()
        return Pair(from, to)
    }
}
