package id.nala.rokidpdf.phone

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pemanggil Claude API dengan mode streaming (server-sent events), supaya jawaban
 * bisa mulai tampil di HUD sebelum kalimatnya selesai.
 *
 * Tidak memakai library tambahan — cukup HttpURLConnection + org.json bawaan Android.
 */
object ClaudeClient {

    /** Cepat & murah — pilihan default untuk jawaban realtime di HUD. */
    const val MODEL_CEPAT = "claude-haiku-4-5-20251001"
    /** Lebih pintar untuk pertanyaan berat, tapi lebih lambat (adaptive thinking). */
    const val MODEL_PINTAR = "claude-sonnet-5"

    private const val URL_API = "https://api.anthropic.com/v1/messages"
    private const val VERSION = "2023-06-01"
    private const val TAG = "ClaudeClient"

    private const val DASAR =
        "Jawabanmu tampil di layar kacamata pintar yang kecil. Tanpa markdown, tanpa tabel, " +
            "tanpa daftar bernomor, tanpa basa-basi. Pakai bahasa yang dipakai penanya. " +
            "Kalau tidak tahu, katakan tidak tahu."

    /** Mode tanya: pengguna bertanya langsung. */
    fun systemTanya(panjang: Int): String = DASAR + " " + when (panjang) {
        0 -> "Jawab maksimal 2 kalimat pendek."
        2 -> "Jawab lengkap tapi padat, maksimal 8 kalimat."
        else -> "Jawab maksimal 4 kalimat pendek."
    }

    /**
     * Mode dengar: yang masuk adalah ucapan LAWAN BICARA, bukan pertanyaan untuk dijawab.
     * Tugasnya membantu pengguna memahami dan menanggapi.
     */
    fun systemDengar(panjang: Int): String = DASAR +
        " Teks yang kamu terima adalah ucapan lawan bicara pengguna dalam sebuah percakapan, " +
        "bukan pertanyaan untukmu. Jangan menjawab seolah dia bicara padamu. " +
        "Format jawaban tepat dua bagian, masing-masing satu baris:\n" +
        "MAKSUD: inti ucapannya dalam satu kalimat.\n" +
        "TANGGAPI: satu bantahan, pertanyaan balik, atau koreksi paling kuat yang bisa dipakai pengguna. " +
        "Kalau ada angka atau klaim yang meragukan, tunjukkan di bagian TANGGAPI. " +
        when (panjang) {
            0 -> "Tiap baris maksimal 12 kata."
            2 -> "Tiap baris boleh sampai 35 kata."
            else -> "Tiap baris maksimal 20 kata."
        }

    fun maxTokens(panjang: Int): Int = when (panjang) {
        0 -> 150
        2 -> 600
        else -> 300
    }

    class Call {
        internal val cancelled = AtomicBoolean(false)
        fun cancel() = cancelled.set(true)
    }

    /**
     * Kirim pertanyaan. Semua callback dipanggil dari thread latar.
     * @param onDelta potongan jawaban yang baru datang
     */
    fun ask(
        apiKey: String,
        model: String,
        question: String,
        context: String?,
        system: String,
        maxTokens: Int,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ): Call {
        val call = Call()
        Thread({
            var conn: HttpURLConnection? = null
            try {
                val sys = if (context.isNullOrBlank()) system
                else "$system\n\nData milik pengguna yang boleh dipakai:\n$context"

                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", maxTokens)
                    .put("stream", true)
                    .put("system", sys)
                    .put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", question)))
                    .toString()

                conn = (URL(URL_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", VERSION)
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    onError(pesanGalat(code, err))
                    return@Thread
                }

                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        if (call.cancelled.get()) return@Thread
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        val obj = try { JSONObject(payload) } catch (e: Exception) { continue }
                        when (obj.optString("type")) {
                            "content_block_delta" -> {
                                val d = obj.optJSONObject("delta") ?: continue
                                // abaikan blok "thinking": yang ditampilkan hanya teks jawaban
                                if (d.optString("type") == "text_delta") {
                                    val t = d.optString("text")
                                    if (t.isNotEmpty()) onDelta(t)
                                }
                            }
                            "message_stop" -> { onDone(); return@Thread }
                            "error" -> {
                                onError(obj.optJSONObject("error")?.optString("message") ?: "Galat dari API")
                                return@Thread
                            }
                        }
                    }
                }
                onDone()
            } catch (e: Exception) {
                Log.w(TAG, "gagal", e)
                if (!call.cancelled.get()) onError("Gagal menghubungi Claude: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }, "claude-call").apply { isDaemon = true }.start()
        return call
    }

    private fun pesanGalat(code: Int, body: String): String {
        val pesan = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) { "" }
        return when (code) {
            401 -> "API key salah atau sudah dinonaktifkan"
            400 -> "Permintaan ditolak: ${pesan.take(120)}"
            429 -> "Terlalu sering. Tunggu sebentar."
            in 500..599 -> "Server Claude sedang bermasalah"
            else -> "Galat $code: ${pesan.take(120)}"
        }
    }
}
