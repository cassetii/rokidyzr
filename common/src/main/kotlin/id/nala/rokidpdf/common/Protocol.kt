package id.nala.rokidpdf.common

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Protokol sederhana HP <-> kacamata lewat Bluetooth Classic (SPP / RFCOMM).
 *
 * Setiap frame: [type: 1 byte][panjang payload: int32][payload].
 * HP bertindak sebagai server, kacamata sebagai client.
 */
object Proto {
    val SPP_UUID: UUID = UUID.fromString("5c2d8f3a-6b1e-4a7c-9e0d-3f1b2a4c6d8e")
    const val SERVICE_NAME = "RokidPdfMirror"
    const val VERSION = 1

    const val T_HELLO: Byte = 1     // kacamata -> HP : versi, lebar & tinggi layar
    const val T_OFFER: Byte = 2     // HP -> kacamata : tawaran dokumen (id, nama, ukuran)
    const val T_NEED: Byte = 3      // kacamata -> HP : "belum punya, kirim"
    const val T_HAVE: Byte = 4      // kacamata -> HP : "sudah punya, tidak perlu kirim"
    const val T_CHUNK: Byte = 5     // HP -> kacamata : potongan isi file
    const val T_FILE_END: Byte = 6  // HP -> kacamata : file selesai
    const val T_READY: Byte = 7     // kacamata -> HP : dokumen terbuka, jumlah halaman
    const val T_VIEW: Byte = 8      // HP -> kacamata : posisi pandang (halaman, x, y, lebar)
    const val T_NAV: Byte = 9       // kacamata -> HP : perintah dari touchpad
    const val T_ERROR: Byte = 10    // dua arah : pesan galat
    const val T_WIFI_OFFER: Byte = 11 // HP -> kacamata : "ambil file lewat Wi-Fi di alamat ini"
    const val T_WIFI_FAIL: Byte = 12  // kacamata -> HP : Wi-Fi gagal, kirim lewat Bluetooth
    const val T_PREVIEW: Byte = 13    // HP -> kacamata : gambar pratinjau selama file belum lengkap

    const val NAV_FORWARD = 1       // gulir ke bawah (lanjut ke halaman berikut bila di dasar)
    const val NAV_BACK = 2          // gulir ke atas (mundur ke halaman sebelumnya bila di puncak)
    const val NAV_NEXT_PAGE = 3
    const val NAV_PREV_PAGE = 4

    const val CHUNK_SIZE = 16 * 1024
    const val MAX_FRAME = 1 shl 20
}

class Frame(val type: Byte, val payload: ByteArray)

data class Hello(val version: Int, val screenW: Int, val screenH: Int)
data class Offer(val docId: String, val name: String, val size: Long)
data class Ready(val docId: String, val pageCount: Int)
data class WifiOffer(val docId: String, val token: String, val port: Int, val ips: List<String>)

/** Gambar pratinjau: [data] = hasil [PreviewCodec.encode]. */
class Preview(val docId: String, val page: Int, val pageCount: Int, val w: Int, val h: Int, val data: ByteArray)

/**
 * Posisi pandang dalam "satuan lebar halaman":
 * x = 0 berarti tepi kiri halaman, x = 1 berarti tepi kanan.
 * y memakai satuan yang sama (y = tinggi/lebar halaman berarti dasar halaman).
 * w = lebar area yang terlihat. Tinggi area = w * (tinggiLayar / lebarLayar) kacamata.
 */
data class ViewState(val docId: String, val page: Int, val x: Float, val y: Float, val w: Float)

object Codec {
    private inline fun build(block: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { it.block() }
        return bos.toByteArray()
    }

    private fun input(f: Frame) = DataInputStream(ByteArrayInputStream(f.payload))

    fun hello(h: Hello) = Frame(Proto.T_HELLO, build {
        writeInt(h.version); writeInt(h.screenW); writeInt(h.screenH)
    })
    fun readHello(f: Frame): Hello = input(f).run { Hello(readInt(), readInt(), readInt()) }

    fun offer(o: Offer) = Frame(Proto.T_OFFER, build {
        writeUTF(o.docId); writeUTF(o.name); writeLong(o.size)
    })
    fun readOffer(f: Frame): Offer = input(f).run { Offer(readUTF(), readUTF(), readLong()) }

    fun need(docId: String) = Frame(Proto.T_NEED, build { writeUTF(docId) })
    fun have(docId: String) = Frame(Proto.T_HAVE, build { writeUTF(docId) })
    fun fileEnd(docId: String) = Frame(Proto.T_FILE_END, build { writeUTF(docId) })
    fun readDocId(f: Frame): String = input(f).readUTF()

    fun chunk(data: ByteArray, len: Int) = Frame(Proto.T_CHUNK, data.copyOf(len))

    fun ready(r: Ready) = Frame(Proto.T_READY, build { writeUTF(r.docId); writeInt(r.pageCount) })
    fun readReady(f: Frame): Ready = input(f).run { Ready(readUTF(), readInt()) }

    fun view(v: ViewState) = Frame(Proto.T_VIEW, build {
        writeUTF(v.docId); writeInt(v.page); writeFloat(v.x); writeFloat(v.y); writeFloat(v.w)
    })
    fun readView(f: Frame): ViewState =
        input(f).run { ViewState(readUTF(), readInt(), readFloat(), readFloat(), readFloat()) }

    fun nav(action: Int) = Frame(Proto.T_NAV, build { writeInt(action) })
    fun readNav(f: Frame): Int = input(f).readInt()

    fun wifiOffer(o: WifiOffer) = Frame(Proto.T_WIFI_OFFER, build {
        writeUTF(o.docId); writeUTF(o.token); writeInt(o.port)
        writeInt(o.ips.size); o.ips.forEach { writeUTF(it) }
    })
    fun readWifiOffer(f: Frame): WifiOffer = input(f).run {
        val docId = readUTF(); val token = readUTF(); val port = readInt()
        val n = readInt().coerceIn(0, 16)
        WifiOffer(docId, token, port, List(n) { readUTF() })
    }

    fun wifiFail(docId: String) = Frame(Proto.T_WIFI_FAIL, build { writeUTF(docId) })

    fun preview(p: Preview) = Frame(Proto.T_PREVIEW, build {
        writeUTF(p.docId); writeInt(p.page); writeInt(p.pageCount)
        writeInt(p.w); writeInt(p.h); writeInt(p.data.size); write(p.data)
    })
    fun readPreview(f: Frame): Preview = input(f).run {
        val docId = readUTF(); val page = readInt(); val count = readInt()
        val w = readInt(); val h = readInt()
        val data = ByteArray(readInt()); readFully(data)
        Preview(docId, page, count, w, h, data)
    }

    fun error(msg: String) = Frame(Proto.T_ERROR, build { writeUTF(msg.take(500)) })
    fun readError(f: Frame): String = input(f).readUTF()
}
