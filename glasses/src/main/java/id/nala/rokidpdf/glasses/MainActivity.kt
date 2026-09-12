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
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
    private lateinit var renderer: PdfHudRenderer

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)

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
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(image, FrameLayout.LayoutParams(match, match))
            addView(statusText, FrameLayout.LayoutParams(match, match))
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

    // ---------- Touchpad ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val action = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> Proto.NAV_FORWARD
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> Proto.NAV_BACK
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { toggleInvert(); return true }
            else -> return super.onKeyDown(keyCode, event)
        }
        if (!GlassesLink.sendNav(action)) showStatus("HP tidak terhubung")
        return true
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
