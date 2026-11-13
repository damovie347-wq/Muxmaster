# MuxMaster — Güncelleme 12 / AV1 Anında Açılış + Sürükleme Zaman Göstergesi

Değişen dosyalar: `data/ScrubSeeker.kt`, `ui/components/Sliders.kt`,
`ui/components/FrameTimeline.kt`, `ui/components/ScrubHud.kt` (yeni),
`ui/screens/TrimScreen.kt`, `DEGISIKLIKLER.md`.
Yeniden encode YOK, thumbnail/kare önbelleği YOK, FFmpeg YOK.

## 1) AV1 VİDEOLARIN GEÇ AÇILMASI — KÖK NEDEN VE ÇÖZÜM
AV1 videoları çoğu telefonda **donanım dekoderi olmadan** (libgav1, CPU) çözülür ve
anahtar kare (keyframe) aralıkları 2–10 sn olabilir. Uygulama her sarmada
`SeekParameters.EXACT` kullanıyordu; bu ayar **önceki anahtar kareden hedefe kadar
TÜM kareleri çözmek** zorundadır. 1080p AV1'de bu saniyeler sürer → video geç açılır,
sürüklerken görüntü hiç gelmez.

Düzeltmeler:
- **Açılış sarması artık anahtar kare ile**: dosya açılırken ilk kare `CLOSEST_SYNC`
  ile ANINDA ekrana gelir; oynatıcı hazır olunca (`onPlaybackStateChanged` →
  `STATE_READY`) kare hassas (`EXACT`) sarmaya dönülür ve gerekiyorsa tam kareye
  oturulur. Sıfırdan açılışta gereksiz `seekTo` hiç yapılmaz.
- **Kodek duyarlı sarma** (`scrubSeekParametersFor`): AV1'de sürükleme/jump sırasında
  `CLOSEST_SYNC` (görüntü anında gelir), parmak kalkınca `EXACT` (hedef kare birebir).
  H.264 / HEVC / VP9 davranışı değişmedi: sürüklemede de kare hassas.
- **AV1 dekoder seçimi**: donanım AV1 dekoderi olan cihazda donanım, olmayan cihazda
  doğrudan AV1 yazılım dekoderi (libgav1) seçilir; yanlış/eksik "donanım" dekoderi
  denemesinden doğan açılış gecikmesi oluşmaz.
- **Çift sarma kaldırıldı**: tutamaç sürüklenirken `LaunchedEffect(startMs/endMs)`
  ikinci bir sarma gönderip AV1 dekoderini meşgul ediyordu; artık gönderilmiyor.
- `ScrubSeeker.waitForFrame`: AV1 anahtar kare sarmasında aynı anahtar kareye düşen
  istekler yeni kare çizmediği için bekleme zaman aşımına giriyordu (sürükleme
  takılıyordu). AV1'de bekleme kapatıldı, istekler doğrudan akar (~90 ms).

## 2) SÜRÜKLERKEN ZAMANI ANINDA GÖRME (mpv / Just Player davranışı)
- Yeni `ScrubHud` / `ScrubHudPopup`: parmak çubukta gezerken **ekranın ortasında**
  hedef an salise hassasiyetiyle (`mm:ss.SS`), parmağın başladığı yerden farkı
  (+/−) ve kalan süre yazar. Gösterge **oynatıcıdan değil parmaktan** beslenir:
  dekoder yetişemese bile anında okunur.
- `MuxSeekSlider` (oynatıcı + kırpma slider'ı) ve `FrameTrimTimeline` (zoom'lu kırpma
  çubuğunun A/B tutamaçları) bu göstergeyi kullanır; kırpma tutamacında kare numarası
  da yazar.
- `SmoothVideoSlider`: sol süre etiketi sürükleme sırasında oynatıcıyı beklemez,
  hedef anı anında gösterir.
- Kırpma ekranında A tutamacı sola çekilirken hedef an eski "başlangıç" değerine
  kırpılıyordu (seçim aralığı bir kare geriden geldiği için görüntü ilerlemiyordu).
  Alt sınır artık 0: oynatıcı parmağın gösterdiği kareye gider.

---

# MuxMaster — Güncelleme 11 / Sürükleme Kök Nedeni + Otomatik Devam + Döndürme + 6x-9x

Değişen dosyalar: `data/ScrubSeeker.kt`, `ui/screens/TrimScreen.kt`, `DEGISIKLIKLER.md`.
Yeniden encode YOK, thumbnail/kare önbelleği YOK, FFmpeg YOK.

## 1) SÜRÜKLEMEDE GÖRÜNTÜNÜN DEĞİŞMEMESİ — GERÇEK KÖK NEDEN
Önceki güncelleme yalnızca KIRPMA ekranını düzeltmişti. **Video oynatıcı ekranı
(`SlotPlayerHolder`) sürükleme sırasında hâlâ `SEEK_SCRUB_STREAM` = `CLOSEST_SYNC`
kullanıyordu.** Bu ayar oynatıcıyı her sarmada en yakın ANAHTAR KAREYE gönderir.
H.264'te anahtar kareler arası 2–15 sn olduğu için zaman çizelgesi hareket ettiği
hâlde ana görüntü ya hiç değişmiyor ya da saniyeler atlıyordu. Slider'ın hareket
etmesi oynatıcının görüntüyü değiştirdiği anlamına gelmiyordu.

Düzeltme:
- Sürükleme sarması artık **her yerde** `SeekParameters.EXACT` (tolerans sıfır):
  oynatıcı önceki anahtar kareden başlayıp hedef kareye kadar gereken kareleri
  çözer ve HEDEF KARENİN KENDİSİNİ gösterir. Anahtar kareye atlama yok.
- Hedef an önce **500 ms ızgarasına** (0,0 – 0,5 – 1,0 – 1,5 …), sonra videonun
  GERÇEK kare sınırına oturur (`FrameClock.snapToSample`). 500 ms yalnızca örnekleme
  noktasıdır; ekrana basılan kare her zaman videoda gerçekten var olan bir karedir.
- `ScrubSeeker` yeniden yazıldı: her istek bir **generation** numarası alır, daha yeni
  istek gelince eskinin beklemesi anında kesilir. Eski kare yeni karenin üzerine
  yazılamaz; kuyruk birikmez; parmak hızlıyken ara örnekler atlanır ama **en son
  hedef her zaman** uygulanır.
- Aynı 500 ms örneği tekrar gönderilmez (dekoder gereksiz meşgul edilmez).
- `setSeekParameters` her örnekte değil, yalnızca gerektiğinde bir kez uygulanır.
- Kırpma ekranındaki tutamaç sürüklemesi kare hassas kalır (tek tek gerçek kareler).

## 2) İLERİ / GERİ SARMA GECİKMESİ
- `delay(60)` + ikinci sarma kaldırıldı (kırpma ekranında her sarma 60 ms bekleyip
  İKİ KEZ gönderiliyordu).
- Sürüklemeden kalan bekleyen istek (`cancelPending`) düğmeye basıldığı anda iptal
  edilir; sarma anında gider.
- Art arda basışlar birikir: 3 × +10 sn = +30 sn (hedef, oynatıcının geciken konumu
  değil son istenen konumdur).
- Oynatma durumu korunur: oynuyorsa oynamaya devam eder, duraklatılmışsa kalır.
- Oynat/Duraklat kararı `playWhenReady` üzerinden verilir: tampon doldururken
  (isPlaying=false) Play'e basınca video yanlışlıkla DURAKLATILMAZ.

## 3) YATAY SÜRÜKLEME SONRASI OTOMATİK DEVAM
- Video yüzeyinde yatay sürükleme başlarken sürükleme öncesi oynatma durumu saklanır
  (`pauseForScrub`), parmak kalkınca `finishScrub` videoyu **kendiliğinden** devam
  ettirir. Ortadaki Play'e tekrar basmak gerekmez.
- Sürükleme öncesi video duraklatılmışsa duraklatılmış kalır.
- 110 ms'lik yapay kısıtlama kaldırıldı; sürükleme, dokunma/Play algılanmasıyla
  çakışmaz (hareket eşiği + olay tüketimi korunur, Touch Offset Scrubbing bozulmaz).

## 4) TELEFON DÖNDÜRÜLDÜĞÜNDE VİDEO BAŞA DÖNMESİN
- KÖK NEDEN: kırpma ekranının yatay/dikey yerleşimi iki ayrı Compose dalıdır. Telefon
  döndürüldüğünde `TrimPlayer` diğer dala taşınıp yeniden kuruluyor, içindeki
  `remember { ExoPlayer }` çöp oluyor ve yeni oynatıcı dosyayı 0. saniyeden açıyordu.
- Yeni `TrimPlayerSession`: ExoPlayer + oynatma konumu + oynatma niyeti + hız + ses +
  en-boy oranı + tam ekran + A noktası damgası `TrimScreen` seviyesinde tutulur.
  Yerleşim değişse de AYNI oynatıcı örneği kullanılır: dosya yeniden yüklenmez,
  dekoder yeniden kurulmaz, video başa dönmez.
- Aynı dosya için `setMediaItem/prepare` tekrar çağrılmaz (`mediaKey` denetimi).
- Track menüsü döndürmeden sonra boş kalmaz (`player.currentTracks` doğrudan okunur).

## 5) 6X – 7X – 8X – 9X
- Hem uzun basma panelinde hem hız listesinde 6x, 7x, 8x, 9x eklendi; hız
  kaydırıcısının aralığı 9x'e çıkarıldı. Mevcut hızlar korundu, aktif hız işaretli.
- Hız değişimi videoyu sıfırlamaz; ekran döndürmede hız korunur.
- SES: ExoPlayer'ın ses hattı (Sonic) 8x'e kadar ses üretir. **8x üzerinde (9x) ses
  hattı kapatılır**: video tam 9 kat hızda oynamaya devam eder, ses duyulmaz.
  8x ve altında uygulamanın mevcut ses davranışı aynen korunur.

## 6) CPU / GPU / RAM
- Video yeniden encode edilmez, orijinal dosyaya dokunulmaz, FFmpeg çalışmaz.
- Thumbnail üretilmez, kare önbelleği tutulmaz, tüm video RAM'e alınmaz.
- Donanım (VPU) dekoderi öncelikli; sürüklemede eski istekler iptal edilir,
  yalnızca gerekli kareler çözülür; gereksiz player initialization yapılmaz.

---

# MuxMaster — Güncelleme 10 / 500 ms Kare Sarması + Klasörde Video Numarasına Git

6 dosya değişti (1 yeni). Yeniden encode YOK, thumbnail/kare önbelleği YOK.

## 1) Sürükleme sorununun KÖK NEDENİ: CLOSEST_SYNC (anahtar kare) sarması
- Eskiden sürükleme sırasında `SeekParameters.CLOSEST_SYNC` kullanılıyordu. Bu ayar
  oynatıcıyı her zaman en yakın **anahtar kareye** gönderir. Anahtar kareler arası
  2–15 sn olduğu için parmak kaydırıldığında görüntü ya hiç değişmiyordu ("aynı kare
  kalıyor") ya da 15–20 sn'lik adımlarla atlıyordu.
- Kırpma ekranında sürükleme artık **EXACT** (tolerans 0) sarma kullanır: hedefin
  GERÇEK karesi çözülür. Çoklu video oynatıcısı (1–4 video) hız için eski davranışta kalır.

## 2) Her 500 ms'de bir gerçek kare (yeni `data/ScrubSeeker.kt`)
- `FrameClock.snapToSample(ms, 500)`: hedef önce en yakın 0,5 sn noktasına (1,35 sn →
  1,5 sn), sonra videonun gerçek kare sınırına oturur. 0,5 sn bir "kare" sayılmaz.
- `ScrubSeeker`: **"en son istek kazanır"** sarma motoru. Parmak hızlı hareket ederken
  ara istekler atlanır, EN SON istenen an her zaman uygulanır; istek kuyruğu birikmez,
  geç biten eski bir sarma güncel karenin üzerine yazmaz.
- Her sarmadan sonra dekoderin kareyi hazırlaması beklenir (en fazla 140 ms), böylece
  görüntü parmağın gerisinde kalmaz.
- Sürükleme sırasındaki 80 ms'lik zaman kısıtlaması (throttle) **kaldırıldı**.
- Görüntü her zaman **ana oynatıcının içinde** güncellenir: thumbnail popup, floating
  preview veya baloncuk yoktur. Tam ekranda sürükleme göstergesi kareyi kapatmasın diye
  üst ortaya alındı.

## 3) Çift sarma isteği giderildi
- Tutamaç sürüklenirken hem zaman çizelgesi hem `LaunchedEffect(startMs/endMs)` sarma
  gönderiyordu (60 ms gecikmeli, ikinci kez). Sürükleme sırasında bu efektler devre dışı;
  sarmayı yalnızca zaman çizelgesi gönderiyor. Dekoder iki kez meşgul edilmiyor.

## 4) Tam ekranda sağa/sola sürükleme hızı
- Hedef an artık parmağın **mutlak** konumundan hesaplanıyor (eskiden biriktirilen delta
  kullanılıyordu; jest yeniden başladığında sıfırlanıp sürükleme 15–20 sn'de takılıyordu).
- Bir ekran genişliği 120 sn yerine **180 sn** tarar; 0,5 sn örnekleri görünür kalır.
- Sürükleme başında oynatma durur, bırakınca son örnek kesin (kare hassas) uygulanır.

## 5) Klasörde video numarasına git + kaldığın yerden devam
- Klasör çubuğundaki "50/300" artık **dokunulabilir**: numarayı yaz, GİT.
- Aynı klasör yeniden seçildiğinde (uygulama kapatılıp açıldığında dahil) **kaldığın video
  numarasından** devam edilir (SharedPreferences).

## 6) "Sonraki video"da yukarı zıplama animasyonu
- `onVideoSelected` eskiden `videoFile = null` yapıyordu; oynatıcı ve kontroller
  kompozisyondan çıkıp sayfa kısalıyor, kaydırma yukarı kırpılıyordu. Artık klasör
  içinde gezinirken eski video ekranda kalır, yeni dosya hazır olunca tek seferde değişir.
  Kaydırma konumları da ekranın üstünde tutuluyor.

## 7) Yanlışlıkla klasörden çıkma
- Çarpı düğmesi "Sonraki video"nun altından satırın **soluna** taşındı ve artık onay ister.

---

# MuxMaster — Güncelleme 9 / Kare Hassas Sarma + Kare Hassas Kırpma

7 dosya değişti (2 yeni, 1 silindi). Yeniden encode YOK, arka plan kare üretimi YOK.

## 1) Kare hassas sarma (Frame-Accurate Scrubbing)
- Yeni `data/FrameClock.kt`: videonun **gerçek** kare zamanlaması. Kare hızı ffprobe'un
  `avg_frame_rate` değerinden (`TrackProber` içinde ek I/O olmadan), kare sayısı varsa
  `nb_frames`'ten okunur. 30 FPS -> 33,33 ms, 60 FPS -> 16,67 ms hassasiyet.
- Sarma toleransı **sıfır**: `SeekParameters.EXACT`. Eskiden 350 ms (sürüklemede 40 ms,
  ana oynatıcıda 1,2 sn) tolerans vardı; bu yüzden oynatıcı en yakın **keyframe**'e
  yuvarlanıp yanlış kare gösterebiliyordu. Artık hedef, kare sınırına oturtulur ve
  oynatıcı tam o kareyi gösterir.
- Hedef an her zaman kare sınırına oturtulur (`FrameClock.snap`), böylece "kare arası"
  bir ana sarma isteği gitmez.

## 2) Dokunma ofseti (Touch Offset) — YouTube davranışı
- `MuxSeekSlider` yeniden yazıldı: çubuğun herhangi bir yerine dokunup sürükleyebilirsiniz.
  Dokunulan nokta ile tutamaç arasındaki mesafe **korunur**; tutamaç parmağın altına
  **atlamaz**. Sürükleme göreli çalışır (parmak ne kadar giderse tutamaç o kadar gider).
- Parmak kalkınca seçilen gerçek karede kalınır; oynatma sürükleme boyunca durur.
- Küçük dokunuşlar (kaydırma olmadan) konumu değiştirmez — ofset korunduğu için yanlışlıkla
  sıçrama olmaz.
- Oynatma tutamacı kırmızı halka ile çizilir (kare hassas sürüklemede net görünür).

## 3) Kare hassas kırpma + zoom (yeni `ui/components/FrameTimeline.kt`)
- `FrameTrimTimeline`: zoom'lu zaman çizelgesi. İki tutamaç (A = başlangıç, B = bitiş)
  **bağımsız** hareket eder, ikisi de dokunma ofsetini korur ve kare sınırına oturur.
- Zoom: iki parmakla (pinch) veya paneldeki − / + düğmeleriyle 1X–60X. Boş alanı tek
  parmakla sürükleyerek görünür pencere kaydırılır. "Tümü" ile tam video.
- Tek kare ayar düğmeleri: **A −1 kare / A +1 kare / B −1 kare / B +1 kare**.
- **A = bu kare / B = bu kare**: oynatıcıda gördüğünüz kareyi doğrudan kesim noktası yapar.
- "A karesi / B karesi" numaraları canlı gösterilir. Thumbnail şeridi YOKTUR: hiçbir kare
  çözülmez, hiçbir kare önbelleğe alınmaz; sadece kare çentikleri çizilir.

## 4) Ana oynatıcıda anlık kare önizlemesi
- Kırpma tutamacı veya sarma çubuğu sürüklenirken görüntü **ana video oynatıcısında**
  güncellenir. Ayrı thumbnail popup / floating preview baloncuğu / video üstünde küçük
  pencere **kaldırıldı** (`ScrubPreviewBubble` ve `VideoThumbnails` silindi).
- Böylece ikinci bir `MediaMetadataRetriever` dekoderi artık çalışmıyor: CPU, GPU ve RAM
  kullanımı düşer.

## 5) Encode yok, kaynak tüketimi minimum
- Sürükleme/kırpma sırasında FFmpeg, encoder, render veya toplu kare çıkarma **yok**.
  Orijinal dosya değişmez; yalnızca ihtiyaç duyulan kare mevcut donanım dekoderinden
  çözülür (`MediaCodecSelector` zaten donanım dekoderini öne alıyor).
- Sarma istekleri ~20–24 ms'de bir birleştirilir; eskisi oynatıcı tarafından atılır,
  yalnızca **en son** istenen kare işlenir. Parmak kalkınca tek kesin sarma yapılır ve
  başka iş kalmaz.
- Kare önbelleği tutulmaz (LRU'ya gerek yok): oynatıcının mevcut, sınırlı geri tamponu
  (`setBackBuffer`) kullanılır; sürükleme bitince hiçbir kare bellekte tutulmaz.
- Kırpma çıktısı hâlâ **stream copy** (`-c copy`). Yeniden kodlama yalnızca kullanıcı
  dışa aktarmada "Başlangıcı tam hassas kes" seçeneğini açık bırakırsa, yalnızca baş
  kısım için değerlendirilir (varsayılan davranış korunmuştur).

## 6) VFR / keyframe notları
- VFR videolarda kare sayısı varsa eşleme "kare sayısı / süre" üzerinden yapılır; bu,
  nominal FPS'ten daha gerçekçidir. Sarma toleranssız olduğu için gösterilen görüntü,
  o zaman damgasının içinde bulunduğu gerçek karedir.
- Uzun GOP'lu dosyalarda artık keyframe'e atlama olmadığı için "yanlış kare" görünmez.

## Değişen dosyalar
1. `data/FrameClock.kt` **(yeni)** — gerçek kare zamanlaması (fps, kare süresi, snap/step).
2. `ui/components/FrameTimeline.kt` **(yeni)** — zoom'lu, iki tutamaçlı kare hassas çizelge.
3. `ui/components/Sliders.kt` — `MuxSeekSlider` dokunma ofseti + kare hassasiyeti;
   kullanılmayan `MuxRangeSlider` kaldırıldı.
4. `ui/screens/TrimScreen.kt` — kırpma paneli yeni çizelgeye geçti, kare adım düğmeleri,
   zoom düğmeleri, önizleme baloncuğu kaldırıldı, sıfır toleranslı sarma.
5. `viewmodel/TrimViewModel.kt` — `frameClock` + kırpma/kesim noktalarının kareye oturtulması.
6. `data/TrackProber.kt` — `avg_frame_rate` / `nb_frames` okuma (ek ffprobe çağrısı yok).
7. `model/Models.kt` — `VideoFile` içine `fps`, `frameCount`.
8. `data/VideoThumbnails.kt` **(silindi)** — ikinci dekoder artık kullanılmıyor.

# MuxMaster — Güncelleme 8 / Değişiklik Özeti

4 dosya değişti (1 yeni). Proje **GitHub'da hatasız derleniyor** (AGP 8.3.0 / Kotlin 1.9.22 /
Gradle 8.5 / compileSdk 34). Bu sürümde `assembleRelease` (R8 + resource shrink +
lintVital) yerelde de çalıştırıldı: **BUILD SUCCESSFUL**.

## 1) Klasör seçince video artık kendiliğinden tam ekrana GEÇMİYOR
- `TrimPlayer` içindeki "video seçilince otomatik tam ekran" etkisi kaldırıldı.
- Tam ekran yalnızca kullanıcı sağdaki **tam ekran düğmesine** basınca açılır.

## 2) Yatay (landscape) kırpma ekranı baştan düzenlendi
- Önceden her şey tek bir dikey kaydırma sütunundaydı: telefon yataya çevrilince
  oynatıcı 260 dp'ye sıkışıyor, slider/kesim alanları ekranın altına taşıyordu.
- Yeni yerleşim (`TrimScreen`):
  - **Yatay:** solda kalan yüksekliğin **tamamını** kullanan oynatıcı, sağda kendi
    içinde kaydırılan kontrol paneli (genişlik ekranın %36'sı, 290–420 dp).
  - **Dikey:** eski düzen aynen korunur.
- `TrimPlayer(fillHeight = true)` parametresi eklendi: yatayda video kutusu sabit
  260 dp yerine `weight(1f)` ile tüm boşluğu doldurur.

## 3) Tam ekranda döndürme hatası düzeltildi
- Video tam ekranda dikey → yatay çevrilince görüntü bozuluyor, ancak ekrana iki kez
  dokununca düzeliyordu (Dialog penceresi yeni ölçüye geçmiyor, video yüzeyi eski
  boyutta kalıyordu).
- `FullscreenPlayerDialog` artık:
  - `Application.registerComponentCallbacks` ile yön değişimini dinler (`configTick`),
  - yön/ölçü değişince pencereyi yeniden `MATCH_PARENT` yapar ve `requestLayout()` çağırır,
  - içeriği `key(layoutKey)` ile yeniden kurar (video yüzeyi yeni ölçüyle oluşur).
- Sonuç: dikey tam ekrandan yatay tam ekrana geçişte görüntü tek karede doğru yerleşir.

## 4) Slider YouTube gibi + tam ekranda sağ üstte "B" düğmesi
- `MuxSeekSlider`: çubuğun **neresine** basılırsa tutamaç oraya atlar ve parmağı
  birebir takip eder (eski 70 ms'lik sıçramalı güncelleme kaldırıldı; sarma isteği
  ~40 ms'de bir birleştirilir).
- Tam ekranda **sol üst = A (makas)**, **sağ üst = B** düğmesi. A konunca B düğmesi
  sarıya döner; B'ye basınca A–B aralığı kesilecekler listesine eklenir.
- A konmadan B'ye basılırsa "önce A noktasını koy" uyarısı görünür.

## 5) Sürüklemede sahne atlaması ve bekleme bitti
- Sürükleme sırasında `SeekParameters.CLOSEST_SYNC` yerine **40 ms tolerans**
  (`SEEK_SCRUB`) kullanılıyor: oynatıcı anahtar kareye değil, hedef ana oturuyor.
  (Sahne atlamasının sebebi buydu.) Parmak kalkınca 350 ms toleranslı kare hassas
  konuma geçilir, kesim noktası tam görünür.
- Yeni `data/VideoThumbnails.kt`: sürükleme sırasında parmağın altındaki anın
  **gerçek karesini** üretir (`MediaMetadataRetriever`, `OPTION_CLOSEST`, 480 px,
  LRU önbellek, tek retriever + `Mutex`, iptal edilen istekler düşer).
- `ScrubPreviewBubble`: çubuğun üstünde küçük kare + zaman etiketi gösterilir.
  Böylece 20 dakikalık videoda bile her yarım saniyedeki sahne görülebilir.

## 6) Tam ekranda ortada yatay sürükleme = ileri/geri sarma
- `FullscreenPlayerDialog` içinde ikinci jest tanıyıcı (`detectHorizontalDragGestures`)
  eklendi: ekranın ortasında parmakla sağa/sola sürükleyince video takip eder.
- Sürükleme tüm video süresine ölçeklenir; ortada hedef zaman ve `+/- N sn` göstergesi
  çıkar. Bırakınca kare hassas konuma oturur. Çift dokunma (±10 sn) ve tek dokunma
  (kontroller) davranışı aynen korunur.

## Değişen dosyalar
1. `app/src/main/java/com/example/muxmaster/ui/screens/TrimScreen.kt`
2. `app/src/main/java/com/example/muxmaster/ui/components/Sliders.kt`
3. `app/src/main/java/com/example/muxmaster/data/VideoThumbnails.kt` **(yeni)**
4. `DEGISIKLIKLER.md`

# MuxMaster — Güncelleme 7 / Değişiklik Özeti

5 dosya değişti. Proje **GitHub'da hatasız derleniyor** (AGP 8.3.0 / Kotlin 1.9.22 /
Gradle 8.5 / compileSdk 34).

## 1) Kırpma: video tam ekran + YouTube gibi slider + A–B hızlı damgalama
- Video seçildiği an oynatıcı **otomatik tam ekran** açılıyor (`TrimPlayer` →
  `LaunchedEffect(video.uri)`). Geri okuyla kırpma ekranına dönülür.
- Tam ekranda slider YouTube gibi: parmak çubuğa inince oynatıcı takip eder
  (`MuxSeekSlider` sürükleme jesti zaten Initial aşamasında tüketiliyor).
- Oynatıcının **sol üst köşesinde** (geri okunun hemen altında) makas düğmesi:
  - 1. dokunuş → o anı **A** olarak damgalar (düğme sarıya döner, çubukta sarı
    bölge A ile tutamaç arasını gösterir).
  - 2. dokunuş → **B** damgası; aradaki bölüm otomatik olarak kesilecekler
    listesine girer. Dakika/saniye yazmaya gerek yok.
  - `TrimViewModel.addCutRangeExact(start, end)` eklendi; yanlış damga için
    `removeLastCutRange()` ve tam ekranda "N kesim" düğmesi (geri al).
- Kesilecek aralıklar artık oynatıcı çubuğunda **kırmızı** gösteriliyor
  (`MuxSeekSlider(markedRanges, markStartMs)`), böylece ne kesildiği tek bakışta görünüyor.
- Aynı makas düğmesi tam ekran dışındaki oynatıcıda da var (sağ üst sırada).

## 2) Klasör anında açılıyor (150–200 video bile)
- `DocumentFile.listFiles()` her dosya için ayrı sağlayıcı sorgusu yapıyordu
  (`isFile` + `name` + `type` → dosya başına 3-4 sorgu).
- Yeni `InputAccess.listMediaChildren()` klasörü **tek cursor sorgusuyla** okuyor
  (`DocumentsContract.buildChildDocumentsUriUsingTree`; ad + mime + boyut aynı satırda).
- Kırpma (`TrimViewModel`) ve mux (`MuxViewModel`) klasör seçimi bu hızlı yola geçti.
  Sağlayıcı hızlı sorguyu reddederse eski yola otomatik dönülür.

## 3) Tam ekran gerçekten tam ekran
- `FullscreenPlayerDialog`: dialog penceresinin arkası **opak siyah** yapıldı
  (`Window.setBackgroundDrawable`) ve karartma `dimAmount = 1.0`.
  Önceden yarı saydam pencere yüzünden arkadaki kırpma seçenekleri görünüyordu.
- İlk kare gelene kadar siyah ekran yerine yükleme halkası (`showLoading`).

## 4) Hızlı kesim akışı (özet)
1. Klasör Seç → video otomatik tam ekran açılır.
2. Makas düğmesi (sol üst) → A, slider'ı/oynatıcıyı ilerlet → makas → B.
3. Aralık kırmızı olarak çubukta görünür; istenirse "N kesim" ile son damga geri alınır.
4. Geri oku → "ÇOKLU KESİMİ UYGULA".

# MuxMaster — Güncelleme 6 / Değişiklik Özeti

11 dosya değişti. Proje **GitHub'da hatasız derleniyor** (AGP 8.3.0 / Kotlin 1.9.22 /
Gradle 8.5 / compileSdk 34; `assembleRelease` + R8 + resource shrink + lintVital temiz).

## 1) Tam ekran / üst panel çakışması
- `MainActivity`: `WindowCompat.setDecorFitsSystemWindows(window, false)` → uygulama
  kenardan kenara çiziyor (Android 15+ ve üretici ROM'larında içerik durum çubuğunun
  altında kalıp başlıkla çakışmıyor).
- `Theme`: durum/gezinme çubuğu **şeffaf**, sistem kontrast perdesi kapalı.
- `HomeScreen`: içerik `WindowInsets.safeDrawing` kadar içeride başlıyor; gradyan ise
  çubukların arkasına kadar uzanıyor (başlık artık saatin altında kalmıyor).
- Scaffold kullanan diğer ekranlar insetleri kendisi yönetiyor (ek kod gerekmedi).

## 2) Video kırpma: çoklu kesim artık hızlı ve sade
- Uzun "kart + dk/sn/ms alanları" yerine **tek satırlık aralık listesi**
  (`CutRangeRow`): dokun → seç (oynatıcı aralığın başına atlar), kalem → hassas
  alanlar açılır, çöp → sil.
- Yeni **"Başlangıç = oynatıcı" / "Bitiş = oynatıcı"** düğmeleri: videoyu oynat,
  tek dokunuşla kesim noktasını oturt (`setCutStartFromPlayhead` /
  `setCutEndFromPlayhead`).
- Zaman çizelgesine **dokununca oynatıcı oraya atlıyor** (`CutTimelineBar.onTapMs`).
- Başlıktaki tekrar eden "Aralık ekle" çipi kaldırıldı; makas düğmesi moda göre
  çalışıyor (tek aralıkta seçimi kesime çevirir, çoklu kesimde yeni aralık açar).
- `addCutRange` / `addCutRangeFromSelection` yeni aralığın indeksini döndürüyor →
  eklenen aralık otomatik seçili geliyor.

## 3) Dil değişince ana ekran da değişiyor
- `HomeScreen`'deki tüm sabit Türkçe yazılar `strings.xml`'e taşındı
  (`values` = tr, `values-en`, `values-de`).
- `MainActivity`'deki açılış/izin diyalogları da aynı şekilde yerelleştirildi.
- `AndroidManifest`: `AppLocalesMetadataHolderService` + `autoStoreLocales` →
  seçilen dil uygulama yeniden açıldığında da korunuyor.

## 4) Yatay mod + yumuşak dönme animasyonu
- `HomeScreen` duyarlı: yatay/tablette **4 kart tek satırda**, kart yüksekliği
  126dp, ikon 34dp, başlık 17sp; içerik 780dp ile sınırlanıp ortalanıyor
  (dev simgeler / dağınık arayüz bitti). Dikeyde eski 2×2 düzen aynı.
- `MainActivity`: dönme anında tam ekran bir perde 220 ms'de yumuşakça açılıyor
  (alpha draw fazında okunur → yeniden kompozisyon/ek CPU yükü yok; SurfaceView
  video katmanı da perdenin altında kaldığı için geçiş akıcı).

## 5) Oynatıcı / kırpma ekranı hızı
- `TrimPlayer` artık **çözülmüş dosya yolundan** (`video.cachePath`) oynatıyor;
  her geçişte içerik sağlayıcısına tekrar sorulmuyor.
- Sürükleme sırasında `SeekParameters.CLOSEST_SYNC` (en yakın anahtar kare → görüntü
  anında), parmak kalkınca 350 ms toleranslı kare hassas sarma. Kesim doğruluğu
  ffmpeg tarafında değişmedi.
- `TrimViewModel`: ffprobe sonuçları URI bazında önbelleğe alınıyor; klasörde
  ileri/geri gezinirken analiz tekrar edilmiyor. Komşu dosyaların meta bilgisi
  arka planda hazırlanıyor (`prefetchQueueNeighbors`) — kopyalama yapmadan
  (`InputAccess.acquireNoCopy`).

---

# Diff

diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/AndroidManifest.xml src/Muxmaster-main/app/src/main/AndroidManifest.xml
--- orig/x/Muxmaster-main/app/src/main/AndroidManifest.xml	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/AndroidManifest.xml	2026-11-10 13:24:58.770848736 +0000
@@ -55,6 +55,18 @@
             android:exported="false"
             android:foregroundServiceType="dataSync|mediaPlayback" />
 
+        <!-- Ayarlar'dan seçilen uygulama dili kalıcı olsun: AppCompat seçilen yerel
+             ayarı (locale) kendisi saklar ve uygulama yeniden açıldığında geri yükler.
+             Bu servis olmadan dil yalnızca o oturum için geçerli oluyordu. -->
+        <service
+            android:name="androidx.appcompat.app.AppLocalesMetadataHolderService"
+            android:enabled="false"
+            android:exported="false">
+            <meta-data
+                android:name="autoStoreLocales"
+                android:value="true" />
+        </service>
+
         <activity
             android:name=".MainActivity"
             android:exported="true"
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/MainActivity.kt src/Muxmaster-main/app/src/main/java/com/example/muxmaster/MainActivity.kt
--- orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/MainActivity.kt	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/java/com/example/muxmaster/MainActivity.kt	2026-11-10 13:35:30.284351558 +0000
@@ -11,8 +11,14 @@
 import androidx.activity.result.contract.ActivityResultContracts
 import androidx.activity.viewModels
 import androidx.appcompat.app.AppCompatActivity
+import androidx.compose.animation.core.Animatable
+import androidx.compose.animation.core.LinearOutSlowInEasing
+import androidx.compose.animation.core.tween
+import androidx.compose.foundation.background
 import androidx.compose.foundation.layout.Arrangement
+import androidx.compose.foundation.layout.Box
 import androidx.compose.foundation.layout.Column
+import androidx.compose.foundation.layout.fillMaxSize
 import androidx.compose.foundation.layout.fillMaxWidth
 import androidx.compose.foundation.layout.padding
 import androidx.compose.material.icons.Icons
@@ -37,8 +43,13 @@
 import androidx.compose.runtime.saveable.rememberSaveable
 import androidx.compose.runtime.setValue
 import androidx.compose.ui.Modifier
+import androidx.compose.ui.graphics.Color
+import androidx.compose.ui.graphics.graphicsLayer
 import androidx.compose.ui.graphics.vector.ImageVector
+import androidx.compose.ui.platform.LocalConfiguration
+import androidx.compose.ui.res.stringResource
 import androidx.compose.ui.unit.dp
+import androidx.core.view.WindowCompat
 import com.example.muxmaster.data.AppPreferences
 import com.example.muxmaster.data.RecentFiles
 import com.example.muxmaster.data.DriveStreamResolver
@@ -291,6 +302,14 @@
     override fun onCreate(savedInstanceState: Bundle?) {
         super.onCreate(savedInstanceState)
 
+        // KENARDAN KENARA (edge-to-edge) — TAM EKRAN DÜZELTMESİ:
+        // Uygulama artık sistem çubuklarının (durum çubuğu / gezinme çubuğu) ARKASINA
+        // kadar çiziyor. Böylece üstteki panel gerçekten ekranın en üstüne kadar
+        // uzanıyor; başlık ve içerik ise her ekranda güvenli alan kadar içeride
+        // kalıyor (Scaffold/WindowInsets). Önceden Android 15+ ve bazı üretici
+        // ROM'larında içerik durum çubuğunun ALTINDA kalıp başlıkla çakışıyordu.
+        WindowCompat.setDecorFitsSystemWindows(window, false)
+
         if (savedInstanceState == null &&
             !muxViewModel.isMuxing && !trimViewModel.isTrimming &&
             !converterViewModel.isConverting && !mergeViewModel.isMerging
@@ -321,18 +340,17 @@
                 if (askStorage.value) {
                     AlertDialog(
                         onDismissRequest = { askStorage.value = false },
-                        title = { Text("Depolama izni") },
-                        text = {
-                            Text(
-                                "Videoların önbelleğe kopyalanmadan, beklemeden doğrudan açılması için " +
-                                    "\"Tüm dosyalara erişim\" iznini açın."
-                            )
-                        },
+                        title = { Text(stringResource(R.string.dialog_storage_title)) },
+                        text = { Text(stringResource(R.string.dialog_storage_text)) },
                         confirmButton = {
-                            TextButton(onClick = { askStorage.value = false; requestStorageAccess() }) { Text("İzin ver") }
+                            TextButton(onClick = { askStorage.value = false; requestStorageAccess() }) {
+                                Text(stringResource(R.string.dialog_storage_allow))
+                            }
                         },
                         dismissButton = {
-                            TextButton(onClick = { askStorage.value = false }) { Text("Şimdi değil") }
+                            TextButton(onClick = { askStorage.value = false }) {
+                                Text(stringResource(R.string.dialog_storage_later))
+                            }
                         }
                     )
                 }
@@ -371,6 +389,25 @@
                     )
                 }
 
+                // ── YUMUŞAK DÖNME GEÇİŞİ ──────────────────────────────────────
+                // Cihaz yatay/dikey çevrildiğinde yerleşim anında değişiyordu ("çat"
+                // etkisi). Artık dönme anında tam ekran bir perde kısa süreliğine
+                // kapanıp yumuşakça açılıyor: geçiş akıcı görünüyor, kare kaybı yok.
+                // Perde sadece GPU'da saydamlık olarak çizilir (alpha, draw fazında
+                // okunur) — yeniden kompozisyon, ek kare üretimi veya CPU yükü yok.
+                val orientation = LocalConfiguration.current.orientation
+                val rotationScrim = remember { Animatable(0f) }
+                var firstOrientation by remember { mutableStateOf(true) }
+                LaunchedEffect(orientation) {
+                    if (firstOrientation) {
+                        firstOrientation = false
+                    } else {
+                        rotationScrim.snapTo(0.75f)
+                        rotationScrim.animateTo(0f, animationSpec = tween(220, easing = LinearOutSlowInEasing))
+                    }
+                }
+
+                Box(modifier = Modifier.fillMaxSize()) {
                 when (screen) {
                     0 -> MuxScreen(
                         viewModel = muxViewModel,
@@ -482,6 +519,15 @@
                         }
                     )
                 }
+
+                // Dönme perdesi (dokunma olaylarını yakalamaz, sadece görsel).
+                Box(
+                    modifier = Modifier
+                        .fillMaxSize()
+                        .background(Color.Black)
+                        .graphicsLayer { alpha = rotationScrim.value }
+                )
+                }
             }
         }
     }
@@ -569,22 +615,22 @@
 ) {
     AlertDialog(
         onDismissRequest = onDismiss,
-        title = { Text("Videoyu nasıl oynatmak istiyorsunuz?") },
+        title = { Text(stringResource(R.string.dialog_play_how_title)) },
         text = {
             Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                 Text(fileName, maxLines = 2)
                 OutlinedButton(onClick = onPlaySingle, modifier = Modifier.fillMaxWidth()) {
                     Icon(Icons.Filled.PlayCircle, null, modifier = Modifier.padding(end = 10.dp))
-                    Text("Tek video olarak oynat", modifier = Modifier.weight(1f))
+                    Text(stringResource(R.string.dialog_play_single), modifier = Modifier.weight(1f))
                 }
                 OutlinedButton(onClick = onPlayMulti, modifier = Modifier.fillMaxWidth()) {
                     Icon(Icons.Filled.GridView, null, modifier = Modifier.padding(end = 10.dp))
-                    Text("Çoklu videoya ekle (Multi Video)", modifier = Modifier.weight(1f))
+                    Text(stringResource(R.string.dialog_play_multi), modifier = Modifier.weight(1f))
                 }
             }
         },
         confirmButton = {},
-        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
+        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
     )
 }
 
@@ -608,19 +654,19 @@
     }
     AlertDialog(
         onDismissRequest = onDismiss,
-        title = { Text("Ne yapmak istiyorsunuz?") },
+        title = { Text(stringResource(R.string.dialog_open_with_title)) },
         text = {
             Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                 Text(fileName, maxLines = 2)
-                Choice(Icons.Filled.PlayCircle, "Oynat (video oynatıcı)", onPlaySingle)
-                Choice(Icons.Filled.GridView, "Çoklu Oynat (Multi Video)", onPlayMulti)
-                Choice(Icons.Filled.Layers, "Mux Master (ses / altyazı ekle-çıkar)", onMux)
-                Choice(Icons.Filled.ContentCut, "Kırp / Kes", onTrim)
-                Choice(Icons.Filled.CallMerge, "Birleştir", onMerge)
-                Choice(Icons.Filled.GraphicEq, "Sesi dönüştür", onConvert)
+                Choice(Icons.Filled.PlayCircle, stringResource(R.string.open_with_play), onPlaySingle)
+                Choice(Icons.Filled.GridView, stringResource(R.string.open_with_multi), onPlayMulti)
+                Choice(Icons.Filled.Layers, stringResource(R.string.open_with_mux), onMux)
+                Choice(Icons.Filled.ContentCut, stringResource(R.string.open_with_trim), onTrim)
+                Choice(Icons.Filled.CallMerge, stringResource(R.string.open_with_merge), onMerge)
+                Choice(Icons.Filled.GraphicEq, stringResource(R.string.open_with_convert), onConvert)
             }
         },
         confirmButton = {},
-        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
+        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
     )
 }
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/data/InputAccess.kt src/Muxmaster-main/app/src/main/java/com/example/muxmaster/data/InputAccess.kt
--- orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/data/InputAccess.kt	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/java/com/example/muxmaster/data/InputAccess.kt	2026-11-10 13:25:36.059478599 +0000
@@ -46,6 +46,17 @@
         return copyToCache(context, uri, copyDir, copyName)
     }
 
+    /**
+     * Yalnızca KOPYALAMADAN okunabilen bir yol döndürür; kopya gerekiyorsa null.
+     *
+     * Arka planda yapılan ön hazırlık (önbelleğe alma) için kullanılır: büyük bir
+     * videoyu sırf meta bilgisini okumak için önbelleğe kopyalamaya kalkmaz.
+     */
+    fun acquireNoCopy(context: Context, uri: Uri): String? {
+        realPath(context, uri)?.let { realPaths.add(it); return it }
+        return direct(context, uri, needChildRead = false)
+    }
+
     /** URI'yi cihazdaki gerçek dosya yoluna çevirir (okunabiliyorsa). */
     fun realPath(context: Context, uri: Uri): String? {
         val candidates = mutableListOf<String>()
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/components/Sliders.kt src/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/components/Sliders.kt
--- orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/components/Sliders.kt	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/components/Sliders.kt	2026-11-10 13:26:43.055196150 +0000
@@ -3,6 +3,7 @@
 import androidx.compose.foundation.Canvas
 import androidx.compose.foundation.gestures.awaitEachGesture
 import androidx.compose.foundation.gestures.awaitFirstDown
+import androidx.compose.foundation.gestures.detectTapGestures
 import androidx.compose.foundation.layout.fillMaxWidth
 import androidx.compose.foundation.layout.height
 import androidx.compose.runtime.Composable
@@ -301,12 +302,29 @@
     modifier: Modifier = Modifier,
     keepColor: Color = Color(0xFF7C6FE0),
     cutColor: Color = Color(0xFFE24B4A),
-    height: Dp = 34.dp
+    height: Dp = 34.dp,
+    onTapMs: ((Long) -> Unit)? = null
 ) {
     Canvas(
         modifier = modifier
             .fillMaxWidth()
             .height(height)
+            .then(
+                if (onTapMs == null) {
+                    Modifier
+                } else {
+                    Modifier.pointerInput(durationMs, onTapMs) {
+                        // Tek dokunuş: çubuğun dokunulan yerine atla (hızlı kesim akışı).
+                        detectTapGestures { offset ->
+                            val w = size.width.toFloat()
+                            if (w > 0f) {
+                                val fraction = (offset.x / w).coerceIn(0f, 1f)
+                                onTapMs((fraction * durationMs.toFloat()).toLong())
+                            }
+                        }
+                    }
+                }
+            )
     ) {
         val total = durationMs.coerceAtLeast(1L).toFloat()
         val barHeight = 16.dp.toPx()
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/screens/HomeScreen.kt src/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/screens/HomeScreen.kt
--- orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/screens/HomeScreen.kt	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/screens/HomeScreen.kt	2026-11-10 13:24:01.881166369 +0000
@@ -9,16 +9,21 @@
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.layout.Arrangement
 import androidx.compose.foundation.layout.Box
+import androidx.compose.foundation.layout.BoxWithConstraints
 import androidx.compose.foundation.layout.Column
 import androidx.compose.foundation.layout.PaddingValues
 import androidx.compose.foundation.layout.Row
 import androidx.compose.foundation.layout.Spacer
+import androidx.compose.foundation.layout.asPaddingValues
 import androidx.compose.foundation.layout.fillMaxSize
 import androidx.compose.foundation.layout.fillMaxWidth
 import androidx.compose.foundation.layout.height
 import androidx.compose.foundation.layout.padding
+import androidx.compose.foundation.layout.safeDrawing
 import androidx.compose.foundation.layout.size
 import androidx.compose.foundation.layout.width
+import androidx.compose.foundation.layout.widthIn
+import androidx.compose.foundation.layout.WindowInsets
 import androidx.compose.foundation.lazy.LazyColumn
 import androidx.compose.foundation.lazy.items
 import androidx.compose.foundation.shape.RoundedCornerShape
@@ -58,11 +63,14 @@
 import androidx.compose.ui.graphics.vector.ImageVector
 import androidx.compose.ui.layout.ContentScale
 import androidx.compose.ui.platform.LocalContext
+import androidx.compose.ui.res.stringResource
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.text.style.TextAlign
 import androidx.compose.ui.text.style.TextOverflow
+import androidx.compose.ui.unit.Dp
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
+import com.example.muxmaster.R
 import com.example.muxmaster.data.MediaThumbs
 import com.example.muxmaster.data.RecentFiles
 import com.example.muxmaster.data.RecentMedia
@@ -86,7 +94,15 @@
  *
  * Büyük, tek dokunuşla erişilebilen kartlar: Mux · Kes · Birleştir · Oynatıcı,
  * altında tam genişlikte "Ses Dönüştürme" kartı ve "Son Dosyalar" listesi.
- * Kartlara dokunulduğunda ilgili ekran, hiçbir ayar bozulmadan açılır.
+ *
+ * Duyarlı (responsive) yerleşim:
+ * - Dikey telefonda 2×2 kart ızgarası.
+ * - Yatay modda / tablette 4 kart TEK satırda, daha alçak kart + daha küçük ikon;
+ *   içerik ortalanır ve [MAX_CONTENT_WIDE] ile sınırlanır (ekran ne kadar geniş
+ *   olursa olsun arayüz dağılmaz, simgeler devleşmez).
+ *
+ * Yazıların tamamı `strings.xml`'den gelir: Ayarlar'dan dil değiştirildiğinde ana
+ * ekran da anında o dile geçer.
  */
 @Composable
 fun HomeScreen(
@@ -123,226 +139,318 @@
         }
     }
 
-    Box(modifier = Modifier.fillMaxSize()) {
+    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
+        // Yatay mod / tablet: geniş ve alçak ekran.
+        val wide = maxWidth > maxHeight || maxWidth >= 600.dp
+        val contentMaxWidth = if (wide) 780.dp else 640.dp
+        val cardHeight = if (wide) 126.dp else 160.dp
+        val iconSize = if (wide) 34.dp else 44.dp
+        val cardTitleSp = if (wide) 17 else 20
+        val cardSubSp = if (wide) 11 else 12
+        val converterHeight = if (wide) 74.dp else 90.dp
+        val insets = WindowInsets.safeDrawing.asPaddingValues()
+
         HomeBackdrop(Modifier.fillMaxSize())
 
-        LazyColumn(
-            modifier = Modifier.fillMaxSize(),
-            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp)
-        ) {
-            item {
-                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
-                    Column(modifier = Modifier.weight(1f)) {
-                        Text(
-                            "MuxMaster",
-                            color = Color.White,
-                            fontSize = 23.sp,
-                            fontWeight = FontWeight.Bold
-                        )
-                        Text(
-                            "Video ve ses araçların",
-                            color = HomeAccentText,
-                            fontSize = 12.sp
-                        )
-                    }
-                    IconButton(onClick = onOpenSettings) {
-                        Icon(Icons.Filled.Settings, "Ayarlar", tint = Color(0xFFCFC6F5))
+        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
+            LazyColumn(
+                modifier = Modifier
+                    .widthIn(max = contentMaxWidth)
+                    .fillMaxWidth(),
+                contentPadding = PaddingValues(
+                    start = 16.dp,
+                    end = 16.dp,
+                    top = 10.dp + insets.calculateTopPadding(),
+                    bottom = 28.dp + insets.calculateBottomPadding()
+                )
+            ) {
+                item {
+                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
+                        Column(modifier = Modifier.weight(1f)) {
+                            Text(
+                                "MuxMaster",
+                                color = Color.White,
+                                fontSize = 23.sp,
+                                fontWeight = FontWeight.Bold
+                            )
+                            Text(
+                                stringResource(R.string.home_tagline),
+                                color = HomeAccentText,
+                                fontSize = 12.sp
+                            )
+                        }
+                        IconButton(onClick = onOpenSettings) {
+                            Icon(
+                                Icons.Filled.Settings,
+                                stringResource(R.string.home_settings_desc),
+                                tint = Color(0xFFCFC6F5)
+                            )
+                        }
                     }
+                    Spacer(Modifier.height(if (wide) 10.dp else 16.dp))
                 }
-                Spacer(Modifier.height(16.dp))
-            }
 
-            item {
-                Row(
-                    horizontalArrangement = Arrangement.spacedBy(12.dp),
-                    modifier = Modifier.fillMaxWidth()
-                ) {
-                    FeatureCard(
-                        title = "Mux",
-                        subtitle = "Ses ve altyazı\nparçalarını ayır",
-                        icon = Icons.Filled.Extension,
-                        gradient = listOf(Color(0xFF8B3DFF), Color(0xFF4C1D95)),
-                        glow = Color(0xFFB388FF),
-                        onClick = onOpenMux,
-                        modifier = Modifier.weight(1f).height(160.dp)
-                    )
-                    FeatureCard(
-                        title = "Kes",
-                        subtitle = "Videoyu kes",
-                        icon = Icons.Filled.ContentCut,
-                        gradient = listOf(Color(0xFF0E8A86), Color(0xFF0B3B48)),
-                        glow = Color(0xFF4DE3DC),
-                        onClick = onOpenTrim,
-                        modifier = Modifier.weight(1f).height(160.dp)
-                    )
-                }
-                Spacer(Modifier.height(12.dp))
-                Row(
-                    horizontalArrangement = Arrangement.spacedBy(12.dp),
-                    modifier = Modifier.fillMaxWidth()
-                ) {
-                    FeatureCard(
-                        title = "Birleştir",
-                        subtitle = "Videoları birleştir",
-                        icon = Icons.Filled.Link,
-                        gradient = listOf(Color(0xFF1F5BE0), Color(0xFF0B2A6B)),
-                        glow = Color(0xFF7FB0FF),
-                        onClick = onOpenMerge,
-                        modifier = Modifier.weight(1f).height(160.dp)
-                    )
-                    FeatureCard(
-                        title = "Oynatıcı",
-                        subtitle = "Medyanızı oynat",
-                        icon = Icons.Filled.PlayCircle,
-                        gradient = listOf(Color(0xFFF97316), Color(0xFF7C2D12)),
-                        glow = Color(0xFFFFC46B),
-                        onClick = onOpenPlayer,
-                        modifier = Modifier.weight(1f).height(160.dp)
-                    )
-                }
-                Spacer(Modifier.height(14.dp))
-            }
-
-            item {
-                Row(
-                    verticalAlignment = Alignment.CenterVertically,
-                    modifier = Modifier
-                        .fillMaxWidth()
-                        .height(90.dp)
-                        .clip(RoundedCornerShape(22.dp))
-                        .background(
-                            Brush.horizontalGradient(
-                                listOf(Color(0xFF6D28D9), Color(0xFF9B5CF6))
+                item {
+                    if (wide) {
+                        // Yatay mod: dört kart tek satırda, alçak ve dengeli.
+                        Row(
+                            horizontalArrangement = Arrangement.spacedBy(10.dp),
+                            modifier = Modifier.fillMaxWidth()
+                        ) {
+                            FeatureCard(
+                                title = stringResource(R.string.home_mux_title),
+                                subtitle = stringResource(R.string.home_mux_sub),
+                                icon = Icons.Filled.Extension,
+                                gradient = listOf(Color(0xFF8B3DFF), Color(0xFF4C1D95)),
+                                glow = Color(0xFFB388FF),
+                                onClick = onOpenMux,
+                                iconSize = iconSize,
+                                titleSp = cardTitleSp,
+                                subtitleSp = cardSubSp,
+                                modifier = Modifier.weight(1f).height(cardHeight)
                             )
-                        )
-                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
-                        .clickable(onClick = onOpenConverter)
-                        .padding(horizontal = 16.dp)
-                ) {
-                    Box(
-                        modifier = Modifier
-                            .size(52.dp)
-                            .clip(RoundedCornerShape(16.dp))
-                            .background(Color.White.copy(alpha = 0.16f)),
-                        contentAlignment = Alignment.Center
-                    ) {
-                        Icon(
-                            Icons.Filled.GraphicEq,
-                            null,
-                            tint = Color.White,
-                            modifier = Modifier.size(30.dp)
-                        )
-                    }
-                    Spacer(Modifier.width(14.dp))
-                    Column(modifier = Modifier.weight(1f)) {
-                        Text(
-                            "Ses Dönüştürme",
-                            color = Color.White,
-                            fontSize = 19.sp,
-                            fontWeight = FontWeight.Bold
-                        )
-                        Text(
-                            "Sesi başka bir formata dönüştür",
-                            color = Color.White.copy(alpha = 0.88f),
-                            fontSize = 12.sp
-                        )
-                    }
-                    Box(
-                        modifier = Modifier
-                            .size(34.dp)
-                            .clip(RoundedCornerShape(50))
-                            .background(Color.White.copy(alpha = 0.18f)),
-                        contentAlignment = Alignment.Center
-                    ) {
-                        Icon(
-                            Icons.Filled.ChevronRight,
-                            null,
-                            tint = Color.White,
-                            modifier = Modifier.size(22.dp)
-                        )
+                            FeatureCard(
+                                title = stringResource(R.string.home_trim_title),
+                                subtitle = stringResource(R.string.home_trim_sub),
+                                icon = Icons.Filled.ContentCut,
+                                gradient = listOf(Color(0xFF0E8A86), Color(0xFF0B3B48)),
+                                glow = Color(0xFF4DE3DC),
+                                onClick = onOpenTrim,
+                                iconSize = iconSize,
+                                titleSp = cardTitleSp,
+                                subtitleSp = cardSubSp,
+                                modifier = Modifier.weight(1f).height(cardHeight)
+                            )
+                            FeatureCard(
+                                title = stringResource(R.string.home_merge_title),
+                                subtitle = stringResource(R.string.home_merge_sub),
+                                icon = Icons.Filled.Link,
+                                gradient = listOf(Color(0xFF1F5BE0), Color(0xFF0B2A6B)),
+                                glow = Color(0xFF7FB0FF),
+                                onClick = onOpenMerge,
+                                iconSize = iconSize,
+                                titleSp = cardTitleSp,
+                                subtitleSp = cardSubSp,
+                                modifier = Modifier.weight(1f).height(cardHeight)
+                            )
+                            FeatureCard(
+                                title = stringResource(R.string.home_player_title),
+                                subtitle = stringResource(R.string.home_player_sub),
+                                icon = Icons.Filled.PlayCircle,
+                                gradient = listOf(Color(0xFFF97316), Color(0xFF7C2D12)),
+                                glow = Color(0xFFFFC46B),
+                                onClick = onOpenPlayer,
+                                iconSize = iconSize,
+                                titleSp = cardTitleSp,
+                                subtitleSp = cardSubSp,
+                                modifier = Modifier.weight(1f).height(cardHeight)
+                            )
+                        }
+                    } else {
+                        Row(
+                            horizontalArrangement = Arrangement.spacedBy(12.dp),
+                            modifier = Modifier.fillMaxWidth()
+                        ) {
+                            FeatureCard(
+                                title = stringResource(R.string.home_mux_title),
+                                subtitle = stringResource(R.string.home_mux_sub),
+                                icon = Icons.Filled.Extension,
+                                gradient = listOf(Color(0xFF8B3DFF), Color(0xFF4C1D95)),
+                                glow = Color(0xFFB388FF),
+                                onClick = onOpenMux,
+                                iconSize = iconSize,
+                                titleSp = cardTitleSp,
+                                subtitleSp = cardSubSp,
+                                modifier = Modifier.weight(1f).height(cardHeight)
+                            )
+                            FeatureCard(
+                                title = stringResource(R.string.home_trim_title),
+                                subtitle = stringResource(R.string.home_trim_sub),
+                                icon = Icons.Filled.ContentCut,
+                                gradient = listOf(Color(0xFF0E8A86), Color(0xFF0B3B48)),
+                                glow = Color(0xFF4DE3DC),
+                                onClick = onOpenTrim,
+                                iconSize = iconSize,
+                                titleSp = cardTitleSp,
+                                subtitleSp = cardSubSp,
+                                modifier = Modifier.weight(1f).height(cardHeight)
+                            )
+                        }
+                        Spacer(Modifier.height(12.dp))
+                        Row(
+                            horizontalArrangement = Arrangement.spacedBy(12.dp),
+                            modifier = Modifier.fillMaxWidth()
+                        ) {
+                            FeatureCard(
+                                title = stringResource(R.string.home_merge_title),
+                                subtitle = stringResource(R.string.home_merge_sub),
+                                icon = Icons.Filled.Link,
+                                gradient = listOf(Color(0xFF1F5BE0), Color(0xFF0B2A6B)),
+                                glow = Color(0xFF7FB0FF),
+                                onClick = onOpenMerge,
+                                iconSize = iconSize,
+                                titleSp = cardTitleSp,
+                                subtitleSp = cardSubSp,
+                                modifier = Modifier.weight(1f).height(cardHeight)
+                            )
+                            FeatureCard(
+                                title = stringResource(R.string.home_player_title),
+                                subtitle = stringResource(R.string.home_player_sub),
+                                icon = Icons.Filled.PlayCircle,
+                                gradient = listOf(Color(0xFFF97316), Color(0xFF7C2D12)),
+                                glow = Color(0xFFFFC46B),
+                                onClick = onOpenPlayer,
+                                iconSize = iconSize,
+                                titleSp = cardTitleSp,
+                                subtitleSp = cardSubSp,
+                                modifier = Modifier.weight(1f).height(cardHeight)
+                            )
+                        }
                     }
+                    Spacer(Modifier.height(14.dp))
                 }
-                Spacer(Modifier.height(22.dp))
-            }
 
-            item {
-                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
-                    Text(
-                        "Son Dosyalar",
-                        color = Color.White,
-                        fontSize = 20.sp,
-                        fontWeight = FontWeight.Bold,
-                        modifier = Modifier.weight(1f)
-                    )
+                item {
                     Row(
                         verticalAlignment = Alignment.CenterVertically,
                         modifier = Modifier
-                            .clip(RoundedCornerShape(8.dp))
-                            .clickable(onClick = onShowAllFiles)
-                            .padding(horizontal = 6.dp, vertical = 4.dp)
+                            .fillMaxWidth()
+                            .height(converterHeight)
+                            .clip(RoundedCornerShape(22.dp))
+                            .background(
+                                Brush.horizontalGradient(
+                                    listOf(Color(0xFF6D28D9), Color(0xFF9B5CF6))
+                                )
+                            )
+                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
+                            .clickable(onClick = onOpenConverter)
+                            .padding(horizontal = 16.dp)
                     ) {
-                        Text(
-                            "Tümünü Gör",
-                            color = HomeLink,
-                            fontSize = 13.sp,
-                            fontWeight = FontWeight.SemiBold
-                        )
-                        Spacer(Modifier.width(4.dp))
-                        Icon(
-                            Icons.Filled.ArrowForward,
-                            null,
-                            tint = HomeLink,
-                            modifier = Modifier.size(16.dp)
-                        )
+                        Box(
+                            modifier = Modifier
+                                .size(if (wide) 44.dp else 52.dp)
+                                .clip(RoundedCornerShape(16.dp))
+                                .background(Color.White.copy(alpha = 0.16f)),
+                            contentAlignment = Alignment.Center
+                        ) {
+                            Icon(
+                                Icons.Filled.GraphicEq,
+                                null,
+                                tint = Color.White,
+                                modifier = Modifier.size(if (wide) 26.dp else 30.dp)
+                            )
+                        }
+                        Spacer(Modifier.width(14.dp))
+                        Column(modifier = Modifier.weight(1f)) {
+                            Text(
+                                stringResource(R.string.home_converter_title),
+                                color = Color.White,
+                                fontSize = if (wide) 17.sp else 19.sp,
+                                fontWeight = FontWeight.Bold
+                            )
+                            Text(
+                                stringResource(R.string.home_converter_sub),
+                                color = Color.White.copy(alpha = 0.88f),
+                                fontSize = 12.sp
+                            )
+                        }
+                        Box(
+                            modifier = Modifier
+                                .size(34.dp)
+                                .clip(RoundedCornerShape(50))
+                                .background(Color.White.copy(alpha = 0.18f)),
+                            contentAlignment = Alignment.Center
+                        ) {
+                            Icon(
+                                Icons.Filled.ChevronRight,
+                                null,
+                                tint = Color.White,
+                                modifier = Modifier.size(22.dp)
+                            )
+                        }
                     }
+                    Spacer(Modifier.height(if (wide) 14.dp else 22.dp))
                 }
-                Spacer(Modifier.height(10.dp))
-            }
 
-            if (recents.isEmpty()) {
                 item {
-                    Column(
-                        horizontalAlignment = Alignment.CenterHorizontally,
-                        modifier = Modifier
-                            .fillMaxWidth()
-                            .clip(RoundedCornerShape(16.dp))
-                            .background(HomeCardBg)
-                            .border(1.dp, HomeCardStroke, RoundedCornerShape(16.dp))
-                            .padding(vertical = 22.dp, horizontal = 16.dp)
-                    ) {
-                        Icon(
-                            Icons.Filled.Movie,
-                            null,
-                            tint = Color(0xFF6D5AA8),
-                            modifier = Modifier.size(30.dp)
-                        )
-                        Spacer(Modifier.height(8.dp))
-                        Text(
-                            "Henüz dosya açmadın",
-                            color = Color.White.copy(alpha = 0.85f),
-                            fontSize = 13.sp,
-                            fontWeight = FontWeight.SemiBold
-                        )
+                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                         Text(
-                            "Açtığın videolar burada listelenir.",
-                            color = HomeAccentText,
-                            fontSize = 11.sp
-                        )
+                            stringResource(R.string.home_recent_title),
+                            color = Color.White,
+                            fontSize = 20.sp,
+                            fontWeight = FontWeight.Bold,
+                            modifier = Modifier.weight(1f)
+                        )
+                        Row(
+                            verticalAlignment = Alignment.CenterVertically,
+                            modifier = Modifier
+                                .clip(RoundedCornerShape(8.dp))
+                                .clickable(onClick = onShowAllFiles)
+                                .padding(horizontal = 6.dp, vertical = 4.dp)
+                        ) {
+                            Text(
+                                stringResource(R.string.home_recent_show_all),
+                                color = HomeLink,
+                                fontSize = 13.sp,
+                                fontWeight = FontWeight.SemiBold
+                            )
+                            Spacer(Modifier.width(4.dp))
+                            Icon(
+                                Icons.Filled.ArrowForward,
+                                null,
+                                tint = HomeLink,
+                                modifier = Modifier.size(16.dp)
+                            )
+                        }
                     }
+                    Spacer(Modifier.height(10.dp))
                 }
-            } else {
-                items(recents, key = { it.uriString }) { item ->
-                    RecentRow(
-                        item = item,
-                        thumbnail = thumbs[item.uriString],
-                        durationMs = durations[item.uriString] ?: item.durationMs,
-                        onOpen = { onOpenFile(item) },
-                        onRemove = {
-                            recents = recents.filter { it.uriString != item.uriString }
-                            RecentFiles.remove(context, item.uriString)
+
+                if (recents.isEmpty()) {
+                    item {
+                        Column(
+                            horizontalAlignment = Alignment.CenterHorizontally,
+                            modifier = Modifier
+                                .fillMaxWidth()
+                                .clip(RoundedCornerShape(16.dp))
+                                .background(HomeCardBg)
+                                .border(1.dp, HomeCardStroke, RoundedCornerShape(16.dp))
+                                .padding(vertical = 22.dp, horizontal = 16.dp)
+                        ) {
+                            Icon(
+                                Icons.Filled.Movie,
+                                null,
+                                tint = Color(0xFF6D5AA8),
+                                modifier = Modifier.size(30.dp)
+                            )
+                            Spacer(Modifier.height(8.dp))
+                            Text(
+                                stringResource(R.string.home_recent_empty_title),
+                                color = Color.White.copy(alpha = 0.85f),
+                                fontSize = 13.sp,
+                                fontWeight = FontWeight.SemiBold
+                            )
+                            Text(
+                                stringResource(R.string.home_recent_empty_sub),
+                                color = HomeAccentText,
+                                fontSize = 11.sp
+                            )
                         }
-                    )
-                    Spacer(Modifier.height(8.dp))
+                    }
+                } else {
+                    items(recents, key = { it.uriString }) { item ->
+                        RecentRow(
+                            item = item,
+                            thumbnail = thumbs[item.uriString],
+                            durationMs = durations[item.uriString] ?: item.durationMs,
+                            onOpen = { onOpenFile(item) },
+                            onRemove = {
+                                recents = recents.filter { it.uriString != item.uriString }
+                                RecentFiles.remove(context, item.uriString)
+                            }
+                        )
+                        Spacer(Modifier.height(8.dp))
+                    }
                 }
             }
         }
@@ -418,6 +526,9 @@
 /**
  * Ana ekran kartı: gradyan zemin + arkada yumuşak ışık halkası + ortalanmış ikon/başlık.
  * Dokunma alanı kartın tamamıdır (tek dokunuşla ilgili ekran açılır).
+ *
+ * [iconSize] / [titleSp] / [subtitleSp] dışarıdan verilir; yatay modda kartlar daha
+ * alçak ve simgeler daha küçük olduğu için ekran "dev simgeler" görüntüsünden kurtulur.
  */
 @Composable
 private fun FeatureCard(
@@ -427,7 +538,10 @@
     gradient: List<Color>,
     glow: Color,
     onClick: () -> Unit,
-    modifier: Modifier = Modifier
+    modifier: Modifier = Modifier,
+    iconSize: Dp = 44.dp,
+    titleSp: Int = 20,
+    subtitleSp: Int = 12
 ) {
     Box(
         modifier = modifier
@@ -454,23 +568,25 @@
             verticalArrangement = Arrangement.Center,
             modifier = Modifier
                 .fillMaxSize()
-                .padding(horizontal = 10.dp, vertical = 14.dp)
+                .padding(horizontal = 10.dp, vertical = 10.dp)
         ) {
-            Icon(icon, null, tint = Color.White, modifier = Modifier.size(44.dp))
-            Spacer(Modifier.height(10.dp))
+            Icon(icon, null, tint = Color.White, modifier = Modifier.size(iconSize))
+            Spacer(Modifier.height(if (iconSize.value >= 40f) 10.dp else 6.dp))
             Text(
                 title,
                 color = Color.White,
-                fontSize = 20.sp,
+                fontSize = titleSp.sp,
                 fontWeight = FontWeight.Bold,
-                textAlign = TextAlign.Center
+                textAlign = TextAlign.Center,
+                maxLines = 1,
+                overflow = TextOverflow.Ellipsis
             )
             Spacer(Modifier.height(4.dp))
             Text(
                 subtitle,
                 color = Color.White.copy(alpha = 0.85f),
-                fontSize = 12.sp,
-                lineHeight = 15.sp,
+                fontSize = subtitleSp.sp,
+                lineHeight = (subtitleSp + 3).sp,
                 textAlign = TextAlign.Center,
                 maxLines = 2,
                 overflow = TextOverflow.Ellipsis
@@ -546,21 +662,21 @@
             IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                 Icon(
                     Icons.Filled.MoreVert,
-                    "Diğer seçenekler",
+                    stringResource(R.string.home_more_desc),
                     tint = Color(0xFF9C8FD6),
                     modifier = Modifier.size(20.dp)
                 )
             }
             DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                 DropdownMenuItem(
-                    text = { Text("Oynat") },
+                    text = { Text(stringResource(R.string.home_action_play)) },
                     onClick = {
                         menuOpen = false
                         onOpen()
                     }
                 )
                 DropdownMenuItem(
-                    text = { Text("Listeden çıkar") },
+                    text = { Text(stringResource(R.string.home_action_remove)) },
                     onClick = {
                         menuOpen = false
                         onRemove()
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/screens/TrimScreen.kt src/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/screens/TrimScreen.kt
--- orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/screens/TrimScreen.kt	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/screens/TrimScreen.kt	2026-11-10 13:30:08.807946968 +0000
@@ -15,6 +15,7 @@
 import androidx.activity.compose.BackHandler
 import androidx.compose.foundation.BorderStroke
 import androidx.compose.foundation.background
+import androidx.compose.foundation.border
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.combinedClickable
 import androidx.compose.foundation.horizontalScroll
@@ -36,7 +37,9 @@
 import androidx.compose.material.icons.filled.Close
 import androidx.compose.material.icons.filled.ContentCut
 import androidx.compose.material.icons.filled.Delete
+import androidx.compose.material.icons.filled.Edit
 import androidx.compose.material.icons.filled.ErrorOutline
+import androidx.compose.material.icons.filled.ExpandLess
 import androidx.compose.material.icons.filled.Folder
 import androidx.compose.material.icons.filled.FolderOpen
 import androidx.compose.material.icons.filled.Forward10
@@ -149,6 +152,13 @@
 /** Yüklenme halkası (LoadingRing) ve oynatıcı slider'ı için turkuaz vurgu rengi. */
 internal val PlayerTurquoise = Color(0xFF66C2B4)
 
+/**
+ * Kare hassas sarma: hedefe 350 ms toleransla en yakın anahtar kareden başlanır.
+ * (Sürükleme biterken kullanılır; sürükleme sırasında [SeekParameters.CLOSEST_SYNC]
+ * kullanılıp görüntünün ANINDA gelmesi sağlanır.)
+ */
+private val SEEK_EXACT = SeekParameters(350_000L, 350_000L)
+
 private fun formatClock(ms: Long): String {
     val safe = ms.coerceAtLeast(0L)
     val m = safe / 60000
@@ -183,6 +193,10 @@
     // her saniye tüm ekranın yeniden çizilmemesi için State olarak tutulur.
     val playheadRef = remember { mutableLongStateOf(0L) }
 
+    // Oynatıcıya DIŞARIDAN sarma isteği (zaman çizelgesine dokunma, aralık seçme).
+    // -1 = istek yok. Değer yazıldığında oynatıcı o ana atlar ve isteği tüketir.
+    val seekRequest = remember { mutableLongStateOf(-1L) }
+
     Scaffold(
         containerColor = BgDark,
         topBar = {
@@ -195,9 +209,13 @@
                 },
                 actions = {
                     if (video != null) {
-                        // MAKAS: oynatıcının bulunduğu yerden yeni bir kesim aralığı ekler.
+                        // MAKAS: tek aralık modunda seçili aralığı kesim listesine ekler,
+                        // çoklu kesim modunda oynatıcının bulunduğu yerden yeni aralık açar.
                         IconButton(
-                            onClick = { viewModel.addCutRangeFromSelection(playheadRef.longValue) },
+                            onClick = {
+                                if (viewModel.isMultiCut) viewModel.addCutRange(playheadRef.longValue)
+                                else viewModel.addCutRangeFromSelection(playheadRef.longValue)
+                            },
                             enabled = !busy
                         ) {
                             Icon(
@@ -227,7 +245,8 @@
                     endMs = if (viewModel.isMultiCut) durationMs else viewModel.trimEndMs,
                     isTrimming = viewModel.isTrimming,
                     followRangeChange = !viewModel.isMultiCut,
-                    playheadRef = playheadRef
+                    playheadRef = playheadRef,
+                    seekRequestMs = seekRequest
                 )
                 Spacer(Modifier.height(12.dp))
             }
@@ -309,64 +328,95 @@
                 // ── Kırpma / kesim başlığı ──────────────────────────────────
                 Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                     Text(
-                        if (viewModel.isMultiCut) "Çoklu kesim (makas)" else "Kırpma Aralığı",
+                        if (viewModel.isMultiCut) "Çoklu kesim" else "Kırpma Aralığı",
                         color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                     )
-                    Spacer(Modifier.weight(1f))
-                    // MAKAS: seçili aralığı (Başlangıç–Bitiş alanları) doğrudan
-                    // "kesilecek aralık" yapar; çoklu kesim modunda yenisini ekler.
-                    Row(
-                        verticalAlignment = Alignment.CenterVertically,
-                        modifier = Modifier
-                            .clip(RoundedCornerShape(20.dp))
-                            .background(Amber.copy(alpha = 0.14f))
-                            .clickable(enabled = !viewModel.isTrimming) {
-                                viewModel.addCutRangeFromSelection(playheadRef.longValue)
-                            }
-                            .padding(horizontal = 10.dp, vertical = 6.dp)
-                    ) {
-                        Icon(Icons.Filled.ContentCut, null, tint = Amber, modifier = Modifier.size(15.dp))
-                        Spacer(Modifier.width(6.dp))
-                        Text(
-                            if (viewModel.isMultiCut) "Aralık ekle" else "Makasla kes",
-                            color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
-                        )
-                    }
                 }
                 Spacer(Modifier.height(8.dp))
 
                 if (viewModel.isMultiCut) {
-                    // ── ÇOKLU KESİM: birden fazla aralık silinir, kalanlar birleştirilir ──
+                    // ── ÇOKLU KESİM (hızlı akış) ────────────────────────────────
+                    // 1) Zaman çizelgesine dokun → oynatıcı oraya atlar.
+                    // 2) "Başlangıç"/"Bitiş" düğmeleri aralığı oynatıcının yerine oturtur.
+                    // 3) İstersen satırdaki kalemle hassas (dk/sn/ms) alanları aç.
+                    // Böylece 20 dakikalık videoda 5 kesim, saniyeler içinde yapılır.
+                    val ranges = viewModel.cutRanges
+                    var selectedCut by remember { mutableIntStateOf(0) }
+                    var expandedCut by remember { mutableIntStateOf(-1) }
+                    val sel = selectedCut.coerceIn(0, (ranges.size - 1).coerceAtLeast(0))
+                    val selectedRange = ranges.getOrNull(sel)
+
                     CutTimelineBar(
                         durationMs = durationMs,
-                        cutRanges = viewModel.cutRanges,
+                        cutRanges = ranges,
                         playheadMs = { playheadRef.longValue },
                         keepColor = Purple,
+                        onTapMs = { ms -> seekRequest.longValue = ms },
                         modifier = Modifier.fillMaxWidth()
                     )
-                    Text(
-                        "Kırmızı: videodan çıkarılacak · Mor: korunacak. Kalan süre: " +
-                            formatClock(viewModel.keptDurationMs),
-                        color = TextMuted, fontSize = 10.sp
-                    )
-                    Spacer(Modifier.height(10.dp))
+                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
+                        Text(
+                            "${ranges.size} aralık · Kalan ${formatClock(viewModel.keptDurationMs)}",
+                            color = TextSec, fontSize = 11.sp, modifier = Modifier.weight(1f)
+                        )
+                        TextButton(onClick = viewModel::clearCutRanges, enabled = !viewModel.isTrimming) {
+                            Text("Temizle", color = TextMuted, fontSize = 11.sp)
+                        }
+                    }
+                    Spacer(Modifier.height(6.dp))
+
+                    // Tek dokunuşluk kesim noktası düğmeleri (seçili aralık için).
+                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
+                        CutPointButton(
+                            label = "Başlangıç = oynatıcı",
+                            enabled = selectedRange != null && !viewModel.isTrimming,
+                            accent = Purple,
+                            onClick = { viewModel.setCutStartFromPlayhead(sel, playheadRef.longValue) },
+                            modifier = Modifier.weight(1f)
+                        )
+                        CutPointButton(
+                            label = "Bitiş = oynatıcı",
+                            enabled = selectedRange != null && !viewModel.isTrimming,
+                            accent = Purple,
+                            onClick = { viewModel.setCutEndFromPlayhead(sel, playheadRef.longValue) },
+                            modifier = Modifier.weight(1f)
+                        )
+                    }
+                    Spacer(Modifier.height(8.dp))
 
-                    viewModel.cutRanges.forEachIndexed { index, range ->
+                    ranges.forEachIndexed { index, range ->
                         key(index) {
-                            CutRangeCard(
+                            CutRangeRow(
                                 index = index,
                                 range = range,
+                                selected = index == sel,
+                                expanded = index == expandedCut,
                                 enabled = !viewModel.isTrimming,
+                                onSelect = {
+                                    selectedCut = index
+                                    seekRequest.longValue = range.startMs
+                                },
+                                onToggleExpand = {
+                                    selectedCut = index
+                                    expandedCut = if (expandedCut == index) -1 else index
+                                },
                                 onStartChange = { viewModel.updateCutRange(index, it, range.endMs) },
                                 onEndChange = { viewModel.updateCutRange(index, range.startMs, it) },
-                                onRemove = { viewModel.removeCutRange(index) }
+                                onRemove = {
+                                    viewModel.removeCutRange(index)
+                                    if (expandedCut == index) expandedCut = -1
+                                }
                             )
-                            Spacer(Modifier.height(8.dp))
+                            Spacer(Modifier.height(6.dp))
                         }
                     }
 
+                    Spacer(Modifier.height(2.dp))
                     OutlinedButton(
-                        onClick = { viewModel.addCutRange(playheadRef.longValue) },
+                        onClick = {
+                            val newIndex = viewModel.addCutRange(playheadRef.longValue)
+                            if (newIndex >= 0) selectedCut = newIndex
+                        },
                         enabled = !viewModel.isTrimming,
                         modifier = Modifier.fillMaxWidth(),
                         shape = RoundedCornerShape(12.dp),
@@ -379,17 +429,7 @@
                             color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                         )
                     }
-                    Spacer(Modifier.height(2.dp))
-                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
-                        Text(
-                            "${viewModel.cutRanges.size} aralık çıkarılacak · Kalan " +
-                                formatClock(viewModel.keptDurationMs),
-                            color = TextSec, fontSize = 11.sp, modifier = Modifier.weight(1f)
-                        )
-                        TextButton(onClick = viewModel::clearCutRanges, enabled = !viewModel.isTrimming) {
-                            Text("Tek aralığa dön", color = TextMuted, fontSize = 11.sp)
-                        }
-                    }
+                    Spacer(Modifier.height(4.dp))
                     Text(
                         "Kırmızı aralıklar videodan çıkarılır, mor bölgeler sırayla birleştirilir. " +
                             "Kesim kayıpsızdır (yeniden kodlama yok).",
@@ -421,24 +461,20 @@
                         color = TextMuted, fontSize = 10.sp
                     )
 
-                    // Çoklu kesim moduna geçiş ipucu (aynı zamanda tıklanabilir kısayol).
+                    // Tek dokunuş: seçili aralığı videodan çıkar (çoklu kesim moduna geçer).
                     Spacer(Modifier.height(10.dp))
-                    Row(
-                        verticalAlignment = Alignment.CenterVertically,
-                        modifier = Modifier
-                            .fillMaxWidth()
-                            .clip(RoundedCornerShape(10.dp))
-                            .clickable(enabled = !viewModel.isTrimming) {
-                                viewModel.addCutRangeFromSelection(playheadRef.longValue)
-                            }
-                            .padding(horizontal = 4.dp, vertical = 6.dp)
+                    OutlinedButton(
+                        onClick = { viewModel.addCutRangeFromSelection(playheadRef.longValue) },
+                        enabled = !viewModel.isTrimming,
+                        modifier = Modifier.fillMaxWidth(),
+                        shape = RoundedCornerShape(10.dp),
+                        border = BorderStroke(1.dp, Amber)
                     ) {
-                        Icon(Icons.Filled.ContentCut, null, tint = Amber, modifier = Modifier.size(15.dp))
+                        Icon(Icons.Filled.ContentCut, null, tint = Amber, modifier = Modifier.size(16.dp))
                         Spacer(Modifier.width(8.dp))
                         Text(
-                            "Makas (yukarıdaki) seçili aralığı kesim listesine ekler; " +
-                                "birden fazla aralık kesilip kalanlar birleştirilir.",
-                            color = Amber, fontSize = 11.sp
+                            "BU ARALIĞI VİDEODAN ÇIKAR (MAKAS)",
+                            color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                         )
                     }
 
@@ -610,14 +646,21 @@
 }
 
 /**
- * Çoklu kesim listesindeki tek bir "kesilecek aralık" kartı: başlangıç ve bitiş
- * zamanı dakika/saniye/salise alanlarıyla düzenlenebilir.
+ * Çoklu kesim listesindeki tek satırlık aralık özeti.
+ *
+ * Eski tasarımda her aralık, iki satır dolusu dk/sn/ms alanıyla açılıyordu; 5 aralıkta
+ * ekran karmaşık bir forma dönüşüyordu. Yeni satır TEK satır: dokun → aralığı seç
+ * (oynatıcı aralığın başına atlar), kalem → hassas alanlar açılır, çöp → sil.
  */
 @Composable
-private fun CutRangeCard(
+private fun CutRangeRow(
     index: Int,
     range: TimeRange,
+    selected: Boolean,
+    expanded: Boolean,
     enabled: Boolean,
+    onSelect: () -> Unit,
+    onToggleExpand: () -> Unit,
     onStartChange: (Long) -> Unit,
     onEndChange: (Long) -> Unit,
     onRemove: () -> Unit
@@ -625,26 +668,96 @@
     Column(
         modifier = Modifier
             .fillMaxWidth()
-            .background(Surface, RoundedCornerShape(12.dp))
-            .padding(horizontal = 10.dp, vertical = 8.dp)
+            .clip(RoundedCornerShape(12.dp))
+            .background(if (selected) Amber.copy(alpha = 0.10f) else Surface)
+            .border(
+                width = if (selected) 1.dp else 0.dp,
+                color = if (selected) Amber.copy(alpha = 0.55f) else Color.Transparent,
+                shape = RoundedCornerShape(12.dp)
+            )
     ) {
-        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
-            Icon(Icons.Filled.ContentCut, null, tint = Red, modifier = Modifier.size(15.dp))
-            Spacer(Modifier.width(6.dp))
+        Row(
+            verticalAlignment = Alignment.CenterVertically,
+            modifier = Modifier
+                .fillMaxWidth()
+                .clickable(enabled = enabled, onClick = onSelect)
+                .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
+        ) {
+            Box(
+                modifier = Modifier
+                    .size(20.dp)
+                    .clip(CircleShape)
+                    .background(if (selected) Amber else SurfaceHigh),
+                contentAlignment = Alignment.Center
+            ) {
+                Text(
+                    "${index + 1}",
+                    color = if (selected) Color.Black else TextSec,
+                    fontSize = 11.sp,
+                    fontWeight = FontWeight.Bold
+                )
+            }
+            Spacer(Modifier.width(8.dp))
             Text(
-                "Kesilecek ${index + 1}",
-                color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
+                "${formatClock(range.startMs)} → ${formatClock(range.endMs)}",
+                color = TextPrimary,
+                fontSize = 12.sp,
+                fontFamily = FontFamily.Monospace,
+                maxLines = 1,
+                modifier = Modifier.weight(1f)
             )
-            Spacer(Modifier.width(8.dp))
-            Text(formatClock(range.durationMs), color = TextMuted, fontSize = 11.sp)
-            Spacer(Modifier.weight(1f))
-            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(30.dp)) {
-                Icon(Icons.Filled.Delete, "Aralığı sil", tint = Red, modifier = Modifier.size(18.dp))
+            Text(
+                formatClock(range.durationMs),
+                color = TextMuted,
+                fontSize = 10.sp,
+                maxLines = 1
+            )
+            IconButton(onClick = onToggleExpand, enabled = enabled, modifier = Modifier.size(32.dp)) {
+                Icon(
+                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.Edit,
+                    "Hassas düzenle",
+                    tint = TextSec,
+                    modifier = Modifier.size(17.dp)
+                )
+            }
+            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(32.dp)) {
+                Icon(Icons.Filled.Delete, "Aralığı sil", tint = Red, modifier = Modifier.size(17.dp))
+            }
+        }
+        if (expanded) {
+            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
+                CompactTimeRow("Başlangıç", range.startMs, enabled, onStartChange)
+                Spacer(Modifier.height(6.dp))
+                CompactTimeRow("Bitiş", range.endMs, enabled, onEndChange)
             }
         }
-        CompactTimeRow("Başlangıç", range.startMs, enabled, onStartChange)
-        Spacer(Modifier.height(6.dp))
-        CompactTimeRow("Bitiş", range.endMs, enabled, onEndChange)
+    }
+}
+
+/** Oynatıcının yerini aralığın başına/sonuna atayan tek dokunuşluk düğme. */
+@Composable
+private fun CutPointButton(
+    label: String,
+    enabled: Boolean,
+    accent: Color,
+    onClick: () -> Unit,
+    modifier: Modifier = Modifier
+) {
+    OutlinedButton(
+        onClick = onClick,
+        enabled = enabled,
+        shape = RoundedCornerShape(10.dp),
+        border = BorderStroke(1.dp, if (enabled) accent.copy(alpha = 0.7f) else Outline),
+        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
+        modifier = modifier
+    ) {
+        Text(
+            label,
+            color = if (enabled) accent else TextMuted,
+            fontSize = 11.sp,
+            fontWeight = FontWeight.SemiBold,
+            maxLines = 1
+        )
     }
 }
 
@@ -1033,7 +1146,8 @@
     endMs: Long,
     isTrimming: Boolean,
     followRangeChange: Boolean,
-    playheadRef: androidx.compose.runtime.MutableLongState
+    playheadRef: androidx.compose.runtime.MutableLongState,
+    seekRequestMs: androidx.compose.runtime.MutableLongState = remember { mutableLongStateOf(-1L) }
 ) {
     val context = LocalContext.current
     val player = remember { buildFastPlayer(context, isMultiView = false) }
@@ -1103,13 +1217,19 @@
         }
     }
 
-    LaunchedEffect(video.uri) {
-        val playable = if (video.uri.scheme == "content" && !isCloudAuthority(video.uri.authority)) {
-            withContext(Dispatchers.IO) {
-                InputAccess.realPath(context, video.uri)
-            }?.let { Uri.fromFile(File(it)) } ?: video.uri
-        } else {
-            video.uri
+    LaunchedEffect(video.uri, video.cachePath) {
+        // HIZ: dosya yolu zaten çözüldüyse (TrimViewModel -> InputAccess) doğrudan
+        // ondan oynatılır. Böylece her video geçişinde içerik sağlayıcısına tekrar
+        // sorulmaz, "önbelleğe alınıyor" beklemesi oluşmaz.
+        val resolved = video.cachePath
+        val playable = when {
+            resolved.startsWith("/") -> Uri.fromFile(File(resolved))
+            video.uri.scheme == "content" && !isCloudAuthority(video.uri.authority) -> {
+                withContext(Dispatchers.IO) {
+                    InputAccess.realPath(context, video.uri)
+                }?.let { Uri.fromFile(File(it)) } ?: video.uri
+            }
+            else -> video.uri
         }
         player.setMediaItem(MediaItem.fromUri(playable))
         player.prepare()
@@ -1207,6 +1327,15 @@
         player.seekTo(clamped)
     }
 
+    // DIŞARIDAN gelen sarma isteği (zaman çizelgesine dokunma / aralık satırını seçme).
+    LaunchedEffect(seekRequestMs.longValue) {
+        val target = seekRequestMs.longValue
+        if (target >= 0L) {
+            seekRequestMs.longValue = -1L
+            seekTo(target)
+        }
+    }
+
     Column(
         modifier = Modifier
             .fillMaxWidth()
@@ -1254,6 +1383,17 @@
             onScaleChange = { scaleMode = it },
             showScale = !video.isAudioOnly,
             modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
+            onScrubChange = { dragging ->
+                // HIZ: sürükleme sırasında en yakın anahtar kareye atlanır -> görüntü
+                // ANINDA gelir (20 dakikalık videoda bile bekleme yok). Parmak kalkınca
+                // kare hassas konuma oturulur ki kesim noktası tam görünsün.
+                if (dragging) {
+                    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
+                } else {
+                    player.setSeekParameters(SEEK_EXACT)
+                    player.seekTo(positionMs)
+                }
+            },
             trailing = {
                 Row(verticalAlignment = Alignment.CenterVertically) {
                     TrackMenuButtons(player, tracks, TextPrimary)
@@ -1368,6 +1508,7 @@
     onScaleChange: (ScaleMode) -> Unit = {},
     showScale: Boolean = true,
     onMenuOpen: (Boolean) -> Unit = {},
+    onScrubChange: (Boolean) -> Unit = {},
     textColor: Color = TextPrimary,
     modifier: Modifier = Modifier,
     onRotateScreen: (() -> Unit)? = null,
@@ -1400,7 +1541,10 @@
             startMs = startMs,
             endMs = endMs,
             onSeekTo = onSeekTo,
-            onDragStateChange = { dragging -> onMenuOpen(dragging) },
+            onDragStateChange = { dragging ->
+                onMenuOpen(dragging)
+                onScrubChange(dragging)
+            },
             textColor = textColor,
             fontSizeSp = 12,
             rowHeight = 36.dp
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/theme/Theme.kt src/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/theme/Theme.kt
--- orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/theme/Theme.kt	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/java/com/example/muxmaster/ui/theme/Theme.kt	2026-11-10 13:24:23.669704754 +0000
@@ -1,6 +1,7 @@
 package com.example.muxmaster.ui.theme
 
 import android.app.Activity
+import android.os.Build
 import android.content.Context
 import android.content.ContextWrapper
 import android.graphics.drawable.ColorDrawable
@@ -75,8 +76,18 @@
             val window = activity?.window
             if (window != null) {
                 val bgArgb = colorScheme.background.toArgb()
-                window.statusBarColor = bgArgb
-                window.navigationBarColor = bgArgb
+                // KENARDAN KENARA (edge-to-edge): durum çubuğu ve gezinme çubuğu artık
+                // şeffaf. Ekranların kendi gradyanı/zemini çubukların ARKASINA kadar
+                // uzanır (tam ekran görünüm); yazılar ise her ekranda güvenli alan
+                // (WindowInsets) kadar aşağıdan başlar. Böylece üst panel başlığı
+                // durum çubuğunun altında kalmaz.
+                window.statusBarColor = android.graphics.Color.TRANSPARENT
+                window.navigationBarColor = android.graphics.Color.TRANSPARENT
+                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
+                    // Sistem çubuklarının arkasına otomatik siyah/beyaz perde çizmesin.
+                    window.isStatusBarContrastEnforced = false
+                    window.isNavigationBarContrastEnforced = false
+                }
                 window.setBackgroundDrawable(ColorDrawable(bgArgb))
                 window.decorView.setBackgroundColor(bgArgb)
                 val controller = WindowCompat.getInsetsController(window, view)
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/viewmodel/TrimViewModel.kt src/Muxmaster-main/app/src/main/java/com/example/muxmaster/viewmodel/TrimViewModel.kt
--- orig/x/Muxmaster-main/app/src/main/java/com/example/muxmaster/viewmodel/TrimViewModel.kt	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/java/com/example/muxmaster/viewmodel/TrimViewModel.kt	2026-11-10 13:25:31.200624205 +0000
@@ -97,6 +97,9 @@
     var cutRanges by mutableStateOf<List<TimeRange>>(emptyList())
         private set
 
+    /** URI -> ffprobe sonucu. Aynı dosyaya dönüldüğünde yeniden analiz edilmez (anında açılır). */
+    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, TrackProber.ProbeResult>()
+
     val isMultiCut: Boolean get() = cutRanges.isNotEmpty()
 
     /** Çoklu kesim modunda videoda KALACAK parçalar (kesim aralıklarının tersi). */
@@ -140,11 +143,13 @@
      * Yeni bir kesim aralığı ekler. Varsayılan olarak oynatıcının bulunduğu yerden
      * [DEFAULT_CUT_LENGTH_MS] kadarını keser; orası doluysa en yakın boş bölüme taşınır.
      */
-    fun addCutRange(playheadMs: Long) {
-        val dur = videoFile?.durationMs ?: return
-        if (dur <= MIN_CUT_MS || isTrimming) return
-        val window = findFreeWindow(dur, playheadMs.coerceIn(0L, dur)) ?: return
-        cutRanges = (cutRanges + window).sortedBy { it.startMs }
+    fun addCutRange(playheadMs: Long): Int {
+        val dur = videoFile?.durationMs ?: return -1
+        if (dur <= MIN_CUT_MS || isTrimming) return -1
+        val window = findFreeWindow(dur, playheadMs.coerceIn(0L, dur)) ?: return -1
+        val list = (cutRanges + window).sortedBy { it.startMs }
+        cutRanges = list
+        return list.indexOf(window)
     }
 
     /**
@@ -153,20 +158,66 @@
      * Seçim anlamlı değilse (çok kısa ya da videonun tamamı) oynatıcının bulunduğu
      * yerden varsayılan uzunlukta bir aralık eklenir.
      */
-    fun addCutRangeFromSelection(playheadMs: Long) {
-        val dur = videoFile?.durationMs ?: return
-        if (dur <= MIN_CUT_MS || isTrimming) return
+    fun addCutRangeFromSelection(playheadMs: Long): Int {
+        val dur = videoFile?.durationMs ?: return -1
+        if (dur <= MIN_CUT_MS || isTrimming) return -1
         val s = trimStartMs.coerceIn(0L, dur)
         val e = trimEndMs.coerceIn(0L, dur)
         val len = e - s
         val usable = len >= MIN_SELECTION_MS && len <= dur - 400L
-        if (usable) {
-            cutRanges = (cutRanges + TimeRange(s, e)).sortedBy { it.startMs }
+        return if (usable) {
+            val list = (cutRanges + TimeRange(s, e)).sortedBy { it.startMs }
+            cutRanges = list
+            list.indexOf(TimeRange(s, e))
         } else {
             addCutRange(playheadMs)
         }
     }
 
+    /**
+     * Çoklu kesimde seçili aralığın BAŞLANGICINI oynatıcının bulunduğu ana taşır.
+     * (Tek dokunuş: videoyu istenen yere kadar oynat → "Başlangıç" düğmesi.)
+     */
+    fun setCutStartFromPlayhead(index: Int, playheadMs: Long) {
+        val dur = videoFile?.durationMs ?: return
+        val r = cutRanges.getOrNull(index) ?: return
+        if (isTrimming) return
+        val s = playheadMs.coerceIn(0L, dur)
+        val e = if (s >= r.endMs - MIN_CUT_MS) {
+            (s + DEFAULT_CUT_LENGTH_MS).coerceAtMost(dur)
+        } else {
+            r.endMs
+        }
+        updateCutRange(index, s, e)
+    }
+
+    /**
+     * Çoklu kesimde seçili aralığın BİTİŞİNİ oynatıcının bulunduğu ana taşır.
+     * Başlangıç noktası ile çakışırsa aralık [DEFAULT_CUT_LENGTH_MS] kadar geriye alınır.
+     */
+    fun setCutEndFromPlayhead(index: Int, playheadMs: Long) {
+        val dur = videoFile?.durationMs ?: return
+        val r = cutRanges.getOrNull(index) ?: return
+        if (isTrimming) return
+        val e = playheadMs.coerceIn(0L, dur)
+        val s = if (e <= r.startMs + MIN_CUT_MS) {
+            (e - DEFAULT_CUT_LENGTH_MS).coerceAtLeast(0L)
+        } else {
+            r.startMs
+        }
+        updateCutRange(index, s, e)
+    }
+
+    /** Aralığı [deltaMs] kadar kaydırır (başlangıç/bitiş birlikte, sınırlar içinde). */
+    fun nudgeCutRange(index: Int, deltaMs: Long) {
+        val dur = videoFile?.durationMs ?: return
+        val r = cutRanges.getOrNull(index) ?: return
+        if (isTrimming || r.durationMs <= 0L) return
+        val len = r.durationMs
+        val s = (r.startMs + deltaMs).coerceIn(0L, (dur - len).coerceAtLeast(0L))
+        updateCutRange(index, s, s + len)
+    }
+
     private fun findFreeWindow(dur: Long, preferredStart: Long): TimeRange? {
         val merged = mergedCutRanges(dur)
         var start = preferredStart.coerceIn(0L, dur)
@@ -311,6 +362,7 @@
 
     fun onVideoSelected(uri: Uri, displayName: String, fromQueue: Boolean = false) {
         if (!fromQueue) { folderQueue = emptyList(); queueIndex = -1; folderName = "" }
+        val key = uri.toString()
         viewModelScope.launch {
             isLoading = true
             loadingMessage = "Dosya analiz ediliyor..."
@@ -336,7 +388,11 @@
             }
 
             val sizeMb = withContext(Dispatchers.IO) { InputAccess.length(cacheFile).toFloat() / (1024 * 1024) }
-            val probe = withContext(Dispatchers.IO) { TrackProber.probe(cacheFile) }
+            // HIZ: aynı dosya daha önce analiz edildiyse ffprobe (en yavaş adım) hiç
+            // çalıştırılmaz. Klasörde ileri/geri gezinirken video ANINDA açılır.
+            val cachedProbe = probeCache[key]
+            val probe = cachedProbe ?: withContext(Dispatchers.IO) { TrackProber.probe(cacheFile) }
+            if (cachedProbe == null && probe.durationMs > 0L) probeCache[key] = probe
 
             val audioOnly = !probe.hasVideo
             if (audioOnly && probe.audioStreams.isEmpty()) {
@@ -363,6 +419,34 @@
             outputFileName = "${rawName}_kirpilmis.$ext"
 
             isLoading = false
+            prefetchQueueNeighbors()
+        }
+    }
+
+    /**
+     * Klasör kuyruğundaki ÖNCEKİ ve SONRAKİ dosyanın meta bilgisini arka planda
+     * hazırlar. Böylece "sonraki video"ya basıldığında ffprobe beklenmez; geçiş
+     * anında olur. Kopyalama YAPILMAZ: dosya kopyalanmadan okunamıyorsa atlanır.
+     */
+    private fun prefetchQueueNeighbors() {
+        if (folderQueue.isEmpty()) return
+        val targets = listOfNotNull(
+            folderQueue.getOrNull(queueIndex + 1),
+            folderQueue.getOrNull(queueIndex - 1)
+        ).filter { probeCache[it.uri.toString()] == null }
+        if (targets.isEmpty()) return
+        viewModelScope.launch(Dispatchers.IO) {
+            targets.forEach { entry ->
+                val k = entry.uri.toString()
+                if (probeCache.containsKey(k)) return@forEach
+                val path = runCatching { InputAccess.acquireNoCopy(app, entry.uri) }.getOrNull()
+                    ?: return@forEach
+                runCatching {
+                    val probe = TrackProber.probe(path)
+                    if (probe.durationMs > 0L) probeCache[k] = probe
+                }
+                runCatching { InputAccess.release(path) }
+            }
         }
     }
 
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/res/values/strings.xml src/Muxmaster-main/app/src/main/res/values/strings.xml
--- orig/x/Muxmaster-main/app/src/main/res/values/strings.xml	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/res/values/strings.xml	2026-11-10 13:24:16.329068219 +0000
@@ -163,4 +163,42 @@
     <string name="action_export_selected">Seçilenleri Dışa Aktar (%1$d)</string>
     <string name="export_none_selected">Önce dışa aktarılacak en az bir parça seçin.</string>
     <string name="export_batch_success">✅ Seçilen %1$d/%2$d parça başarıyla dışa aktarıldı.</string>
+
+    <!-- Ana ekran (HomeScreen) -->
+    <string name="home_tagline">Video ve ses araçların</string>
+    <string name="home_settings_desc">Ayarlar</string>
+    <string name="home_mux_title">Mux</string>
+    <string name="home_mux_sub">Ses ve altyazı\nparçalarını ayır</string>
+    <string name="home_trim_title">Kes</string>
+    <string name="home_trim_sub">Videoyu kes</string>
+    <string name="home_merge_title">Birleştir</string>
+    <string name="home_merge_sub">Videoları birleştir</string>
+    <string name="home_player_title">Oynatıcı</string>
+    <string name="home_player_sub">Medyanızı oynat</string>
+    <string name="home_converter_title">Ses Dönüştürme</string>
+    <string name="home_converter_sub">Sesi başka bir formata dönüştür</string>
+    <string name="home_recent_title">Son Dosyalar</string>
+    <string name="home_recent_show_all">Tümünü Gör</string>
+    <string name="home_recent_empty_title">Henüz dosya açmadın</string>
+    <string name="home_recent_empty_sub">Açtığın videolar burada listelenir.</string>
+    <string name="home_more_desc">Diğer seçenekler</string>
+    <string name="home_action_play">Oynat</string>
+    <string name="home_action_remove">Listeden çıkar</string>
+
+    <!-- Açılış / izin diyalogları (MainActivity) -->
+    <string name="dialog_storage_title">Depolama izni</string>
+    <string name="dialog_storage_text">Videoların önbelleğe kopyalanmadan, beklemeden doğrudan açılması için &quot;Tüm dosyalara erişim&quot; iznini açın.</string>
+    <string name="dialog_storage_allow">İzin ver</string>
+    <string name="dialog_storage_later">Şimdi değil</string>
+    <string name="dialog_play_how_title">Videoyu nasıl oynatmak istiyorsunuz?</string>
+    <string name="dialog_play_single">Tek video olarak oynat</string>
+    <string name="dialog_play_multi">Çoklu videoya ekle (Multi Video)</string>
+    <string name="dialog_cancel">İptal</string>
+    <string name="dialog_open_with_title">Ne yapmak istiyorsunuz?</string>
+    <string name="open_with_play">Oynat (video oynatıcı)</string>
+    <string name="open_with_multi">Çoklu Oynat (Multi Video)</string>
+    <string name="open_with_mux">Mux Master (ses / altyazı ekle-çıkar)</string>
+    <string name="open_with_trim">Kırp / Kes</string>
+    <string name="open_with_merge">Birleştir</string>
+    <string name="open_with_convert">Sesi dönüştür</string>
 </resources>
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/res/values-de/strings.xml src/Muxmaster-main/app/src/main/res/values-de/strings.xml
--- orig/x/Muxmaster-main/app/src/main/res/values-de/strings.xml	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/res/values-de/strings.xml	2026-11-10 13:24:16.329389038 +0000
@@ -153,4 +153,42 @@
     <string name="action_export_selected">Ausgewählte exportieren (%1$d)</string>
     <string name="export_none_selected">Bitte zuerst mindestens eine Spur zum Exportieren auswählen.</string>
     <string name="export_batch_success">✅ %1$d/%2$d ausgewählte Spuren erfolgreich exportiert.</string>
+
+    <!-- Startseite -->
+    <string name="home_tagline">Deine Video- und Audio-Werkzeuge</string>
+    <string name="home_settings_desc">Einstellungen</string>
+    <string name="home_mux_title">Mux</string>
+    <string name="home_mux_sub">Audio- und Untertitel-\nspuren trennen</string>
+    <string name="home_trim_title">Schneiden</string>
+    <string name="home_trim_sub">Video kürzen</string>
+    <string name="home_merge_title">Zusammenfügen</string>
+    <string name="home_merge_sub">Videos verbinden</string>
+    <string name="home_player_title">Player</string>
+    <string name="home_player_sub">Medien abspielen</string>
+    <string name="home_converter_title">Audio-Umwandlung</string>
+    <string name="home_converter_sub">Audio in ein anderes Format umwandeln</string>
+    <string name="home_recent_title">Letzte Dateien</string>
+    <string name="home_recent_show_all">Alle ansehen</string>
+    <string name="home_recent_empty_title">Noch keine Datei geöffnet</string>
+    <string name="home_recent_empty_sub">Geöffnete Videos werden hier gelistet.</string>
+    <string name="home_more_desc">Weitere Optionen</string>
+    <string name="home_action_play">Abspielen</string>
+    <string name="home_action_remove">Aus Liste entfernen</string>
+
+    <!-- Dialoge (MainActivity) -->
+    <string name="dialog_storage_title">Speicherberechtigung</string>
+    <string name="dialog_storage_text">Damit Videos ohne Kopie im Cache direkt geöffnet werden, aktiviere &quot;Zugriff auf alle Dateien&quot;.</string>
+    <string name="dialog_storage_allow">Erlauben</string>
+    <string name="dialog_storage_later">Nicht jetzt</string>
+    <string name="dialog_play_how_title">Wie möchtest du das Video abspielen?</string>
+    <string name="dialog_play_single">Als einzelnes Video abspielen</string>
+    <string name="dialog_play_multi">Zu Multi-Video hinzufügen</string>
+    <string name="dialog_cancel">Abbrechen</string>
+    <string name="dialog_open_with_title">Was möchtest du tun?</string>
+    <string name="open_with_play">Abspielen (Video-Player)</string>
+    <string name="open_with_multi">In Multi-Video abspielen</string>
+    <string name="open_with_mux">Mux Master (Audio/Untertitel hinzufügen oder entfernen)</string>
+    <string name="open_with_trim">Kürzen / Schneiden</string>
+    <string name="open_with_merge">Zusammenfügen</string>
+    <string name="open_with_convert">Audio umwandeln</string>
 </resources>
diff -ru '--exclude=DEGISIKLIKLER.md' orig/x/Muxmaster-main/app/src/main/res/values-en/strings.xml src/Muxmaster-main/app/src/main/res/values-en/strings.xml
--- orig/x/Muxmaster-main/app/src/main/res/values-en/strings.xml	2026-11-09 17:34:07.000000000 +0000
+++ src/Muxmaster-main/app/src/main/res/values-en/strings.xml	2026-11-10 13:24:16.329245168 +0000
@@ -153,4 +153,42 @@
     <string name="action_export_selected">Export Selected (%1$d)</string>
     <string name="export_none_selected">Please select at least one track to export first.</string>
     <string name="export_batch_success">✅ Successfully exported %1$d/%2$d selected tracks.</string>
+
+    <!-- Home screen -->
+    <string name="home_tagline">Your video and audio tools</string>
+    <string name="home_settings_desc">Settings</string>
+    <string name="home_mux_title">Mux</string>
+    <string name="home_mux_sub">Split audio and\nsubtitle tracks</string>
+    <string name="home_trim_title">Trim</string>
+    <string name="home_trim_sub">Cut the video</string>
+    <string name="home_merge_title">Merge</string>
+    <string name="home_merge_sub">Join videos</string>
+    <string name="home_player_title">Player</string>
+    <string name="home_player_sub">Play your media</string>
+    <string name="home_converter_title">Audio Conversion</string>
+    <string name="home_converter_sub">Convert audio to another format</string>
+    <string name="home_recent_title">Recent Files</string>
+    <string name="home_recent_show_all">See All</string>
+    <string name="home_recent_empty_title">No files opened yet</string>
+    <string name="home_recent_empty_sub">Videos you open are listed here.</string>
+    <string name="home_more_desc">More options</string>
+    <string name="home_action_play">Play</string>
+    <string name="home_action_remove">Remove from list</string>
+
+    <!-- Dialogs (MainActivity) -->
+    <string name="dialog_storage_title">Storage permission</string>
+    <string name="dialog_storage_text">To open videos directly without copying them to the cache, enable &quot;All files access&quot;.</string>
+    <string name="dialog_storage_allow">Allow</string>
+    <string name="dialog_storage_later">Not now</string>
+    <string name="dialog_play_how_title">How do you want to play the video?</string>
+    <string name="dialog_play_single">Play as a single video</string>
+    <string name="dialog_play_multi">Add to multi video</string>
+    <string name="dialog_cancel">Cancel</string>
+    <string name="dialog_open_with_title">What do you want to do?</string>
+    <string name="open_with_play">Play (video player)</string>
+    <string name="open_with_multi">Play in multi video</string>
+    <string name="open_with_mux">Mux Master (add/remove audio or subtitles)</string>
+    <string name="open_with_trim">Trim / Cut</string>
+    <string name="open_with_merge">Merge</string>
+    <string name="open_with_convert">Convert audio</string>
 </resources>

## Sürüklemede sahne görünmeme düzeltmesi
- ScrubSeeker: kare algılama `onRenderedFirstFrame` (yalnızca bir kez tetiklenir) yerine `VideoFrameMetadataListener` ile her karede yapılır; sarma zaman aşımı 2500 ms. Video kesme ve oynatıcıda sürüklerken 0,5 sn karelerin hepsi görünür.
