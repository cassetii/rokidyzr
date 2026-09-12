package id.nala.rokidpdf.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import id.nala.rokidpdf.common.AskText
import id.nala.rokidpdf.common.Hello
import id.nala.rokidpdf.common.HudCfg
import id.nala.rokidpdf.common.NoteIndex
import id.nala.rokidpdf.common.Preview
import id.nala.rokidpdf.common.PreviewCodec
import id.nala.rokidpdf.common.Proto
import id.nala.rokidpdf.common.ViewState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

class MainActivity : Activity(), PhoneLink.Listener {

    private lateinit var canvasView: PdfCanvasView
    private lateinit var statusText: TextView
    private lateinit var pageText: TextView
    private lateinit var askPanel: LinearLayout
    private lateinit var askScroll: ScrollView
    private lateinit var askHeardView: TextView
    private lateinit var askAnswerView: TextView

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var doc: DocInfo? = null

    // Pratinjau: dibatasi maks ~6 gambar/detik, kompresi di thread terpisah
    private val ui = Handler(Looper.getMainLooper())
    private val encoder = Executors.newSingleThreadExecutor()
    private var lastPreviewAt = 0L
    private val previewRunnable = Runnable { sendPreviewNow() }

    private lateinit var prefs: Prefs
    private var ask: AskSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
        loadSavedNotes()
        PhoneLink.listener = this
        if (hasBtPermission()) PhoneLink.start(this) else requestBtPermission()
        PhoneLink.hello?.let { onHello(it) }
        onStatus(PhoneLink.lastStatus)
    }

    override fun onDestroy() {
        ui.removeCallbacks(previewRunnable)
        ask?.release()
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
            addView(btn("Tanya") { toggleAsk() })
            addView(btn("Dengar") { toggleListen() })
            addView(btn("Catatan") { pickNotes() })
            addView(btn("⚙") { showSettings() })
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
        // Panel jawaban: menutupi area PDF saat bertanya, ketuk untuk menutup.
        askHeardView = TextView(this).apply {
            setTextColor(Color.rgb(160, 165, 170)); textSize = 15f
        }
        askAnswerView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f
            setPadding(0, (8 * dp).toInt(), 0, 0)
            setTextIsSelectable(true)
        }
        askScroll = ScrollView(this).apply { addView(askAnswerView) }
        askPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 19, 22))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            visibility = View.GONE
            addView(askHeardView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(askScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            setOnClickListener { visibility = View.GONE }
        }
        val center = FrameLayout(this).apply {
            addView(canvasView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(askPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 25, 28))
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(center, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
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
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PICK -> loadPdf(uri)
            REQ_NOTES -> loadNotes(uri)
        }
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

    // ---------- Tanya-jawab suara ----------

    private fun askSession(): AskSession {
        ask?.let { return it }
        val a = AskSession(this, prefs, object : AskSession.Output {
            override fun status(text: String) = hud(Proto.TEXT_STATUS, text, false)
            override fun note(text: String) = hud(Proto.TEXT_NOTE, text, false)
            override fun heard(text: String, final: Boolean) = hud(Proto.TEXT_HEARD, text, false)
            override fun answerDelta(text: String) = hud(Proto.TEXT_ANSWER, text, true)
            override fun answerDone(ms: Long) {
                PhoneLink.sendText(AskText(Proto.TEXT_ANSWER, "", true, true))
                statusText.text = "Jawaban selesai (${ms / 100 / 10f} detik)"
            }
            override fun error(text: String) {
                hud(Proto.TEXT_STATUS, text, false)
                statusText.text = text
            }
        })
        ask = a
        return a
    }

    /** Kirim teks ke kacamata DAN tampilkan di layar HP (supaya bisa dipakai tanpa kacamata). */
    private fun hud(kind: Int, text: String, append: Boolean) {
        PhoneLink.sendText(AskText(kind, text, append, false))
        askPanel.visibility = View.VISIBLE
        when (kind) {
            Proto.TEXT_HEARD -> {
                askHeardView.text = "\u201c$text\u201d"
                askAnswerView.text = ""
                statusText.text = text
            }
            Proto.TEXT_ANSWER -> {
                if (append) askAnswerView.append(text) else askAnswerView.text = text
                askScroll.post { askScroll.fullScroll(View.FOCUS_DOWN) }
            }
            else -> statusText.text = text
        }
    }

    private fun toggleAsk() {
        if (!hasMicPermission()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return }
        val a = askSession()
        if (a.isListening) {
            a.stop()
        } else {
            askPanel.visibility = View.VISIBLE
            askHeardView.text = "Mendengarkan… ketuk \"Tanya\" lagi untuk berhenti"
            askAnswerView.text = ""
            a.start()
        }
    }

    private fun toggleListen() {
        if (!hasMicPermission()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return }
        askSession().toggleListenMode()
    }

    private fun pickNotes() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/markdown", "text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQ_NOTES)
    }

    private fun loadNotes(uri: Uri) {
        statusText.text = "Memuat catatan…"
        Thread {
            try {
                val teks = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                File(filesDir, "catatan.md").writeText(teks)
                val idx = NoteIndex.fromMarkdown(teks)
                runOnUiThread {
                    askSession().notes = idx
                    statusText.text = "Catatan dimuat: ${idx.entries.size} entri"
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Gagal membaca catatan: ${e.message}" }
            }
        }.start()
    }

    /** Muat ulang catatan yang tersimpan saat aplikasi dibuka. */
    private fun loadSavedNotes() {
        val f = File(filesDir, "catatan.md")
        if (!f.exists()) return
        Thread {
            val idx = try { NoteIndex.fromMarkdown(f.readText()) } catch (e: Exception) { null }
            if (idx != null) runOnUiThread { askSession().notes = idx }
        }.start()
    }

    private fun kirimCfg() = PhoneLink.sendCfg(HudCfg(prefs.textSp, prefs.invert))

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun showSettings() {
        val dp = resources.displayMetrics.density
        val keyField = EditText(this).apply {
            hint = "API key (sk-ant-…)"
            setText(prefs.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val konteksField = EditText(this).apply {
            hint = "Catatan/data yang boleh dipakai menjawab (opsional)"
            setText(prefs.konteks)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val modelBtn = Button(this).apply {
            isAllCaps = false
            text = labelModel()
            setOnClickListener {
                prefs.model = if (prefs.model == ClaudeClient.MODEL_CEPAT) ClaudeClient.MODEL_PINTAR
                else ClaudeClient.MODEL_CEPAT
                text = labelModel()
            }
        }
        val ukuranLabel = TextView(this).apply { text = "Ukuran teks di kacamata: ${prefs.textSp} sp" }
        val ukuranBar = SeekBar(this).apply {
            max = 18                       // 8..26 sp
            progress = prefs.textSp - 8
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    prefs.textSp = value + 8
                    ukuranLabel.text = "Ukuran teks di kacamata: ${prefs.textSp} sp"
                    kirimCfg()             // langsung terlihat di kacamata
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val jedaLabel = TextView(this).apply { text = labelJeda() }
        val jedaBar = SeekBar(this).apply {
            max = 10
            progress = prefs.jedaDetik
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    prefs.jedaDetik = value
                    jedaLabel.text = labelJeda()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val analisisBtn = Button(this).apply {
            isAllCaps = false
            text = labelAnalisis()
            setOnClickListener {
                prefs.selaluAnalisis = !prefs.selaluAnalisis
                text = labelAnalisis()
            }
        }
        val panjangBtn = Button(this).apply {
            isAllCaps = false
            text = labelPanjang()
            setOnClickListener {
                prefs.panjang = (prefs.panjang + 1) % 3
                text = labelPanjang()
            }
        }
        val warnaBtn = Button(this).apply {
            isAllCaps = false
            text = labelWarna()
            setOnClickListener {
                prefs.invert = !prefs.invert
                text = labelWarna()
                kirimCfg()
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(keyField)
            addView(jedaLabel)
            addView(jedaBar)
            addView(analisisBtn)
            addView(modelBtn)
            addView(panjangBtn)
            addView(ukuranLabel)
            addView(ukuranBar)
            addView(warnaBtn)
            addView(konteksField)
        }
        AlertDialog.Builder(this)
            .setTitle("Pengaturan")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Simpan") { _, _ ->
                prefs.apiKey = keyField.text.toString()
                prefs.konteks = konteksField.text.toString()
                statusText.text = if (prefs.apiKey.isBlank()) "API key kosong" else "Pengaturan disimpan"
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun labelJeda(): String {
        val d = prefs.jedaDetik
        return if (d == 0) "Jeda selesai bicara: langsung (0 detik)"
        else "Jeda selesai bicara: $d detik"
    }

    private fun labelAnalisis() =
        if (prefs.selaluAnalisis) "Claude: selalu dipanggil" else "Claude: hanya bila catatan tak cocok"

    private fun labelPanjang() = "Panjang jawaban: " + when (prefs.panjang) {
        0 -> "ringkas"
        2 -> "detail"
        else -> "sedang"
    }

    private fun labelWarna() =
        if (prefs.invert) "Warna: terbalik (teks terang)" else "Warna: normal"

    private fun labelModel() =
        if (prefs.model == ClaudeClient.MODEL_CEPAT) "Model: cepat (Haiku 4.5)" else "Model: pintar (Sonnet 5)"

    // ---------- PhoneLink.Listener ----------

    override fun onStatus(text: String) { statusText.text = text }

    override fun onHello(hello: Hello) {
        if (hello.screenW > 0 && hello.screenH > 0) {
            canvasView.setBoxAspect(hello.screenH.toFloat() / hello.screenW)
        }
        kirimCfg()
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

    override fun onAsk(action: Int) {
        when (action) {
            Proto.ASK_START -> toggleAsk()
            Proto.ASK_STOP -> ask?.stop()
            Proto.ASK_LISTEN -> toggleListen()
            Proto.ASK_CLEAR -> ask?.cancelAll()
            Proto.ASK_CANCEL -> ask?.cancelAll()
        }
    }

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
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleAsk()
            else onStatus("Izin mikrofon ditolak, fitur Tanya tidak bisa dipakai")
            return
        }
        if (requestCode == REQ_BT) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) PhoneLink.start(this)
            else onStatus("Izin Bluetooth ditolak. Aplikasi tidak bisa tersambung ke kacamata.")
        }
    }

    companion object {
        private const val PREVIEW_INTERVAL_MS = 160L
        private const val REQ_PICK = 1
        private const val REQ_BT = 2
        private const val REQ_MIC = 3
        private const val REQ_NOTES = 4
    }
}
