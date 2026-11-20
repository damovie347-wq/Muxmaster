package com.example.muxmaster.data

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AĞ (HTTP/HTTPS) OYNATMA ÖN BELLEĞİ — kalıcı, disk tabanlı, LRU tahliyeli.
 *
 * KÖK NEDEN (kullanıcı şikâyeti: "ilk başta güzel oynuyor, sonra donuyor; ileri alınca
 * oynamıyor, 0,1 saniye oynayıp duruyor"):
 *  - Önceki sürümde ağ kaynakları `DefaultHttpDataSource` ile DOĞRUDAN okunuyordu; hiçbir
 *    ön bellek yoktu. Her sarma (seek) yeni bir HTTP bağlantısı + TLS el sıkışması + Google
 *    Drive yönlendirmesi demekti. Birkaç sarmadan sonra bağlantılar yavaşlıyor/kopuyor,
 *    oynatıcı da "tampon dolduruyorum" durumunda kilitli kalıyordu.
 *  - Drive bağlantıları kısa ömürlüdür; aynı baytların tekrar tekrar indirilmesi hem kotayı
 *    hem pili yakıyordu.
 *
 * ÇÖZÜM: İndirilen her bayt `SimpleCache` içine yazılır. Aynı aralığa yapılan her sarma
 * diskten (mikrosaniyeler) karşılanır, ağa hiç gidilmez. Ön bellek 512 MB ile LRU olarak
 * sınırlıdır; en eski parçalar otomatik silinir. Disk hatası olursa oynatma ağdan kesintisiz
 * devam eder ([CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR]) — yani ön bellek asla engel değil.
 *
 * AÇILIŞ GECİKMESİ DÜZELTMESİ (yeni):
 *  - `SimpleCache` ilk oluşturulurken disk indeksini okur. Bu, dolmuş bir önbellekte saniyeler
 *    sürebilir. Eskiden bu kurulum OYNATMA YOLUNDA (ilk `DataSource.open()`) yapılıyordu;
 *    yani kullanıcı "video açılıyor" ekranında indeksin okunmasını bekliyordu.
 *  - Artık ön bellek uygulama açılışında ARKA PLANDA ısıtılır ([warmUp]). Oynatma yolu asla
 *    beklemez: ön bellek hazır değilse oynatma doğrudan ağdan başlar ([ready]).
 */
object MediaCache {

    /** Ön bellek üst sınırı (LRU ile otomatik tahliye). */
    private const val MAX_BYTES: Long = 512L * 1024L * 1024L

    /** `clearWorkCache` bu klasörü silmez (açık SimpleCache bozulmasın). */
    const val DIR_NAME = "media_cache"

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var disabled = false

    private val warming = AtomicBoolean(false)

    /** Ön bellek HAZIRSA döner; hazır değilse `null` (asla beklemez). */
    fun ready(): SimpleCache? = simpleCache

    /**
     * Ön belleği arka planda hazırlar. Uygulama açılışında bir kez çağrılır; tek uçuşludur
     * (aynı anda ikinci bir kurulum başlamaz). Ana iş parçacığını ASLA bloklamaz.
     */
    fun warmUp(context: Context) {
        if (simpleCache != null || disabled) return
        if (!warming.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                cache(app)
            } catch (_: Throwable) {
                // Isıtma başarısızsa oynatma ağdan devam eder.
            } finally {
                warming.set(false)
            }
        }, "MediaCacheWarm").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }.start()
    }

    /**
     * Tekil [SimpleCache] (gerekirse kurar — yalnızca arka plan iş parçacığından çağırın).
     * Aynı klasörde ikinci bir örnek açılamayacağı için süreç genelinde tekildir.
     */
    fun cache(context: Context): SimpleCache? {
        simpleCache?.let { return it }
        if (disabled) return null
        synchronized(this) {
            simpleCache?.let { return it }
            if (disabled) return null
            return try {
                val app = context.applicationContext
                val dir = File(app.cacheDir, DIR_NAME)
                if (!dir.exists() && !dir.mkdirs()) {
                    disabled = true
                    return null
                }
                val created = SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(app)
                )
                simpleCache = created
                created
            } catch (_: Throwable) {
                // Disk yok/dolu/izin yok: ön bellek devre dışı, oynatma ağdan devam eder.
                disabled = true
                null
            }
        }
    }

    /**
     * Ön bellekli DataSource üretir. Ön bellek HENÜZ HAZIR DEĞİLSE `null` döner ve
     * çağıran taraf doğrudan ağ kaynağını kullanır: ilk kare hiçbir zaman indeks
     * okumasını beklemez. (Isıtma arka planda zaten sürmektedir.)
     */
    fun newDataSource(context: Context, upstream: DataSource.Factory): DataSource? {
        val c = simpleCache ?: run {
            warmUp(context)
            return null
        }
        return try {
            CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Kalıcı önbellek anahtarı.
     *
     * Google Drive linkleri her açılışta farklı sorgu parametreleri (imza/token, `confirm=t`,
     * `export=download` ...) taşıyabilir. Varsayılan anahtar TAM URI olduğu için aynı dosya
     * her seferinde "önbellekte yok" sayılıyordu. Dosya kimliği sabit olduğundan anahtar
     * `drive:<id>` yapılır: aynı video ikinci kez açıldığında tamamen diskten gelir.
     *
     * NEDEN GEREKLİ: ExoPlayer bir kaynağı diske yazmak için `DataSpec.key` ister; bu anahtar
     * `MediaItem.setCustomCacheKey` ile verilir. Anahtar verilmezse `CacheDataSource` önbelleği
     * HİÇ KULLANMAZ (sadece doğrudan ağdan okur). Bu yüzden her ağ kaynağı için sabit anahtar
     * üretilir. Yerel dosyalarda `null` döner: yerel video asla diske kopyalanmaz.
     */
    fun cacheKeyFor(uri: Uri): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val s = uri.toString()
        if (DriveStreamResolver.isGoogleDriveUrl(s)) {
            DriveStreamResolver.extractDriveFileId(s)?.let { return "drive:$it" }
        }
        return "http:" + s.substringBefore('#')
    }
}
