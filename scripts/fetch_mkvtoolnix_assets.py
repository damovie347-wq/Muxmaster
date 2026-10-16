#!/usr/bin/env python3
"""
mkvmerge/mkvextract + exhale (gercek ISO/IEC 23003-3 USAC / xHE-AAC mp4a-40-42 encoder)
+ libopusenc (Termux resmi libopus tabanli temiz Ogg/Opus encoder) icin TUM calisma-zamani
(runtime) bagimliliklarini gercek ELF NEEDED kayitlarini okuyarak indirir, derler ve
ffmpeg-kit ile cakismamasi icin izole eder.
"""

import glob
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

SKIP_SONAMES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}

EXHALE_TARBALLS = [
    "https://gitlab.com/ecodis/exhale/-/archive/v1.2.2/exhale-v1.2.2.tar.gz",
    "https://gitlab.com/ecodis/exhale/-/archive/v1.2.1/exhale-v1.2.1.tar.gz",
    "https://gitlab.com/ecodis/exhale/-/archive/master/exhale-master.tar.gz",
    "https://github.com/dtseto/exhale/archive/refs/heads/main.tar.gz",
    "https://github.com/MoSal/exhale/archive/refs/heads/mosal_mod.tar.gz",
]

EXHALE_GIT_REPOS = [
    "https://gitlab.com/ecodis/exhale.git",
    "https://github.com/dtseto/exhale.git",
    "https://github.com/MoSal/exhale.git",
]

OPUS_WAV_ENC_CPP = r'''
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <algorithm>

extern "C" {
    struct OpusEncoder;
    OpusEncoder* opus_encoder_create(int32_t Fs, int channels, int application, int *error);
    int opus_encoder_ctl(OpusEncoder *st, int request, ...);
    int32_t opus_encode(OpusEncoder *st, const int16_t *pcm, int frame_size, unsigned char *data, int32_t max_data_bytes);
    void opus_encoder_destroy(OpusEncoder *st);
}

#define OPUS_APPLICATION_VOIP 2048
#define OPUS_APPLICATION_AUDIO 2049
#define OPUS_APPLICATION_RESTRICTED_LOWDELAY 2051
#define OPUS_SET_BITRATE_REQUEST 4002
#define OPUS_SET_VBR_REQUEST 4006
#define OPUS_SET_VBR_CONSTRAINT_REQUEST 4020
#define OPUS_SET_COMPLEXITY_REQUEST 4010
#define OPUS_SET_SIGNAL_REQUEST 4024
#define OPUS_SIGNAL_MUSIC 3002
#define OPUS_GET_LOOKAHEAD_REQUEST 4027

static uint32_t ogg_crc_table[256];
static void init_ogg_crc() {
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t r = i << 24;
        for (int j = 0; j < 8; j++) {
            r = (r & 0x80000000U) ? ((r << 1) ^ 0x04c11db7U) : (r << 1);
        }
        ogg_crc_table[i] = r;
    }
}

static uint32_t compute_ogg_crc(const uint8_t* data, size_t len) {
    uint32_t crc = 0;
    for (size_t i = 0; i < len; i++) {
        crc = (crc << 8) ^ ogg_crc_table[((crc >> 24) & 0xFF) ^ data[i]];
    }
    return crc;
}

static bool write_ogg_page(
    FILE* f,
    uint8_t header_type,
    uint64_t granule_pos,
    uint32_t serial_no,
    uint32_t& seq_no,
    const std::vector<std::vector<uint8_t>>& packets
) {
    std::vector<uint8_t> seg_table;
    size_t body_size = 0;
    for (const auto& pkt : packets) {
        size_t rem = pkt.size();
        body_size += rem;
        while (rem >= 255) {
            seg_table.push_back(255);
            rem -= 255;
        }
        seg_table.push_back(static_cast<uint8_t>(rem));
    }
    if (seg_table.size() > 255) return false;

    std::vector<uint8_t> page(27 + seg_table.size() + body_size, 0);
    memcpy(&page[0], "OggS", 4);
    page[4] = 0;
    page[5] = header_type;
    for (int i = 0; i < 8; i++) page[6 + i] = static_cast<uint8_t>((granule_pos >> (8 * i)) & 0xFF);
    for (int i = 0; i < 4; i++) page[14 + i] = static_cast<uint8_t>((serial_no >> (8 * i)) & 0xFF);
    for (int i = 0; i < 4; i++) page[18 + i] = static_cast<uint8_t>((seq_no >> (8 * i)) & 0xFF);
    seq_no++;
    page[26] = static_cast<uint8_t>(seg_table.size());
    memcpy(&page[27], seg_table.data(), seg_table.size());
    size_t offset = 27 + seg_table.size();
    for (const auto& pkt : packets) {
        memcpy(&page[offset], pkt.data(), pkt.size());
        offset += pkt.size();
    }
    uint32_t crc = compute_ogg_crc(page.data(), page.size());
    for (int i = 0; i < 4; i++) page[22 + i] = static_cast<uint8_t>((crc >> (8 * i)) & 0xFF);

    return fwrite(page.data(), 1, page.size(), f) == page.size();
}

// Usage: libopusenc.so <raw_s16le_48k_path> <out_opus_path> <channels> <bitrate_bps>
int main(int argc, char** argv) {
    if (argc < 5) return 2;
    const char* in_path = argv[1];
    const char* out_path = argv[2];
    int channels = atoi(argv[3]);
    int bitrate_bps = atoi(argv[4]);
    if (channels < 1 || channels > 2) channels = 1;
    if (bitrate_bps < 6000) bitrate_bps = 6000;
    if (bitrate_bps > 510000) bitrate_bps = 510000;

    init_ogg_crc();

    int err = 0;
    OpusEncoder* enc = opus_encoder_create(48000, channels, OPUS_APPLICATION_AUDIO, &err);
    if (!enc || err != 0) return 3;

    opus_encoder_ctl(enc, OPUS_SET_BITRATE_REQUEST, bitrate_bps);
    opus_encoder_ctl(enc, OPUS_SET_VBR_REQUEST, 1);
    opus_encoder_ctl(enc, OPUS_SET_VBR_CONSTRAINT_REQUEST, 1);
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY_REQUEST, 6);
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL_REQUEST, OPUS_SIGNAL_MUSIC);

    int32_t lookahead = 312;
    opus_encoder_ctl(enc, OPUS_GET_LOOKAHEAD_REQUEST, &lookahead);

    FILE* fin = fopen(in_path, "rb");
    if (!fin) { opus_encoder_destroy(enc); return 4; }
    fseek(fin, 0, SEEK_END);
    long total_bytes = ftell(fin);
    fseek(fin, 0, SEEK_SET);
    if (total_bytes <= 0) total_bytes = 1;

    FILE* fout = fopen(out_path, "wb");
    if (!fout) { fclose(fin); opus_encoder_destroy(enc); return 5; }

    uint32_t serial_no = 0x4D55584D;
    uint32_t seq_no = 0;

    std::vector<uint8_t> head(19, 0);
    memcpy(&head[0], "OpusHead", 8);
    head[8] = 1;
    head[9] = static_cast<uint8_t>(channels);
    uint16_t preskip = static_cast<uint16_t>(lookahead);
    head[10] = static_cast<uint8_t>(preskip & 0xFF);
    head[11] = static_cast<uint8_t>((preskip >> 8) & 0xFF);
    uint32_t sr = 48000;
    for (int i = 0; i < 4; i++) head[12 + i] = static_cast<uint8_t>((sr >> (8 * i)) & 0xFF);
    write_ogg_page(fout, 0x02, 0, serial_no, seq_no, {head});

    const char* vendor = "MuxMaster";
    uint32_t vlen = static_cast<uint32_t>(strlen(vendor));
    std::vector<uint8_t> tags(8 + 4 + vlen + 4, 0);
    memcpy(&tags[0], "OpusTags", 8);
    for (int i = 0; i < 4; i++) tags[8 + i] = static_cast<uint8_t>((vlen >> (8 * i)) & 0xFF);
    memcpy(&tags[12], vendor, vlen);
    write_ogg_page(fout, 0x00, 0, serial_no, seq_no, {tags});

    const int frame_size = 960; // 20ms at 48kHz (50 frames/sec)
    const int avg_frame_bytes = std::max(10, bitrate_bps / (50 * 8));
    const int max_frame_bytes = std::min(1275, std::max(24, (avg_frame_bytes * 15) / 10));

    std::vector<int16_t> pcm_buf(frame_size * channels, 0);
    unsigned char pkt_buf[1500];
    std::vector<std::vector<uint8_t>> page_pkts;
    uint64_t granule_pos = preskip;
    long read_bytes_total = 0;
    int last_pct = -1;

    while (true) {
        size_t want_samples = frame_size * channels;
        size_t got = fread(pcm_buf.data(), sizeof(int16_t), want_samples, fin);
        if (got == 0) break;
        read_bytes_total += got * sizeof(int16_t);
        bool is_eof = (got < want_samples);
        if (is_eof) {
            memset(pcm_buf.data() + got, 0, (want_samples - got) * sizeof(int16_t));
        }
        int32_t nb = opus_encode(enc, pcm_buf.data(), frame_size, pkt_buf, max_frame_bytes);
        if (nb < 0) { fclose(fin); fclose(fout); opus_encoder_destroy(enc); return 6; }

        granule_pos += (got / channels);
        page_pkts.emplace_back(pkt_buf, pkt_buf + nb);

        if (is_eof || page_pkts.size() >= 25) {
            int c = fgetc(fin);
            if (c == EOF) is_eof = true;
            else ungetc(c, fin);
            write_ogg_page(fout, is_eof ? 0x04 : 0x00, granule_pos, serial_no, seq_no, page_pkts);
            page_pkts.clear();
            if (is_eof) break;
        }

        int pct = static_cast<int>((read_bytes_total * 100LL) / total_bytes);
        if (pct != last_pct) {
            last_pct = pct;
            printf("progress %d%%\n", pct);
            fflush(stdout);
        }
    }
    if (!page_pkts.empty()) {
        write_ogg_page(fout, 0x04, granule_pos, serial_no, seq_no, page_pkts);
    }

    fclose(fin);
    fclose(fout);
    opus_encoder_destroy(enc);
    return 0;
}
'''


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


def find_all_files(root, filename):
    matches = []
    for dirpath, _, files in os.walk(root):
        if filename in files:
            matches.append(os.path.join(dirpath, filename))
    return matches


def is_arm64_elf(path):
    try:
        out = subprocess.run(["readelf", "-h", path], capture_output=True, text=True, check=True).stdout
    except Exception:
        return False
    return ("ELF64" in out) and ("AArch64" in out)


def find_file(root, filename):
    candidates = find_all_files(root, filename)
    for c in candidates:
        if is_arm64_elf(c):
            return c
    if candidates:
        log(f"UYARI: '{filename}' bulundu ({len(candidates)} aday) ama hicbiri ELF64/AArch64 degil.")
    return None


def real_copy(src, dst):
    shutil.copy2(os.path.realpath(src), dst)


def get_needed(elf_path):
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


def find_ndk_cxx():
    ndk_roots = []
    for env in ("ANDROID_NDK_LATEST_HOME", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK"):
        v = os.environ.get(env)
        if v and os.path.isdir(v):
            ndk_roots.append(v)
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v and os.path.isdir(v):
            for d in sorted(glob.glob(os.path.join(v, "ndk", "*")), reverse=True):
                if os.path.isdir(d):
                    ndk_roots.append(d)
            nb = os.path.join(v, "ndk-bundle")
            if os.path.isdir(nb):
                ndk_roots.append(nb)
    for fallback_sdk in ("/usr/local/lib/android/sdk", "/opt/android-sdk"):
        if os.path.isdir(fallback_sdk):
            for d in sorted(glob.glob(os.path.join(fallback_sdk, "ndk", "*")), reverse=True):
                if os.path.isdir(d):
                    ndk_roots.append(d)

    for root in ndk_roots:
        bin_dir = os.path.join(root, "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin")
        if not os.path.isdir(bin_dir):
            continue
        for api in (26, 24, 27, 28, 29, 30, 31, 33, 34, 35, 21):
            cand = os.path.join(bin_dir, f"aarch64-linux-android{api}-clang++")
            if os.path.isfile(cand) and os.access(cand, os.X_OK):
                return cand
        for cand in sorted(glob.glob(os.path.join(bin_dir, "aarch64-linux-android*-clang++"))):
            if os.path.isfile(cand) and os.access(cand, os.X_OK):
                return cand
    w = shutil.which("aarch64-linux-gnu-g++")
    if w:
        return w
    return None


def find_strip_tool():
    cands = []
    for env in ("ANDROID_NDK_LATEST_HOME", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK"):
        v = os.environ.get(env)
        if v:
            cands.append(os.path.join(v, "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin", "llvm-strip"))
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v:
            for d in sorted(glob.glob(os.path.join(v, "ndk", "*")), reverse=True):
                cands.append(os.path.join(d, "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin", "llvm-strip"))
            cands.append(os.path.join(v, "ndk-bundle", "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin", "llvm-strip"))
    for name in ("llvm-strip", "aarch64-linux-gnu-strip"):
        w = shutil.which(name)
        if w:
            cands.append(w)
    for c in cands:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return None


def has_debug_sections(path):
    try:
        out = subprocess.run(["readelf", "-S", "-W", path], capture_output=True, text=True).stdout
        return ".debug_" in out
    except Exception:
        return False


def strip_debug_all(out_dir):
    tool = find_strip_tool()
    if not tool:
        log("UYARI: strip araci bulunamadi; debug sembol temizligi atlandi.")
        return
    log(f"== Debug semboller temizleniyor ({tool}) ==")
    saved = 0
    for fn in sorted(os.listdir(out_dir)):
        path = os.path.join(out_dir, fn)
        if not os.path.isfile(path) or not has_debug_sections(path):
            continue
        tmp = path + ".stripped_tmp"
        try:
            r = subprocess.run([tool, "--strip-debug", "-o", tmp, path], capture_output=True, text=True)
            if r.returncode != 0 or not os.path.isfile(tmp):
                continue
            if get_needed(tmp) != get_needed(path):
                continue
            before = os.path.getsize(path)
            after = os.path.getsize(tmp)
            if after >= before:
                continue
            mode = os.stat(path).st_mode
            os.replace(tmp, path)
            os.chmod(path, mode)
            saved += before - after
            log(f"  {fn}: {before} -> {after} bytes")
        except Exception:
            pass
        finally:
            if os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except Exception:
                    pass
    log(f"  Toplam kazanc: {saved / (1024*1024):.1f} MB")


def download_exhale_source(dest_root):
    os.makedirs(dest_root, exist_ok=True)
    for url in EXHALE_TARBALLS:
        try:
            log(f"  exhale kaynak arsivi indiriliyor: {url}")
            data = fetch(url)
            tar_path = os.path.join(WORK, "exhale_src.tar.gz")
            with open(tar_path, "wb") as f:
                f.write(data)
            with tarfile.open(tar_path, mode="r:*") as t:
                t.extractall(dest_root)
            for dirpath, dirnames, _ in os.walk(dest_root):
                if "src" in dirnames and "include" in dirnames:
                    return dirpath
        except Exception as e:
            log(f"  UYARI: {url} indirilemedi ({e})")

    for git_url in EXHALE_GIT_REPOS:
        try:
            log(f"  exhale git klonlaniyor: {git_url}")
            clone_dir = os.path.join(dest_root, "git_exhale")
            if os.path.exists(clone_dir):
                shutil.rmtree(clone_dir)
            r = subprocess.run(["git", "clone", "--depth", "1", git_url, clone_dir], capture_output=True, text=True)
            if r.returncode == 0 and os.path.isdir(os.path.join(clone_dir, "src")):
                return clone_dir
        except Exception as e:
            log(f"  UYARI: git clone {git_url} basarisiz ({e})")
    return None


def build_exhale_binary(cxx, out_path):
    log("== Gercek xHE-AAC (USAC / mp4a-40-42) encoder (libexhale.so) derleniyor ==")
    src_root = download_exhale_source(os.path.join(WORK, "exhale_src"))
    if not src_root:
        raise RuntimeError("exhale kaynak kodu indirilemedi!")

    inc_dir = os.path.join(src_root, "include")
    lib_dir = os.path.join(src_root, "src", "lib")
    app_dir = os.path.join(src_root, "src", "app")
    cpp_files = sorted(glob.glob(os.path.join(lib_dir, "*.cpp")) + glob.glob(os.path.join(app_dir, "*.cpp")))
    if not cpp_files:
        raise RuntimeError(f"exhale icinde .cpp dosyalari bulunamadi: {src_root}")

    flag_sets = [
        ["-std=c++11", "-O3", "-ffast-math", "-funroll-loops", "-fPIE", "-pie", "-static-libstdc++", "-DNDEBUG", "-DBA_MORE_CBR=1"],
        ["-std=c++11", "-O3", "-ffast-math", "-funroll-loops", "-fPIE", "-pie", "-static-libstdc++", "-DNDEBUG"],
        ["-std=c++11", "-O2", "-fPIE", "-pie", "-DNDEBUG"],
    ]
    last_err = ""
    for flags in flag_sets:
        cmd = [cxx] + flags + [f"-I{inc_dir}", f"-I{lib_dir}", f"-I{app_dir}"] + cpp_files + ["-lm", "-o", out_path]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode == 0 and os.path.isfile(out_path) and is_arm64_elf(out_path):
            os.chmod(out_path, 0o755)
            log(f"  libexhale.so basariyla derlendi ({os.path.getsize(out_path)} bytes).")
            return
        last_err = r.stderr
    raise RuntimeError(f"libexhale.so derlenemedi: {last_err}")


def build_opusenc_binary(cxx, libopus_so_path, out_path):
    log("== Temiz Ogg/Opus encoder (libopusenc.so) derleniyor ==")
    cpp_path = os.path.join(WORK, "opus_wav_enc.cpp")
    with open(cpp_path, "w", encoding="utf-8") as f:
        f.write(OPUS_WAV_ENC_CPP)

    lib_dir = os.path.dirname(libopus_so_path)
    lib_name = os.path.basename(libopus_so_path)
    flag_sets = [
        ["-std=c++11", "-O2", "-fno-fast-math", "-fPIE", "-pie", "-static-libstdc++"],
        ["-std=c++11", "-O2", "-fno-fast-math", "-fPIE", "-pie"],
    ]
    last_err = ""
    for flags in flag_sets:
        cmd = [cxx] + flags + [cpp_path, f"-L{lib_dir}", f"-l:{lib_name}", "-lm", "-o", out_path]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode == 0 and os.path.isfile(out_path) and is_arm64_elf(out_path):
            os.chmod(out_path, 0o755)
            log(f"  libopusenc.so basariyla derlendi ({os.path.getsize(out_path)} bytes).")
            return
        last_err = r.stderr
    raise RuntimeError(f"libopusenc.so derlenemedi: {last_err}")


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

    extracted_pkg_cache = {"mkvtoolnix": extracted}

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

    # --- Android NDK C++ derleyicisini bul ---
    cxx = find_ndk_cxx()
    if not cxx:
        log("HATA: Android NDK aarch64 clang++ derleyicisi bulunamadi!")
        sys.exit(1)
    log(f"== AArch64 C++ derleyici bulundu: {cxx} ==")

    # --- 1) libexhale.so (Gercek xHE-AAC mp4a-40-42 encoder) derle ---
    exhale_dst = os.path.join(OUT_DIR, "libexhale.so")
    build_exhale_binary(cxx, exhale_dst)

    # --- 2) Termux resmi libopus.so'yu indir ve libopusenc.so'yu derle ---
    opus_pkg = contents_map.get("libopus.so", "libopus")
    opus_root = ensure_package_extracted(opus_pkg)
    if not opus_root:
        log(f"HATA: '{opus_pkg}' paketi indirilemedi.")
        sys.exit(1)
    opus_so_src = find_file(opus_root, "libopus.so")
    if not opus_so_src:
        log("HATA: libopus.so pakette bulunamadi.")
        sys.exit(1)
    opus_so_dst = os.path.join(OUT_DIR, "libopus.so")
    real_copy(opus_so_src, opus_so_dst)
    log(f"  + libopus.so (paket: {opus_pkg})")

    opusenc_dst = os.path.join(OUT_DIR, "libopusenc.so")
    build_opusenc_binary(cxx, opus_so_dst, opusenc_dst)

    # --- Ozyinemeli bagimlilik cozumlemesi ---
    log("== Calisma-zamani bagimliliklari ozyinemeli olarak cozumleniyor ==")
    ENTRY_POINTS = {"libmkvmerge.so", "libmkvextract.so", "libexhale.so", "libopusenc.so"}
    resolved_sonames = set(ENTRY_POINTS) | {"libopus.so"}
    unresolved_sonames = set()
    queue = [os.path.join(OUT_DIR, fn) for fn in resolved_sonames]
    processed_files = set()

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

    # --- Ilk dogrulama ---
    log("== Ilk dogrulama: tum bagimliliklar karsilaniyor mu? ==")
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
        sys.exit(1)

    strip_debug_all(OUT_DIR)

    log("== ffmpeg-kit ile isim cakismasini onlemek icin bagimliliklar izole ediliyor ==")
    PREFIX = "muxtn_"

    def strip_so_version(fn):
        idx = fn.find(".so")
        if idx == -1:
            return fn
        return fn[:idx + 3]

    all_files_before = sorted(present)
    needed_before = {fn: get_needed(os.path.join(OUT_DIR, fn)) for fn in all_files_before}

    rename_map = {}
    used_targets = set(ENTRY_POINTS)
    for fn in all_files_before:
        if fn in ENTRY_POINTS:
            continue
        normalized = strip_so_version(fn)
        core = normalized[3:] if normalized.startswith("lib") else normalized
        new_name = f"lib{PREFIX}{core}"
        if new_name in used_targets:
            log(f"HATA: normalize sonrasi isim cakismasi: '{fn}' -> '{new_name}' zaten kullanimda.")
            sys.exit(1)
        used_targets.add(new_name)
        rename_map[fn] = new_name

    for old_name, new_name in rename_map.items():
        os.rename(os.path.join(OUT_DIR, old_name), os.path.join(OUT_DIR, new_name))
        try:
            subprocess.run(["patchelf", "--set-soname", new_name, os.path.join(OUT_DIR, new_name)],
                            check=True, capture_output=True)
        except Exception as e:
            log(f"UYARI: {new_name} icin set-soname basarisiz: {e}")

    for fn in all_files_before:
        current_path = os.path.join(OUT_DIR, rename_map.get(fn, fn))
        for old_needed in needed_before[fn]:
            if old_needed in rename_map:
                try:
                    subprocess.run(
                        ["patchelf", "--replace-needed", old_needed, rename_map[old_needed], current_path],
                        check=True, capture_output=True
                    )
                except Exception as e:
                    log(f"HATA: {current_path} icinde {old_needed} -> {rename_map[old_needed]} guncellenemedi: {e}")
                    sys.exit(1)

    for old_name, new_name in sorted(rename_map.items()):
        log(f"  {old_name}  ->  {new_name}")

    for fn in os.listdir(OUT_DIR):
        if not fn.endswith(".so"):
            log(f"HATA: '{fn}' hala '.so' ile bitmiyor.")
            sys.exit(1)

    log("== rpath=$ORIGIN tum .so dosyalarina yaziliyor ==")
    for fn in os.listdir(OUT_DIR):
        set_rpath_origin(os.path.join(OUT_DIR, fn))

    log("== Son dogrulama: yeniden adlandirma sonrasi butunluk kontrolu ==")
    present_final = set(os.listdir(OUT_DIR))
    final_missing = set()
    for fn in sorted(present_final):
        for soname in get_needed(os.path.join(OUT_DIR, fn)):
            if soname in SKIP_SONAMES:
                continue
            if soname not in present_final:
                final_missing.add(soname)

    if final_missing:
        log("HATA: yeniden adlandirma sonrasi asagidaki bagimliliklar hala eksik:")
        for s in sorted(final_missing):
            log(f"    - {s}")
        sys.exit(1)

    log("== TAMAM: tum bagimliliklar cozumlendi ve ffmpeg-kit'ten izole edildi ==")
    total_size = 0
    for fn in sorted(present_final):
        p = os.path.join(OUT_DIR, fn)
        sz = os.path.getsize(p)
        total_size += sz
        log(f"  {fn} ({sz} bytes)")
    log(f"Toplam: {len(present_final)} dosya, {total_size / (1024*1024):.1f} MB")


if __name__ == "__main__":
    main()
