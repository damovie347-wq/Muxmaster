# MuxMaster — Android Video/Audio/Altyazı Mux Aracı

Kotlin + Jetpack Compose + Material3 + MVVM. Gerçek ffmpeg-kit tabanlı mux işlemi (re-encode yok, `-c copy`).

## Kurulum

1. Bu klasörü (`MuxMaster/`) Android Studio'da **Open** ile aç.
2. Gradle wrapper bu zip'te YOK (binary `gradle-wrapper.jar` taşınamadığı için). Android Studio
   projeyi açtığında "Gradle wrapper bulunamadı" uyarısı verirse **"OK / Create"** diyerek
   otomatik oluşturmasına izin ver, ya da `File > Sync Project with Gradle Files` ile devam et —
   Android Studio kendi bundled Gradle dağıtımını kullanabilir.
3. Sync tamamlandıktan sonra fiziksel bir cihaz veya emülatörde **Run** et (minSdk 26 / Android 8.0+).

## Bilinen kritik nokta — ffmpeg-kit

`com.arthenica:ffmpeg-kit-full` resmi olarak retired edildi ve Maven Central'dan kaldırıldı.
`app/build.gradle.kts` içinde bunun yerine aynı `com.arthenica.ffmpegkit.*` API'sini koruyan bir
topluluk fork'u (`com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1`) kullanılıyor. Eğer bu koordinat da
ileride kaldırılırsa:
- GitHub'da "ffmpeg-kit fork" aratıp güncel bir Maven Central / JitPack yayını bul,
- `app/build.gradle.kts`'teki tek satırı değiştir,
- Kotlin kodunda HİÇBİR DEĞİŞİKLİK gerekmez (paket adı aynı kaldığı sürece).

## Mimari

```
model/        -> AudioTrackItem, SubtitleTrackItem, VideoFile, MergeItem (immutable data class'lar)
data/         -> TrackProber: ffprobe ile GERÇEK stream okuma (JSON parse) + kesim için
                 ileri-yönlü keyframe arama (findForwardKeyframeSec)
viewmodel/    -> MuxViewModel / TrimViewModel / ConverterViewModel / MergeViewModel:
                 tüm state + gerçek ffmpeg komut üretimi + execution
ui/theme/     -> İstenen renk paleti + Material3 dark color scheme
ui/components/-> VideoCard, AudioTrackCard, SubtitleTrackCard, BottomMuxBar
ui/screens/   -> MuxScreen (Scaffold + Tab + LazyColumn), TrimScreen, ConverterScreen,
                 MergeScreen (Scaffold + sıralı video listesi), SettingsScreen
MainActivity  -> SAF picker'lar (video/ses/altyazı/klasör, çoklu video) + gerçek
                 display-name çözümleme + 5 ekran arası navigasyon
```

## Akış

1. Video seç → cache'e kopyalanır → `ffprobe -show_streams` ile GERÇEK audio/subtitle track'leri okunur.
2. Track'ler listede görünür: silinebilir, sıralanabilir, dil/title/delay/default düzenlenebilir.
3. "+" ile yeni ses/altyazı dosyası eklenebilir (SAF, persistable permission alınır).
4. Çıktı klasörü (SAF tree) ve dosya adı seçilir.
5. **START MUXING** → gerçek `FFmpegKit.executeWithArgumentsAsync` çalışır:
   - Delay'i olan **orijinal** track'ler önce kendi dosyalarına ayıklanır (extract), sonra
     `-itsoffset` ile ayrı input olarak mux'a girer (negatif delay dahil çalışır).
   - Delay'siz orijinal track'ler doğrudan `-map 0:N` ile kopyalanır (gereksiz re-encode/extract yok).
   - Tüm komut `String` yerine `List<String>` (argv array) olarak verilir — dosya adında boşluk/
     özel karakter olsa da komut bozulmaz.
6. Çıktı önce cache'e (`temp_output.mkv`) yazılır, boyutu doğrulanır (0 byte kontrolü), sonra
   SAF `DocumentFile.createFile()` + `openOutputStream()` ile seçilen klasöre kopyalanır.
7. Gerçek dosya boyutu (`%.1f MB`) başarı mesajında gösterilir.

## Video Kırpma (Trim) — bu revizyonda düzeltilen hatalar

1. **Dakika/saniye kutusunu silip yeni değer yazamama.** Eskiden `TimeField` kutusunun
   gösterdiği metin her recomposition'da doğrudan üstteki state'ten (`totalMs`'ten
   bölünerek) yeniden hesaplanıyordu; state ara sırada (ör. minimum aralık sınırlaması)
   farklı bir sayıya yuvarlanınca kutu, kullanıcı henüz yazmayı bitirmeden eski değere
   (ör. "20") geri dönüyordu. Çözüm: `TimeField` artık kendi yerel metin durumunu tutuyor
   ve dışarıdaki değeri SADECE odakta değilken (blur anında ya da video/slider gibi dış
   bir değişiklikte) metne yansıtıyor (bkz. `ui/screens/TrimScreen.kt`).
2. **Kesimin istenen noktadan 20-30+ saniye ERKEN başlaması.** `-c copy` (kalite kaybı
   olmadan) kesimde çıktı mutlaka bir keyframe'den başlamak zorundadır. Eskiden `-ss`
   girdi seçeneği olarak (`-i`den önce) veriliyordu; bu, ffmpeg'in hedeften ÖNCEKİ en
   yakın keyframe'e atlamasına yol açıyordu — keyframe aralığı (GOP) geniş olan
   videolarda bu, isteğin çok öncesinden başlayan kesimler üretiyordu. Çözüm:
   `TrackProber.findForwardKeyframeSec` fonksiyonu ffprobe ile (paket düzeyindeki "K"
   bayrağını okuyarak, `-skip_frame nokey` gibi codec'e bağlı yaklaşımlar yerine) hedef
   noktadan SONRAKİ ilk gerçek keyframe'i buluyor; kesim tam olarak o noktadan başlıyor.
   Böylece kesim hiçbir zaman istenenden daha erken başlamaz (bkz. `viewmodel/TrimViewModel.kt`).

## Video Birleştirme (Merge) — yeni özellik

Yeni bir ekran (`ui/screens/MergeScreen.kt` + `viewmodel/MergeViewModel.kt`) eklendi:

1. Çoklu video seçilir (SAF `OpenMultipleDocuments`), her biri ffprobe ile analiz edilir.
2. Liste yukarı/aşağı oklarla yeniden sıralanabilir; birleştirme bu sırayla yapılır.
3. Videolar arasında video codec, çözünürlük ya da ses codec'i uyuşmazsa (kayıpsız
   birleştirme mümkün olmayacağı için) işlem başlamadan açık bir uyarı gösterilir —
   sessizce yeniden kodlamaya asla başvurulmaz.
4. Uyumluysa ffmpeg'in **concat demuxer**'ı ile tamamen `-c copy` (kalite kaybı yok,
   yeniden kodlama yok) olarak birleştirilir; bu yüzden çok hızlıdır.
5. Ana ekranın üst çubuğundaki yeni "birleştir" ikonundan açılır.

## Bilgisayarsız (sadece telefon) derleme — GitHub Actions

Bu projeye `.github/workflows/build.yml` eklendi. Bu sayede:

1. GitHub.com'da (telefon tarayıcısından da olur) ücretsiz hesap aç.
2. Yeni bir **public** repo oluştur (public seçersen Actions dakika sınırı yok).
3. Bu zip'in içeriğini repoya yükle ("Add file > Upload files", klasör klasör veya
   hepsini sürükleyip bırakarak — telefonda dosya yöneticisinden çoklu seçim yapılabilir).
4. Repo'da **Actions** sekmesine gir → workflow otomatik push'ta tetiklenir,
   tetiklenmezse "Run workflow" butonuna bas.
5. Build bitince (birkaç dakika) workflow run sayfasındaki **Artifacts** bölümünden
   `muxmaster-debug-apk` dosyasını indir (bir .zip içinde .apk gelir, telefonda aç/unzip et).
6. Telefonda "bilinmeyen kaynaklardan yükleme" iznini aç, APK'yı kur.

Bu yöntemde **gerçek Android SDK + Gradle + ffmpeg-kit native .so dosyaları** sorunsuz
çalışır; "Google AI Studio" veya "Firebase Studio" gibi tarayıcı tabanlı araçların
native kod kısıtlamaları burada söz konusu değildir çünkü bu standart bir CI ortamıdır.

## Bu ortamda yapılamayan şey

Bu proje internetsiz/Android SDK'sız bir sandbox'ta yazıldı; gerçek bir `gradlew build` ile derleme
ve cihazda çalıştırma burada doğrulanamadı. Kod, FFmpegKit'in gerçek (resmi) API imzalarına göre
dikkatle yazıldı, ancak Android Studio'da ilk sync/build sırasında küçük bir uyumsuzluk çıkarsa
(örn. fork'un metod imzası farklıysa) hata mesajını paylaşman yeterli.

## Son güncelleme

- **Video / Ses Kırp**: seçilen aralığı birebir oynatan dahili oynatıcı (oynat/duraklat, ±10 sn, tam ekran),
  "Klasör Seç" ile klasördeki tüm videoları kuyruğa alma ve ok düğmesiyle sıradakine geçme,
  sadece-ses dosyalarını (mp3, m4a, opus, flac…) da aynı şekilde kırpma.
- **Ses Dönüştürme**: HE-AAC ve HE-AAC v2 (.m4a) eklendi (Android MediaCodec AAC encoder'ı ile).
- **Hata düzeltmesi**: ekran dikey/yatay döndürülünce ana ekrana atma sorunu giderildi.
- **Uygulama simgesi**: yeni adaptive launcher ikonu eklendi.

## CI notu (GitHub Actions)

`android-actions/setup-android` KULLANMA: yeni cmdline-tools'ta `tools` paketi olmadığından
"Failed to find package 'tools'" hatasıyla derleme başlamadan çöküyor. `.github/workflows/build.yml`
runner'daki hazır Android SDK'yı kullanır. `.github` gizli klasör olduğundan tarayıcıdan zip
yüklerken atlanabilir; bu durumda GitHub'da `.github/workflows/build.yml` dosyasını elle düzenleyip içeriği yapıştır.

## Güncelleme 3

- Oynatıcı: tam ekranda ekrana dokununca kontroller gizlenir/görünür; tam ekrana girip çıkınca konum korunur;
  ekran yönü serbest (dikey/yatay) + döndür düğmesi; ses ve altyazı parçası seçimi (müzik/altyazı simgeleri).
- Hassas kesim: başlangıç–ilk keyframe arası yeniden kodlanır, kalanı kayıpsız kopyalanır (libx264 gerekir; yoksa eski yönteme düşer).
- Klasör kuyruğu için kapat (✕) düğmesi.
- Geri tuşu alt ekranlardan ana ekrana döner.
- Girdi dosyaları artık önbelleğe KOPYALANMAZ (fd üzerinden okunur); olmazsa otomatik eski yönteme düşer. Açılışta önbellek temizlenir.
