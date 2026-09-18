package id.nala.rokidpdf.glasses

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import id.nala.rokidpdf.common.PreviewCodec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Menampilkan potongan layar MacBook di HUD lewat Wi-Fi.
 *
 * Mac menjalankan `tools/mac-screen/nalahud_screen.py`. Kacamata menemukan alamat
 * Mac sendiri lewat siaran UDP, jadi tidak ada IP yang perlu diketik di kacamata.
 *
 * Gambar memakai format yang sama dengan pratinjau PDF (16 tingkat abu-abu, Deflate).
 */
object MacScreen {
    private const val TAG = "MacScreen"
    private const val PORT_TCP_DEFAULT = 45454
    private const val PORT_DISCOVERY = 45455
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'H'.code.toByte(), 'U'.code.toByte(), 'D'.code.toByte())

    const val CMD_GESER = 1
    const val CMD_ZOOM = 2
    const val CMD_MODE_IKUT = 3        // 0=manual, 1=ikut kursor mouse, 2=ikut ketikan
    const val CMD_UKURAN = 4

    interface Listener {
        fun onMacStatus(text: String)
        fun onMacFrame(bitmap: Bitmap)
        fun onMacConnected(connected: Boolean)
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    private var screenW = 480
    private var screenH = 352
    private var pixels = IntArray(0)

    val isConnected: Boolean get() = socket?.isConnected == true && running

    fun start(screenW: Int, screenH: Int) {
        if (running) return
        this.screenW = screenW
        this.screenH = screenH
        running = true
        Thread({ loop() }, "mac-screen").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
    }

    private fun status(t: String) {
        Log.i(TAG, t)
        main.post { listener?.onMacStatus(t) }
    }

    private fun loop() {
        while (running) {
            try {
                val (host, port) = cariMac() ?: run {
                    status("Mencari MacBook…\nJalankan nalahud_screen.py")
                    Thread.sleep(3000)
                    null
                } ?: continue

                status("Menyambung ke $host…")
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                socket = s
                val din = DataInputStream(s.getInputStream().buffered(1 shl 16))
                out = DataOutputStream(s.getOutputStream())
                kirimPerintah(CMD_UKURAN, screenW, screenH)
                main.post { listener?.onMacConnected(true) }
                status("Terhubung ke MacBook")

                terimaFrame(din)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "koneksi Mac putus: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                out = null
                main.post { listener?.onMacConnected(false) }
            }
            if (running) Thread.sleep(2000)
        }
    }

    /** Siarkan UDP ke seluruh jaringan; Mac membalas dengan nomor port-nya. */
    private fun cariMac(): Pair<String, Int>? {
        val s = DatagramSocket()
        try {
            s.broadcast = true
            s.soTimeout = 1500
            val alamat = InetAddress.getByName("255.255.255.255")
            s.send(DatagramPacket(MAGIC, MAGIC.size, alamat, PORT_DISCOVERY))
            val buf = ByteArray(16)
            val balasan = DatagramPacket(buf, buf.size)
            s.receive(balasan)
            if (balasan.length >= 6 && buf.copyOf(4).contentEquals(MAGIC)) {
                val port = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                return balasan.address.hostAddress!! to (if (port in 1..65535) port else PORT_TCP_DEFAULT)
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            s.close()
        }
    }

    private fun terimaFrame(din: DataInputStream) {
        val kepala = ByteArray(4)
        while (running) {
            din.readFully(kepala)
            if (!kepala.contentEquals(MAGIC)) throw IOException("aliran tidak sinkron")
            val w = din.readInt()
            val h = din.readInt()
            val len = din.readInt()
            if (w !in 1..2000 || h !in 1..2000 || len !in 1..(4 shl 20)) throw IOException("frame tidak wajar")
            val data = ByteArray(len)
            din.readFully(data)

            val n = w * h
            if (pixels.size < n) pixels = IntArray(n)
            PreviewCodec.decode(data, w, h, pixels)
            val bmp = Bitmap.createBitmap(pixels, 0, w, w, h, Bitmap.Config.ARGB_8888)
            main.post { listener?.onMacFrame(bmp) }
        }
    }

    /** Kirim perintah ke Mac (geser pandangan, zoom, ikut kursor). */
    fun kirimPerintah(cmd: Int, a1: Int, a2: Int = 0) {
        val o = out ?: return
        Thread({
            try {
                synchronized(o) {
                    o.writeInt(cmd); o.writeInt(a1); o.writeInt(a2)
                    o.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "gagal kirim perintah", e)
            }
        }, "mac-cmd").apply { isDaemon = true }.start()
    }
}
