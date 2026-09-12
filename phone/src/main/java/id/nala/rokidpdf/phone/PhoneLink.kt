package id.nala.rokidpdf.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import id.nala.rokidpdf.common.Codec
import id.nala.rokidpdf.common.Frame
import id.nala.rokidpdf.common.FrameConnection
import id.nala.rokidpdf.common.Hello
import id.nala.rokidpdf.common.Offer
import id.nala.rokidpdf.common.AskText
import id.nala.rokidpdf.common.Preview
import id.nala.rokidpdf.common.Proto
import id.nala.rokidpdf.common.ViewState
import id.nala.rokidpdf.common.WifiOffer
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** Dokumen yang sedang dibuka di HP (salinan lokal di cache). */
data class DocInfo(val docId: String, val name: String, val file: File)

/**
 * Server di HP.
 * - Bluetooth (SPP): jalur kendali, posisi pandang, pratinjau, dan cadangan kirim file.
 * - Wi-Fi (TCP): jalur cepat untuk kirim file bila kacamata ada di jaringan yang sama.
 */
object PhoneLink {
    private const val TAG = "PhoneLink"

    interface Listener {
        fun onStatus(text: String)
        fun onHello(hello: Hello)
        fun onGlassesReady(docId: String, pageCount: Int)
        fun onTransfer(sent: Long, total: Long, via: String)
        fun onNav(action: Int)
        /** Kacamata menekan touchpad: mulai/berhenti mendengarkan. */
        fun onAsk(action: Int)
        /** Kacamata sedang menunggu file: kirim pratinjau posisi saat ini. */
        fun onPreviewWanted()
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var conn: FrameConnection? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var wifiServer: ServerSocket? = null
    @Volatile private var wifiToken: String = ""
    @Volatile private var currentDoc: DocInfo? = null
    @Volatile private var readyDocId: String? = null
    @Volatile var hello: Hello? = null; private set
    @Volatile var lastStatus: String = "Belum aktif"; private set

    val isConnected: Boolean get() = conn?.isOpen == true

    private fun status(text: String) {
        lastStatus = text
        Log.i(TAG, text)
        main.post { listener?.onStatus(text) }
    }

    fun start(context: Context) {
        if (running) { status(lastStatus); return }
        running = true
        val app = context.applicationContext
        startWifiServer()
        Thread({ acceptLoop(app) }, "bt-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { wifiServer?.close() } catch (_: Exception) {}
        wifiServer = null
        conn?.close()
    }

    // ------------------------------------------------------------------ Bluetooth

    @SuppressLint("MissingPermission")
    private fun acceptLoop(context: Context) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        while (running) {
            try {
                if (adapter == null || !adapter.isEnabled) {
                    status("Bluetooth HP mati. Nyalakan Bluetooth.")
                    Thread.sleep(3000)
                    continue
                }
                status("Menunggu kacamata tersambung…")
                val ss = adapter.listenUsingRfcommWithServiceRecord(Proto.SERVICE_NAME, Proto.SPP_UUID)
                server = ss
                val socket = ss.accept()
                try { ss.close() } catch (_: Exception) {}
                val name = try { socket.remoteDevice.name ?: socket.remoteDevice.address } catch (_: Exception) { "kacamata" }
                status("Terhubung ke $name")

                val closedLatch = CountDownLatch(1)
                val c = FrameConnection(
                    closeable = socket,
                    input = socket.inputStream,
                    output = socket.outputStream,
                    onFrame = { handleFrame(it) },
                    onClosed = { closedLatch.countDown() },
                )
                conn = c
                readyDocId = null
                hello = null
                c.start()
                closedLatch.await()
                conn = null
                readyDocId = null
                if (running) status("Koneksi kacamata terputus")
            } catch (e: SecurityException) {
                status("Izin Bluetooth belum diberikan")
                Thread.sleep(3000)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "accept gagal", e)
                    Thread.sleep(2000)
                }
            }
        }
    }

    private fun handleFrame(f: Frame) {
        when (f.type) {
            Proto.T_HELLO -> {
                val h = Codec.readHello(f)
                hello = h
                main.post { listener?.onHello(h) }
                offerCurrent()
            }
            Proto.T_NEED -> {
                val id = Codec.readDocId(f)
                val doc = currentDoc
                if (doc != null && doc.docId == id) sendFile(doc)
            }
            Proto.T_WIFI_FAIL -> {
                val doc = currentDoc
                if (doc != null && doc.docId == Codec.readDocId(f)) {
                    status("Wi-Fi tidak terjangkau, kirim lewat Bluetooth…")
                    streamFileBluetooth(doc)
                }
            }
            Proto.T_HAVE -> status("Kacamata sudah punya file ini, membuka…")
            Proto.T_READY -> {
                val r = Codec.readReady(f)
                readyDocId = r.docId
                status("Tampil di kacamata (${r.pageCount} halaman)")
                main.post { listener?.onGlassesReady(r.docId, r.pageCount) }
            }
            Proto.T_NAV -> {
                val action = Codec.readNav(f)
                main.post { listener?.onNav(action) }
            }
            Proto.T_ASK -> {
                val a = Codec.readAsk(f)
                main.post { listener?.onAsk(a) }
            }
            Proto.T_ERROR -> status("Kacamata: ${Codec.readError(f)}")
        }
    }

    fun setDocument(doc: DocInfo) {
        currentDoc = doc
        readyDocId = null
        offerCurrent()
    }

    private fun offerCurrent() {
        val doc = currentDoc ?: return
        val c = conn ?: return
        readyDocId = null
        c.send(Codec.offer(Offer(doc.docId, doc.name, doc.file.length())))
        main.post { listener?.onPreviewWanted() }
    }

    /** Coba Wi-Fi dulu (jauh lebih cepat); kacamata membalas WIFI_FAIL bila tidak terjangkau. */
    private fun sendFile(doc: DocInfo) {
        val c = conn ?: return
        val ss = wifiServer
        val ips = localIpv4()
        if (ss != null && !ss.isClosed && ips.isNotEmpty()) {
            wifiToken = UUID.randomUUID().toString()
            status("Mengirim ${doc.name}… (mencoba Wi-Fi)")
            c.send(Codec.wifiOffer(WifiOffer(doc.docId, wifiToken, ss.localPort, ips)))
        } else {
            streamFileBluetooth(doc)
        }
        main.post { listener?.onPreviewWanted() }
    }

    private fun streamFileBluetooth(doc: DocInfo) {
        val c = conn ?: return
        val total = doc.file.length()
        status("Mengirim ${doc.name} lewat Bluetooth…")
        c.sendJob { write ->
            val buf = ByteArray(Proto.CHUNK_SIZE)
            var sent = 0L
            var lastReport = 0L
            doc.file.inputStream().use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    write(Codec.chunk(buf, n))
                    sent += n
                    if (sent - lastReport >= 64 * 1024 || sent == total) {
                        lastReport = sent
                        val s = sent
                        main.post { listener?.onTransfer(s, total, "Bluetooth") }
                    }
                }
            }
            write(Codec.fileEnd(doc.docId))
        }
    }

    // ------------------------------------------------------------------ Wi-Fi

    private fun startWifiServer() {
        if (wifiServer != null) return
        try {
            val ss = ServerSocket(0)
            wifiServer = ss
            Thread({
                while (running && !ss.isClosed) {
                    try {
                        val s = ss.accept()
                        Thread({ serveWifi(s) }, "wifi-serve").apply { isDaemon = true }.start()
                    } catch (e: Exception) {
                        if (ss.isClosed) break
                    }
                }
            }, "wifi-accept").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.w(TAG, "Server Wi-Fi tidak bisa dibuka", e)
        }
    }

    private fun serveWifi(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15000
            val din = DataInputStream(s.getInputStream())
            val dout = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            val token = din.readUTF()
            val doc = currentDoc
            if (doc == null || token != wifiToken) {
                dout.writeLong(-1); dout.flush()
                return
            }
            val total = doc.file.length()
            dout.writeLong(total)
            status("Mengirim ${doc.name} lewat Wi-Fi…")
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            var lastReport = 0L
            doc.file.inputStream().use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    dout.write(buf, 0, n)
                    sent += n
                    if (sent - lastReport >= 256 * 1024 || sent == total) {
                        lastReport = sent
                        val v = sent
                        main.post { listener?.onTransfer(v, total, "Wi-Fi") }
                    }
                }
            }
            dout.flush()
        }
    }

    /** Alamat IPv4 lokal HP (Wi-Fi / hotspot), tanpa jaringan seluler. */
    private fun localIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filter { n ->
                val nm = n.name.lowercase()
                !(nm.startsWith("rmnet") || nm.startsWith("ccmni") || nm.startsWith("pdp") ||
                    nm.startsWith("dummy") || nm.startsWith("tun") || nm.startsWith("ppp"))
            }
            .sortedBy { n ->
                val nm = n.name.lowercase()
                if (nm.startsWith("wlan") || nm.startsWith("swlan") || nm.startsWith("ap")) 0 else 1
            }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>() }
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .take(4)
    } catch (e: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------ posisi & pratinjau

    fun wantsPreview(docId: String?): Boolean =
        docId != null && isConnected && hello != null && currentDoc?.docId == docId && readyDocId != docId

    fun sendPreview(p: Preview) {
        if (!wantsPreview(p.docId)) return
        conn?.sendLatest(Codec.preview(p))
    }

    /** Kirim teks ke HUD. Potongan jawaban dikirim berurutan (bukan digabung). */
    fun sendText(t: AskText) {
        conn?.send(Codec.text(t))
    }

    fun sendView(v: ViewState) {
        if (readyDocId != v.docId) return
        conn?.sendLatest(Codec.view(v))
    }
}
