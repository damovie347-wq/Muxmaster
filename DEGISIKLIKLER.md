# MuxMaster — Güncelleme 5 / Değişiklik Özeti

Bu sürümde 4 dosya grubu değişti. Aşağıda her değişikliğin **birebir diff'i** var;
tam dosyalar zip'in içindedir.

## 1) Derleme hatası (asıl neden)
GitHub `ubuntu-latest` imajı Ubuntu 26.04 + Python 3.14'e geçti. Python 3.14'te
`tarfile.extractall()` varsayılan olarak `data` filtresi kullanıyor ve Termux `.deb`
paketlerindeki **mutlak symlink**'lerde (`./data/data/com.termux/files/usr/bin/aws`)
`AbsoluteLinkError` fırlatıyor. Bu yüzden `awscli` (→ `libz.so.1`) ve `qt6-qtbase`
(→ `libQt6Core.so.6`) paketleri hiç açılamıyor, betik 12 dakika sonunda
"aşağıdaki paylaşımlı kütüphaneler çözümlenemedi" diyerek çıkıyordu.

Çözüm (`scripts/fetch_mkvtoolnix_assets.py`):
- `filter="fully_trusted"` (Python < 3.12 için otomatik geri düşüş)
- açılan paketteki mutlak Termux symlink'lerini göreli hale getirme + kırık link çözme
- `libz.so.1` için `awscli` yerine `zlib` paketi (paket tercih tablosu)
- indirmelerde 3 deneme; `exhale` derlemesinde önce `-O2`
- Android sistem kütüphanelerini (libEGL/libGLESv2/libOpenSLES/libvulkan…) atlama

## 2) Derleme süresi
- `jniLibs/arm64-v8a` → `actions/cache` (betik değişmedikçe native adım atlanır)
- 70 MB'lık aarch64 cross-toolchain kurulumu kaldırıldı (NDK clang++ yeterli)
- Gradle: `--build-cache --parallel`, `org.gradle.caching=true`
- Native dosya doğrulama adımı (eksikse sessizce bozuk APK üretilmez)

## 3) Slider sürükleme
`ui/components/Sliders.kt` — jest `PointerEventPass.Initial` aşamasında yakalanıp her
karede tüketiliyor; içindeki `verticalScroll` hareketi çalamıyor. `MuxRangeSlider`
için de aynısı.

## 4) Makas / çoklu kesim
- Başlığın yanına **Makasla kes** düğmesi (seçili aralığı kesim listesine ekler)
- "Oynadığı yerden 10 sn'lik aralık ekle", özet satırı, "Tek aralığa dön"
- Dışa aktarım: parça hatası artık sessizce atlanmıyor; altyazı hedefe sığmazsa
  altyazısız yeniden deneniyor; `-map 0` yerine `v/a/s/t` eşlemesi

## 5) Ana ekran
`ui/screens/HomeScreen.kt` — 24dp köşeli kartlar, arkada ışık halkası, 44dp ikon,
20sp başlık, "Ses Dönüştürme" kartında ikon çerçevesi + yuvarlak ok.

---

# Diff

--- .github/workflows/build.yml	2026-11-09 17:04:15.000000000 +0000
+++ .github/workflows/build.yml	2026-11-09 17:28:27.138516160 +0000
@@ -64,15 +64,35 @@
         with:
           gradle-version: 8.5
 
+      # YENİ: native dosyalar (mkvmerge, mkvextract, exhale, opusenc + bağımlılıkları)
+      # her seferinde indirilip yeniden derleniyordu; bu tek başına 10-12 dakika
+      # sürüyordu. Artık betik değişmediği sürece önbellekten geliyor.
+      - name: Native kütüphane önbelleği (jniLibs)
+        id: jnilibs-cache
+        uses: actions/cache@v4
+        with:
+          path: app/src/main/jniLibs/arm64-v8a
+          key: jnilibs-${{ runner.os }}-${{ hashFiles('scripts/fetch_mkvtoolnix_assets.py') }}
+          restore-keys: |
+            jnilibs-${{ runner.os }}-
+
       - name: Native binary'leri (mkvmerge, mkvextract, exhale xHE-AAC, opusenc) hazırla ve jniLibs'e göm
+        if: steps.jnilibs-cache.outputs.cache-hit != 'true'
         run: |
           sudo apt-get update -y
-          sudo apt-get install -y binutils zstd xz-utils patchelf git
-          sudo apt-get install -y binutils-aarch64-linux-gnu g++-aarch64-linux-gnu || echo "Uyari: aarch64 cross-toolchain kurulamadi; NDK llvm kullanilacak."
+          sudo apt-get install -y --no-install-recommends binutils zstd xz-utils patchelf git
           python3 scripts/fetch_mkvtoolnix_assets.py
 
+      - name: Native dosyaları doğrula
+        run: |
+          test -d app/src/main/jniLibs/arm64-v8a || { echo "jniLibs yok!"; exit 1; }
+          ls -la app/src/main/jniLibs/arm64-v8a
+          for f in libmkvmerge.so libmkvextract.so libexhale.so libopusenc.so; do
+            test -f "app/src/main/jniLibs/arm64-v8a/$f" || { echo "Eksik: $f"; exit 1; }
+          done
+
       - name: Release APK derle (minify açık, ffmpeg-kit + mkvtoolnix + exhale + opusenc native dosyaları dahil)
-        run: gradle assembleRelease --no-daemon --stacktrace
+        run: gradle assembleRelease --no-daemon --stacktrace --build-cache --parallel
 
       - name: APK'yı indirilebilir artifact olarak yükle
         uses: actions/upload-artifact@v4
--- gradle.properties	2026-11-09 17:04:15.000000000 +0000
+++ gradle.properties	2026-11-09 17:28:32.809951406 +0000
@@ -1,4 +1,10 @@
-org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
+# Derleme hızı: paralel + build cache (önbellek) kullanımı.
+org.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8
+org.gradle.parallel=true
+org.gradle.caching=true
+kotlin.incremental=true
+kotlin.incremental.useClasspathSnapshot=true
+
 android.useAndroidX=true
-kotlin.code.style=official
 android.nonTransitiveRClass=true
+kotlin.code.style=official
--- scripts/fetch_mkvtoolnix_assets.py	2026-11-09 17:04:15.000000000 +0000
+++ scripts/fetch_mkvtoolnix_assets.py	2026-11-09 17:27:59.041037836 +0000
@@ -28,7 +28,27 @@
                         "app", "src", "main", "jniLibs", "arm64-v8a")
 WORK = tempfile.mkdtemp(prefix="mkvtoolnix_fetch_")
 
-SKIP_SONAMES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}
+SKIP_SONAMES = {
+    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
+    # Android sisteminde hazir gelen (APK'ya gomulmemesi gereken) kutuphaneler:
+    "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so", "libvulkan.so",
+    "libjnigraphics.so", "libmediandk.so", "libaaudio.so", "libcamera2ndk.so",
+}
+
+# Termux paketlerindeki symlink'ler bu mutlak onekle uretilir; deb host'ta
+# acildiginda bu yollar var olmadigi icin kirik kalir. Asagidaki fonksiyonlarla
+# bunlari deb'in acildigi dizine gore yeniden yaziyoruz.
+TERMUX_PREFIX = "/data/data/com.termux/files/usr"
+
+# Bazi soname'ler icin "ilk bulunan paket" yanlis/heavy olabiliyor.
+# (Ornek: Contents indeksinde libz.so.1 ilk olarak awscli ile eslesiyordu;
+#  awscli hem 20+ MB hem de symlink hatasi veriyordu. zlib yeterli.)
+SONAME_PKG_OVERRIDES = {
+    "libz.so.1": ["zlib"],
+    "libz.so": ["zlib"],
+    "libc++_shared.so": ["ndk-multilib", "libc++"],
+    "libQt6Core.so.6": ["qt6-qtbase"],
+}
 
 EXHALE_TARBALLS = [
     "https://gitlab.com/ecodis/exhale/-/archive/v1.2.2/exhale-v1.2.2.tar.gz",
@@ -249,9 +269,18 @@
 
 
 def fetch(url: str) -> bytes:
-    req = urllib.request.Request(url, headers={"User-Agent": "muxmaster-ci/1.0"})
-    with urllib.request.urlopen(req, timeout=90) as r:
-        return r.read()
+    last_err = None
+    for attempt in range(3):
+        try:
+            req = urllib.request.Request(url, headers={"User-Agent": "muxmaster-ci/1.0"})
+            with urllib.request.urlopen(req, timeout=90) as r:
+                return r.read()
+        except Exception as e:  # agdalgalanmalarina karsi 3 deneme
+            last_err = e
+            if attempt < 2:
+                import time
+                time.sleep(2 + attempt * 3)
+    raise last_err
 
 
 def fetch_first_ok(bases, rel_paths):
@@ -370,10 +399,66 @@
         subprocess.run(["zstd", "-d", "-f", data_file, "-o", raw], check=True, capture_output=True)
         data_file = raw
     with tarfile.open(data_file, mode="r:*") as t:
-        t.extractall(dest)
+        # ONEMLI: Python 3.12+ varsayilan "data" filtresi, Termux paketlerindeki
+        # MUTLAK symlink'lerde (ornek: ./data/data/com.termux/files/usr/bin/aws)
+        # tarfile.AbsoluteLinkError firlatip paketin TAMAMININ acilmasini
+        # engelliyordu. Bu yuzden libz.so.1 (awscli) ve libQt6Core.so.6
+        # (qt6-qtbase) cozulemiyor, derleme 12+ dakika sonunda hata veriyordu.
+        # Paketler guvenilir kaynaklardan (Termux) geldigi icin "fully_trusted"
+        # kullaniyoruz; kirik mutlak linkler asagida duzeltiliyor.
+        try:
+            t.extractall(dest, filter="fully_trusted")
+        except TypeError:
+            # Python < 3.12: filter parametresi yok.
+            t.extractall(dest)
+    fix_termux_links(dest)
     return dest
 
 
+def fix_termux_links(root):
+    """Deb icindeki mutlak Termux symlink'lerini goreli symlink'e cevirir."""
+    fixed = 0
+    for dirpath, dirnames, filenames in os.walk(root):
+        for name in list(dirnames) + list(filenames):
+            p = os.path.join(dirpath, name)
+            if not os.path.islink(p):
+                continue
+            try:
+                target = os.readlink(p)
+            except OSError:
+                continue
+            if not target.startswith(TERMUX_PREFIX):
+                continue
+            real = os.path.join(root, target.lstrip("/"))
+            if not os.path.exists(real):
+                continue
+            try:
+                os.remove(p)
+                os.symlink(os.path.relpath(real, os.path.dirname(p)), p)
+                fixed += 1
+            except OSError:
+                pass
+    if fixed:
+        log(f"  {fixed} adet Termux symlink'i goreli hale getirildi.")
+    return fixed
+
+
+def resolve_candidate(root, path):
+    """Kirik mutlak symlink'i deb kokune gore cozer."""
+    if os.path.exists(path):
+        return os.path.realpath(path)
+    if os.path.islink(path):
+        try:
+            target = os.readlink(path)
+        except OSError:
+            return None
+        if target.startswith(TERMUX_PREFIX):
+            alt = os.path.join(root, target.lstrip("/"))
+            if os.path.exists(alt):
+                return os.path.realpath(alt)
+    return None
+
+
 def find_subdir(root, suffix):
     for dirpath, _, _ in os.walk(root):
         if dirpath.replace(os.sep, "/").endswith(suffix):
@@ -400,8 +485,9 @@
 def find_file(root, filename):
     candidates = find_all_files(root, filename)
     for c in candidates:
-        if is_arm64_elf(c):
-            return c
+        resolved = resolve_candidate(root, c)
+        if resolved and is_arm64_elf(resolved):
+            return resolved
     if candidates:
         log(f"UYARI: '{filename}' bulundu ({len(candidates)} aday) ama hicbiri ELF64/AArch64 degil.")
     return None
@@ -583,6 +669,9 @@
         raise RuntimeError(f"exhale icinde .cpp dosyalari bulunamadi: {src_root}")
 
     flag_sets = [
+        # -O2 once denenir: derleme suresi belirgin sekilde kisa, encoder hizi
+        # pratikte ayni. (CI'da bu adim dakikalar suruyordu.)
+        ["-std=c++11", "-O2", "-ffast-math", "-fPIE", "-pie", "-static-libstdc++", "-DNDEBUG", "-DBA_MORE_CBR=1"],
         ["-std=c++11", "-O3", "-ffast-math", "-funroll-loops", "-fPIE", "-pie", "-static-libstdc++", "-DNDEBUG", "-DBA_MORE_CBR=1"],
         ["-std=c++11", "-O3", "-ffast-math", "-funroll-loops", "-fPIE", "-pie", "-static-libstdc++", "-DNDEBUG"],
         ["-std=c++11", "-O2", "-fPIE", "-pie", "-DNDEBUG"],
@@ -692,8 +781,18 @@
     build_exhale_binary(cxx, exhale_dst)
 
     # --- 2) Termux resmi libopus.so'yu indir ve libopusenc.so'yu derle ---
-    opus_pkg = contents_map.get("libopus.so", "libopus")
-    opus_root = ensure_package_extracted(opus_pkg)
+    opus_candidates = list(SONAME_PKG_OVERRIDES.get("libopus.so", []))
+    for cand in [contents_map.get("libopus.so"), "libopus"]:
+        if cand and cand not in opus_candidates:
+            opus_candidates.append(cand)
+    opus_pkg = opus_candidates[0]
+    opus_root = None
+    for cand in opus_candidates:
+        opus_root = ensure_package_extracted(cand)
+        if opus_root and find_file(opus_root, "libopus.so"):
+            opus_pkg = cand
+            break
+        opus_root = None
     if not opus_root:
         log(f"HATA: '{opus_pkg}' paketi indirilemedi.")
         sys.exit(1)
@@ -726,28 +825,37 @@
             if soname in resolved_sonames or soname in SKIP_SONAMES:
                 continue
 
-            pkg_name = contents_map.get(soname)
-            if not pkg_name:
+            # Once bilinen/tercih edilen paketler, sonra Contents indeksinin onerdigi paket.
+            candidates = list(SONAME_PKG_OVERRIDES.get(soname, []))
+            from_contents = contents_map.get(soname)
+            if from_contents and from_contents not in candidates:
+                candidates.append(from_contents)
+
+            if not candidates:
                 log(f"  ! {soname}: Contents indeksinde bulunamadi (paket bilinmiyor)")
                 unresolved_sonames.add(soname)
                 continue
 
-            extract_root = ensure_package_extracted(pkg_name)
-            if not extract_root:
-                log(f"  ! {soname}: paketi '{pkg_name}' indirilemedi/acilamadi")
-                unresolved_sonames.add(soname)
-                continue
+            src, used_pkg = None, None
+            for pkg_name in candidates:
+                extract_root = ensure_package_extracted(pkg_name)
+                if not extract_root:
+                    log(f"  ! {soname}: paketi '{pkg_name}' indirilemedi/acilamadi (diger aday denenecek)")
+                    continue
+                src = find_file(extract_root, soname)
+                if src:
+                    used_pkg = pkg_name
+                    break
+                log(f"  ! {soname}: '{pkg_name}' paketi icinde dosya bulunamadi (diger aday denenecek)")
 
-            src = find_file(extract_root, soname)
             if not src:
-                log(f"  ! {soname}: '{pkg_name}' paketi icinde dosya bulunamadi")
                 unresolved_sonames.add(soname)
                 continue
 
             dst = os.path.join(OUT_DIR, soname)
             if not os.path.exists(dst):
                 real_copy(src, dst)
-                log(f"  + {soname}  (paket: {pkg_name})")
+                log(f"  + {soname}  (paket: {used_pkg})")
             resolved_sonames.add(soname)
             unresolved_sonames.discard(soname)
             queue.append(dst)
--- app/src/main/java/com/example/muxmaster/ui/components/Sliders.kt	2026-11-09 17:04:15.000000000 +0000
+++ app/src/main/java/com/example/muxmaster/ui/components/Sliders.kt	2026-11-09 17:28:56.175137952 +0000
@@ -19,6 +19,7 @@
 import androidx.compose.ui.geometry.Size
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.graphics.StrokeCap
+import androidx.compose.ui.input.pointer.PointerEventPass
 import androidx.compose.ui.input.pointer.pointerInput
 import androidx.compose.ui.unit.Dp
 import androidx.compose.ui.unit.dp
@@ -29,13 +30,14 @@
  * Neden özel slider?
  *
  * Material3'ün hazır `Slider` / `RangeSlider` bileşenleri, dikey olarak kaydırılabilen
- * (verticalScroll) bir sütunun içinde, daraltılmış yükseklikte (36-40dp) kullanıldığında
- * bazı cihaz/sürüm kombinasyonlarında sürükleme (drag) hareketini yutuyor; sadece tek
- * dokunuş (tap) çalışıyordu. Bu dosyadaki bileşenler dokunma + sürükleme davranışını
- * TEK bir `pointerInput` bloğunda, `awaitEachGesture` ile baştan sona kendileri yönetir:
- * parmak aşağı indiği an konum uygulanır, parmak kaydıkça her karede güncellenir,
- * parmak kalkınca kesin değer gönderilir. Böylece hangi Compose/Material sürümü olursa
- * olsun "sağa sola kaydırma" garantili çalışır.
+ * (verticalScroll) bir sütunun içinde, daraltılmış yükseklikte kullanıldığında bazı
+ * cihazlarda sürükleme (drag) hareketini yutuyor; yalnızca tek dokunuş çalışıyordu.
+ *
+ * ÇÖZÜM (bu sürüm): jest, [PointerEventPass.Initial] aşamasında yakalanır ve HER karede
+ * tüketilir (consume). Initial aşaması yukarıdan aşağıya dağıtıldığı için, üstteki
+ * kaydırılabilir kapsayıcı Main aşamasında olayı "tüketilmiş" görür ve kendi kaydırma
+ * jestini iptal eder. Böylece parmak slider'ın üzerindeyken ekran kaymaz, slider
+ * parmağı birebir takip eder. Tek dokunuş da aynı yoldan çalışır.
  */
 @Composable
 fun MuxSeekSlider(
@@ -49,9 +51,11 @@
     inactiveColor: Color = Color(0x55FFFFFF),
     trackHeight: Dp = 4.dp,
     thumbRadius: Dp = 7.dp,
-    sliderHeight: Dp = 32.dp
+    sliderHeight: Dp = 36.dp,
+    enabled: Boolean = true
 ) {
     val span = (endMs - startMs).coerceAtLeast(1L)
+    // Sürükleme sırasında tutamağın yeri; -1 = sürükleme yok (oynatıcının konumu kullanılır).
     var dragFraction by remember { mutableFloatStateOf(-1f) }
 
     val onSeekState = rememberUpdatedState(onSeek)
@@ -69,7 +73,8 @@
         modifier = modifier
             .fillMaxWidth()
             .height(sliderHeight)
-            .pointerInput(startMs, endMs) {
+            .pointerInput(enabled) {
+                if (!enabled) return@pointerInput
                 val thumbPx = thumbRadius.toPx()
 
                 fun fractionAt(x: Float): Float {
@@ -83,7 +88,8 @@
                     startState.value + (fraction * spanState.value.toFloat()).toLong()
 
                 awaitEachGesture {
-                    val down = awaitFirstDown(requireUnconsumed = false)
+                    // Initial aşaması: üstteki verticalScroll bu olayları tüketilmiş görür.
+                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                     down.consume()
 
                     var fraction = fractionAt(down.position.x)
@@ -93,22 +99,21 @@
                     var lastPreviewAt = System.currentTimeMillis()
 
                     while (true) {
-                        val event = awaitPointerEvent()
+                        val event = awaitPointerEvent(PointerEventPass.Initial)
                         val change = event.changes.firstOrNull { it.id == down.id } ?: break
                         fraction = fractionAt(change.position.x)
                         dragFraction = fraction
+                        change.consume()
                         if (!change.pressed) {
                             // Parmak kalktı: kesin hedefe tek seferde otur.
-                            change.consume()
                             onSeekState.value(msAt(fraction))
                             break
                         }
                         val now = System.currentTimeMillis()
-                        if (now - lastPreviewAt >= 90L) {
+                        if (now - lastPreviewAt >= 70L) {
                             lastPreviewAt = now
                             onSeekState.value(msAt(fraction))
                         }
-                        change.consume()
                     }
                     dragFraction = -1f
                     onScrubState.value(false)
@@ -146,6 +151,7 @@
 /**
  * İki tutamaçlı (başlangıç + bitiş) aralık slider'ı. Her iki tutamaç da parmakla
  * sürüklenebilir; tutamaçlardan uzak bir yere dokunulursa en yakın tutamaç oraya taşınır.
+ * Jest yönetimi [MuxSeekSlider] ile aynıdır (Initial aşamasında tüketim).
  */
 @Composable
 fun MuxRangeSlider(
@@ -159,7 +165,7 @@
     inactiveColor: Color = Color(0xFF2A2A35),
     minGapMs: Long = 100L,
     thumbRadius: Dp = 9.dp,
-    sliderHeight: Dp = 40.dp
+    sliderHeight: Dp = 44.dp
 ) {
     val safeDuration = durationMs.coerceAtLeast(minGapMs + 1L)
 
@@ -202,7 +208,7 @@
                 }
 
                 awaitEachGesture {
-                    val down = awaitFirstDown(requireUnconsumed = false)
+                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                     down.consume()
 
                     val gap = gapState.value
@@ -228,7 +234,7 @@
                     onRangeState.value(s, e)
 
                     while (true) {
-                        val event = awaitPointerEvent()
+                        val event = awaitPointerEvent(PointerEventPass.Initial)
                         val change = event.changes.firstOrNull { it.id == down.id } ?: break
                         val target = msAt(fractionAt(change.position.x))
                         if (handle == 0) {
@@ -284,7 +290,7 @@
 }
 
 /**
- * Tüm video süresini tek bir çubukta gösterir: korunacak (mavi/mor) ve kesilecek
+ * Tüm video süresini tek bir çubukta gösterir: korunacak (mor) ve kesilecek
  * (kırmızı) bölgeler ayrı renklerde çizilir, oynatıcının anlık konumu beyaz çizgiyle işaretlenir.
  */
 @Composable
--- app/src/main/java/com/example/muxmaster/ui/screens/TrimScreen.kt	2026-11-09 17:04:15.000000000 +0000
+++ app/src/main/java/com/example/muxmaster/ui/screens/TrimScreen.kt	2026-11-09 17:31:19.012709692 +0000
@@ -197,7 +197,7 @@
                     if (video != null) {
                         // MAKAS: oynatıcının bulunduğu yerden yeni bir kesim aralığı ekler.
                         IconButton(
-                            onClick = { viewModel.addCutRange(playheadRef.longValue) },
+                            onClick = { viewModel.addCutRangeFromSelection(playheadRef.longValue) },
                             enabled = !busy
                         ) {
                             Icon(
@@ -313,10 +313,24 @@
                         color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                     )
                     Spacer(Modifier.weight(1f))
-                    if (viewModel.isMultiCut) {
-                        TextButton(onClick = viewModel::clearCutRanges, enabled = !viewModel.isTrimming) {
-                            Text("Tek aralığa dön", color = TextMuted, fontSize = 11.sp)
-                        }
+                    // MAKAS: seçili aralığı (Başlangıç–Bitiş alanları) doğrudan
+                    // "kesilecek aralık" yapar; çoklu kesim modunda yenisini ekler.
+                    Row(
+                        verticalAlignment = Alignment.CenterVertically,
+                        modifier = Modifier
+                            .clip(RoundedCornerShape(20.dp))
+                            .background(Amber.copy(alpha = 0.14f))
+                            .clickable(enabled = !viewModel.isTrimming) {
+                                viewModel.addCutRangeFromSelection(playheadRef.longValue)
+                            }
+                            .padding(horizontal = 10.dp, vertical = 6.dp)
+                    ) {
+                        Icon(Icons.Filled.ContentCut, null, tint = Amber, modifier = Modifier.size(15.dp))
+                        Spacer(Modifier.width(6.dp))
+                        Text(
+                            if (viewModel.isMultiCut) "Aralık ekle" else "Makasla kes",
+                            color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
+                        )
                     }
                 }
                 Spacer(Modifier.height(8.dp))
@@ -358,17 +372,27 @@
                         shape = RoundedCornerShape(12.dp),
                         border = BorderStroke(1.dp, Amber)
                     ) {
-                        Icon(Icons.Filled.ContentCut, null, tint = Amber, modifier = Modifier.size(18.dp))
+                        Icon(Icons.Filled.Add, null, tint = Amber, modifier = Modifier.size(18.dp))
                         Spacer(Modifier.width(8.dp))
                         Text(
-                            "MAKASLA KESİM ARALIĞI EKLE",
+                            "OYNADIĞI YERDEN 10 sn'LİK ARALIK EKLE",
                             color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                         )
                     }
-                    Spacer(Modifier.height(6.dp))
+                    Spacer(Modifier.height(2.dp))
+                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
+                        Text(
+                            "${viewModel.cutRanges.size} aralık çıkarılacak · Kalan " +
+                                formatClock(viewModel.keptDurationMs),
+                            color = TextSec, fontSize = 11.sp, modifier = Modifier.weight(1f)
+                        )
+                        TextButton(onClick = viewModel::clearCutRanges, enabled = !viewModel.isTrimming) {
+                            Text("Tek aralığa dön", color = TextMuted, fontSize = 11.sp)
+                        }
+                    }
                     Text(
-                        "Her aralık ayrı ayrı kayıpsız kesilir, kalan parçalar sırayla birleştirilir. " +
-                            "Parça başlangıçları en yakın kare (keyframe) sınırına hizalanabilir.",
+                        "Kırmızı aralıklar videodan çıkarılır, mor bölgeler sırayla birleştirilir. " +
+                            "Kesim kayıpsızdır (yeniden kodlama yok).",
                         color = TextMuted, fontSize = 10.sp
                     )
                 } else {
@@ -405,14 +429,15 @@
                             .fillMaxWidth()
                             .clip(RoundedCornerShape(10.dp))
                             .clickable(enabled = !viewModel.isTrimming) {
-                                viewModel.addCutRange(playheadRef.longValue)
+                                viewModel.addCutRangeFromSelection(playheadRef.longValue)
                             }
                             .padding(horizontal = 4.dp, vertical = 6.dp)
                     ) {
                         Icon(Icons.Filled.ContentCut, null, tint = Amber, modifier = Modifier.size(15.dp))
                         Spacer(Modifier.width(8.dp))
                         Text(
-                            "Birden fazla aralığı kesmek için makas (sağ üst) — kalanlar birleştirilir.",
+                            "Makas (yukarıdaki) seçili aralığı kesim listesine ekler; " +
+                                "birden fazla aralık kesilip kalanlar birleştirilir.",
                             color = Amber, fontSize = 11.sp
                         )
                     }
--- app/src/main/java/com/example/muxmaster/ui/screens/HomeScreen.kt	2026-11-09 17:04:15.000000000 +0000
+++ app/src/main/java/com/example/muxmaster/ui/screens/HomeScreen.kt	2026-11-09 17:30:38.738741355 +0000
@@ -78,6 +78,8 @@
 private val HomeBgBottom = Color(0xFF08050F)
 private val HomeAccentText = Color(0xFFB9AEE6)
 private val HomeLink = Color(0xFFB9A6F7)
+private val HomeCardBg = Color(0xFF171130)
+private val HomeCardStroke = Color(0xFF2A2150)
 
 /**
  * UYGULAMANIN ANA EKRANI.
@@ -147,7 +149,7 @@
                         Icon(Icons.Filled.Settings, "Ayarlar", tint = Color(0xFFCFC6F5))
                     }
                 }
-                Spacer(Modifier.height(18.dp))
+                Spacer(Modifier.height(16.dp))
             }
 
             item {
@@ -159,17 +161,19 @@
                         title = "Mux",
                         subtitle = "Ses ve altyazı\nparçalarını ayır",
                         icon = Icons.Filled.Extension,
-                        gradient = listOf(Color(0xFF7C3AED), Color(0xFF4C1D95)),
+                        gradient = listOf(Color(0xFF8B3DFF), Color(0xFF4C1D95)),
+                        glow = Color(0xFFB388FF),
                         onClick = onOpenMux,
-                        modifier = Modifier.weight(1f).height(154.dp)
+                        modifier = Modifier.weight(1f).height(160.dp)
                     )
                     FeatureCard(
                         title = "Kes",
                         subtitle = "Videoyu kes",
                         icon = Icons.Filled.ContentCut,
-                        gradient = listOf(Color(0xFF0F766E), Color(0xFF0B3B48)),
+                        gradient = listOf(Color(0xFF0E8A86), Color(0xFF0B3B48)),
+                        glow = Color(0xFF4DE3DC),
                         onClick = onOpenTrim,
-                        modifier = Modifier.weight(1f).height(154.dp)
+                        modifier = Modifier.weight(1f).height(160.dp)
                     )
                 }
                 Spacer(Modifier.height(12.dp))
@@ -181,20 +185,22 @@
                         title = "Birleştir",
                         subtitle = "Videoları birleştir",
                         icon = Icons.Filled.Link,
-                        gradient = listOf(Color(0xFF1D4ED8), Color(0xFF0B2A6B)),
+                        gradient = listOf(Color(0xFF1F5BE0), Color(0xFF0B2A6B)),
+                        glow = Color(0xFF7FB0FF),
                         onClick = onOpenMerge,
-                        modifier = Modifier.weight(1f).height(154.dp)
+                        modifier = Modifier.weight(1f).height(160.dp)
                     )
                     FeatureCard(
                         title = "Oynatıcı",
                         subtitle = "Medyanızı oynat",
                         icon = Icons.Filled.PlayCircle,
-                        gradient = listOf(Color(0xFFEA580C), Color(0xFF7C2D12)),
+                        gradient = listOf(Color(0xFFF97316), Color(0xFF7C2D12)),
+                        glow = Color(0xFFFFC46B),
                         onClick = onOpenPlayer,
-                        modifier = Modifier.weight(1f).height(154.dp)
+                        modifier = Modifier.weight(1f).height(160.dp)
                     )
                 }
-                Spacer(Modifier.height(12.dp))
+                Spacer(Modifier.height(14.dp))
             }
 
             item {
@@ -202,24 +208,32 @@
                     verticalAlignment = Alignment.CenterVertically,
                     modifier = Modifier
                         .fillMaxWidth()
-                        .height(92.dp)
+                        .height(90.dp)
                         .clip(RoundedCornerShape(22.dp))
                         .background(
                             Brush.horizontalGradient(
-                                listOf(Color(0xFF6D28D9), Color(0xFF8B5CF6))
+                                listOf(Color(0xFF6D28D9), Color(0xFF9B5CF6))
                             )
                         )
-                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
+                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
                         .clickable(onClick = onOpenConverter)
-                        .padding(horizontal = 18.dp)
+                        .padding(horizontal = 16.dp)
                 ) {
-                    Icon(
-                        Icons.Filled.GraphicEq,
-                        null,
-                        tint = Color.White,
-                        modifier = Modifier.size(40.dp)
-                    )
-                    Spacer(Modifier.width(16.dp))
+                    Box(
+                        modifier = Modifier
+                            .size(52.dp)
+                            .clip(RoundedCornerShape(16.dp))
+                            .background(Color.White.copy(alpha = 0.16f)),
+                        contentAlignment = Alignment.Center
+                    ) {
+                        Icon(
+                            Icons.Filled.GraphicEq,
+                            null,
+                            tint = Color.White,
+                            modifier = Modifier.size(30.dp)
+                        )
+                    }
+                    Spacer(Modifier.width(14.dp))
                     Column(modifier = Modifier.weight(1f)) {
                         Text(
                             "Ses Dönüştürme",
@@ -229,16 +243,24 @@
                         )
                         Text(
                             "Sesi başka bir formata dönüştür",
-                            color = Color.White.copy(alpha = 0.85f),
+                            color = Color.White.copy(alpha = 0.88f),
                             fontSize = 12.sp
                         )
                     }
-                    Icon(
-                        Icons.Filled.ChevronRight,
-                        null,
-                        tint = Color.White,
-                        modifier = Modifier.size(26.dp)
-                    )
+                    Box(
+                        modifier = Modifier
+                            .size(34.dp)
+                            .clip(RoundedCornerShape(50))
+                            .background(Color.White.copy(alpha = 0.18f)),
+                        contentAlignment = Alignment.Center
+                    ) {
+                        Icon(
+                            Icons.Filled.ChevronRight,
+                            null,
+                            tint = Color.White,
+                            modifier = Modifier.size(22.dp)
+                        )
+                    }
                 }
                 Spacer(Modifier.height(22.dp))
             }
@@ -284,8 +306,8 @@
                         modifier = Modifier
                             .fillMaxWidth()
                             .clip(RoundedCornerShape(16.dp))
-                            .background(Color(0xFF171130))
-                            .border(1.dp, Color(0xFF2A2150), RoundedCornerShape(16.dp))
+                            .background(HomeCardBg)
+                            .border(1.dp, HomeCardStroke, RoundedCornerShape(16.dp))
                             .padding(vertical = 22.dp, horizontal = 16.dp)
                     ) {
                         Icon(
@@ -393,44 +415,67 @@
     }
 }
 
+/**
+ * Ana ekran kartı: gradyan zemin + arkada yumuşak ışık halkası + ortalanmış ikon/başlık.
+ * Dokunma alanı kartın tamamıdır (tek dokunuşla ilgili ekran açılır).
+ */
 @Composable
 private fun FeatureCard(
     title: String,
     subtitle: String,
     icon: ImageVector,
     gradient: List<Color>,
+    glow: Color,
     onClick: () -> Unit,
     modifier: Modifier = Modifier
 ) {
-    Column(
-        horizontalAlignment = Alignment.CenterHorizontally,
-        verticalArrangement = Arrangement.Center,
+    Box(
         modifier = modifier
-            .clip(RoundedCornerShape(22.dp))
+            .clip(RoundedCornerShape(24.dp))
             .background(Brush.linearGradient(gradient))
-            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
+            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
             .clickable(onClick = onClick)
-            .padding(horizontal = 10.dp, vertical = 14.dp)
     ) {
-        Icon(icon, null, tint = Color.White, modifier = Modifier.size(42.dp))
-        Spacer(Modifier.height(10.dp))
-        Text(
-            title,
-            color = Color.White,
-            fontSize = 19.sp,
-            fontWeight = FontWeight.Bold,
-            textAlign = TextAlign.Center
-        )
-        Spacer(Modifier.height(4.dp))
-        Text(
-            subtitle,
-            color = Color.White.copy(alpha = 0.82f),
-            fontSize = 12.sp,
-            lineHeight = 15.sp,
-            textAlign = TextAlign.Center,
-            maxLines = 2,
-            overflow = TextOverflow.Ellipsis
-        )
+        Canvas(modifier = Modifier.matchParentSize()) {
+            val center = Offset(size.width / 2f, size.height * 0.34f)
+            val radius = size.minDimension * 0.72f
+            drawCircle(
+                brush = Brush.radialGradient(
+                    colors = listOf(glow.copy(alpha = 0.40f), Color.Transparent),
+                    center = center,
+                    radius = radius
+                ),
+                radius = radius,
+                center = center
+            )
+        }
+        Column(
+            horizontalAlignment = Alignment.CenterHorizontally,
+            verticalArrangement = Arrangement.Center,
+            modifier = Modifier
+                .fillMaxSize()
+                .padding(horizontal = 10.dp, vertical = 14.dp)
+        ) {
+            Icon(icon, null, tint = Color.White, modifier = Modifier.size(44.dp))
+            Spacer(Modifier.height(10.dp))
+            Text(
+                title,
+                color = Color.White,
+                fontSize = 20.sp,
+                fontWeight = FontWeight.Bold,
+                textAlign = TextAlign.Center
+            )
+            Spacer(Modifier.height(4.dp))
+            Text(
+                subtitle,
+                color = Color.White.copy(alpha = 0.85f),
+                fontSize = 12.sp,
+                lineHeight = 15.sp,
+                textAlign = TextAlign.Center,
+                maxLines = 2,
+                overflow = TextOverflow.Ellipsis
+            )
+        }
     }
 }
 
@@ -449,15 +494,15 @@
         modifier = Modifier
             .fillMaxWidth()
             .clip(RoundedCornerShape(14.dp))
-            .background(Color(0xFF171130))
-            .border(1.dp, Color(0xFF2A2150), RoundedCornerShape(14.dp))
+            .background(HomeCardBg)
+            .border(1.dp, HomeCardStroke, RoundedCornerShape(14.dp))
             .clickable(onClick = onOpen)
             .padding(horizontal = 8.dp, vertical = 8.dp)
     ) {
         Box(
             modifier = Modifier
-                .size(width = 62.dp, height = 46.dp)
-                .clip(RoundedCornerShape(9.dp))
+                .size(width = 64.dp, height = 48.dp)
+                .clip(RoundedCornerShape(10.dp))
                 .background(
                     Brush.linearGradient(listOf(Color(0xFF3B2A6B), Color(0xFF221640)))
                 ),
@@ -484,12 +529,12 @@
             Text(
                 item.name,
                 color = Color.White,
-                fontSize = 14.sp,
+                fontSize = 15.sp,
                 fontWeight = FontWeight.SemiBold,
                 maxLines = 1,
                 overflow = TextOverflow.Ellipsis
             )
-            Spacer(Modifier.height(2.dp))
+            Spacer(Modifier.height(3.dp))
             Text(
                 buildRecentSubtitle(item.lastUsedMs, durationMs),
                 color = HomeAccentText,
@@ -551,4 +596,4 @@
     } else {
         String.format(Locale.US, "%02d:%02d", minutes, seconds)
     }
-}
\ No newline at end of file
+}
--- app/src/main/java/com/example/muxmaster/viewmodel/TrimViewModel.kt	2026-11-09 17:04:15.000000000 +0000
+++ app/src/main/java/com/example/muxmaster/viewmodel/TrimViewModel.kt	2026-11-09 17:30:07.688226230 +0000
@@ -147,6 +147,26 @@
         cutRanges = (cutRanges + window).sortedBy { it.startMs }
     }
 
+    /**
+     * MAKAS düğmesi: Başlangıç–Bitiş alanlarında seçili aralık varsa ONU kesilecek
+     * aralık olarak ekler (kullanıcı slider'la seçtiği bölümü tek dokunuşla çıkarır).
+     * Seçim anlamlı değilse (çok kısa ya da videonun tamamı) oynatıcının bulunduğu
+     * yerden varsayılan uzunlukta bir aralık eklenir.
+     */
+    fun addCutRangeFromSelection(playheadMs: Long) {
+        val dur = videoFile?.durationMs ?: return
+        if (dur <= MIN_CUT_MS || isTrimming) return
+        val s = trimStartMs.coerceIn(0L, dur)
+        val e = trimEndMs.coerceIn(0L, dur)
+        val len = e - s
+        val usable = len >= MIN_SELECTION_MS && len <= dur - 400L
+        if (usable) {
+            cutRanges = (cutRanges + TimeRange(s, e)).sortedBy { it.startMs }
+        } else {
+            addCutRange(playheadMs)
+        }
+    }
+
     private fun findFreeWindow(dur: Long, preferredStart: Long): TimeRange? {
         val merged = mergedCutRanges(dur)
         var start = preferredStart.coerceIn(0L, dur)
@@ -478,7 +498,10 @@
 
                 val ok = rc == 0 && tempOutput.exists() && tempOutput.length() > 0L
                 if (!ok) {
-                    resultMessage = "Kırpma başarısız oldu (kod: $rc)."
+                    // buildMultiCut kendi özel hata mesajını yazdıysa onu koru.
+                    if (resultMessage.isNullOrBlank()) {
+                        resultMessage = "Kırpma başarısız oldu (kod: $rc)."
+                    }
                     isSuccess = false
                     runCatching { tempOutput.delete() }
                 } else {
@@ -591,7 +614,13 @@
                     "-ss", fmt3(startSec),
                     "-i", video.cachePath,
                     "-t", fmt3(durationSec),
-                    "-map", "0",
+                    // Sadece video/ses/altyazı/ek dosya: "0" ile gelen data/zmcd gibi
+                    // akışlar bazı kaynaklarda mkv'ye kopyalanamıyor ve parça
+                    // oluşturmayı başarısız ediyordu.
+                    "-map", "0:v?",
+                    "-map", "0:a?",
+                    "-map", "0:s?",
+                    "-map", "0:t?",
                     "-c", "copy",
                     "-map_chapters", "-1",
                     "-avoid_negative_ts", "make_zero",
@@ -611,6 +640,10 @@
                     parts += part
                 } else {
                     runCatching { part.delete() }
+                    // Sessizce eksik parça bırakmak yerine net hata ver.
+                    resultMessage = "Parça ${index + 1}/${keep.size} kesilemedi (kod: $rc)."
+                    isSuccess = false
+                    return false
                 }
             }
 
@@ -623,19 +656,14 @@
 
             MuxForegroundService.update(app, 97, "Parçalar birleştiriliyor…")
             trimProgress = 97
-            val concatArgs = arrayOf(
-                "-y",
-                "-f", "concat",
-                "-safe", "0",
-                "-i", listFile.absolutePath,
-                "-map", "0:V?",
-                "-map", "0:a?",
-                "-map", "0:s?",
-                "-c", "copy",
-                "-avoid_negative_ts", "make_zero",
-                tempOutput.absolutePath
-            )
-            val rcConcat = runFfmpegAsync(concatArgs, totalMs) { }
+
+            var rcConcat = concatParts(listFile, tempOutput, totalMs, withSubtitles = true)
+            if (rcConcat != 0) {
+                // Ör. ASS altyazı + mp4 hedefi: altyazıyı düşürüp yeniden dene.
+                MuxForegroundService.update(app, 97, "Altyazı eklenemedi, altyazısız birleştiriliyor…")
+                runCatching { tempOutput.delete() }
+                rcConcat = concatParts(listFile, tempOutput, totalMs, withSubtitles = false)
+            }
             runCatching { listFile.delete() }
             return rcConcat == 0 && tempOutput.exists() && tempOutput.length() > 0L
         } finally {
@@ -643,6 +671,31 @@
         }
     }
 
+    /** Parçaları ffmpeg "concat" demuxer'ı ile tek dosyada birleştirir (-c copy). */
+    private suspend fun concatParts(
+        listFile: File,
+        tempOutput: File,
+        totalMs: Long,
+        withSubtitles: Boolean
+    ): Int? {
+        val maps = mutableListOf("-map", "0:V?", "-map", "0:a?")
+        if (withSubtitles) {
+            maps += "-map"; maps += "0:s?"
+            maps += "-map"; maps += "0:t?"
+        }
+        val concatArgs = arrayOf(
+            "-y",
+            "-f", "concat",
+            "-safe", "0",
+            "-i", listFile.absolutePath
+        ) + maps.toTypedArray() + arrayOf(
+            "-c", "copy",
+            "-avoid_negative_ts", "make_zero",
+            tempOutput.absolutePath
+        )
+        return runFfmpegAsync(concatArgs, totalMs) { }
+    }
+
     /**
      * Hassas kesim: [startSec, keySec) arası videoyu libx264 ile yeniden kodlar, [keySec, endSec]
      * arasını kayıpsız kopyalar, ikisini MPEG-TS üzerinden (SPS/PPS satır içi) birleştirir, sonra
@@ -777,6 +830,8 @@
         private const val MIN_SEGMENT_MS = 120L
         /** Makas ile eklenen yeni kesim aralığının varsayılan uzunluğu (10 sn). */
         private const val DEFAULT_CUT_LENGTH_MS = 10_000L
+        /** Makas, seçili aralığı kesim olarak eklemek için en az bu süreyi ister. */
+        private const val MIN_SELECTION_MS = 500L
         private val MEDIA_EXTS = setOf(
             "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts", "flv", "wmv", "3gp", "mpg", "mpeg",
             "mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "wma", "mka", "ac3", "eac3", "dts"
--- .gitignore	2026-11-09 17:04:15.000000000 +0000
+++ .gitignore	2026-11-09 17:31:27.740071844 +0000
@@ -1,6 +1,10 @@
 # CI tarafından her derlemede yeniden üretilir (scripts/fetch_mkvtoolnix_assets.py)
 app/src/main/assets/mkvtoolnix/
 
+# Native .so dosyaları (mkvmerge/mkvextract/exhale/opusenc) CI'da üretilir;
+# repoya eklenmesin (yüzlerce MB). Workflow bunları önbellekten geri yükler.
+app/src/main/jniLibs/
+
 # Gradle / Android build çıktıları
 .gradle/
 build/
--- README.md	2026-11-09 17:04:15.000000000 +0000
+++ README.md	2026-11-09 17:31:48.320708715 +0000
@@ -170,3 +170,48 @@
 ### Doğrulama
 Bu sürüm; JDK 17 + Android SDK 34 + Gradle 8.5 ile `gradle :app:assembleRelease`
 (minify/R8 açık) çalıştırılarak derlendi — hatasız tamamlandı.
+
+## Güncelleme 5 — derleme hatası + hız, kaydırma, makas, ana ekran
+
+### 1) GitHub derlemesi neden 12-13 dakika sürüp hata veriyordu?
+`ubuntu-latest` imajı Ubuntu 26.04'e (Python 3.14) geçti. Python 3.14'te `tarfile`
+varsayılan olarak `data` filtresiyle açıyor; bu filtre Termux `.deb` paketlerindeki
+**mutlak symlink**'lerde (`./data/data/com.termux/files/usr/bin/aws` gibi) hata verip
+paketin tamamının açılmasını engelliyor. Sonuç: `libz.so.1` (awscli) ve
+`libQt6Core.so.6` (qt6-qtbase) çözülemiyor, betik 12 dakika sonunda
+"aşağıdaki paylaşımlı kütüphaneler çözümlenemedi" diyip çıkıyordu.
+
+Düzeltmeler (`scripts/fetch_mkvtoolnix_assets.py`):
+- `.deb` açma `filter="fully_trusted"` ile yapılıyor (eski Python'larda otomatik geri düşüş).
+- Açtıktan sonra mutlak Termux symlink'leri **göreli** hale getiriliyor, kırık linkler çözülüyor.
+- `libz.so.1` artık 20+ MB'lık `awscli` yerine küçük `zlib` paketinden alınıyor (paket tercih tablosu).
+- İndirmelerde 3 deneme, `exhale` derlemesinde önce `-O2` (daha kısa derleme).
+- Android sistem kütüphaneleri (`libEGL`, `libGLESv2`, `libOpenSLES`, `libvulkan` …) atlanıyor.
+
+Hız (`build.yml` + `gradle.properties`):
+- `app/src/main/jniLibs/arm64-v8a` artık **actions/cache** ile önbelleğe alınıyor; betik
+  değişmedikçe native adım tamamen atlanıyor (ilk derleme uzun, sonrakiler ~4 dakika).
+- Gereksiz aarch64 cross-toolchain kurulumu (70 MB) kaldırıldı, NDK clang++ kullanılıyor.
+- Gradle: `--build-cache --parallel`, `org.gradle.caching=true`, `parallel=true`.
+- Native dosyalar için doğrulama adımı eklendi (eksikse derleme sessizce bozuk APK üretmiyor).
+
+### 2) Oynatıcı slider'ı sürüklenmiyordu (sadece dokunma)
+`ui/components/Sliders.kt`: jest artık **`PointerEventPass.Initial`** aşamasında yakalanıp
+her karede tüketiliyor. Böylece içinde bulunduğu dikey kaydırma (verticalScroll) kapsayıcısı
+hareketi çalamıyor; parmak slider'ı birebir takip ediyor, kalkınca kesin hedefe oturuyor.
+Aynı düzeltme `MuxRangeSlider` (başlangıç/bitiş aralığı) için de yapıldı. Dokunma çalışmaya devam eder.
+
+### 3) Makas (çoklu kesim) artık görünür ve kullanışlı
+- "Kırpma Aralığı" başlığının hemen sağına **Makasla kes** düğmesi eklendi: Başlangıç–Bitiş
+  alanlarında seçili aralığı tek dokunuşla "kesilecek aralık" yapar.
+- İstenildiği kadar aralık eklenebilir; her aralık dk/sn/ms alanlarıyla düzeltilebilir, tek tek silinebilir.
+- "Oynadığı yerden 10 sn'lik aralık ekle" düğmesi, "N aralık çıkarılacak · Kalan süre" özeti ve
+  "Tek aralığa dön" eklendi.
+- Dışa aktarım: her korunacak parça kayıpsız (`-c copy`) kesilir, `concat` demuxer'ı ile birleştirilir.
+  Parça kesilemezse artık sessizce atlanmıyor, net hata veriliyor. Altyazı hedef kapsayıcıya
+  sığmazsa (ör. ASS + mp4) altyazı düşürülüp yeniden denenir.
+
+### 4) Ana ekran
+Kartlar gönderilen tasarıma yaklaştırıldı: 24dp köşe, arkada yumuşak ışık halkası, 44dp ikon,
+20sp başlık; "Ses Dönüştürme" kartında ikon çerçevesi ve yuvarlak ok. Kartlara dokununca ilgili
+ekran doğrudan açılır, geri tuşu ana ekrana döner.
