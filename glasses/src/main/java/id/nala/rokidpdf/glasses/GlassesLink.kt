package id.nala.rokidpdf.glasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import id.nala.rokidpdf.common.AskText
import id.nala.rokidpdf.common.Codec
import id.nala.rokidpdf.common.Frame
import id.nala.rokidpdf.common.FrameConnection
import id.nala.rokidpdf.common.Hello
import id.nala.rokidpdf.common.Offer
import id.nala.rokidpdf.common.PreviewCodec
import id.nala.rokidpdf.common.Proto
import id.nala.rokidpdf.common.Ready
import id.nala.rokidpdf.common.ViewState
import id.nala.rokidpdf.common.WifiOffer
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch

/**
 * Client di kacamata: menyambung ke HP (Bluetooth), menerima file (Wi-Fi bila bisa, atau Bluetooth),
 * menampilkan pratinjau selama file belum lengkap.
 */
object GlassesLink {
    private const val TAG = "GlassesLink"
    private const val PREFS = "link"
    private const val KEY_LAST = "last_phone"

    interface Listener {
        fun onStatus(text: String)
        fun onProgress(received: Long, total: Long, via: String)
        fun onDocument(docId: String, file: File)
        fun onView(v: ViewState)
        fun onPreview(docId: String, bitmap: Bitmap, page: Int, pageCount: Int)
        fun onConnection(connected: Boolean)
        /** Panggilan Bluetooth ditolak sistem: Activity diminta mengajukan izin runtime. */
        fun onPermissionNeeded(detail: String)
        fun onText(t: AskText)
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var conn: FrameConnection? = null
    /** Dokumen yang sudah terbuka tajam di kacamata; pratinjau untuk dokumen ini diabaikan. */
    @Volatile private var openedDocId: String? = null
    private lateinit var docsDir: File
    private var screenW = 480
    private var screenH = 640

    // Status penerimaan file (dilindungi lock: diakses thread Bluetooth dan thread Wi-Fi)
    private val lock = Any()
    private var incoming: Offer? = null
    private var incomingOut: FileOutputStream? = null
    private var received = 0L
    private var lastReport = 0L
    private var previewPixels = IntArray(0)

    val isConnected: Boolean get() = conn?.isOpen == true

    private fun status(t: String) {
        Log.i(TAG, t)
        main.post { listener?.onStatus(t) }
    }

    private fun progress(r: Long, total: Long, via: String) = main.post { listener?.onProgress(r, total, via) }

    fun start(context: Context, screenW: Int, screenH: Int) {
        this.screenW = screenW
        this.screenH = screenH
        if (running) return
        running = true
        val app = context.applicationContext
        docsDir = File(app.filesDir, "docs").apply { mkdirs() }
        Thread({ connectLoop(app) }, "bt-connect").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        conn?.close()
    }

    // ------------------------------------------------------------------ koneksi Bluetooth

    @SuppressLint("MissingPermission")
    private fun connectLoop(context: Context) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        while (running) {
            try {
                if (adapter == null || !adapter.isEnabled) {
                    status("Bluetooth kacamata mati")
                    Thread.sleep(3000); continue
                }
                val last = prefs.getString(KEY_LAST, null)
                val candidates = adapter.bondedDevices.orEmpty().sortedBy { d ->
                    when {
                        d.address == last -> 0
                        d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE -> 1
                        else -> 2
                    }
                }
                if (candidates.isEmpty()) {
                    status("Belum ada HP yang dipasangkan.\nPasangkan lewat Hi Rokid.")
                    Thread.sleep(5000); continue
                }
                status("Mencari aplikasi di HP…")
                var socket: BluetoothSocket? = null
                var device: BluetoothDevice? = null
                for (d in candidates) {
                    if (!running) break
                    val s = try { d.createRfcommSocketToServiceRecord(Proto.SPP_UUID) } catch (_: Exception) { null } ?: continue
                    try {
                        s.connect()
                        socket = s; device = d
                        break
                    } catch (_: Exception) {
                        try { s.close() } catch (_: Exception) {}
                    }
                }
                if (socket == null || device == null) {
                    status("HP belum siap.\nBuka aplikasi \"PDF ke Rokid\" di HP.")
                    Thread.sleep(3000); continue
                }
                prefs.edit().putString(KEY_LAST, device.address).apply()
                runSession(socket)
            } catch (e: SecurityException) {
                // Android menolak panggilan Bluetooth. Minta Activity mengajukan izin runtime,
                // lalu coba lagi. Tampilkan detail supaya jelas izin mana yang kurang.
                val detail = e.message?.take(120).orEmpty()
                Log.w(TAG, "SecurityException: $detail", e)
                main.post { listener?.onPermissionNeeded(detail) }
                status("Menunggu izin Bluetooth…\n$detail")
                Thread.sleep(4000)
            } catch (e: Exception) {
                Log.w(TAG, "connect loop", e)
                Thread.sleep(3000)
            }
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val closed = CountDownLatch(1)
        val c = FrameConnection(
            closeable = socket,
            input = socket.inputStream,
            output = socket.outputStream,
            onFrame = { handleFrame(it) },
            onClosed = { closed.countDown() },
        )
        conn = c
        openedDocId = null
        c.start()
        c.send(Codec.hello(Hello(Proto.VERSION, screenW, screenH)))
        main.post { listener?.onConnection(true) }
        status("Terhubung ke HP.\nPilih PDF di HP.")
        closed.await()
        conn = null
        synchronized(lock) { abortIncomingLocked() }
        main.post { listener?.onConnection(false) }
        if (running) status("Koneksi HP terputus")
    }

    private fun handleFrame(f: Frame) {
        when (f.type) {
            Proto.T_OFFER -> onOffer(Codec.readOffer(f))
            Proto.T_CHUNK -> synchronized(lock) {
                val o = incoming ?: return
                val out = incomingOut ?: FileOutputStream(partFile(o.docId)).also { incomingOut = it }
                out.write(f.payload)
                received += f.payload.size
                if (received - lastReport >= 64 * 1024 || received == o.size) {
                    lastReport = received
                    progress(received, o.size, "Bluetooth")
                }
            }
            Proto.T_FILE_END -> synchronized(lock) {
                val id = Codec.readDocId(f)
                try { incomingOut?.close() } catch (_: Exception) {}
                incomingOut = null
                finishLocked(id, partFile(id))
            }
            Proto.T_WIFI_OFFER -> {
                val w = Codec.readWifiOffer(f)
                Thread({ downloadWifi(w) }, "wifi-download").apply { isDaemon = true }.start()
            }
            Proto.T_PREVIEW -> onPreview(f)
            Proto.T_VIEW -> {
                val v = Codec.readView(f)
                main.post { listener?.onView(v) }
            }
            Proto.T_TEXT -> {
                val t = Codec.readText(f)
                main.post { listener?.onText(t) }
            }
            Proto.T_ERROR -> status("HP: ${Codec.readError(f)}")
        }
    }

    // ------------------------------------------------------------------ pratinjau

    private fun onPreview(f: Frame) {
        val p = Codec.readPreview(f)
        if (p.docId == openedDocId || p.w <= 0 || p.h <= 0 || p.w * p.h > 4_000_000) return
        val n = p.w * p.h
        if (previewPixels.size < n) previewPixels = IntArray(n)
        try {
            PreviewCodec.decode(p.data, p.w, p.h, previewPixels)
            val bmp = Bitmap.createBitmap(previewPixels, 0, p.w, p.w, p.h, Bitmap.Config.ARGB_8888)
            main.post { listener?.onPreview(p.docId, bmp, p.page, p.pageCount) }
        } catch (e: Exception) {
            Log.w(TAG, "pratinjau rusak", e)
        }
    }

    // ------------------------------------------------------------------ penerimaan file

    private fun partFile(docId: String) = File(docsDir, "$docId.part")

    private fun onOffer(o: Offer) {
        val target = File(docsDir, "${o.docId}.pdf")
        synchronized(lock) {
            abortIncomingLocked()
            if (target.exists() && target.length() == o.size) {
                conn?.send(Codec.have(o.docId))
                target.setLastModified(System.currentTimeMillis())
                openDocument(o.docId, target)
                return
            }
            incoming = o
            received = 0L; lastReport = 0L
            partFile(o.docId).delete()
        }
        status("Menerima ${o.name}…")
        conn?.send(Codec.need(o.docId))
    }

    /** Unduh lewat Wi-Fi langsung dari HP. Gagal -> minta HP kirim lewat Bluetooth. */
    private fun downloadWifi(w: WifiOffer) {
        val o = synchronized(lock) { incoming?.takeIf { it.docId == w.docId } } ?: return
        val tmp = File(docsDir, "${o.docId}.wifi")
        for (ip in w.ips) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, w.port), 1500)
                    s.soTimeout = 15000
                    val dout = DataOutputStream(s.getOutputStream())
                    dout.writeUTF(w.token); dout.flush()
                    val din = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
                    val size = din.readLong()
                    if (size != o.size) throw IOException("ukuran berbeda: $size")
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var last = 0L
                    FileOutputStream(tmp).use { out ->
                        while (got < size) {
                            val n = din.read(buf, 0, minOf(buf.size.toLong(), size - got).toInt())
                            if (n < 0) throw IOException("koneksi Wi-Fi terputus")
                            out.write(buf, 0, n)
                            got += n
                            if (got - last >= 256 * 1024 || got == size) { last = got; progress(got, size, "Wi-Fi") }
                        }
                    }
                }
                synchronized(lock) {
                    if (incoming?.docId == o.docId) finishLocked(o.docId, tmp) else tmp.delete()
                }
                return
            } catch (e: Exception) {
                Log.i(TAG, "Wi-Fi $ip gagal: ${e.message}")
                tmp.delete()
            }
        }
        status("Wi-Fi tidak terjangkau,\nlewat Bluetooth…")
        conn?.send(Codec.wifiFail(w.docId))
    }

    private fun finishLocked(docId: String, file: File) {
        val o = incoming ?: return
        incoming = null
        if (o.docId != docId || file.length() != o.size) {
            file.delete()
            conn?.send(Codec.error("File tidak lengkap, silakan kirim ulang"))
            status("File tidak lengkap")
            return
        }
        val target = File(docsDir, "${o.docId}.pdf")
        target.delete()
        if (!file.renameTo(target)) { file.copyTo(target, overwrite = true); file.delete() }
        cleanupOldDocs(keep = target)
        openDocument(o.docId, target)
    }

    private fun abortIncomingLocked() {
        try { incomingOut?.close() } catch (_: Exception) {}
        incoming?.let { partFile(it.docId).delete() }
        incomingOut = null; incoming = null
    }

    private fun openDocument(docId: String, file: File) {
        main.post { listener?.onDocument(docId, file) }
    }

    /** Dipanggil Activity setelah PDF berhasil dibuka di kacamata. */
    fun sendReady(docId: String, pageCount: Int) {
        openedDocId = docId
        conn?.send(Codec.ready(Ready(docId, pageCount)))
    }

    fun sendNav(action: Int): Boolean {
        val c = conn ?: return false
        c.send(Codec.nav(action))
        return true
    }

    fun sendAsk(action: Int): Boolean {
        val c = conn ?: return false
        c.send(Codec.ask(action))
        return true
    }

    fun sendError(msg: String) { conn?.send(Codec.error(msg)) }

    /** Simpan hanya 5 PDF terakhir agar memori kacamata tidak penuh. */
    private fun cleanupOldDocs(keep: File) {
        val pdfs = docsDir.listFiles { f -> f.name.endsWith(".pdf") }?.sortedByDescending { it.lastModified() } ?: return
        pdfs.drop(5).filter { it != keep }.forEach { it.delete() }
    }
}
