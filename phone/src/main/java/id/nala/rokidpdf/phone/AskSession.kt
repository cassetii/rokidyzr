package id.nala.rokidpdf.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Alur "tanya-jawab": dengarkan lewat mikrofon HP -> ubah jadi teks -> kirim ke Claude ->
 * alirkan jawabannya ke HUD kacamata.
 *
 * Semua fungsi publik harus dipanggil dari thread UI (SpeechRecognizer mengharuskannya).
 */
class AskSession(
    private val context: Context,
    private val prefs: Prefs,
    private val out: Output,
) {
    interface Output {
        fun status(text: String)
        fun heard(text: String, final: Boolean)
        fun answerDelta(text: String)
        fun answerDone(ms: Long)
        fun error(text: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var call: ClaudeClient.Call? = null
    private var startedAt = 0L
    var isListening = false
        private set

    fun start() {
        if (isListening) return
        val key = prefs.apiKey
        if (key.isBlank()) { out.error("API key belum diisi.\nBuka menu Pengaturan di HP."); return }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            out.error("Pengenalan suara tidak tersedia di HP ini"); return
        }
        cancelAnswer()

        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { out.status("Mendengarkan…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { out.status("Memproses…") }

            override fun onPartialResults(partial: Bundle?) {
                teksPertama(partial)?.let { out.heard(it, false) }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val teks = teksPertama(results)
                if (teks.isNullOrBlank()) { out.error("Tidak ada suara yang terdengar"); return }
                out.heard(teks, true)
                tanyaClaude(teks)
            }

            override fun onError(error: Int) {
                isListening = false
                out.error(pesanGalatSuara(error))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.bahasa)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            startedAt = SystemClock.uptimeMillis()
            r.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            out.error("Gagal memulai mikrofon: ${e.message}")
        }
    }

    /** Berhenti mendengarkan dan langsung proses apa yang sudah terdengar. */
    fun stop() {
        if (!isListening) return
        try { recognizer?.stopListening() } catch (e: Exception) { Log.w(TAG, "stop", e) }
    }

    fun cancelAll() {
        isListening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        cancelAnswer()
    }

    fun release() {
        cancelAll()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun cancelAnswer() {
        call?.cancel()
        call = null
    }

    private fun tanyaClaude(pertanyaan: String) {
        out.status("Claude berpikir…")
        call = ClaudeClient.ask(
            apiKey = prefs.apiKey,
            model = prefs.model,
            question = pertanyaan,
            context = prefs.konteks.takeIf { it.isNotBlank() },
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

    companion object { private const val TAG = "AskSession" }
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

    /** Catatan/data pribadi yang ikut dikirim sebagai bahan jawaban (opsional). */
    var konteks: String
        get() = sp.getString("konteks", "").orEmpty()
        set(v) { sp.edit().putString("konteks", v).apply() }
}
