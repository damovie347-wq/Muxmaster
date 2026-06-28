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
model/        -> AudioTrackItem, SubtitleTrackItem, VideoFile (immutable data class'lar)
data/         -> TrackProber: ffprobe ile GERÇEK stream okuma (JSON parse)
viewmodel/    -> MuxViewModel: tüm state + gerçek ffmpeg komut üretimi + execution
ui/theme/     -> İstenen renk paleti + Material3 dark color scheme
ui/components/-> VideoCard, AudioTrackCard, SubtitleTrackCard, BottomMuxBar
ui/screens/   -> MuxScreen (Scaffold + Tab + LazyColumn)
MainActivity  -> SAF picker'lar (video/ses/altyazı/klasör) + gerçek display-name çözümleme
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
