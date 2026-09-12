package id.nala.rokidpdf.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import id.nala.rokidpdf.common.AskText
import id.nala.rokidpdf.common.HudCfg
import id.nala.rokidpdf.common.Proto
import id.nala.rokidpdf.common.ViewState
import java.io.File

/**
 * Layar HUD kacamata.
 * Hitam = transparen di layar Micro-LED, jadi PDF dibalik warnanya: teks terang di atas "kosong".
 *
 * Alur tampilan:
 *  1. Selama file dikirim -> tampil PRATINJAU (gambar dari HP), sudah bisa zoom/geser dari HP.
 *  2. File lengkap -> otomatis beralih ke render TAJAM langsung dari PDF.
 *
 * Kontrol touchpad:
 *  - geser maju/bawah  : gulir ke bawah (otomatis ke halaman berikut di dasar halaman)
 *  - geser mundur/atas : gulir ke atas
 *  - ketuk             : ganti mode warna (terbalik / normal)
 */
class MainActivity : Activity(), GlassesLink.Listener {

    private lateinit var image: ImageView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var pageText: TextView
    private lateinit var askBox: LinearLayout
    private lateinit var askScroll: ScrollView
    private lateinit var askStatus: TextView
    private lateinit var askHeard: TextView
    private lateinit var askAnswer: TextView
    private lateinit var askNote: TextView
    private lateinit var renderer: PdfHudRenderer

    private var asking = false

    private var hasFrame = false
    @Volatile private var sharpDocId: String? = null   // dokumen yang sudah dirender tajam
    private var inverted = true
    private val hidePageText = Runnable { pageText.visibility = View.GONE }

    private val invertFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -0.299f, -0.587f, -0.114f, 0f, 255f,
        -0.299f, -0.587f, -0.114f, 0f, 255f,
        -0.299f, -0.587f, -0.114f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            colorFilter = invertFilter
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            text = "PDF HUD\nMenyiapkan…"
        }
        infoText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            setBackgroundColor(Color.BLACK); setPadding(10, 4, 10, 4)
            visibility = View.GONE
        }
        pageText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            setBackgroundColor(Color.BLACK); setPadding(12, 4, 12, 4)
            visibility = View.GONE
        }
        // Ukuran awal kecil supaya banyak teks muat; bisa diatur dari HP (Pengaturan).
        askStatus = TextView(this).apply { setTextColor(Color.WHITE); textSize = 10f }
        askHeard = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f }
        askAnswer = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            setLineSpacing(0f, 1.05f)
        }
        // Baris catatan pribadi: muncul seketika saat ucapan cocok dengan catatan Anda.
        askNote = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setPadding(0, 2, 0, 4)
            visibility = View.GONE
        }
        askScroll = ScrollView(this).apply { addView(askAnswer) }
        askBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(8, 6, 8, 6)
            visibility = View.GONE
            addView(askStatus)
            addView(askHeard)
            addView(askNote)
            addView(askScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(image, FrameLayout.LayoutParams(match, match))
            addView(statusText, FrameLayout.LayoutParams(match, match))
            addView(askBox, FrameLayout.LayoutParams(match, match))
            addView(infoText, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.START))
            addView(pageText, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.END))
        }
        setContentView(root)

        renderer = PdfHudRenderer(w, h) { bmp, v, count -> runOnUiThread { showSharpFrame(bmp, v, count) } }

        GlassesLink.listener = this
        // Selalu mulai. Kalau sistem menolak, GlassesLink memanggil onPermissionNeeded()
        // dan kita ajukan izin runtime saat itu juga, lalu proses mencoba lagi sendiri.
        requestBtIfNeeded()
        GlassesLink.start(this, w, h)
    }

    override fun onDestroy() {
        GlassesLink.listener = null
        renderer.release()
        if (isFinishing) GlassesLink.stop()
        super.onDestroy()
    }

    // ---------- tampilan ----------

    private fun showImage(bmp: Bitmap, page: Int, pageCount: Int) {
        image.setImageBitmap(bmp)
        image.invalidate()
        if (!hasFrame) { hasFrame = true; statusText.visibility = View.GONE }
        val label = "${page + 1}/$pageCount"
        if (pageText.text.toString() != label) {
            pageText.text = label
            pageText.visibility = View.VISIBLE
            pageText.removeCallbacks(hidePageText)
            pageText.postDelayed(hidePageText, 1500)
        }
    }

    private fun showSharpFrame(bmp: Bitmap, v: ViewState, pageCount: Int) {
        showImage(bmp, v.page, pageCount)
        renderer.frameConsumed()
        infoText.visibility = View.GONE
    }

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    private fun showInfo(text: String) {
        infoText.text = text
        infoText.visibility = View.VISIBLE
    }

    // ---------- GlassesLink.Listener ----------

    override fun onStatus(text: String) {
        if (!hasFrame) showStatus(text)
    }

    override fun onConnection(connected: Boolean) {
        if (!connected) {
            hasFrame = false
            sharpDocId = null
            image.setImageDrawable(null)
            infoText.visibility = View.GONE
            showStatus("Koneksi HP terputus.\nMenyambung ulang…")
        }
    }

    override fun onPreview(docId: String, bitmap: Bitmap, page: Int, pageCount: Int) {
        if (docId == sharpDocId) return            // sudah tampil tajam, abaikan pratinjau
        showImage(bitmap, page, pageCount)
    }

    override fun onProgress(received: Long, total: Long, via: String) {
        val pct = if (total > 0) received * 100 / total else 0
        if (hasFrame) showInfo("Pratinjau · menyiapkan versi tajam $pct% ($via)")
        else showStatus("Menerima PDF lewat $via\n$pct%")
    }

    override fun onDocument(docId: String, file: File) {
        sharpDocId = null
        if (hasFrame) showInfo("Membuka versi tajam…") else showStatus("Membuka PDF…")
        renderer.open(docId, file,
            onOpened = { count ->
                sharpDocId = docId
                GlassesLink.sendReady(docId, count)
            },
            onError = { msg -> runOnUiThread { showStatus(msg) }; GlassesLink.sendError(msg) })
    }

    override fun onView(v: ViewState) = renderer.request(v)

    override fun onText(t: AskText) {
        showAsk(true)
        when (t.kind) {
            Proto.TEXT_STATUS -> askStatus.text = t.text
            Proto.TEXT_HEARD -> askHeard.text = "\u201c${t.text}\u201d"
            Proto.TEXT_NOTE -> {
                askNote.text = formatCatatan(t.text)
                askNote.visibility = View.VISIBLE
            }
            Proto.TEXT_ANSWER -> {
                if (t.append) askAnswer.append(t.text) else askAnswer.text = t.text
                askScroll.post { askScroll.fullScroll(View.FOCUS_DOWN) }
                if (t.done) { asking = false; askStatus.text = "Ketuk untuk tanya lagi · tahan untuk tutup" }
            }
        }
    }

    /** Baris judul ditebalkan, butir isi dibiarkan biasa — enak dipindai di layar kecil. */
    private fun formatCatatan(teks: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (baris in teks.lines()) {
            if (baris.isBlank()) continue
            val butir = baris.startsWith("  - ")
            val mulai = sb.length
            sb.append(if (butir) "  \u00b7 " + baris.removePrefix("  - ") else "\u25B8 " + baris)
            if (!butir) sb.setSpan(StyleSpan(Typeface.BOLD), mulai, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
        }
        return sb
    }

    override fun onCfg(c: HudCfg) = applyCfg(c)

    private fun applyCfg(c: HudCfg) {
        val sp = c.textSp.coerceIn(8, 26).toFloat()
        askAnswer.textSize = sp
        askNote.textSize = (sp - 1f).coerceAtLeast(8f)
        askHeard.textSize = (sp - 2f).coerceAtLeast(8f)
        askStatus.textSize = (sp - 3f).coerceAtLeast(7f)
        if (c.invert != inverted) toggleInvert()
    }

    /** Tampilkan/sembunyikan panel tanya-jawab di atas tampilan PDF. */
    private fun showAsk(show: Boolean) {
        askBox.visibility = if (show) View.VISIBLE else View.GONE
        if (show) statusText.visibility = View.GONE
    }

    // ---------- Touchpad ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Ketuk tengah  : mulai/berhenti bicara (tanya Claude)
        // Tahan tengah  : tutup panel tanya, atau ganti mode warna saat panel tertutup
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.repeatCount == 0) event.startTracking()
            return true
        }
        val action = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> Proto.NAV_FORWARD
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> Proto.NAV_BACK
            else -> return super.onKeyDown(keyCode, event)
        }
        // Saat panel tanya terbuka, swipe menggulir teks jawaban.
        // Swipe mundur ketika sudah di puncak akan menutup panel.
        if (askBox.visibility == View.VISIBLE) {
            val step = (askScroll.height * 0.8f).toInt().coerceAtLeast(40)
            if (action == Proto.NAV_FORWARD) {
                askScroll.smoothScrollBy(0, step)
            } else {
                if (askScroll.scrollY <= 0) { GlassesLink.sendAsk(Proto.ASK_CANCEL); asking = false; showAsk(false) }
                else askScroll.smoothScrollBy(0, -step)
            }
            return true
        }
        if (!GlassesLink.sendNav(action)) showStatus("HP tidak terhubung")
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Tahan = hidup/matikan Mode Dengar (menangkap ucapan lawan bicara).
            if (GlassesLink.sendAsk(Proto.ASK_LISTEN)) {
                showAsk(true)
                askStatus.text = "Mode dengar…"
            } else {
                showStatus("HP tidak terhubung")
            }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            !event.isCanceled && event.eventTime - event.downTime < 600
        ) {
            askPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun askPressed() {
        if (!GlassesLink.sendAsk(if (asking) Proto.ASK_STOP else Proto.ASK_START)) {
            showStatus("HP tidak terhubung")
            return
        }
        asking = !asking
        showAsk(true)
        if (asking) {
            askStatus.text = "Menyiapkan…"
            askHeard.text = ""
            askAnswer.text = ""
            askNote.visibility = View.GONE
        }
    }

    private fun toggleInvert() {
        inverted = !inverted
        image.colorFilter = if (inverted) invertFilter else null
    }

    // ---------- Izin ----------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Ajukan izin Bluetooth yang belum diberikan (aman dipanggil berkali-kali). */
    private fun requestBtIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val need = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            .filterNot { granted(it) }
        if (need.isEmpty() || askedAt > 0L && SystemClock.uptimeMillis() - askedAt < 8000L) return
        askedAt = SystemClock.uptimeMillis()
        try { requestPermissions(need.toTypedArray(), REQ_BT) } catch (_: Exception) {}
    }

    override fun onPermissionNeeded(detail: String) = requestBtIfNeeded()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        val ok = grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (!ok && !hasFrame) showStatus(diagnostics())
    }

    /** Ringkasan status izin — ditampilkan bila izin tetap ditolak, agar jelas apa yang kurang. */
    private fun diagnostics(): String {
        val c = if (granted(Manifest.permission.BLUETOOTH_CONNECT)) "OK" else "belum"
        val b = if (granted(Manifest.permission.BLUETOOTH)) "OK" else "belum"
        return "Izin Bluetooth ditolak\n" +
            "BLUETOOTH: $b · CONNECT: $c\n" +
            "target SDK ${applicationInfo.targetSdkVersion} · Android ${Build.VERSION.SDK_INT}\n" +
            "Beri izin di Setelan aplikasi, atau lewat ADB."
    }

    companion object {
        private const val REQ_BT = 1
        private var askedAt = 0L
    }
}
