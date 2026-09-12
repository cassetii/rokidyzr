package id.nala.rokidpdf.common

import kotlin.math.ln

/**
 * Indeks catatan pribadi (.md) + pencocokan cepat dari ucapan.
 *
 * Dirancang tahan terhadap catatan hasil ekstrak PDF, yang biasanya:
 *  - berjudul "Halaman 12" (tidak bermakna),
 *  - mengandung boilerplate yang berulang di tiap halaman (nama vendor, judul deck),
 *  - berisi paragraf panjang campur aduk.
 *
 * Yang ditampilkan bukan potongan awal isi, melainkan KALIMAT YANG PALING
 * RELEVAN dengan ucapan — seperti cuplikan hasil pencarian.
 *
 * Tanpa ketergantungan Android supaya bisa diuji di komputer.
 */
class NoteIndex private constructor(
    val entries: List<Entry>,
    /** Kalimat utuh yang berulang di banyak entri. */
    private val boilerplate: Set<String>,
    /** Frasa (bukan kalimat penuh) yang berulang di banyak entri, mis. nama vendor / kop deck. */
    private val frasaUmum: List<String>,
) {

    class Entry(judulAsli: String, val body: String) {
        /** Kalimat-kalimat isi, sudah dibersihkan. */
        val kalimat: List<String> = pecahKalimat(body)

        /** Judul tampilan: kalau judul asli cuma "Halaman 7", pakai kalimat pertama yang bermakna. */
        var title: String = judulAsli
            internal set

        /** Kata untuk pencocokan. Diisi ulang setelah boilerplate diketahui. */
        var terms: Set<String> = tokenize("$judulAsli $body")
            internal set

        val titleTerms: Set<String> get() = tokenize(title)
    }

    class Hit(val entry: Entry, val score: Double, val cuplikan: String)

    private val df: Map<String, Int> = HashMap<String, Int>().also { map ->
        for (e in entries) for (t in e.terms) map[t] = (map[t] ?: 0) + 1
    }

    /**
     * Cari entri yang paling cocok dengan [ucapan].
     * @param minScore ambang agar obrolan biasa tidak memicu tampilan.
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
                    val bobot = ln(1.0 + n / (df[t] ?: 1))
                    skor += if (t in e.titleTerms) bobot * 2.0 else bobot
                    if (t.any { c -> c.isDigit() }) skor += 1.0
                }
                Hit(e, skor, "")
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(maxHasil)
            .map { Hit(it.entry, it.score, cuplikan(it.entry, kata)) }
            .toList()
    }

    /** Ambil 1–2 kalimat isi yang paling banyak memuat kata dari ucapan. */
    private fun cuplikan(e: Entry, kata: Set<String>, maxChars: Int = 150): String {
        val terbaik = e.kalimat
            .asSequence()
            .filter { it.length in 15..400 && normal(it) !in boilerplate }
            .map { k ->
                val tk = tokenize(k)
                val cocok = kata.count { it in tk }
                // sedikit bonus untuk kalimat yang tidak terlalu panjang
                k to (cocok * 10.0 - k.length / 200.0)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(2)
            .map { potong(rapikan(it.first), maxChars) }
            .toList()

        if (terbaik.isNotEmpty()) return terbaik.joinToString("\n")
        // tidak ada kalimat yang memuat kata itu: pakai kalimat pertama yang bermakna
        val cadangan = e.kalimat.firstOrNull { it.length >= 15 && normal(it) !in boilerplate }
        return cadangan?.let { potong(rapikan(it), maxChars) }.orEmpty()
    }

    /** Buang frasa boilerplate dan sisa penomoran slide dari sebuah kalimat. */
    private fun rapikan(kalimat: String): String {
        var t = kalimat
        for (f in frasaUmum) {
            if (t.length <= f.length) continue
            t = t.replace(f, " ", ignoreCase = true)   // buang semua kemunculan
        }
        return bersihSisaNomor(t)
    }

    companion object {
        private val STOP = setOf(
            "yang", "dan", "di", "ke", "dari", "untuk", "dengan", "itu", "ini", "ada", "apa",
            "saya", "kamu", "kita", "dia", "mereka", "tidak", "bisa", "sudah", "akan", "pada",
            "atau", "juga", "kalau", "jadi", "kan", "sih", "ya", "nya", "the", "a", "is", "of",
            "halaman", "page", "slide",
        )

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
                .filter { it.isNotEmpty() && (it.length > 1 || it[0].isDigit()) }
                .map { normalisasi(it) }
                .filter { it !in STOP }
                .toSet()

        private fun normal(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

        /** Hasil ekstrak PDF sering menyisakan penomoran lepas: "... Project 1 2 A." */
        private fun bersihSisaNomor(s: String): String = s
            .replace(Regex("(?:\\s+(?:\\d{1,2}|[a-fA-F])\\b\\.?){2,}\\s*$"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trimEnd(',', ';', '·', '-')
            .trim()

        private fun potong(s: String, maxChars: Int): String =
            if (s.length <= maxChars) s else s.take(maxChars - 1).substringBeforeLast(' ') + "…"

        /** Buang penanda markdown dan sisa-sisa ekstraksi PDF. */
        private fun bersihkan(baris: String): String = baris
            .replace("**", "")
            .replace("`", "")
            .replace(Regex("^\\s*[-*+]\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** Pecah isi jadi kalimat. Titik, titik dua, dan baris baru dianggap batas. */
        private fun pecahKalimat(body: String): List<String> =
            body.split(Regex("(?<=[.!?;])\\s+|\\n+"))
                .map { bersihkan(it) }
                .filter { it.isNotEmpty() && it != "---" }

        /**
         * Cari rangkaian 4–8 kata yang muncul di banyak entri berbeda.
         * Dipakai untuk membuang kop/boilerplate yang menempel di tengah kalimat.
         */
        private fun cariFrasaUmum(entries: List<Entry>, ambang: Int): List<String> {
            if (entries.size < 4 || entries.size > 2000) return emptyList()
            val df = HashMap<String, Int>()
            for (e in entries) {
                val terlihat = HashSet<String>()
                for (k in e.kalimat) {
                    // Boilerplate hampir selalu di awal kalimat (kop/judul deck),
                    // jadi cukup periksa bagian awalnya — jauh lebih hemat.
                    val kata = k.split(' ').filter { it.isNotBlank() }.take(40)
                    for (n in 4..6) {
                        if (kata.size < n) break
                        for (i in 0..kata.size - n) terlihat.add(kata.subList(i, i + n).joinToString(" "))
                    }
                }
                for (f in terlihat) df[f] = (df[f] ?: 0) + 1
            }
            // Urut dari yang terpanjang supaya frasa besar dibuang lebih dulu.
            // Frasa pendek tetap disimpan: potongan yang sama bisa muncul dengan lanjutan berbeda.
            return df.filterValues { it >= ambang }.keys.sortedByDescending { it.length }.take(1000)
        }

        private val JUDUL_HALAMAN = Regex("^(halaman|page|slide|hal)\\s*[.:]?\\s*\\d+$", RegexOption.IGNORE_CASE)

        /**
         * Baca catatan Markdown. Heading jadi entri; bila tidak ada heading,
         * tiap baris jadi entri (cocok untuk daftar "Pasang 1 PK: Rp 450.000").
         */
        fun fromMarkdown(text: String): NoteIndex {
            val hasil = mutableListOf<Entry>()
            var judul: String? = null
            val isi = StringBuilder()

            fun simpan() {
                val t = bersihkan(judul.orEmpty())
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
                val perBaris = text.lines()
                    .map { bersihkan(it) }
                    .filter { it.isNotBlank() && it != "---" }
                    .map { baris ->
                        val p = baris.split(":", limit = 2)
                        if (p.size == 2 && p[0].length <= 60) Entry(p[0].trim(), p[1].trim())
                        else Entry(potong(baris, 60), baris)
                    }
                if (perBaris.size > hasil.size) return NoteIndex(perBaris, emptySet(), emptyList())
            }

            // Deteksi boilerplate: kalimat yang muncul di banyak entri (kop deck, nama vendor, dsb).
            val hitung = HashMap<String, Int>()
            for (e in hasil) for (k in e.kalimat.map { normal(it) }.toSet()) hitung[k] = (hitung[k] ?: 0) + 1
            val ambang = maxOf(3, hasil.size / 8)
            val boiler = hitung.filterValues { it >= ambang }.keys.toSet()

            // Frasa yang berulang di banyak halaman (kop deck, nama vendor) — dibuang saat ditampilkan.
            val frasa = cariFrasaUmum(hasil, ambang)

            // Buang halaman yang isinya hanya boilerplate (slide pembatas, daftar isi berulang):
            // tidak membawa informasi dan hanya mengotori pencocokan.
            val berguna = hasil.filter { e ->
                e.kalimat.any { it.length >= 8 && normal(it) !in boiler }
            }

            for (e in berguna) {
                // Frasa boilerplate dibuang dari isi SEBELUM diindeks, supaya kata seperti
                // nama vendor atau kop deck tidak memicu cocokan palsu di setiap halaman.
                val isiBersih = e.kalimat
                    .filter { normal(it) !in boiler }
                    .map { k -> bersihSisaNomor(frasa.fold(k) { acc, f -> acc.replace(f, " ", ignoreCase = true) }) }
                    .filter { it.isNotBlank() }
                e.terms = tokenize(e.title + " " + isiBersih.joinToString(" "))

                if (JUDUL_HALAMAN.containsMatchIn(e.title) || e.title.equals("Catatan", true)) {
                    val kandidat = isiBersih.asSequence()
                        .filter { it.length >= 8 }
                    val baru = kandidat.firstOrNull { it.length <= 120 } ?: kandidat.firstOrNull()
                    if (baru != null) e.title = potong(baru, 70)
                }
            }
            return NoteIndex(berguna, boiler, frasa)
        }
    }
}
