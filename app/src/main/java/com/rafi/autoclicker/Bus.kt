package com.rafi.autoclicker

import android.os.Handler
import android.os.Looper

object Bus {
    /** Status jalan per tombol ▶ (indeks 0,1,2 = tombol 1,2,3 pada tombol melayang). */
    val runningSlots = BooleanArray(3)
    /** True bila ADA slot yang sedang berjalan (dipakai kode lama yang hanya tahu satu status). */
    val running: Boolean get() = runningSlots.any { it }
    fun setRunning(slot: Int, v: Boolean) { if (slot in runningSlots.indices) runningSlots[slot] = v }
    /** True selama CaptureService hidup (tombol melayang aktif). */
    @Volatile var serviceActive = false
    @Volatile var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    class Entry(val time: Long, val text: String)

    private val hist = ArrayDeque<Entry>()

    /** Riwayat aktivitas terbaru (paling lama di depan). */
    fun history(): List<Entry> = synchronized(hist) { hist.toList() }

    fun clearHistory() { synchronized(hist) { hist.clear() } }

    fun log(s: String) {
        synchronized(hist) {
            hist.addLast(Entry(System.currentTimeMillis(), s))
            while (hist.size > 60) hist.removeFirst()
        }
        main.post { listener?.invoke(s) }
    }

    /** Memberi tahu UI bahwa status berubah, tanpa menambah riwayat (listener menerima string kosong). */
    fun ping() { main.post { listener?.invoke("") } }
}
