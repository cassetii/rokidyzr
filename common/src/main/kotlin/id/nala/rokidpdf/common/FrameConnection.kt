package id.nala.rokidpdf.common

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Koneksi dua arah berbasis frame di atas stream apa pun (socket Bluetooth, atau pipa saat pengujian).
 *
 * - Satu thread pembaca memanggil [onFrame] untuk setiap frame masuk.
 * - Satu thread penulis mengirim antrean frame secara berurutan.
 * - [sendLatest] menggabungkan pesan yang sering (posisi pandang): hanya yang TERBARU yang dikirim,
 *   sehingga zoom/geser tidak menumpuk antrean dan terasa realtime.
 * - [sendJob] menjalankan pekerjaan panjang (kirim file) di thread penulis tanpa memuat file ke memori.
 */
class FrameConnection(
    private val closeable: Closeable,
    input: InputStream,
    output: OutputStream,
    private val onFrame: (Frame) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) {
    private class Job(val block: ((Frame) -> Unit) -> Unit)

    private object LatestToken
    private object StopToken

    private val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val dout = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
    private val queue = LinkedBlockingQueue<Any>()
    private val latest = AtomicReference<Frame?>(null)
    private val closed = AtomicBoolean(false)

    val isOpen: Boolean get() = !closed.get()

    fun start() {
        Thread({ readLoop() }, "frame-reader").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "frame-writer").apply { isDaemon = true }.start()
    }

    fun send(frame: Frame) {
        if (isOpen) queue.offer(frame)
    }

    fun sendLatest(frame: Frame) {
        if (!isOpen) return
        if (latest.getAndSet(frame) == null) queue.offer(LatestToken)
    }

    fun sendJob(block: ((Frame) -> Unit) -> Unit) {
        if (isOpen) queue.offer(Job(block))
    }

    fun close(cause: Throwable? = null) {
        if (!closed.compareAndSet(false, true)) return
        queue.offer(StopToken)
        try { closeable.close() } catch (_: IOException) {}
        onClosed(cause)
    }

    private fun writeFrame(f: Frame) {
        dout.writeByte(f.type.toInt())
        dout.writeInt(f.payload.size)
        dout.write(f.payload)
    }

    private fun writeLoop() {
        try {
            while (isOpen) {
                when (val item = queue.take()) {
                    is Frame -> writeFrame(item)
                    // Selama pekerjaan panjang (kirim file), frame "terbaru" (pratinjau/posisi)
                    // disisipkan di antara potongan file supaya kacamata tetap responsif.
                    is Job -> item.block { f ->
                        writeFrame(f)
                        latest.getAndSet(null)?.let { writeFrame(it); dout.flush() }
                    }
                    LatestToken -> latest.getAndSet(null)?.let { writeFrame(it) }
                    StopToken -> break
                }
                if (queue.isEmpty()) dout.flush()
            }
        } catch (t: Throwable) {
            close(t)
        }
    }

    private fun readLoop() {
        try {
            while (isOpen) {
                val type = din.readByte()
                val len = din.readInt()
                if (len < 0 || len > Proto.MAX_FRAME) throw IOException("Frame tidak valid: $len byte")
                val payload = ByteArray(len)
                din.readFully(payload)
                onFrame(Frame(type, payload))
            }
        } catch (t: Throwable) {
            close(t)
        }
    }
}
