package id.nala.rokidpdf.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import id.nala.rokidpdf.common.Proto
import id.nala.rokidpdf.common.ViewState
import id.nala.rokidpdf.common.Viewport
import kotlin.math.min

/**
 * Tampilan PDF di HP.
 * Kotak hijau di tengah = persis area yang tampil di kacamata.
 * Pinch untuk zoom, geser satu jari untuk menggeser, ketuk dua kali untuk zoom cepat.
 */
class PdfCanvasView(context: Context) : View(context) {

    /** Dipanggil setiap posisi pandang berubah: (halaman, x, y, lebar). */
    var onViewportChanged: ((page: Int, x: Float, y: Float, w: Float) -> Unit)? = null

    private var renderer: PdfRenderer? = null
    var pageIndex = 0; private set
    val pageCount: Int get() = renderer?.pageCount ?: 0

    private val vp = Viewport()
    private var pageBitmap: Bitmap? = null
    private val box = RectF()
    private val drawMatrix = Matrix()
    private val dp = resources.displayMetrics.density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(61, 220, 132); style = Paint.Style.STROKE; strokeWidth = 2.5f * dp
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(61, 220, 132); textSize = 12f * dp
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY; textSize = 16f * dp; textAlign = Paint.Align.CENTER
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomAt(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (box.isEmpty) return false
                vp.pan(dx / box.width(), dy / box.height())
                changed()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (vp.w < 0.9f) { vp.set(0f, vp.y, 1f); changed() } else zoomAt(2.5f, e.x, e.y)
                return true
            }
        })

    fun setDocument(r: PdfRenderer?) {
        renderer = r
        pageIndex = 0
        vp.set(0f, 0f, 1f)
        if (r != null && r.pageCount > 0) showPage(0) else { pageBitmap = null; invalidate() }
    }

    /** Samakan rasio kotak dengan layar kacamata (dikirim kacamata saat tersambung). */
    fun setBoxAspect(heightOverWidth: Float) {
        vp.boxAspect = heightOverWidth.coerceIn(0.4f, 3f)
        computeBox()
        vp.clamp()
        changed()
    }

    fun showPage(index: Int, alignBottom: Boolean = false) {
        val r = renderer ?: return
        if (index !in 0 until r.pageCount) return
        pageIndex = index
        renderPageBitmap(r, index)
        if (alignBottom) vp.toBottom() else vp.toTop()
        changed()
    }

    fun nextPage() = showPage(pageIndex + 1)
    fun prevPage() = showPage(pageIndex - 1)

    /** Perintah dari touchpad kacamata. */
    fun nav(action: Int) {
        when (action) {
            Proto.NAV_FORWARD -> if (vp.scrollForward()) changed() else if (pageIndex < pageCount - 1) nextPage()
            Proto.NAV_BACK -> if (vp.scrollBack()) changed() else if (pageIndex > 0) showPage(pageIndex - 1, alignBottom = true)
            Proto.NAV_NEXT_PAGE -> nextPage()
            Proto.NAV_PREV_PAGE -> prevPage()
        }
    }

    /** Kirim ulang posisi saat ini (mis. setelah kacamata siap). */
    fun republish() = onViewportChanged?.invoke(pageIndex, vp.x, vp.y, vp.w)

    /**
     * Render area kotak hijau persis seperti yang akan tampil di kacamata (untuk pratinjau
     * selama file belum sampai). Harus dipanggil di thread UI (pemilik PdfRenderer).
     */
    fun renderGlassesPreview(outW: Int, outH: Int): Bitmap? {
        val r = renderer ?: return null
        if (pageIndex !in 0 until r.pageCount || outW <= 0 || outH <= 0) return null
        val page = r.openPage(pageIndex)
        try {
            val (tx, ty, s) = Viewport.renderTransform(
                ViewState("", pageIndex, vp.x, vp.y, vp.w), page.width.toFloat(), outW)
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.postTranslate(tx, ty)
            m.postScale(s, s)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    private fun renderPageBitmap(r: PdfRenderer, index: Int) {
        val page = r.openPage(index)
        try {
            val pw = page.width.toFloat()
            val ph = page.height.toFloat()
            vp.pageAspect = ph / pw
            // Resolusi render di HP: ~2x lebar layar, dibatasi agar hemat memori.
            val screenW = if (width > 0) width else resources.displayMetrics.widthPixels
            var bw = min(screenW * 2, 2400)
            var bh = (bw * ph / pw).toInt()
            if (bh > 4000) { bw = (bw * 4000f / bh).toInt(); bh = 4000 }
            val bmp = Bitmap.createBitmap(bw.coerceAtLeast(1), bh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            pageBitmap?.recycle()
            pageBitmap = bmp
        } finally {
            page.close()
        }
    }

    private fun zoomAt(factor: Float, fx: Float, fy: Float) {
        if (box.isEmpty) return
        vp.zoomAt(factor, (fx - box.left) / box.width(), (fy - box.top) / box.height())
        changed()
    }

    private fun changed() {
        invalidate()
        if (renderer != null) onViewportChanged?.invoke(pageIndex, vp.x, vp.y, vp.w)
    }

    private fun computeBox() {
        if (width == 0 || height == 0) return
        val m = 20f * dp
        val aw = width - 2 * m
        val ah = height - 2 * m - 16f * dp
        val bw = min(aw, ah / vp.boxAspect)
        val bh = bw * vp.boxAspect
        val left = (width - bw) / 2f
        val top = (height - bh) / 2f + 8f * dp
        box.set(left, top, left + bw, top + bh)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeBox()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(32, 33, 36))
        val bmp = pageBitmap
        if (bmp == null || box.isEmpty) {
            canvas.drawText("Ketuk \"Buka PDF\" untuk mulai", width / 2f, height / 2f, hintPaint)
            return
        }
        // k = piksel layar per satuan lebar halaman
        val k = box.width() / vp.w
        val left = box.left - vp.x * k
        val top = box.top - vp.y * k
        canvas.drawRect(left, top, left + k, top + vp.pageAspect * k, pagePaint)
        val s = k / bmp.width
        drawMatrix.setScale(s, s)
        drawMatrix.postTranslate(left, top)
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)

        // Gelapkan area di luar kotak kacamata
        canvas.drawRect(0f, 0f, width.toFloat(), box.top, dimPaint)
        canvas.drawRect(0f, box.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, box.top, box.left, box.bottom, dimPaint)
        canvas.drawRect(box.right, box.top, width.toFloat(), box.bottom, dimPaint)
        canvas.drawRect(box, boxPaint)
        val zoomPct = (100f / vp.w).toInt()
        canvas.drawText("Area kacamata · zoom $zoomPct%", box.left, box.top - 6f * dp, labelPaint)
    }
}
