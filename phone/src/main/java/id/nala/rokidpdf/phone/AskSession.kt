package id.nala.rokidpdf.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import id.nala.rokidpdf.common.NoteIndex

/**
 * Mendengarkan lewat mikrofon HP, lalu:
 *  - mencocokkan ucapan dengan catatan pribadi (lokal, instan, tanpa biaya), dan
 *  - bila perlu, meminta analisis ke Claude.
 *
 * Kuncinya ada di "penentu selesai bicara": mesin suara Android sering memotong
 * kalimat setelah jeda pendek. Di sini hasilnya dikumpulkan lintas sesi, dan ucapan
 * baru dianggap selesai setelah benar-benar sunyi selama [Prefs.jedaDetik] detik.
 *
 * Semua fungsi publik dipanggil dari thread UI (syarat SpeechRecognizer).
 */
class AskSession(
    private val context: Context,
    private val prefs: Prefs,
    private val out: Output,
) {
    interface Output {
        fun status(text: String)
        /** Cocokan dari catatan pribadi — tampil tanpa API. */
        fun note(text: String)
        fun heard(text: String, final: Boolean)
        fun answerDelta(text: String)
        fun answerDone(ms: Long)
        fun error(text: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var call: ClaudeClient.Call? = null

    private var startedAt = 0L
    private var lastNote = ""

    /** Kalimat yang sedang terkumpul (bisa lintas beberapa sesi mesin suara). */
    private val ucapan = StringBuilder()
    private var potonganTerakhir = ""

    /** Catatan pribadi (.md). Null bila belum dimuat. */
    var notes: NoteIndex? = null

    /** Mikrofon sedang menyala. */
    var isListening = false
        private set

    /** Mode dengar: terus mendengarkan lawan bicara sampai dimatikan. */
    var mode = MODE_TANYA
        private set

    private val selesaiBicara = Runnable { finalkanUcapan() }

    // ------------------------------------------------------------------ kendali

    /** Hidup/matikan mode dengar lawan bicara. */
    fun toggleListenMode() {
        if (mode == MODE_DENGAR) {
            mode = MODE_TANYA
            cancelAll()
            out.status("Mode dengar mati")
        } else {
            if (prefs.apiKey.isBlank() && notes == null) {
                out.error("Belum ada catatan dan API key.\nMuat catatan .md dulu di HP.")
                return
            }
            mode = MODE_DENGAR
            out.status("Mendengarkan... (jeda ${prefs.jedaDetik} detik)")
            mulaiRekam()
        }
    }

    /** Mode tanya: tekan untuk bicara. */
    fun start() {
        if (isListening) return
        mode = MODE_TANYA
        ucapan.setLength(0)
        potonganTerakhir = ""
        mulaiRekam()
    }

    /** Berhenti dan langsung proses apa yang sudah terdengar. */
    fun stop() {
        if (mode == MODE_DENGAR) { toggleListenMode(); return }
        handler.removeCallbacks(selesaiBicara)
        try { recognizer?.stopListening() } catch (e: Exception) { Log.w(TAG, "stop", e) }
    }

    fun cancelAll() {
        isListening = false
        lastNote = ""
        ucapan.setLength(0)
        potonganTerakhir = ""
        handler.removeCallbacks(selesaiBicara)
        try { recognizer?.cancel() } catch (_: Exception) {}
        call?.cancel()
        call = null
    }

    fun release() {
        mode = MODE_TANYA
        cancelAll()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    // ------------------------------------------------------------------ perekaman

    private fun mulaiRekam() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            out.error("Pengenalan suara tidak tersedia di HP ini"); return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
            it.setRecognitionListener(pendengar)
        }
        val jedaMs = prefs.jedaDetik * 1000L
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.bahasa)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Sebagian HP menghormati nilai ini; kalaupun tidak, penentu jeda di bawah tetap bekerja.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, jedaMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, jedaMs)
        }
        try {
            if (ucapan.isEmpty()) startedAt = SystemClock.uptimeMillis()
            r.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            out.error("Gagal memulai mikrofon: ${e.message}")
        }
    }

    /** Mesin suara berhenti sendiri setelah jeda pendek — nyalakan lagi diam-diam. */
    private fun lanjutMendengar() {
        if (mode != MODE_DENGAR) return
        handler.postDelayed({ if (mode == MODE_DENGAR && !isListening) mulaiRekam() }, 150)
    }

    private val pendengar = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (mode == MODE_TANYA) out.status("Mendengarkan...")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (mode == MODE_TANYA) out.status("Memproses...")
        }

        override fun onPartialResults(partial: Bundle?) {
            val t = teksPertama(partial) ?: return
            adaUcapanBaru(t, sesiSelesai = false)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val t = teksPertama(results)
            if (!t.isNullOrBlank()) adaUcapanBaru(t, sesiSelesai = true)
            if (mode == MODE_DENGAR) {
                lanjutMendengar()                 // tetap mendengar; tunggu sunyi penuh
            } else {
                handler.removeCallbacks(selesaiBicara)
                finalkanUcapan()                  // mode tanya: langsung proses
            }
        }

        override fun onError(error: Int) {
            isListening = false
            val wajar = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (mode == MODE_DENGAR) {
                if (wajar) { lanjutMendengar(); return }
                mode = MODE_TANYA
            }
            out.error(pesanGalatSuara(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------------------------------ penentu selesai bicara

    /**
     * Dipanggil setiap ada teks baru. Teks dikumpulkan, lalu hitungan mundur "sunyi"
     * di-reset. Ucapan dianggap selesai kalau tidak ada teks baru selama jeda penuh.
     */
    private fun adaUcapanBaru(teks: String, sesiSelesai: Boolean) {
        val bersih = teks.trim()
        if (bersih.isEmpty() || bersih == potonganTerakhir) return

        val gabungan: String
        if (sesiSelesai) {
            // Sesi mesin suara selesai: kunci potongan ini ke kalimat yang terkumpul.
            if (ucapan.isNotEmpty()) ucapan.append(' ')
            ucapan.append(bersih)
            potonganTerakhir = ""
            gabungan = ucapan.toString()
        } else {
            potonganTerakhir = bersih
            gabungan = (ucapan.toString() + " " + bersih).trim()
        }

        out.heard(gabungan, false)

        handler.removeCallbacks(selesaiBicara)
        val jedaMs = prefs.jedaDetik * 1000L
        if (jedaMs <= 0L) finalkanUcapan() else handler.postDelayed(selesaiBicara, jedaMs)
    }

    /** Sunyi sudah cukup lama: proses kalimat yang terkumpul. */
    private fun finalkanUcapan() {
        val teks = (ucapan.toString() + " " + potonganTerakhir).trim()
        ucapan.setLength(0)
        potonganTerakhir = ""
        if (teks.isBlank()) return

        out.heard(teks, true)

        val adaCocokan = cocokkanCatatan(teks)

        // Panggil Claude hanya bila perlu: kalimat cukup berisi, dan (dalam mode dengar)
        // catatan lokal belum menjawab -- supaya hemat biaya.
        val cukupPanjang = teks.split(" ").size >= 4
        val perluClaude = prefs.apiKey.isNotBlank() && cukupPanjang &&
            (mode == MODE_TANYA || !adaCocokan || prefs.selaluAnalisis)
        if (perluClaude) tanyaClaude(teks)
        else if (mode == MODE_DENGAR) out.status("Mendengarkan...")
    }

    /** Cari isi catatan yang cocok. Murni lokal. @return true bila ada yang cocok. */
    private fun cocokkanCatatan(ucapanTeks: String): Boolean {
        val idx = notes ?: return false
        val hit = idx.cari(ucapanTeks, maxHasil = 2)
        if (hit.isEmpty()) return false
        // Bentuk tampilan: judul sebagai kepala, lalu kalimat yang relevan sebagai butir.
        val teks = hit.joinToString("\n") { h ->
            val butir = h.cuplikan.lines().filter { it.isNotBlank() }.joinToString("\n") { "  - $it" }
            if (butir.isBlank()) h.entry.title else "${h.entry.title}\n$butir"
        }
        if (teks != lastNote) {
            lastNote = teks
            out.note(teks)
        }
        return true
    }

    private fun tanyaClaude(pertanyaan: String) {
        startedAt = SystemClock.uptimeMillis()
        out.status(if (mode == MODE_DENGAR) "Menganalisis..." else "Claude berpikir...")
        call = ClaudeClient.ask(
            apiKey = prefs.apiKey,
            model = prefs.model,
            question = pertanyaan,
            context = prefs.konteks.takeIf { it.isNotBlank() },
            system = if (mode == MODE_DENGAR) ClaudeClient.systemDengar(prefs.panjang)
            else ClaudeClient.systemTanya(prefs.panjang),
            maxTokens = ClaudeClient.maxTokens(prefs.panjang),
            onDelta = { out.answerDelta(it) },
            onDone = { out.answerDone(SystemClock.uptimeMillis() - startedAt) },
            onError = { out.error(it) },
        )
    }

    private fun teksPertama(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun pesanGalatSuara(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Masalah pada mikrofon"
        SpeechRecognizer.ERROR_CLIENT -> "Pengenalan suara dibatalkan"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tidak ada internet"
        SpeechRecognizer.ERROR_NO_MATCH -> "Tidak terdengar jelas, coba lagi"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mesin suara sedang sibuk"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tidak ada suara, coba lagi"
        else -> "Galat pengenalan suara ($code)"
    }

    companion object {
        private const val TAG = "AskSession"
        const val MODE_TANYA = 0
        const val MODE_DENGAR = 1
    }
}

/** Pengaturan tersimpan di penyimpanan privat aplikasi (tidak ikut ke GitHub). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nala_rokid", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "").orEmpty()
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }

    var model: String
        get() = sp.getString("model", ClaudeClient.MODEL_CEPAT).orEmpty()
        set(v) { sp.edit().putString("model", v).apply() }

    var bahasa: String
        get() = sp.getString("bahasa", "id-ID").orEmpty()
        set(v) { sp.edit().putString("bahasa", v).apply() }

    /** Lama sunyi sebelum ucapan dianggap selesai. 0 = langsung tampil. */
    var jedaDetik: Int
        get() = sp.getInt("jeda_detik", 5)
        set(v) { sp.edit().putInt("jeda_detik", v.coerceIn(0, 10)).apply() }

    /** Panggil Claude walau catatan lokal sudah menemukan jawaban. */
    var selaluAnalisis: Boolean
        get() = sp.getBoolean("selalu_analisis", false)
        set(v) { sp.edit().putBoolean("selalu_analisis", v).apply() }

    /** Ukuran teks jawaban di HUD (sp). Default 14 = skala "body" design system Rokid. */
    var textSp: Int
        get() = sp.getInt("text_sp", 14)
        set(v) { sp.edit().putInt("text_sp", v.coerceIn(8, 26)).apply() }

    /** 0 = ringkas, 1 = sedang, 2 = detail. */
    var panjang: Int
        get() = sp.getInt("panjang", 1)
        set(v) { sp.edit().putInt("panjang", v.coerceIn(0, 2)).apply() }

    var invert: Boolean
        get() = sp.getBoolean("invert", true)
        set(v) { sp.edit().putBoolean("invert", v).apply() }

    /** Catatan/data pribadi yang ikut dikirim sebagai bahan jawaban (opsional). */
    var konteks: String
        get() = sp.getString("konteks", "").orEmpty()
        set(v) { sp.edit().putString("konteks", v).apply() }
}
