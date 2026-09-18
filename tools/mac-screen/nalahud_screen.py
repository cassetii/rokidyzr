#!/usr/bin/env python3
"""
Nala HUD — kirim sebagian layar Mac ke Rokid Glasses lewat Wi-Fi.

Kenapa "sebagian": layar kacamata hanya 480 piksel. Kalau seluruh layar Mac
dikecilkan ke sana, teksnya jadi sepertiga ukuran asli dan tidak terbaca.
Jadi yang dikirim adalah potongan layar berukuran 480x352 piksel logis
(1:1, ukuran asli) yang mengikuti kursor mouse.

Pemakaian:
    pip install mss
    python3 nalahud_screen.py

Kacamata menemukan Mac ini sendiri lewat siaran UDP — tidak perlu mengetik IP.

Izin: macOS akan meminta izin "Screen Recording" untuk Terminal saat pertama
dijalankan (System Settings > Privacy & Security > Screen Recording).
"""

import socket
import struct
import sys
import threading
import time
import zlib

PORT_TCP = 45454          # aliran gambar
PORT_DISCOVERY = 45455    # siaran penemuan
MAGIC = b"NHUD"

# Ukuran default mengikuti kanvas acuan Rokid (480x352). Dikoreksi oleh kacamata saat menyambung.
DEFAULT_W, DEFAULT_H = 480, 352
FPS_MAX = 12

# Perintah dari kacamata
CMD_GESER = 1      # arg: dx, dy (piksel)
CMD_ZOOM = 2       # arg: level (persen, 50..300)
CMD_MODE_IKUT = 3  # arg: 0=manual, 1=kursor mouse, 2=ikut ketikan
CMD_UKURAN = 4     # arg: lebar, tinggi layar kacamata


class Pengaturan:
    def __init__(self):
        self.w = DEFAULT_W
        self.h = DEFAULT_H
        self.zoom = 100          # 100 = ukuran asli (1:1)
        self.mode_ikut = 2       # default: ikut ketikan
        self.caret_gagal = 0
        self.x = 0
        self.y = 0
        self.lock = threading.Lock()


def posisi_kursor():
    """Posisi kursor mouse. Butuh pyobjc; kalau tidak ada, fitur ikut-kursor mati."""
    try:
        from Quartz import CGEventGetLocation, CGEventCreate
        p = CGEventGetLocation(CGEventCreate(None))
        return int(p.x), int(p.y)
    except Exception:
        return None


try:
    import numpy as np
    ADA_NUMPY = True
except ImportError:
    np = None
    ADA_NUMPY = False


def ke_4bit(raw_bgra, w, h):
    """
    Ubah piksel BGRA jadi 16 tingkat abu-abu, 2 piksel per byte, lalu dikompres.
    Format ini sama persis dengan PreviewCodec di aplikasi kacamata.

    Jalur numpy ~38x lebih cepat (1,5 ms vs 57 ms per frame) dan hasilnya
    identik byte per byte dengan jalur murni di bawahnya.
    """
    if ADA_NUMPY:
        a = np.frombuffer(raw_bgra, dtype=np.uint8).reshape(-1, 4)[:w * h].astype(np.uint32)
        y = (a[:, 2] * 77 + a[:, 1] * 150 + a[:, 0] * 29) >> 8
        q = ((y * 15 + 127) // 255).astype(np.uint8)
        if q.size % 2:
            q = np.append(q, np.uint8(0))
        packed = ((q[0::2] << 4) | q[1::2]).astype(np.uint8).tobytes()
        return zlib.compress(packed, 1)

    n = w * h
    packed = bytearray((n + 1) // 2)
    mv = memoryview(raw_bgra)
    for i in range(n):
        j = i * 4
        b = mv[j]; g = mv[j + 1]; r = mv[j + 2]
        y = (r * 77 + g * 150 + b * 29) >> 8
        q = (y * 15 + 127) // 255
        k = i >> 1
        if i & 1 == 0:
            packed[k] = q << 4
        else:
            packed[k] |= q
    return zlib.compress(bytes(packed), 1)


def posisi_caret():
    """
    Posisi kursor teks (caret) lewat Accessibility API macOS.
    Inilah yang membuat tampilan ikut saat Anda MENGETIK, bukan saat mouse bergerak.
    Butuh izin Accessibility dan paket pyobjc.
    """
    try:
        from ApplicationServices import (
            AXUIElementCreateSystemWide, AXUIElementCopyAttributeValue,
            AXUIElementCopyParameterizedAttributeValue, AXValueGetValue,
            kAXValueCGRectType,
        )
        sistem = AXUIElementCreateSystemWide()
        err, fokus = AXUIElementCopyAttributeValue(sistem, "AXFocusedUIElement", None)
        if err or fokus is None:
            return None
        err, rentang = AXUIElementCopyAttributeValue(fokus, "AXSelectedTextRange", None)
        if err or rentang is None:
            return None
        err, kotak = AXUIElementCopyParameterizedAttributeValue(
            fokus, "AXBoundsForRange", rentang, None)
        if err or kotak is None:
            return None
        ok, rect = AXValueGetValue(kotak, kAXValueCGRectType, None)
        if not ok:
            return None
        return int(rect.origin.x), int(rect.origin.y)
    except Exception:
        return None


def titik_perubahan(sct, mon, kecil_sebelum):
    """
    Cadangan bila caret tak terbaca: cari BAGIAN LAYAR YANG BERUBAH.
    Layar penuh diambil kecil (1/8), dibandingkan dengan frame sebelumnya,
    lalu titik tengah perubahan dipakai sebagai pusat pandangan.
    Bekerja di aplikasi apa pun, termasuk yang tidak mendukung Accessibility.
    """
    if not ADA_NUMPY:
        return None, kecil_sebelum
    shot = sct.grab(mon)
    a = np.frombuffer(shot.bgra, dtype=np.uint8).reshape(shot.height, shot.width, 4)
    kecil = a[::8, ::8, 1].astype(np.int16)          # kanal hijau sudah cukup
    if kecil_sebelum is None or kecil_sebelum.shape != kecil.shape:
        return None, kecil
    beda = np.abs(kecil - kecil_sebelum) > 12
    if beda.sum() < 4:                                # terlalu sedikit: anggap diam
        return None, kecil
    ys, xs = np.nonzero(beda)
    cx = int(xs.mean() * 8) + mon["left"]
    cy = int(ys.mean() * 8) + mon["top"]
    return (cx, cy), kecil


def layani_penemuan(stop):
    """Jawab siaran UDP dari kacamata supaya IP Mac ditemukan otomatis."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.settimeout(1.0)
    s.bind(("", PORT_DISCOVERY))
    print(f"[discovery] mendengarkan di UDP {PORT_DISCOVERY}")
    while not stop.is_set():
        try:
            data, addr = s.recvfrom(64)
        except socket.timeout:
            continue
        except OSError:
            break
        if data.startswith(MAGIC):
            s.sendto(MAGIC + struct.pack("!H", PORT_TCP), addr)
    s.close()


def baca_perintah(conn, cfg, stop):
    """Terima perintah dari kacamata (geser, zoom, ikut kursor, ukuran layar)."""
    conn.settimeout(1.0)
    buf = b""
    while not stop.is_set():
        try:
            b = conn.recv(12)
        except socket.timeout:
            continue
        except OSError:
            break
        if not b:
            break
        buf += b
        while len(buf) >= 12:
            cmd, a1, a2 = struct.unpack("!iii", buf[:12])
            buf = buf[12:]
            with cfg.lock:
                if cmd == CMD_GESER:
                    cfg.mode_ikut = 0
                    cfg.x += a1
                    cfg.y += a2
                elif cmd == CMD_ZOOM:
                    cfg.zoom = max(50, min(300, a1))
                elif cmd == CMD_MODE_IKUT:
                    cfg.mode_ikut = max(0, min(2, a1))
                    print(f"[mode] {['manual','ikut kursor','ikut ketikan'][cfg.mode_ikut]}")
                elif cmd == CMD_UKURAN and 100 <= a1 <= 2000 and 100 <= a2 <= 2000:
                    cfg.w, cfg.h = a1, a2
            print(f"[perintah] cmd={cmd} a1={a1} a2={a2}")


def kirim_layar(conn, cfg, stop):
    import mss

    with mss.mss() as sct:
        mon = sct.monitors[1]
        lebar_layar, tinggi_layar = mon["width"], mon["height"]
        print(f"[layar] {lebar_layar}x{tinggi_layar}")
        interval = 1.0 / FPS_MAX
        terakhir = None
        kecil_sebelum = None
        while not stop.is_set():
            mulai = time.time()
            with cfg.lock:
                w, h, zoom, mode = cfg.w, cfg.h, cfg.zoom, cfg.mode_ikut
                pusat = None
                if mode == 2:                       # ikut ketikan
                    pusat = posisi_caret()
                    if pusat is None:               # caret tak terbaca -> ikuti perubahan layar
                        pusat, kecil_sebelum = titik_perubahan(sct, mon, kecil_sebelum)
                elif mode == 1:                     # ikut kursor mouse
                    pusat = posisi_kursor()
                if pusat:
                    cfg.x, cfg.y = pusat[0] - w // 2, pusat[1] - h // 3
                # zoom < 100 berarti mengambil area lebih luas lalu dikecilkan
                amb_w = int(w * 100 / zoom)
                amb_h = int(h * 100 / zoom)
                cfg.x = max(mon["left"], min(cfg.x, mon["left"] + lebar_layar - amb_w))
                cfg.y = max(mon["top"], min(cfg.y, mon["top"] + tinggi_layar - amb_h))
                x, y = cfg.x, cfg.y

            shot = sct.grab({"left": x, "top": y, "width": amb_w, "height": amb_h})
            raw, sw, sh = shot.bgra, shot.width, shot.height

            if (sw, sh) != (w, h):
                raw, sw, sh = skala_terdekat(raw, sw, sh, w, h)

            data = ke_4bit(raw, sw, sh)
            if data == terakhir:                      # layar tidak berubah: jangan kirim ulang
                time.sleep(interval)
                continue
            terakhir = data
            try:
                conn.sendall(MAGIC + struct.pack("!iii", sw, sh, len(data)) + data)
            except OSError:
                break
            sisa = interval - (time.time() - mulai)
            if sisa > 0:
                time.sleep(sisa)


def skala_terdekat(raw, sw, sh, tw, th):
    """Perkecil dengan mengambil piksel terdekat — cepat dan cukup untuk teks."""
    if ADA_NUMPY:
        a = np.frombuffer(raw, dtype=np.uint8).reshape(sh, sw, 4)
        yi = (np.arange(th) * sh // th).clip(0, sh - 1)
        xi = (np.arange(tw) * sw // tw).clip(0, sw - 1)
        return a[yi][:, xi].tobytes(), tw, th
    out = bytearray(tw * th * 4)
    for ty in range(th):
        sy = ty * sh // th
        baris = sy * sw * 4
        tb = ty * tw * 4
        for tx in range(tw):
            sx = tx * sw // tw
            si = baris + sx * 4
            ti = tb + tx * 4
            out[ti:ti + 4] = raw[si:si + 4]
    return bytes(out), tw, th


def main():
    cfg = Pengaturan()
    stop = threading.Event()
    threading.Thread(target=layani_penemuan, args=(stop,), daemon=True).start()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("", PORT_TCP))
    srv.listen(1)
    print(f"[server] siap di TCP {PORT_TCP} — buka Nala HUD di kacamata")
    if not ADA_NUMPY:
        print("[info] numpy tidak ada — encode ~38x lebih lambat (pasang: pip install numpy)")
    if posisi_caret() is None:
        print("[info] caret tak terbaca; ikut-ketikan memakai deteksi perubahan layar.")
        print("       Untuk akurasi: pip install pyobjc-framework-ApplicationServices")
        print("       lalu beri izin Accessibility untuk Terminal.")

    try:
        while True:
            conn, addr = srv.accept()
            print(f"[sambung] kacamata {addr[0]}")
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            stop.clear()
            t = threading.Thread(target=baca_perintah, args=(conn, cfg, stop), daemon=True)
            t.start()
            try:
                kirim_layar(conn, cfg, stop)
            except Exception as e:
                print(f"[putus] {e}")
            finally:
                stop.set()
                conn.close()
                print("[putus] menunggu sambungan lagi")
    except KeyboardInterrupt:
        print("\n[selesai]")
    finally:
        stop.set()
        srv.close()


if __name__ == "__main__":
    main()
