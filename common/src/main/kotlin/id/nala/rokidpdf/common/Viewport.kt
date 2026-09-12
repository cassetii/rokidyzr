package id.nala.rokidpdf.common

import kotlin.math.max
import kotlin.math.min

/**
 * Logika posisi pandang, tanpa ketergantungan Android supaya bisa diuji.
 *
 * Satuan: "lebar halaman" (lihat [ViewState]).
 * @param boxAspect tinggi/lebar layar kacamata (480x640 -> 4/3).
 */
class Viewport(var boxAspect: Float = 4f / 3f) {
    var x = 0f; private set
    var y = 0f; private set
    var w = 1f; private set

    /** tinggi/lebar halaman yang sedang dibuka (A4 potret ~ 1.414). */
    var pageAspect = 1.4142f
        set(value) { field = value; clamp() }

    val h: Float get() = w * boxAspect

    fun set(x: Float, y: Float, w: Float) {
        this.x = x; this.y = y; this.w = w
        clamp()
    }

    /** Zoom dengan faktor [factor] (>1 = memperbesar) di titik fokus relatif kotak (0..1). */
    fun zoomAt(factor: Float, relX: Float, relY: Float) {
        if (factor <= 0f || factor.isNaN()) return
        val px = x + relX * w
        val py = y + relY * h
        val nw = (w / factor).coerceIn(MIN_W, MAX_W)
        w = nw
        x = px - relX * nw
        y = py - relY * nw * boxAspect
        clamp()
    }

    /** Geser; [relDx], [relDy] = pergeseran jari dibagi ukuran kotak (arah seperti GestureDetector.onScroll). */
    fun pan(relDx: Float, relDy: Float) {
        x += relDx * w
        y += relDy * h
        clamp()
    }

    fun atBottom(): Boolean = y + h >= pageAspect - EPS
    fun atTop(): Boolean = y <= min(0f, pageAspect - h) + EPS

    /** Gulir satu "layar" ke bawah. @return false bila sudah di dasar (pemanggil pindah halaman). */
    fun scrollForward(): Boolean {
        if (atBottom()) return false
        y += h * SCROLL_STEP
        clamp()
        return true
    }

    /** Gulir satu "layar" ke atas. @return false bila sudah di puncak. */
    fun scrollBack(): Boolean {
        if (atTop()) return false
        y -= h * SCROLL_STEP
        clamp()
        return true
    }

    fun toTop() { y = 0f; clamp() }
    fun toBottom() { y = pageAspect - h; clamp() }

    fun clamp() {
        w = w.coerceIn(MIN_W, MAX_W)
        x = clampRange(x, 0f, 1f - w)
        y = clampRange(y, 0f, pageAspect - h)
    }

    private fun clampRange(v: Float, a: Float, b: Float): Float =
        if (a <= b) v.coerceIn(a, b) else v.coerceIn(b, a)

    companion object {
        const val MIN_W = 0.06f   // zoom maksimum ~16x lebar halaman
        const val MAX_W = 1.6f
        const val SCROLL_STEP = 0.85f
        private const val EPS = 0.002f

        /**
         * Transformasi render di kacamata: titik halaman (point PDF) -> piksel bitmap.
         * Hasil: skala dan translasi (dipakai sebagai Matrix: postTranslate(tx,ty) lalu postScale(s,s)).
         */
        fun renderTransform(v: ViewState, pageWidthPt: Float, bitmapWidthPx: Int): Triple<Float, Float, Float> {
            val scale = bitmapWidthPx / (max(v.w, MIN_W) * pageWidthPt)
            return Triple(-v.x * pageWidthPt, -v.y * pageWidthPt, scale)
        }
    }
}
