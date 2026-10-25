# Rafi Automatic (Rafi HayDay) — dari script.json "lvl 7 1 farm"

Aplikasi Android yang menjalankan ulang script Image Clicker kamu: cari gambar di layar (template matching OpenCV),
lalu klik/double-klik, plus klik koordinat tetap. Script sudah tertanam (app/src/main/assets/script.json).
Gambar input (PNG) kamu pasang lewat tombol "Impor" di dalam aplikasi.

## Tampilan (redesign)
- Opening screen **Rafi HayDay** dengan loading yang mengikuti inisialisasi nyata, lalu otomatis masuk ke halaman utama.
- Halaman utama berupa dashboard: tombol **Mulai**, ringkasan status sistem (gambar, aksesibilitas, izin overlay),
  jumlah langkah script, aktivitas terbaru, dan pengaturan. Tema hijau pastel + krem + aksen kuning.
- Semua komponen UI ada di `Ui.kt` (tanpa library tambahan). Logika script, service, dan preferensi tidak diubah.
- Nama paket/applicationId tetap `com.rafi.autoclicker`, jadi bisa menimpa instalasi lama.

## Cara build APK (tanpa Android Studio, lewat GitHub)
1. Buat repo GitHub baru, upload seluruh isi folder ini (termasuk folder `.github`).
2. Tab **Actions** -> workflow **Build APK** -> Run workflow (atau otomatis saat push).
3. Setelah hijau, unduh artifact **ImageClicker-apk** -> ekstrak -> `app-debug.apk`. Install di HP.

(Alternatif: buka folder ini di Android Studio, lalu Build > Build APK(s).)

## Cara pakai
1. Install APK (izinkan "install dari sumber tak dikenal").
2. Di app: **Impor file** -> pilih semua PNG (`17zu1LoU_0.png`, dst, nama harus persis `<id>_0.png`) atau langsung `3s16H50z.zip`
   hasil export. Info di atas menunjukkan gambar yang masih kurang.
3. **Aktifkan Layanan Aksesibilitas** (Image Clicker) di Pengaturan.
4. Isi resolusi layar saat script direkam (default 2400x1080 landscape; sesuaikan dengan HP kamu waktu merekam).
   Koordinat dan gambar otomatis diskalakan bila layar HP berbeda.
5. **Mulai** -> izinkan rekam layar -> buka game dalam mode landscape. Hentikan lewat tombol STOP di notifikasi.

## Catatan perilaku
- Repeat = ulangi pencarian sampai ketemu; Next = lanjut; Action = lompat ke langkah tujuan (sesuai id di script).
- Nilai `similar` di script ada yang sangat rendah (10-28); aplikasi memakai max(similar, batas minimal) agar tidak salah klik.
  Naikkan/turunkan "batas minimal kemiripan" jika terlalu sering salah klik atau tidak ketemu.
- Kode ini belum dikompilasi/diuji di perangkat; kalau ada error build, kirim lognya.

## Tombol melayang & pengaturan manual
- Setelah MULAI, muncul tombol melayang: ✥ geser, ▶/■ jalan/berhenti, ⚙ buka aplikasi, ✕ tutup.
  Buka game dulu, lalu tekan ▶. Izinkan "Tampil di atas aplikasi lain" saat diminta.
- "Atur langkah": ketuk satu langkah untuk mengubah Click/DoubleClick/LongClick/Next (saat gambar ketemu),
  Repeat/Next/Action + langkah tujuan (saat tidak ketemu), jeda, kemiripan, posisi klik, urutan (naik/turun), hapus.
  Perubahan berlaku pada ▶ berikutnya. "Reset bawaan" mengembalikan script asli.

## Banyak script
- Dashboard punya daftar **Script**: ketuk untuk mengaktifkan, **Ubah** untuk ganti nama, ikon tempat sampah untuk menghapus.
  Script aktif yang dijalankan saat Mulai / ▶. **Atur langkah** dan **Reset bawaan** berlaku untuk script aktif.
- Bawaan: `lvl 7 1 farm`, `min`, `max`. `min` dan `max` berasal dari `min_n_max.txt` (macro klik koordinat, 50 klik, 10 putaran).
- **Impor file** menerima `.json` / `.txt` script dan menambahkannya sebagai script baru (tidak menimpa). Format Image Clicker
  (`actions`) dan format macro (`targets`, seperti min_n_max.txt) dikenali otomatis; macro diubah jadi langkah "Single Click".
- Script boleh membawa `own_timing` (pakai jeda dari script, bukan jeda tetap aplikasi), `ref_w`/`ref_h` (ukuran layar rekaman)
  dan `cycles` (jumlah putaran bawaan, dipakai bila "Jumlah putaran" di pengaturan 0).
- Ukuran layar rekaman min/max tidak ada di file macro; diset 2560x1440 (resolusi terkecil yang memuat semua titik, x maks 2304,
  y maks 1351). Bila salah, edit `ref_w`/`ref_h` di `assets/scripts/min.json` dan `max.json`.
- **Rentang langkah**: kartu "Rentang langkah (script aktif)" di dashboard. Isi "Mulai dari langkah" dan "Sampai langkah"
  (0 = sampai terakhir); script menjalankan rentang itu lalu mengulang lagi dari langkah awal rentang. Disimpan per script
  (`from_step` / `to_step` di file script).
- **Tambah script sendiri**: tombol "Tambah script dari file". Selain format Image Clicker dan macro (min_n_max), tersedia format teks Notepad: satu klik per baris `x,y,jeda`, dengan baris opsional `nama:`, `putaran:`, `jeda:`, `layar:`. Lihat `contoh/contoh_script.txt`.
- **Tombol melayang**: ketuk ✥ untuk melipat (tinggal ✥ dan ▶/■) atau membuka semua tombol; geser ✥ untuk memindahkan.
  Tombol daftar (ikon list) membuka pilihan script untuk mengganti script aktif tanpa membuka aplikasi (tidak bisa saat script berjalan).

## Pilih script untuk tombol melayang (jumlah ▶ mengikuti pilihan)
- Di tab **Script**, tiap script punya pil **▶ +** di sebelah kanan. Ketuk untuk memasukkan script itu ke tombol melayang; pil berubah jadi **▶ 1**, **▶ 2**, dst (nomor = urutan memilih). Ketuk lagi untuk mengeluarkannya.
- Jumlah tombol ▶ di tombol melayang = jumlah script yang dipilih (maksimal 3): pilih 2 script -> 2 tombol ▶, pilih 3 -> 3 tombol ▶, pilih 1 -> 1 tombol ▶ (tanpa angka).
- Belum memilih apa pun -> otomatis hanya script aktif (1 tombol ▶). Membatalkan semua pilihan -> tombol **Mulai** meminta kamu memilih dulu.
- Pilihan berlaku saat **Mulai**. Bila tombol melayang sedang aktif, tutup dulu lalu Mulai lagi.
- Menahan salah satu tombol ▶ di tombol melayang masih bisa menukar script tombol itu (bila script tujuan sudah dipakai tombol lain, keduanya bertukar).
- Batas 3 diatur di `ScriptStore.MAX_SLOTS` (dan `engines`/`Bus.runningSlots` berukuran 3); naikkan ketiganya bersamaan bila mau lebih.

## Pengulangan (repeat) per script
- Tab **Script** -> kartu **Pengulangan (script aktif)** punya 3 pilihan: **Sekali** (jalan sampai langkah terakhir lalu berhenti), **N kali** (isi jumlahnya, mis. 5, lalu berhenti sendiri), **Tanpa batas** (berulang terus sampai kamu menekan ■ di tombol melayang).
- Disimpan per script (`repeat` di file script: 0 = tanpa batas, 1 = sekali, N = N kali), jadi tiap tombol ▶ bisa punya pengulangan sendiri. Berlaku pada ▶ berikutnya.
- Script yang belum diatur ikut "Jumlah putaran" di Pengaturan, lalu `cycles` bawaan script. Bila rentang langkah dipakai, tiap putaran mengulang rentang itu.
- Daftar script menampilkan ringkasan pengulangan tiap script (mis. "30 langkah · 5x").

## Tombol melayang saat diperkecil
- Saat diperkecil (ketuk ✥), yang tersisa ✥ + semua tombol ▶ yang dipakai: 1 script = ✥ + ▶1, 2 script = ✥ + ▶1 + ▶2, 3 script = ✥ + ▶1 + ▶2 + ▶3. Ketuk ✥ lagi untuk memperbesar (muncul juga tombol Pengaturan dan Tutup).
