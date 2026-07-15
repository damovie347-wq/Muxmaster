#!/usr/bin/env python3
"""
mkvmerge/mkvextract icin TUM calisma-zamani (runtime) bagimliliklarini,
tahmine dayanmadan, gercek ELF NEEDED kayitlarini okuyarak ve Termux apt
deposunun resmi "Contents" indeksinden hangi paketin hangi .so dosyasini
sagladigini bularak, ozyinemeli (recursive) sekilde indirir.

ONCEKI YAKLASIMIN HATASI: sadece "mkvtoolnix" paketinin kendi usr/lib
klasoru + elle secilmis "zlib" kopyalaniyordu. mkvmerge ise ayri paketler
halinde dagitilan libebml/libmatroska/boost/pugixml/fmt/libc++_shared vb.
kutuphanelere de ihtiyac duyuyor. Bunlardan biri eksik olunca linker
"library X.so not found: needed by main executable" hatasiyla CRASH
oluyordu (cikti 0 byte).

YENI YAKLASIM:
  1) mkvmerge/mkvextract binary'leri kopyalanir.
  2) Her dosya icin `readelf -d` ile NEEDED (ihtiyac duyulan .so) listesi okunur.
  3) Her ihtiyac icin: zaten kopyalandi mi? / Android'in kendi sistem
     kutuphanesi mi (SKIP_SONAMES)? degilse -> apt Contents-aarch64.gz
     indeksinden o dosyayi hangi paketin sagladigi bulunur, o paket indirilip
     acilir, tam dosya kopyalanir ve KENDI bagimliliklari icin de kuyruga eklenir.
  4) Kuyruk bitene kadar (sabit noktaya ulasana kadar) tekrar edilir.
  5) Sonunda TUM .so/binary dosyalarina rpath=$ORIGIN yazilir (savunma amacli;
     NativeTools.kt zaten LD_LIBRARY_PATH da ayarliyor).
  6) Son bir dogrulama gecisiyle hicbir eksik bagimlilik kalmadigi teyit edilir;
     eksik varsa build ACIKCA basarisiz olur (sessiz/yaniltici basari YOK).
"""

import gzip
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ARCH = "aarch64"

REPOS = [
    (["https://packages.termux.dev/apt/termux-main", "https://packages-cf.termux.dev/apt/termux-main"], "stable"),
    (["https://packages.termux.dev/apt/termux-x11", "https://packages-cf.termux.dev/apt/termux-x11"], "x11"),
]

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "app", "src", "main", "jniLibs", "arm64-v8a")
WORK = tempfile.mkdtemp(prefix="mkvtoolnix_fetch_")

# Bunlar Android isletim sisteminin KENDISI tarafindan her zaman saglanir;
# Termux paketlerinden kopyalanmaya CALISILMAMALI (versiyon/ABI uyumsuzlugu
# riski + zaten linker bunlari sistemden bulur).
SKIP_SONAMES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}


def log(*a):
    print(*a, flush=True)


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "muxmaster-ci/1.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()


def fetch_first_ok(bases, rel_paths):
    last_err = None
    for base in bases:
        for p in rel_paths:
            url = f"{base}/{p}"
            try:
                return fetch(url), url
            except Exception as e:
                last_err = e
    raise RuntimeError(f"Indirilemedi: {last_err}")


def parse_packages_text(text):
    pkgs, cur = {}, {}
    for raw in text.split("\n"):
        if raw.strip() == "":
            if cur.get("Package"):
                pkgs[cur["Package"]] = cur
            cur = {}
            continue
        if ":" in raw and not raw.startswith(" "):
            k, v = raw.split(":", 1)
            cur[k.strip()] = v.strip()
    if cur.get("Package"):
        pkgs[cur["Package"]] = cur
    return pkgs


def load_all_packages():
    combined = {}
    for bases, dist in REPOS:
        try:
            data, url = fetch_first_ok(bases, [
                f"dists/{dist}/main/binary-{ARCH}/Packages.xz",
                f"dists/{dist}/main/binary-{ARCH}/Packages.gz",
                f"dists/{dist}/main/binary-{ARCH}/Packages",
            ])
            if url.endswith(".xz"):
                import lzma
                text = lzma.decompress(data).decode("utf-8", "replace")
            elif url.endswith(".gz"):
                text = gzip.decompress(data).decode("utf-8", "replace")
            else:
                text = data.decode("utf-8", "replace")
            pkgs = parse_packages_text(text)
            repo_base = url.split(f"/dists/{dist}/")[0]
            for name, stanza in pkgs.items():
                if name not in combined:
                    combined[name] = (stanza, repo_base)
        except Exception as e:
            log(f"UYARI: {dist} Packages indeksi okunamadi: {e}")
    return combined


def load_contents_map():
    """dosya-adi (basename) -> paket-adi haritasi ('.so' iceren yollarla sinirli)."""
    mapping = {}
    for bases, dist in REPOS:
        try:
            data, url = fetch_first_ok(bases, [f"dists/{dist}/Contents-{ARCH}.gz"])
        except Exception as e:
            log(f"UYARI: {dist} Contents indeksi alinamadi: {e}")
            continue
        try:
            text = gzip.decompress(data).decode("utf-8", "replace")
        except Exception as e:
            log(f"UYARI: {dist} Contents indeksi acilamadi: {e}")
            continue
        count = 0
        for raw in text.split("\n"):
            if ".so" not in raw:
                continue
            bits = raw.rsplit(None, 1)
            if len(bits) != 2:
                continue
            path, pkglist = bits
            base = path.rsplit("/", 1)[-1]
            if ".so" not in base:
                continue
            first_pkg = pkglist.split(",")[0]
            pkg_name = first_pkg.rsplit("/", 1)[-1]
            if base not in mapping:
                mapping[base] = pkg_name
                count += 1
        log(f"  {dist}: Contents indeksinden {count} adet .so girdisi okundu.")
    return mapping


def download_deb(combined, name):
    entry = combined.get(name)
    if not entry:
        return None
    stanza, repo_base = entry
    url = f"{repo_base}/{stanza['Filename']}"
    try:
        data = fetch(url)
        path = os.path.join(WORK, f"pkg_{name}.deb")
        with open(path, "wb") as f:
            f.write(data)
        return path
    except Exception as e:
        log(f"UYARI: {name}.deb indirilemedi: {e}")
        return None


def extract_deb_data(deb_path, dest):
    ar_dir = tempfile.mkdtemp(dir=WORK)
    subprocess.run(["ar", "x", os.path.abspath(deb_path)], cwd=ar_dir, check=True, capture_output=True)
    data_file = next((os.path.join(ar_dir, f) for f in os.listdir(ar_dir) if f.startswith("data.tar")), None)
    if not data_file:
        raise RuntimeError("data.tar yok")
    os.makedirs(dest, exist_ok=True)
    if data_file.endswith(".zst"):
        raw = os.path.join(ar_dir, "data.tar")
        subprocess.run(["zstd", "-d", "-f", data_file, "-o", raw], check=True, capture_output=True)
        data_file = raw
    with tarfile.open(data_file, mode="r:*") as t:
        t.extractall(dest)
    return dest


def find_subdir(root, suffix):
    for dirpath, _, _ in os.walk(root):
        if dirpath.replace(os.sep, "/").endswith(suffix):
            return dirpath
    return None


def find_file(root, filename):
    for dirpath, _, files in os.walk(root):
        if filename in files:
            return os.path.join(dirpath, filename)
    return None


def real_copy(src, dst):
    shutil.copy2(os.path.realpath(src), dst)


def get_needed(elf_path):
    """`readelf -d` cikisindan NEEDED (Shared library) SONAME listesini doner."""
    try:
        out = subprocess.run(["readelf", "-d", elf_path], capture_output=True, text=True, check=True).stdout
    except Exception as e:
        log(f"UYARI: readelf calisamadi ({elf_path}): {e}")
        return []
    needed = []
    for line in out.splitlines():
        m = re.search(r"\(NEEDED\)\s+Shared library:\s+\[([^\]]+)\]", line)
        if m:
            needed.append(m.group(1))
    return needed


def set_rpath_origin(path):
    try:
        subprocess.run(["patchelf", "--set-rpath", "$ORIGIN", path], check=True, capture_output=True)
    except Exception as e:
        log(f"UYARI: patchelf rpath basarisiz ({path}): {e}")


def main():
    log("== mkvtoolnix paket indeksi yukleniyor ==")
    combined = load_all_packages()
    if not combined:
        log("HATA: hicbir apt deposu okunamadi.")
        sys.exit(1)

    log("== Contents (.so -> paket) indeksi yukleniyor ==")
    contents_map = load_contents_map()
    if not contents_map:
        log("HATA: Contents indeksi hic yuklenemedi; bagimliliklar cozumlenemez.")
        sys.exit(1)

    if os.path.isdir(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(OUT_DIR, exist_ok=True)

    log("== mkvtoolnix .deb indiriliyor ==")
    deb = download_deb(combined, "mkvtoolnix")
    if not deb:
        log("HATA: mkvtoolnix paketi indirilemedi.")
        sys.exit(1)

    extracted = extract_deb_data(deb, os.path.join(WORK, "extracted_mkvtoolnix"))
    bin_dir = find_subdir(extracted, "usr/bin")
    if not bin_dir:
        log("HATA: paket icinde usr/bin bulunamadi.")
        sys.exit(1)
    mkvmerge_src = os.path.join(bin_dir, "mkvmerge")
    mkvextract_src = os.path.join(bin_dir, "mkvextract")
    if not (os.path.isfile(mkvmerge_src) and os.path.isfile(mkvextract_src)):
        log("HATA: mkvmerge/mkvextract binary'leri pakette bulunamadi.")
        sys.exit(1)

    real_copy(mkvmerge_src, os.path.join(OUT_DIR, "libmkvmerge.so"))
    real_copy(mkvextract_src, os.path.join(OUT_DIR, "libmkvextract.so"))
    os.chmod(os.path.join(OUT_DIR, "libmkvmerge.so"), 0o755)
    os.chmod(os.path.join(OUT_DIR, "libmkvextract.so"), 0o755)
    log("  libmkvmerge.so / libmkvextract.so kopyalandi.")

    # --- Ozyinemeli bagimlilik cozumlemesi ---
    log("== Calisma-zamani bagimliliklari ozyinemeli olarak cozumleniyor ==")
    extracted_pkg_cache = {"mkvtoolnix": extracted}
    resolved_sonames = {"libmkvmerge.so", "libmkvextract.so"}
    unresolved_sonames = set()
    queue = [os.path.join(OUT_DIR, "libmkvmerge.so"), os.path.join(OUT_DIR, "libmkvextract.so")]
    processed_files = set()

    def ensure_package_extracted(pkg_name):
        if pkg_name in extracted_pkg_cache:
            return extracted_pkg_cache[pkg_name]
        deb_path = download_deb(combined, pkg_name)
        if not deb_path:
            extracted_pkg_cache[pkg_name] = None
            return None
        try:
            dest = extract_deb_data(deb_path, os.path.join(WORK, f"extracted_{pkg_name}"))
        except Exception as e:
            log(f"UYARI: {pkg_name}.deb acilamadi: {e}")
            dest = None
        extracted_pkg_cache[pkg_name] = dest
        return dest

    while queue:
        current = queue.pop()
        if current in processed_files:
            continue
        processed_files.add(current)

        for soname in get_needed(current):
            if soname in resolved_sonames or soname in SKIP_SONAMES:
                continue

            pkg_name = contents_map.get(soname)
            if not pkg_name:
                log(f"  ! {soname}: Contents indeksinde bulunamadi (paket bilinmiyor)")
                unresolved_sonames.add(soname)
                continue

            extract_root = ensure_package_extracted(pkg_name)
            if not extract_root:
                log(f"  ! {soname}: paketi '{pkg_name}' indirilemedi/acilamadi")
                unresolved_sonames.add(soname)
                continue

            src = find_file(extract_root, soname)
            if not src:
                log(f"  ! {soname}: '{pkg_name}' paketi icinde dosya bulunamadi")
                unresolved_sonames.add(soname)
                continue

            dst = os.path.join(OUT_DIR, soname)
            if not os.path.exists(dst):
                real_copy(src, dst)
                log(f"  + {soname}  (paket: {pkg_name})")
            resolved_sonames.add(soname)
            unresolved_sonames.discard(soname)
            queue.append(dst)

    # --- Tum dosyalara rpath=$ORIGIN (savunma amacli, LD_LIBRARY_PATH'e ek olarak) ---
    log("== rpath=$ORIGIN tum .so dosyalarina yaziliyor ==")
    for fn in os.listdir(OUT_DIR):
        set_rpath_origin(os.path.join(OUT_DIR, fn))

    # --- Son dogrulama: gercekten hicbir NEEDED eksik kalmamis mi? ---
    log("== Son dogrulama: tum bagimliliklar karsilaniyor mu? ==")
    present = set(os.listdir(OUT_DIR))
    still_missing = set()
    for fn in sorted(present):
        for soname in get_needed(os.path.join(OUT_DIR, fn)):
            if soname in SKIP_SONAMES:
                continue
            if soname not in present:
                still_missing.add(soname)

    if still_missing or unresolved_sonames:
        all_missing = still_missing | unresolved_sonames
        log("HATA: asagidaki paylasimli kutuphaneler cozumlenemedi:")
        for s in sorted(all_missing):
            log(f"    - {s}")
        log("APK bu haliyle derlense bile mkvmerge/mkvextract calisma zamaninda CRASH olur.")
        sys.exit(1)

    log("== TAMAM: tum bagimliliklar cozumlendi ==")
    total_size = 0
    for fn in sorted(os.listdir(OUT_DIR)):
        p = os.path.join(OUT_DIR, fn)
        sz = os.path.getsize(p)
        total_size += sz
        log(f"  {fn} ({sz} bytes)")
    log(f"Toplam: {len(present)} dosya, {total_size / (1024*1024):.1f} MB")


if __name__ == "__main__":
    main()
