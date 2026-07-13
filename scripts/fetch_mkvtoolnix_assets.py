#!/usr/bin/env python3
"""
mkvmerge / mkvextract (MKVToolNix) arm64 (aarch64) ikili dosyalarını ve GERÇEKTEN
ihtiyaç duydukları paylaşılan kütüphaneleri (.so) Termux'un resmi apt depolarından
indirip app/src/main/assets/mkvtoolnix/ altına koyar.

mkvtoolnix paketi Termux'un "main" deposunda DEĞİL, ayrı "x11-repo" deposundadır
(bu yüzden Termux'ta `pkg install x11-repo` önce gerekiyor) - bu script hem main
hem x11 deposunun paket indekslerini okur.

Hangi .so dosyalarının gerekli olduğunu TAHMİN ETMİYORUZ: mkvmerge/mkvextract
ikilileri indirildikten sonra `readelf -d` ile onların GERÇEK (NEEDED) listesi
okunuyor, sadece o kütüphaneler (ve onların da kendi NEEDED zinciri) indiriliyor.
Böylece paketin METADATA'sında görünen ama CLI ikilileri tarafından hiç
kullanılmayan Qt/X11 gibi GUI bağımlılıkları asla dahil edilmez.

Kullanım: python3 scripts/fetch_mkvtoolnix_assets.py
Gereksinimler (Ubuntu runner'da hazır gelir): ar, tar, readelf, zstd, python3
"""
import gzip
import lzma
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ARCH = "aarch64"

# (base URL adayları, dağıtım adı) - main ve x11 (mkvtoolnix burada) depoları
REPOS = [
    (["https://packages.termux.dev/apt/termux-main", "https://packages-cf.termux.dev/apt/termux-main"], "stable"),
    (["https://packages.termux.dev/apt/termux-x11", "https://packages-cf.termux.dev/apt/termux-x11"], "x11"),
]

# İndeksin gösterdiği en son mkvtoolnix sürümü bozuk/boş çıkarsa denenecek bilinen sağlam sürümler
MKVTOOLNIX_FALLBACK_VERSIONS = ["99.0", "98.0", "97.0", "96.0"]
MIN_VALID_DEB_SIZE = 50_000  # bundan küçük .deb bozuk/boş kabul edilir

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "app", "src", "main", "assets", "mkvtoolnix")
WORK = tempfile.mkdtemp(prefix="mkvtoolnix_fetch_")

ALIAS = {
    "z": ["zlib", "libz"],
    "lzma": ["liblzma"],
    "bz2": ["bzip2", "libbz2"],
    "iconv": ["libiconv"],
    "stdc++": ["libc++"],
    "c++": ["libc++"],
    "c++_shared": ["libc++"],
    "gcc_s": ["libgcc"],
    "magic": ["file", "libmagic"],
    "icuuc": ["icu"], "icudata": ["icu"], "icui18n": ["icu"],
}


def log(*a):
    print(*a, flush=True)


def fetch(url: str) -> bytes:
    log(f"GET {url}")
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
    raise RuntimeError(f"Hiçbir depodan indirilemedi: {rel_paths} (son hata: {last_err})")


def parse_packages_text(text):
    pkgs, cur, cur_key = {}, {}, None
    for raw in text.split("\n"):
        if raw.strip() == "":
            if cur.get("Package"):
                pkgs[cur["Package"]] = cur
            cur, cur_key = {}, None
            continue
        if raw.startswith(" ") and cur_key:
            cur[cur_key] += "\n" + raw.strip()
            continue
        if ":" in raw:
            k, v = raw.split(":", 1)
            cur[k.strip()] = v.strip()
            cur_key = k.strip()
    if cur.get("Package"):
        pkgs[cur["Package"]] = cur
    return pkgs


def load_one_repo(bases, dist):
    data, url = fetch_first_ok(bases, [
        f"dists/{dist}/main/binary-{ARCH}/Packages.xz",
        f"dists/{dist}/main/binary-{ARCH}/Packages.gz",
        f"dists/{dist}/main/binary-{ARCH}/Packages",
    ])
    if url.endswith(".xz"):
        text = lzma.decompress(data).decode("utf-8", "replace")
    elif url.endswith(".gz"):
        text = gzip.decompress(data).decode("utf-8", "replace")
    else:
        text = data.decode("utf-8", "replace")
    pkgs = parse_packages_text(text)
    repo_base = url.split(f"/dists/{dist}/")[0]
    return pkgs, repo_base


def load_all_packages():
    """Tüm depoları okur, {paket_adı: (stanza, repo_base)} olarak birleştirir."""
    combined = {}
    for bases, dist in REPOS:
        try:
            pkgs, repo_base = load_one_repo(bases, dist)
            log(f"[{dist}] {len(pkgs)} paket bulundu. Repo: {repo_base}")
            for name, stanza in pkgs.items():
                if name not in combined:
                    combined[name] = (stanza, repo_base)
        except Exception as e:
            log(f"UYARI: '{dist}' deposu okunamadı: {e}")
    return combined


def fetch_bytes_checked(url, min_size=1):
    data = fetch(url)
    if len(data) < min_size:
        raise RuntimeError(f"dosya çok küçük/boş ({len(data)} bytes): {url}")
    return data


def download_deb(combined, name):
    """Genel amaçlı: index'teki (tek) sürümü indirir."""
    entry = combined.get(name)
    if not entry:
        return None
    stanza, repo_base = entry
    url = f"{repo_base}/{stanza['Filename']}"
    try:
        data = fetch_bytes_checked(url, 1000)
    except Exception as e:
        log(f"UYARI: {name} indirilemedi: {e}")
        return None
    path = os.path.join(WORK, f"pkg_{name}.deb")
    with open(path, "wb") as f:
        f.write(data)
    return path


def download_mkvtoolnix(combined):
    """mkvtoolnix'e özel: index'teki sürüm bozuksa (Termux'ta güncelleme build'i
    başarısız olup 0 byte yüklenmiş olabiliyor) bilinen sağlam eski sürümlere düşer."""
    entry = combined.get("mkvtoolnix")
    if not entry:
        log("HATA: 'mkvtoolnix' paketi ne main ne de x11 deposu indeksinde bulunamadı.")
        return None
    stanza, repo_base = entry

    pool_dir = "/".join(stanza["Filename"].split("/")[:-1]) if "Filename" in stanza else "pool/main/m/mkvtoolnix"

    candidates = []
    if "Filename" in stanza:
        candidates.append(stanza["Filename"])
    for v in MKVTOOLNIX_FALLBACK_VERSIONS:
        candidates.append(f"{pool_dir}/mkvtoolnix_{v}_{ARCH}.deb")

    tried = set()
    for rel in candidates:
        if rel in tried:
            continue
        tried.add(rel)
        url = f"{repo_base}/{rel}"
        try:
            data = fetch_bytes_checked(url, MIN_VALID_DEB_SIZE)
        except Exception as e:
            log(f"UYARI: {url} kullanılamadı: {e}")
            continue
        path = os.path.join(WORK, "pkg_mkvtoolnix.deb")
        with open(path, "wb") as f:
            f.write(data)
        log(f"mkvtoolnix indirildi: {url} ({len(data)} bytes)")
        return path

    log("HATA: mkvtoolnix için denenen hiçbir sürüm/URL geçerli bir .deb döndürmedi.")
    return None


def extract_deb_data(deb_path, dest):
    ar_dir = tempfile.mkdtemp(dir=WORK)
    subprocess.run(["ar", "x", os.path.abspath(deb_path)], cwd=ar_dir, check=True)
    data_file = next((os.path.join(ar_dir, f) for f in os.listdir(ar_dir) if f.startswith("data.tar")), None)
    if not data_file:
        raise RuntimeError(f"{deb_path} içinde data.tar.* yok")
    os.makedirs(dest, exist_ok=True)
    if data_file.endswith(".zst"):
        raw = os.path.join(ar_dir, "data.tar")
        subprocess.run(["zstd", "-d", "-f", data_file, "-o", raw], check=True)
        data_file = raw
    with tarfile.open(data_file, mode="r:*") as t:
        t.extractall(dest)
    return dest


def find_subdir(root, suffix):
    for dirpath, _dirs, _files in os.walk(root):
        if dirpath.replace(os.sep, "/").endswith(suffix):
            return dirpath
    return None


def needed_sonames(binpath):
    out = subprocess.run(["readelf", "-d", binpath], capture_output=True, text=True, check=True).stdout
    return [m.group(1) for m in re.finditer(r"\(NEEDED\)\s+Shared library:\s+\[([^\]]+)\]", out)]


def candidate_pkg_names(soname):
    base = re.sub(r"^lib", "", soname)
    base = re.sub(r"\.so.*$", "", base)
    cands = [f"lib{base}", base] + ALIAS.get(base, [])
    seen, result = set(), []
    for c in cands:
        if c not in seen:
            seen.add(c); result.append(c)
    return result


def real_copy(src, dst):
    shutil.copy2(os.path.realpath(src), dst)


def main():
    combined = load_all_packages()
    if not combined:
        log("HATA: hiçbir Termux deposu okunamadı.")
        sys.exit(1)

    if os.path.isdir(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(OUT_DIR, exist_ok=True)

    deb = download_mkvtoolnix(combined)
    if not deb:
        sys.exit(1)

    extracted = extract_deb_data(deb, os.path.join(WORK, "extracted_mkvtoolnix"))
    bin_dir = find_subdir(extracted, "usr/bin")
    if not bin_dir:
        log("HATA: mkvtoolnix paketinde usr/bin dizini bulunamadı.")
        sys.exit(1)

    mkvmerge_src = os.path.join(bin_dir, "mkvmerge")
    mkvextract_src = os.path.join(bin_dir, "mkvextract")
    if not (os.path.exists(mkvmerge_src) and os.path.exists(mkvextract_src)):
        log(f"HATA: mkvmerge/mkvextract {bin_dir} içinde yok. İçerik: {os.listdir(bin_dir)}")
        sys.exit(1)

    real_copy(mkvmerge_src, os.path.join(OUT_DIR, "mkvmerge"))
    real_copy(mkvextract_src, os.path.join(OUT_DIR, "mkvextract"))
    log("mkvmerge ve mkvextract kopyalandı.")

    own_lib_dir = find_subdir(extracted, "usr/lib")
    if own_lib_dir:
        for f in os.listdir(own_lib_dir):
            full = os.path.join(own_lib_dir, f)
            if os.path.isfile(full) or os.path.islink(full):
                real_copy(full, os.path.join(OUT_DIR, f))

    fetched_pkgs = {"mkvtoolnix"}
    queue = [os.path.join(OUT_DIR, "mkvmerge"), os.path.join(OUT_DIR, "mkvextract")]
    unresolved = []

    while queue:
        current = queue.pop(0)
        try:
            needed = needed_sonames(current)
        except Exception as e:
            log(f"UYARI: readelf başarısız ({current}): {e}")
            continue

        for soname in needed:
            dst = os.path.join(OUT_DIR, soname)
            if os.path.exists(dst):
                continue
            found = False
            for cand in candidate_pkg_names(soname):
                if cand in fetched_pkgs or cand not in combined:
                    continue
                try:
                    log(f"'{soname}' için deneniyor: paket '{cand}'")
                    deb2 = download_deb(combined, cand)
                    if not deb2:
                        continue
                    ex2 = extract_deb_data(deb2, os.path.join(WORK, f"extracted_{cand}"))
                    fetched_pkgs.add(cand)
                    lib_dir2 = find_subdir(ex2, "usr/lib")
                    if not lib_dir2:
                        continue
                    src_path = os.path.join(lib_dir2, soname)
                    if not os.path.exists(src_path):
                        continue
                    real_copy(src_path, dst)
                    base_prefix = soname.split(".so")[0]
                    for f in os.listdir(lib_dir2):
                        if f.startswith(base_prefix):
                            d2 = os.path.join(OUT_DIR, f)
                            fp = os.path.join(lib_dir2, f)
                            if not os.path.exists(d2) and (os.path.isfile(fp) or os.path.islink(fp)):
                                real_copy(fp, d2)
                    found = True
                    queue.append(dst)
                    break
                except Exception as e:
                    log(f"UYARI: '{cand}' paketi işlenirken hata: {e}")
            if not found:
                unresolved.append(soname)

    if unresolved:
        log("HATA: şu paylaşımlı kütüphaneler bulunamadı: " + ", ".join(sorted(set(unresolved))))
        log("Çözüm: scripts/fetch_mkvtoolnix_assets.py içindeki ALIAS sözlüğüne doğru Termux paket adını ekleyin.")
        sys.exit(1)

    with open(os.path.join(OUT_DIR, "MANIFEST.txt"), "w") as f:
        f.write("Kullanılan Termux paketleri: " + ", ".join(sorted(fetched_pkgs)) + "\n\nDosyalar:\n")
        for fn in sorted(os.listdir(OUT_DIR)):
            f.write(f"  {fn}\n")

    log("Tamamlandı. assets/mkvtoolnix/ içeriği:")
    for fn in sorted(os.listdir(OUT_DIR)):
        size = os.path.getsize(os.path.join(OUT_DIR, fn))
        log(f"  {fn}  ({size} bytes)")


if __name__ == "__main__":
    main()
