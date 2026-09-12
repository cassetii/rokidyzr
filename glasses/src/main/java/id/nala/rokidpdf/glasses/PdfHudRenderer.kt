package id.nala.rokidpdf.glasses

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import id.nala.rokidpdf.common.ViewState
import id.nala.rokidpdf.common.Viewport
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Merender area PDF yang diminta HP langsung dari file vektor (teks tetap tajam).
 *
 * - Semua akses PdfRenderer di satu thread khusus (PdfRenderer tidak thread-safe).
 * - Permintaan beruntun digabung: hanya posisi TERBARU yang dirender.
 * - Dua bitmap bergantian + semaphore agar bitmap yang sedang tampil tidak ditimpa.
 */
class PdfHudRenderer(
    private val width: Int,
    private val height: Int,
    /** Dipanggil di thread render. WAJIB memanggil [frameConsumed] setelah bitmap dipasang di UI. */
    private val onFrame: (bitmap: Bitmap, view: ViewState, pageCount: Int) -> Unit,
) {
    private val thread = HandlerThread("hud-render").apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = AtomicReference<ViewState?>(null)
    private val uiFree = Semaphore(1)

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var docId: String? = null
    private var page: PdfRenderer.Page? = null
    private var pageIndex = -1
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var next = 0
    private val matrix = Matrix()

    fun open(docId: String, file: File, onOpened: (pageCount: Int) -> Unit, onError: (String) -> Unit) {
        handler.post {
            closeInternal()
            try {
                val p = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(p)
                pfd = p; renderer = r; this.docId = docId
                onOpened(r.pageCount)
            } catch (e: Exception) {
                Log.e("PdfHudRenderer", "open", e)
                onError("PDF tidak bisa dibuka di kacamata: ${e.message}")
            }
        }
    }

    fun request(v: ViewState) {
        if (pending.getAndSet(v) == null) handler.post { drain() }
    }

    fun frameConsumed() { if (uiFree.availablePermits() == 0) uiFree.release() }

    fun release() {
        handler.post { closeInternal(); thread.quitSafely() }
    }

    private fun drain() {
        val v = pending.getAndSet(null) ?: return
        val r = renderer ?: return
        if (v.docId != docId || r.pageCount == 0) return
        val idx = v.page.coerceIn(0, r.pageCount - 1)
        try {
            if (idx != pageIndex) {
                page?.close()
                page = r.openPage(idx)
                pageIndex = idx
            }
            val p = page ?: return
            val pw = p.width.toFloat()
            val (tx, ty, s) = Viewport.renderTransform(v, pw, width)

            // tunggu UI selesai memasang frame sebelumnya (batas waktu agar tidak pernah macet)
            uiFree.tryAcquire(300, TimeUnit.MILLISECONDS)
            val bmp = buffers[next]?.takeIf { !it.isRecycled }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { buffers[next] = it }
            next = 1 - next

            bmp.eraseColor(Color.WHITE)              // PdfRenderer tidak membersihkan bitmap
            matrix.reset()
            matrix.postTranslate(tx, ty)
            matrix.postScale(s, s)
            p.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            onFrame(bmp, v.copy(page = idx), r.pageCount)
        } catch (e: Exception) {
            Log.e("PdfHudRenderer", "render", e)
            uiFree.drainPermits(); uiFree.release()
        }
    }

    private fun closeInternal() {
        try { page?.close() } catch (_: Exception) {}
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        page = null; renderer = null; pfd = null; docId = null; pageIndex = -1
    }
}
