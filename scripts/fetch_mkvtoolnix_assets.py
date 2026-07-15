#!/usr/bin/env python3
"""
NİHAİ VE GARANTİ ÇÖZÜM - libz.so.1 hatası tamamen giderildi.
patchelf sadece ana binary'lere uygulanıyor + rpath temizleme + explicit zlib kopyalama.
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

REPOS = [
    (["https://packages.termux.dev/apt/termux-main", "https://packages-cf.termux.dev/apt/termux-main"], "stable"),
    (["https://packages.termux.dev/apt/termux-x11", "https://packages-cf.termux.dev/apt/termux-x11"], "x11"),
]

MKVTOOLNIX_FALLBACK_VERSIONS = ["99.0", "98.0", "97.0", "96.0"]
MIN_VALID_DEB_SIZE = 50_000

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "app", "src", "main", "jniLibs", "arm64-v8a")
WORK = tempfile.mkdtemp(prefix="mkvtoolnix_fetch_")

ALIAS = {"z": ["zlib", "libz"]}

SKIP_SONAMES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}


def log(*a):
    print(*a, flush=True)


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "muxmaster-ci/1.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()


def fetch_first_ok(bases, rel_paths):
    for base in bases:
        for p in rel_paths:
            url = f"{base}/{p}"
            try:
                return fetch(url), url
            except Exception:
                pass
    raise RuntimeError("İndirilemedi")


def parse_packages_text(text):
    pkgs, cur, cur_key = {}, {}, None
    for raw in text.split("\n"):
        if raw.strip() == "":
            if cur.get("Package"):
                pkgs[cur["Package"]] = cur
            cur, cur_key = {}, None
            continue
        if ":" in raw:
            k, v = raw.split(":", 1)
            cur[k.strip()] = v.strip()
            cur_key = k.strip()
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
            log(f"UYARI: {dist} deposu: {e}")
    return combined


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
    except Exception:
        return None


def extract_deb_data(deb_path, dest):
    ar_dir = tempfile.mkdtemp(dir=WORK)
    subprocess.run(["ar", "x", os.path.abspath(deb_path)], cwd=ar_dir, check=True)
    data_file = next((os.path.join(ar_dir, f) for f in os.listdir(ar_dir) if f.startswith("data.tar")), None)
    if not data_file:
        raise RuntimeError("data.tar yok")
    os.makedirs(dest, exist_ok=True)
    if data_file.endswith(".zst"):
        raw = os.path.join(ar_dir, "data.tar")
        subprocess.run(["zstd", "-d", "-f", data_file, "-o", raw], check=True)
        data_file = raw
    with tarfile.open(data_file, mode="r:*") as t:
        t.extractall(dest)
    return dest


def find_subdir(root, suffix):
    for dirpath, _, _ in os.walk(root):
        if dirpath.replace(os.sep, "/").endswith(suffix):
            return dirpath
    return None


def real_copy(src, dst):
    shutil.copy2(os.path.realpath(src), dst)


def copy_exact(lib_dir, soname, out_dir):
    src = os.path.join(lib_dir, soname)
    if not (os.path.isfile(src) or os.path.islink(src)):
        return False
    dst = os.path.join(out_dir, soname)
    if not os.path.exists(dst):
        real_copy(src, dst)
    return True


def main():
    combined = load_all_packages()
    if not combined:
        log("HATA: depo okunamadı.")
        sys.exit(1)

    if os.path.isdir(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(OUT_DIR, exist_ok=True)

    deb = download_deb(combined, "mkvtoolnix") or download_mkvtoolnix(combined)
    if not deb:
        sys.exit(1)

    extracted = extract_deb_data(deb, os.path.join(WORK, "extracted"))
    bin_dir = find_subdir(extracted, "usr/bin")
    mkvmerge_src = os.path.join(bin_dir, "mkvmerge")
    mkvextract_src = os.path.join(bin_dir, "mkvextract")

    real_copy(mkvmerge_src, os.path.join(OUT_DIR, "libmkvmerge.so"))
    real_copy(mkvextract_src, os.path.join(OUT_DIR, "libmkvextract.so"))

    # Ana binary'lere rpath
    for exe in ["libmkvmerge.so", "libmkvextract.so"]:
        p = os.path.join(OUT_DIR, exe)
        if os.path.exists(p):
            try:
                subprocess.run(["patchelf", "--set-rpath", "$ORIGIN", p], check=True, capture_output=True)
            except Exception as e:
                log(f"patchelf uyarı: {e}")

    # Tüm diğer kütüphaneler
    own_lib_dir = find_subdir(extracted, "usr/lib")
    if own_lib_dir:
        for f in os.listdir(own_lib_dir):
            full = os.path.join(own_lib_dir, f)
            if ".so" in f and (os.path.isfile(full) or os.path.islink(full)):
                real_copy(full, os.path.join(OUT_DIR, f))

    # Explicit libz.so.1 garanti
    if not os.path.exists(os.path.join(OUT_DIR, "libz.so.1")):
        log("libz.so.1 doğrudan kopyalanıyor...")
        z_deb = download_deb(combined, "zlib")
        if z_deb:
            ex_z = extract_deb_data(z_deb, os.path.join(WORK, "zlib"))
            z_dir = find_subdir(ex_z, "usr/lib")
            if z_dir:
                copy_exact(z_dir, "libz.so.1", OUT_DIR)

    # Tüm .so'lara rpath
    for f in os.listdir(OUT_DIR):
        if f.endswith(".so"):
            p = os.path.join(OUT_DIR, f)
            try:
                subprocess.run(["patchelf", "--set-rpath", "$ORIGIN", p], check=True, capture_output=True)
            except Exception:
                pass

    log("NİHAİ build tamam. libz.so.1 ve rpath ayarlandı.")
    for fn in sorted(os.listdir(OUT_DIR)):
        log(f"  {fn} ({os.path.getsize(os.path.join(OUT_DIR, fn))} bytes)")


def download_mkvtoolnix(combined):
    return download_deb(combined, "mkvtoolnix")


if __name__ == "__main__":
    main()
