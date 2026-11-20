package com.example.muxmaster.data

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File

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
 * diskten (mikrosaniyeler) karşılanır, ağa hiç gidilmez. Ön bellek 768 MB ile LRU olarak
 * sınırlıdır; en eski parçalar otomatik silinir. Disk hatası olursa oynatma ağdan kesintisiz
 * devam eder ([CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR]) — yani ön bellek asla engel değil.
 *
 * Bellek/CPU maliyeti: yalnızca sıralı disk yazımı. Kod çözme, kopyalama veya yeniden
 * paketleme YOK.
 */
object MediaCache {

    /** Ön bellek üst sınırı (LRU ile otomatik tahliye). */
    private const val MAX_BYTES: Long = 768L * 1024L * 1024L

    /** `clearWorkCache` bu klasörü silmez (açık SimpleCache bozulmasın). */
    const val DIR_NAME = "media_cache"

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var disabled = false

    /**
     * Tekil [SimpleCache]. Aynı klasörde ikinci bir örnek açılamayacağı için süreç genelinde
     * tekildir. İlk oluşturma disk indeksini okur; bu yüzden ağ oynatma iş parçacığından
     * (ana iş parçacığından değil) çağrılır.
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
     * Ön bellekli DataSource üretir. Ön bellek açılamazsa `null` döner; çağıran taraf
     * doğrudan ağ kaynağını kullanır (davranış bozulmaz).
     */
    fun newDataSource(context: Context, upstream: DataSource.Factory): DataSource? {
        val c = cache(context) ?: return null
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
}
