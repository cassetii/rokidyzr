package id.nala.rokidpdf.common

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Kompresi gambar pratinjau untuk dikirim lewat Bluetooth.
 * Layar kacamata monokrom, jadi cukup 16 tingkat abu-abu (4 bit/piksel) lalu dikompres Deflate.
 * Halaman teks (sebagian besar putih) biasanya jadi ~15–40 KB untuk 480x640.
 */
object PreviewCodec {

    fun encode(pixels: IntArray, w: Int, h: Int): ByteArray {
        val n = w * h
        val packed = ByteArray((n + 1) / 2)
        for (i in 0 until n) {
            val p = pixels[i]
            val y = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            val q = (y * 15 + 127) / 255
            val j = i shr 1
            packed[j] = if (i and 1 == 0) (q shl 4).toByte() else (packed[j].toInt() or q).toByte()
        }
        val def = Deflater(Deflater.BEST_SPEED)
        def.setInput(packed)
        def.finish()
        val out = ByteArrayOutputStream(packed.size / 4)
        val buf = ByteArray(32 * 1024)
        while (!def.finished()) {
            val c = def.deflate(buf)
            out.write(buf, 0, c)
        }
        def.end()
        return out.toByteArray()
    }

    /** Menulis hasil ke [out] (ARGB, ukuran minimal w*h). */
    fun decode(data: ByteArray, w: Int, h: Int, out: IntArray) {
        val n = w * h
        val packed = ByteArray((n + 1) / 2)
        val inf = Inflater()
        inf.setInput(data)
        var off = 0
        while (off < packed.size && !inf.finished()) {
            val c = inf.inflate(packed, off, packed.size - off)
            if (c == 0 && (inf.needsInput() || inf.needsDictionary())) break
            off += c
        }
        inf.end()
        for (i in 0 until n) {
            val b = packed[i shr 1].toInt()
            val q = if (i and 1 == 0) (b shr 4) and 0xF else b and 0xF
            val v = q * 17
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
}
