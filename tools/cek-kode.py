import re, glob, sys
ok = True

def kode_saja(t):
    """Buang string dulu, baru komentar.

    Urutan ini penting: teks seperti "*/*" (tipe MIME) mengandung "/*" dan akan
    dikira awal komentar kalau komentar dibuang lebih dulu.
    """
    t = re.sub(r'"""(?:.|\n)*?"""', '""', t)           # string multi-baris
    t = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', t)        # string biasa
    t = re.sub(r'/\*.*?\*/', ' ', t, flags=re.S)      # blok komentar
    t = re.sub(r'//[^\n]*', ' ', t)                    # komentar baris
    return t
files = glob.glob('phone/src/main/java/**/*.kt', recursive=True) + \
        glob.glob('glasses/src/main/java/**/*.kt', recursive=True) + \
        glob.glob('common/src/main/kotlin/**/*.kt', recursive=True)

# 1) konstanta ALL_CAPS dipakai tapi tak didefinisikan di file/objek yang diimpor
for f in files:
    t = kode_saja(open(f).read())
    defined = set(re.findall(r'(?:const val|val|var)\s+([A-Z][A-Z0-9_]{2,})', t))
    # konstanta milik objek lain diakses lewat titik -> abaikan
    used = set(re.findall(r'(?<![\w.])([A-Z][A-Z0-9_]{2,})(?![\w(])', t))
    android = {'MATCH_PARENT','WRAP_CONTENT','MODE_PRIVATE','TYPE_CLASS_TEXT','RESULT_OK',
               'VERTICAL','HORIZONTAL','UUID'}  # UUID = kelas JDK, bukan konstanta
    missing = sorted(used - defined - android)
    if missing:
        ok = False
        print("KONSTANTA TAK DITEMUKAN", f, missing)

# 2) simbol common dipakai tanpa import
common = set()
for f in glob.glob('common/src/main/kotlin/id/nala/rokidpdf/common/*.kt'):
    common |= set(re.findall(r'^(?:object|class|data class|enum class|interface)\s+(\w+)', open(f).read(), re.M))
for f in files:
    if '/common/' in f: continue
    t = kode_saja(open(f).read()); imps = re.findall(r'^import\s+(\S+)\s*$', t, re.M)
    for i in set(imps):
        if imps.count(i) > 1: ok = False; print("IMPORT DUPLIKAT", f, i)
    for name in common:
        if re.search(r'(?<![\w.])%s\s*[.(]' % name, t) and f'id.nala.rokidpdf.common.{name}' not in imps:
            ok = False; print("PAKAI TANPA IMPORT", f, name)

# 3) anggota interface Listener/Output yang belum di-override
def members(path, marker):
    m = re.search(marker + r'\s*\{(.*?)\n    \}', open(path).read(), re.S)
    return set(re.findall(r'fun (\w+)\(', m.group(1))) if m else set()
pairs = [('phone/src/main/java/id/nala/rokidpdf/phone/PhoneLink.kt', 'interface Listener',
          'phone/src/main/java/id/nala/rokidpdf/phone/MainActivity.kt'),
         ('glasses/src/main/java/id/nala/rokidpdf/glasses/GlassesLink.kt', 'interface Listener',
          'glasses/src/main/java/id/nala/rokidpdf/glasses/MainActivity.kt'),
         ('phone/src/main/java/id/nala/rokidpdf/phone/AskSession.kt', 'interface Output',
          'phone/src/main/java/id/nala/rokidpdf/phone/MainActivity.kt')]
for link, marker, act in pairs:
    miss = members(link, marker) - set(re.findall(r'override fun (\w+)\(', open(act).read()))
    if miss: ok = False; print("BELUM DI-OVERRIDE", act, miss)

# 4) fungsi privat dipanggil tapi tidak ada
for f in files:
    t = kode_saja(open(f).read())
    defined = set(re.findall(r'fun (\w+)\(', t))
    calls = set(re.findall(r'(?<![\w.])([a-z][A-Za-z]{3,})\(', t))
    lokal = {c for c in calls if c in {'askPressed','toggleDengar','terapkanUkuran','ubahUkuran','showAsk',
        'showStatus','showInfo','showImage','formatCatatan','applyCfg','pusat','toggleInvert','kirimCfg',
        'toggleAsk','toggleListen','showSettings','pickNotes','loadNotes','loadSavedNotes','askSession',
        'schedulePreview','sendPreviewNow','labelJeda','labelAnalisis','labelPanjang','labelModel','hud'}}
    miss = sorted(lokal - defined)
    if miss: ok = False; print("FUNGSI TAK ADA", f, miss)

print("HASIL:", "BERSIH" if ok else "ADA MASALAH")
sys.exit(0 if ok else 1)
