package id.nala.rokidpdf.common

import kotlin.math.ln

/**
 * Indeks catatan pribadi (file .md) + pencocokan cepat dari ucapan.
 *
 * Tujuannya: saat lawan bicara menyebut sesuatu yang ada di catatan Anda
 * (harga, spesifikasi, nama pelanggan), isinya langsung muncul di HUD —
 * tanpa internet, tanpa biaya API, dalam hitungan milidetik.
 *
 * Tidak bergantung pada Android supaya bisa diuji di komputer.
 */
class NoteIndex private constructor(val entries: List<Entry>) {

    class Entry(val title: String, val body: String) {
        val terms: Set<String> = tokenize("$title $body")
        /** Judul dipakai sebagai kunci utama: kalau judul disebut, skornya lebih tinggi. */
        val titleTerms: Set<String> = tokenize(title)

        /** Baris yang ringkas untuk HUD. */
        fun ringkas(maxChars: Int = 220): String {
            val isi = body.replace(Regex("\\s*\n\\s*"), " · ").trim()
            val teks = if (isi.isEmpty()) title else "$title — $isi"
            return if (teks.length <= maxChars) teks else teks.take(maxChars - 1) + "…"
        }
    }

    class Hit(val entry: Entry, val score: Double)

    /** Berapa banyak entri memuat sebuah kata (untuk membobot kata langka). */
    private val df: Map<String, Int> = HashMap<String, Int>().also { map ->
        for (e in entries) for (t in e.terms) map[t] = (map[t] ?: 0) + 1
    }

    /**
     * Cari entri yang paling cocok dengan [ucapan].
     * @param minScore ambang agar ucapan biasa tidak memicu tampilan yang mengganggu.
     */
    fun cari(ucapan: String, maxHasil: Int = 2, minScore: Double = 1.6): List<Hit> {
        if (entries.isEmpty()) return emptyList()
        val kata = tokenize(ucapan)
        if (kata.isEmpty()) return emptyList()
        val n = entries.size.toDouble()
        return entries.asSequence()
            .map { e ->
                var skor = 0.0
                for (t in kata) {
                    if (t !in e.terms) continue
                    // kata langka bernilai lebih tinggi daripada kata umum
                    val bobot = ln(1.0 + n / (df[t] ?: 1))
                    skor += if (t in e.titleTerms) bobot * 2.0 else bobot
                }
                // angka yang cocok (mis. "305", "2pk") hampir selalu penting
                for (t in kata) if (t.any { c -> c.isDigit() } && t in e.terms) skor += 1.0
                Hit(e, skor)
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(maxHasil)
            .toList()
    }

    companion object {
        /** Kata umum yang tidak membantu pencocokan. */
        private val STOP = setOf(
            "yang", "dan", "di", "ke", "dari", "untuk", "dengan", "itu", "ini", "ada", "apa",
            "saya", "kamu", "kita", "dia", "mereka", "tidak", "bisa", "sudah", "akan", "pada",
            "atau", "juga", "kalau", "jadi", "kan", "sih", "ya", "nya", "the", "a", "is", "of",
        )

        /** Imbuhan akhir yang sering muncul: "garansinya" -> "garansi", "harganya" -> "harga". */
        private val AKHIRAN = listOf("nya", "lah", "kah", "pun", "ku", "mu")

        private fun normalisasi(kata: String): String {
            for (a in AKHIRAN) {
                if (kata.length > a.length + 3 && kata.endsWith(a)) return kata.dropLast(a.length)
            }
            return kata
        }

        fun tokenize(s: String): Set<String> =
            s.lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                // angka satu digit tetap dipakai ("1 PK" berbeda dengan "2 PK")
                .filter { it.isNotEmpty() && (it.length > 1 || it[0].isDigit()) }
                .map { normalisasi(it) }
                .filter { it !in STOP }
                .toSet()

        /**
         * Baca catatan Markdown. Setiap heading (`#`, `##`, …) menjadi satu entri
         * berisi teks di bawahnya. Teks sebelum heading pertama jadi entri "Catatan".
         * Bila tidak ada heading sama sekali, setiap baris menjadi satu entri —
         * cocok untuk daftar sederhana seperti "Pasang 1 PK: Rp 450.000".
         */
        fun fromMarkdown(text: String): NoteIndex {
            val hasil = mutableListOf<Entry>()
            var judul: String? = null
            val isi = StringBuilder()

            fun simpan() {
                val t = judul?.trim().orEmpty()
                val b = isi.toString().trim()
                if (t.isNotEmpty() || b.isNotEmpty()) hasil.add(Entry(t.ifEmpty { "Catatan" }, b))
                isi.setLength(0)
            }

            for (baris in text.lines()) {
                val h = Regex("^#{1,6}\\s+(.*)").find(baris.trim())
                if (h != null) {
                    simpan()
                    judul = h.groupValues[1]
                } else {
                    isi.append(baris).append('\n')
                }
            }
            simpan()

            if (hasil.size <= 1 && text.lines().count { it.isNotBlank() } > 1) {
                // tidak ada heading: pecah per baris / per butir daftar
                val perBaris = text.lines()
                    .map { it.trim().removePrefix("-").removePrefix("*").trim() }
                    .filter { it.isNotBlank() }
                    .map { baris ->
                        val p = baris.split(":", limit = 2)
                        if (p.size == 2 && p[0].length <= 60) Entry(p[0].trim(), p[1].trim())
                        else Entry(baris.take(60), baris)
                    }
                if (perBaris.size > hasil.size) return NoteIndex(perBaris)
            }
            return NoteIndex(hasil)
        }
    }
}
