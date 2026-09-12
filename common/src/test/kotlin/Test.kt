import id.nala.rokidpdf.common.*
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.*
import kotlin.math.abs

fun check(c: Boolean, m: String) { if (!c) throw AssertionError(m); println("OK  $m") }
fun near(a: Float, b: Float, e: Float = 1e-4f) = abs(a - b) < e

fun main() {
    // --- Codec round trip
    val v = ViewState("abc", 3, 0.12f, 0.4f, 0.33f)
    check(Codec.readView(Codec.view(v)) == v, "view roundtrip")
    check(Codec.readOffer(Codec.offer(Offer("id1", "Laporan.pdf", 123456L))) == Offer("id1","Laporan.pdf",123456L), "offer roundtrip")
    check(Codec.readHello(Codec.hello(Hello(1,480,640))) == Hello(1,480,640), "hello roundtrip")
    check(Codec.readReady(Codec.ready(Ready("x", 12))) == Ready("x",12), "ready roundtrip")

    // --- Loopback connection: phone <-> glasses over pipes
    val p2gIn = PipedInputStream(1 shl 20); val p2gOut = PipedOutputStream(p2gIn)
    val g2pIn = PipedInputStream(1 shl 20); val g2pOut = PipedOutputStream(g2pIn)
    val file = ByteArray(700_000) { (it * 31 % 251).toByte() }
    val docId = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }.take(24)
    val received = ByteArrayOutputStream()
    val views = CopyOnWriteArrayList<ViewState>()
    val done = CountDownLatch(1)
    lateinit var phone: FrameConnection
    lateinit var glasses: FrameConnection
    glasses = FrameConnection(Closeable {}, p2gIn, g2pOut, onFrame = { f ->
        when (f.type) {
            Proto.T_OFFER -> glasses.send(Codec.need(Codec.readOffer(f).docId))
            Proto.T_CHUNK -> received.write(f.payload)
            Proto.T_FILE_END -> glasses.send(Codec.ready(Ready(Codec.readDocId(f), 5)))
            Proto.T_VIEW -> { views.add(Codec.readView(f)); if (Codec.readView(f).page == 99) done.countDown() }
        }
    }, onClosed = {})
    val readyLatch = CountDownLatch(1)
    phone = FrameConnection(Closeable {}, g2pIn, p2gOut, onFrame = { f ->
        when (f.type) {
            Proto.T_NEED -> phone.sendJob { write ->
                val buf = ByteArray(Proto.CHUNK_SIZE); val ins = ByteArrayInputStream(file)
                while (true) { val n = ins.read(buf); if (n <= 0) break; write(Codec.chunk(buf, n)) }
                write(Codec.fileEnd(Codec.readDocId(f)))
            }
            Proto.T_READY -> readyLatch.countDown()
        }
    }, onClosed = {})
    glasses.start(); phone.start()
    phone.send(Codec.offer(Offer(docId, "a.pdf", file.size.toLong())))
    check(readyLatch.await(10, TimeUnit.SECONDS), "file transfer + READY")
    check(received.toByteArray().contentEquals(file), "file bytes identical (${file.size} B)")

    // burst of 2000 view updates -> coalesced, last one must arrive
    repeat(2000) { i -> phone.sendLatest(Codec.view(ViewState(docId, 1, i / 2000f, 0f, 0.5f))) }
    phone.sendLatest(Codec.view(ViewState(docId, 99, 0f, 0f, 0.5f)))
    check(done.await(5, TimeUnit.SECONDS), "latest view delivered")
    check(views.size < 2001, "views coalesced (${views.size} dari 2001 terkirim)")
    check(views.last().page == 99, "last view is newest")

    // --- Viewport math
    val vp = Viewport(4f/3f); vp.pageAspect = 1.4142f
    vp.set(0f, 0f, 1f)
    check(near(vp.h, 4f/3f), "h = w*aspect")
    vp.zoomAt(2f, 0.5f, 0.5f)   // zoom 2x around center
    check(near(vp.w, 0.5f), "zoom 2x -> w=0.5 (w=${vp.w})")
    check(near(vp.x, 0.25f), "zoom keeps center x (x=${vp.x})")
    check(near(vp.y, 0.3333f, 1e-3f), "zoom keeps center y (y=${vp.y})")
    vp.pan(10f, 0f)
    check(near(vp.x, 0.5f), "pan clamped to right edge (x=${vp.x})")
    vp.pan(-10f, -10f)
    check(near(vp.x, 0f) && near(vp.y, 0f), "pan clamped to top-left")
    var steps = 0; while (vp.scrollForward()) steps++
    check(vp.atBottom() && steps >= 1, "scroll reaches bottom in $steps steps")
    check(!vp.scrollForward(), "scrollForward at bottom returns false -> next page")
    vp.toTop(); check(vp.atTop(), "toTop")
    vp.zoomAt(1000f, 0f, 0f); check(near(vp.w, Viewport.MIN_W), "zoom capped")
    vp.zoomAt(0.001f, 0f, 0f); check(near(vp.w, Viewport.MAX_W), "zoom-out capped")
    check(vp.atBottom(), "viewport larger than page -> atBottom")

    // render transform: A4 612pt wide, viewing right half at x=0.5,w=0.5 into 480px
    val (tx, ty, s) = Viewport.renderTransform(ViewState("d", 0, 0.5f, 0.1f, 0.5f), 612f, 480)
    check(near(s, 480f / 306f) && near(tx, -306f) && near(ty, -61.2f, 1e-3f), "render transform")
    // point at page x=306pt (left edge of viewport) -> pixel 0; x=612 -> 480
    check(near((306f + tx) * s, 0f) && near((612f + tx) * s, 480f, 1e-3f), "viewport edges map to bitmap edges")

    // --- Wi-Fi offer codec
    val wo = WifiOffer("doc", "tok-123", 40123, listOf("192.168.1.5", "10.0.0.2"))
    check(Codec.readWifiOffer(Codec.wifiOffer(wo)) == wo, "wifi offer roundtrip")

    // --- Preview codec: simulasi halaman teks 480x640 (latar putih, baris-baris teks)
    val W = 480; val H = 640
    val rnd = java.util.Random(7)
    val px = IntArray(W * H) { 0xFFFFFFFF.toInt() }
    for (line in 0 until 28) {
        val y0 = 20 + line * 22
        var x = 16
        while (x < W - 30) {
            val wordLen = 18 + rnd.nextInt(50)
            for (yy in y0 until y0 + 12) for (xx in x until minOf(x + wordLen, W - 16))
                if (rnd.nextInt(3) == 0) { val g = rnd.nextInt(256); px[yy * W + xx] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            x += wordLen + 8
        }
    }
    val enc = PreviewCodec.encode(px, W, H)
    val dec = IntArray(W * H)
    PreviewCodec.decode(enc, W, H, dec)
    var maxErr = 0
    for (i in px.indices) maxErr = maxOf(maxErr, abs((px[i] and 0xFF) - (dec[i] and 0xFF)))
    check(maxErr <= 9, "preview decode error kecil (maks $maxErr dari 255)")
    check(enc.size < 80_000, "ukuran pratinjau ${enc.size / 1024} KB (teks padat, dari 1200 KB mentah)")
    val rp = Codec.readPreview(Codec.preview(Preview("d", 2, 9, W, H, enc)))
    check(rp.page == 2 && rp.pageCount == 9 && rp.data.contentEquals(enc), "preview frame roundtrip")

    // --- Pratinjau disisipkan di tengah pengiriman file (kacamata tetap responsif)
    val aIn = PipedInputStream(1 shl 16); val aOut = PipedOutputStream(aIn)
    val order = CopyOnWriteArrayList<Byte>()
    val gotEnd = CountDownLatch(1)
    val sink = FrameConnection(Closeable {}, aIn, ByteArrayOutputStream(), onFrame = { f ->
        order.add(f.type); if (f.type == Proto.T_FILE_END) gotEnd.countDown()
    }, onClosed = {})
    val src = FrameConnection(Closeable {}, PipedInputStream(PipedOutputStream()), aOut, onFrame = {}, onClosed = {})
    sink.start(); src.start()
    val started = CountDownLatch(1)
    src.sendJob { write ->
        repeat(200) { i ->
            write(Codec.chunk(ByteArray(8192), 8192))
            if (i == 20) { started.countDown(); Thread.sleep(50) }
            if (i > 20) Thread.sleep(1)
        }
        write(Codec.fileEnd("x"))
    }
    started.await()
    src.sendLatest(Codec.preview(Preview("x", 0, 1, 4, 4, ByteArray(10))))
    check(gotEnd.await(10, TimeUnit.SECONDS), "file selesai")
    val pIdx = order.indexOf(Proto.T_PREVIEW); val endIdx = order.indexOf(Proto.T_FILE_END)
    check(pIdx in 1 until endIdx, "pratinjau terkirim DI TENGAH file (posisi $pIdx dari $endIdx)")
    src.close(); sink.close()

    phone.close(); glasses.close()
    println("\nSEMUA TES LULUS")
}
