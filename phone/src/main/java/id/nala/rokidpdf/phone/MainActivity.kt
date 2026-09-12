package id.nala.rokidpdf.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import id.nala.rokidpdf.common.Hello
import id.nala.rokidpdf.common.Preview
import id.nala.rokidpdf.common.PreviewCodec
import id.nala.rokidpdf.common.ViewState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

class MainActivity : Activity(), PhoneLink.Listener {

    private lateinit var canvasView: PdfCanvasView
    private lateinit var statusText: TextView
    private lateinit var pageText: TextView

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var doc: DocInfo? = null

    // Pratinjau: dibatasi maks ~6 gambar/detik, kompresi di thread terpisah
    private val ui = Handler(Looper.getMainLooper())
    private val encoder = Executors.newSingleThreadExecutor()
    private var lastPreviewAt = 0L
    private val previewRunnable = Runnable { sendPreviewNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        PhoneLink.listener = this
        if (hasBtPermission()) PhoneLink.start(this) else requestBtPermission()
        PhoneLink.hello?.let { onHello(it) }
        onStatus(PhoneLink.lastStatus)
    }

    override fun onDestroy() {
        ui.removeCallbacks(previewRunnable)
        encoder.shutdown()
        PhoneLink.listener = null
        closeRenderer()
        if (isFinishing) PhoneLink.stop()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; setOnClickListener { onClick() }
        }

        statusText = TextView(this).apply {
            setTextColor(Color.rgb(61, 220, 132)); textSize = 13f
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), 0)
            addView(btn("Buka PDF") { pickPdf() })
            addView(btn("Kirim ulang") { doc?.let { PhoneLink.setDocument(it) } })
        }
        canvasView = PdfCanvasView(this).apply {
            onViewportChanged = { page, x, y, w ->
                doc?.let { PhoneLink.sendView(ViewState(it.docId, page, x, y, w)) }
                schedulePreview()
                updatePageText()
            }
        }
        pageText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (8 * dp).toInt())
            addView(btn("◀ Hal") { canvasView.prevPage() })
            addView(pageText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(btn("Hal ▶") { canvasView.nextPage() })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 25, 28))
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(canvasView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)
        updatePageText()
    }

    private fun updatePageText() {
        val n = canvasView.pageCount
        pageText.text = if (n == 0) "—" else "Halaman ${canvasView.pageIndex + 1} / $n"
    }

    // ---------- Buka PDF ----------

    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) data?.data?.let { loadPdf(it) }
    }

    /** Salin ke cache sambil menghitung sidik jari (SHA-256), lalu buka. */
    private fun loadPdf(uri: Uri) {
        onStatus("Membuka PDF…")
        Thread {
            try {
                val name = queryName(uri) ?: "dokumen.pdf"
                val out = File(cacheDir, "current.pdf")
                val md = MessageDigest.getInstance("SHA-256")
                contentResolver.openInputStream(uri)!!.use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            md.update(buf, 0, n)
                            os.write(buf, 0, n)
                        }
                    }
                }
                val id = md.digest().joinToString("") { "%02x".format(it) }.take(24)
                runOnUiThread { openLocal(DocInfo(id, name, out)) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Gagal membuka: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun openLocal(d: DocInfo) {
        closeRenderer()
        try {
            val p = ParcelFileDescriptor.open(d.file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(p)
            pfd = p; renderer = r; doc = d
            canvasView.setDocument(r)
            updatePageText()
            PhoneLink.setDocument(d)
            if (!PhoneLink.isConnected) onStatus("PDF siap. Buka aplikasi PDF HUD di kacamata.")
        } catch (e: Exception) {
            Toast.makeText(this, "PDF tidak bisa dibaca (terkunci/rusak?): ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun closeRenderer() {
        canvasView.setDocument(null)
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        renderer = null; pfd = null
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // ---------- Pratinjau selama file dikirim ----------

    private fun schedulePreview() {
        if (!PhoneLink.wantsPreview(doc?.docId)) return
        ui.removeCallbacks(previewRunnable)
        val wait = (PREVIEW_INTERVAL_MS - (SystemClock.uptimeMillis() - lastPreviewAt)).coerceAtLeast(0L)
        ui.postDelayed(previewRunnable, wait)
    }

    private fun sendPreviewNow() {
        val d = doc ?: return
        val h = PhoneLink.hello ?: return
        if (!PhoneLink.wantsPreview(d.docId)) return
        lastPreviewAt = SystemClock.uptimeMillis()
        val w = h.screenW.coerceIn(1, 1280)
        val ht = h.screenH.coerceIn(1, 1280)
        val bmp = try { canvasView.renderGlassesPreview(w, ht) } catch (e: Exception) { null } ?: return
        val pixels = IntArray(w * ht)
        bmp.getPixels(pixels, 0, w, 0, 0, w, ht)
        bmp.recycle()
        val page = canvasView.pageIndex
        val count = canvasView.pageCount
        encoder.execute {
            val data = PreviewCodec.encode(pixels, w, ht)
            PhoneLink.sendPreview(Preview(d.docId, page, count, w, ht, data))
        }
    }

    // ---------- PhoneLink.Listener ----------

    override fun onStatus(text: String) { statusText.text = text }

    override fun onHello(hello: Hello) {
        if (hello.screenW > 0 && hello.screenH > 0) {
            canvasView.setBoxAspect(hello.screenH.toFloat() / hello.screenW)
        }
    }

    override fun onGlassesReady(docId: String, pageCount: Int) {
        ui.removeCallbacks(previewRunnable)
        if (docId == doc?.docId) canvasView.republish()
    }

    override fun onTransfer(sent: Long, total: Long, via: String) {
        val pct = if (total > 0) sent * 100 / total else 0
        statusText.text = "Mengirim lewat $via… $pct% (${sent / 1024} / ${total / 1024} KB) · pratinjau aktif"
    }

    override fun onPreviewWanted() {
        lastPreviewAt = 0L
        schedulePreview()
    }

    override fun onNav(action: Int) { canvasView.nav(action) }

    // ---------- Izin ----------

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) PhoneLink.start(this)
            else onStatus("Izin Bluetooth ditolak. Aplikasi tidak bisa tersambung ke kacamata.")
        }
    }

    companion object {
        private const val PREVIEW_INTERVAL_MS = 160L
        private const val REQ_PICK = 1
        private const val REQ_BT = 2
    }
}
