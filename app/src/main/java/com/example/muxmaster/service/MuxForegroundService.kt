package com.example.muxmaster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process as AndroidProcess
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.muxmaster.MainActivity
import com.example.muxmaster.R
import java.io.File

object MuxCancelBus {
    @Volatile var onCancelRequested: (() -> Unit)? = null
}

/**
 * Mux ve Ses Dönüştürme işlemleri sırasında (uygulama arka plana alınsa / ekran kapansa bile)
 * işlemin ön plandakiyle birebir aynı tam CPU hızında çalışmasını sağlayan servis.
 *
 * KÖK NEDEN (pil optimizasyonu kapatılsa bile arka planda yavaşlama):
 * 1) Android 10–14 ve OEM çekirdekleri (Samsung Freecess, Xiaomi PowerKeeper/Millet,
 *    Qualcomm/MediaTek uclamp & cpuset), uygulama ekran dışına çıktığında (`top-app`
 *    grubundan düştüğünde) salt `dataSync` servislerini arka plan (`bg` / `foreground`)
 *    cpuset'ine taşıyıp sadece yavaş verimlilik çekirdeklerine (LITTLE cores) hapsediyor
 *    ve FFmpeg/MediaCodec/native iş parçacıklarının frekansını kısıtlıyordu.
 * 2) Ayrıca her ilerleme adımında `ContextCompat.startForegroundService()` + `nm.notify()`
 *    çağrılması saniyede 20+ IPC çağrısı üreterek Android NotificationManager hız sınırına
 *    takılıyor ve ana iş parçacığını tıkıyordu.
 *
 * KALICI ÇÖZÜM:
 * - `mediaPlayback|dataSync` foreground service tipi + sıfır sesli (0.0f volume, AudioFocus
 *   istemeyen, diğer müzik/video uygulamalarını ASLA durdurmayan) `MODE_STATIC` döngüsel
 *   `AudioTrack` ile Android AudioFlinger nezdinde süreç "aktif medya oynatıcı" statüsünde
 *   tutulur; böylece OEM güç yöneticileri süreci asla yavaş çekirdeklere hapsetmez.
 * - Arka plan hızlandırıcı (`boostAllProcessThreads`) her 800 ms'de `/proc/self/task`
 *   altındaki tüm FFmpeg, MediaCodec ve native iş parçacıklarını `THREAD_PRIORITY_URGENT_AUDIO`
 *   (-16 nice) önceliğine yükseltir ve `taskset -p -a ff <pid>` ile tüm performans (BIG/Prime)
 *   çekirdeklerini açık tutar.
 * - Bildirim güncellemeleri doğrudan bellek içi servis örneği üzerinden ve en fazla 350 ms'de
 *   bir yapılacak şekilde hız sınırlandırıldı (IPC darboğazı tamamen kaldırıldı).
 */
class MuxForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var silentAudioTrack: AudioTrack? = null
    @Volatile private var boosterRunning = false
    private var boosterThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createChannelIfNeeded()
        val initialNotif = buildNotification(0, getString(R.string.notif_muxing_progress), ongoing = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                startForeground(NOTIF_ID, initialNotif, types)
            } else {
                startForeground(NOTIF_ID, initialNotif)
            }
        } catch (_: Exception) {
            runCatching { startForeground(NOTIF_ID, initialNotif) }
        }
        acquireWakeLock()
        startSilentAudioKeepAlive()
        startCpuBooster()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val text = intent.getStringExtra(EXTRA_TEXT) ?: getString(R.string.notif_muxing_progress)
                updateNotificationInternal(progress, text)
            }
            ACTION_CANCEL -> {
                MuxCancelBus.onCancelRequested?.invoke()
            }
            ACTION_STOP -> {
                val finalText = intent.getStringExtra(EXTRA_TEXT)
                val success = intent.getBooleanExtra(EXTRA_SUCCESS, true)
                stopCpuBooster()
                stopSilentAudioKeepAlive()
                releaseWakeLock()
                if (!finalText.isNullOrBlank()) {
                    notify(buildNotification(100, finalText, ongoing = false, success = success))
                }
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        stopCpuBooster()
        stopSilentAudioKeepAlive()
        releaseWakeLock()
        super.onDestroy()
    }

    internal fun updateNotificationInternal(progress: Int, text: String) {
        notify(buildNotification(progress, text, ongoing = true))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MuxMaster:ConvertWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) { }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        wakeLock = null
    }

    /**
     * AudioFlinger üzerinde 0.0f ses seviyesinde statik tampon döngüsü başlatır.
     * AudioFocus İSTEMEZ (diğer uygulamaların sesini kesmez), CPU tüketmez (%0),
     * fakat işletim sisteminin ve OEM pil yöneticilerinin süreci "arka planda
     * kısıtlanamaz aktif medya uygulaması" olarak görmesini sağlar.
     */
    private fun startSilentAudioKeepAlive() {
        if (silentAudioTrack != null) return
        try {
            val sampleRate = 8000
            val frameCount = sampleRate / 10 // 100 ms (800 örnek = 1600 bayt)
            val bufferSizeInBytes = frameCount * 2
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack(
                attrs,
                format,
                bufferSizeInBytes,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            val silence = ShortArray(frameCount)
            track.write(silence, 0, silence.size)
            track.setLoopPoints(0, frameCount, -1)
            track.setVolume(0f)
            track.play()
            silentAudioTrack = track
        } catch (_: Throwable) {
            silentAudioTrack = null
        }
    }

    private fun stopSilentAudioKeepAlive() {
        val t = silentAudioTrack
        silentAudioTrack = null
        if (t != null) {
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    /**
     * Süreç içindeki tüm iş parçacıklarını (FFmpegKit'in arka planda açtığı C/pthread
     * iş parçacıkları dahil) periyodik olarak `THREAD_PRIORITY_URGENT_AUDIO` önceliğine
     * yükseltir ve tüm CPU çekirdeklerine (0xff) afinite tanımlar.
     */
    private fun startCpuBooster() {
        if (boosterRunning) return
        boosterRunning = true
        val t = Thread({
            val myPid = AndroidProcess.myPid()
            runCatching {
                if (File("/system/bin/taskset").canExecute()) {
                    ProcessBuilder("/system/bin/taskset", "-p", "-a", "ff", myPid.toString())
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                }
            }
            while (boosterRunning) {
                boostAllProcessThreads()
                try {
                    Thread.sleep(750L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "MuxCpuBooster")
        t.isDaemon = true
        t.priority = Thread.MAX_PRIORITY
        boosterThread = t
        t.start()
    }

    private fun stopCpuBooster() {
        boosterRunning = false
        boosterThread?.interrupt()
        boosterThread = null
    }

    private fun notify(notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try { nm.notify(NOTIF_ID, notification) } catch (_: SecurityException) { }
    }

    private fun buildNotification(progress: Int, text: String, ongoing: Boolean, success: Boolean = true): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, piFlags)

        val icon = android.R.drawable.stat_sys_download_done

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else icon)
            .setContentTitle(getString(R.string.notif_muxing_title))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (ongoing) {
            val contentText = if (text.isNotBlank()) text else getString(R.string.notif_progress_text, progress)
            builder.setContentText(contentText)
            builder.setProgress(100, progress.coerceIn(0, 100), false)

            val cancelIntent = Intent(this, MuxForegroundService::class.java).apply { action = ACTION_CANCEL }
            val cancelPendingIntent = PendingIntent.getService(
                this, 1, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel_desc), cancelPendingIntent)
        } else {
            builder.setContentText(text)
        }

        return builder.build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
                ).apply { setSound(null, null); description = getString(R.string.notif_channel_desc) }
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "mux_progress_channel"
        private const val NOTIF_ID = 4242
        private const val ACTION_UPDATE = "com.example.muxmaster.action.UPDATE_PROGRESS"
        private const val ACTION_CANCEL = "com.example.muxmaster.action.CANCEL"
        private const val ACTION_STOP = "com.example.muxmaster.action.STOP"
        private const val EXTRA_PROGRESS = "extra_progress"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_SUCCESS = "extra_success"

        @Volatile private var activeInstance: MuxForegroundService? = null
        @Volatile private var lastUpdateMs: Long = 0L
        @Volatile private var lastProgressVal: Int = -1

        /**
         * Uygulama sürecindeki tüm iş parçacıklarını (FFmpeg C pthread'leri dahil)
         * en yüksek ses/medya önceliğine yükseltir.
         */
        fun boostAllProcessThreads() {
            try {
                val taskDir = File("/proc/self/task")
                val children = taskDir.list() ?: return
                for (name in children) {
                    val tid = name.toIntOrNull() ?: continue
                    runCatching {
                        val cur = AndroidProcess.getThreadPriority(tid)
                        if (cur > AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO) {
                            AndroidProcess.setThreadPriority(tid, AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
                        }
                    }
                }
            } catch (_: Throwable) { }
        }

        fun start(context: Context) {
            lastUpdateMs = 0L
            lastProgressVal = -1
            val intent = Intent(context, MuxForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) { }
        }

        fun update(context: Context, progress: Int, text: String) {
            val now = SystemClock.elapsedRealtime()
            val clamped = progress.coerceIn(0, 100)
            // Gereksiz IPC ve NotificationManager darboğazını önle (en fazla 350 ms'de bir güncelle)
            if (clamped == lastProgressVal && now - lastUpdateMs < 1000L) return
            if (clamped in 1..98 && now - lastUpdateMs < 350L) return
            lastProgressVal = clamped
            lastUpdateMs = now

            val instance = activeInstance
            if (instance != null) {
                instance.updateNotificationInternal(clamped, text)
                return
            }
            val intent = Intent(context, MuxForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_PROGRESS, clamped)
                putExtra(EXTRA_TEXT, text)
            }
            try { ContextCompat.startForegroundService(context, intent) } catch (_: Exception) { }
        }

        fun stop(context: Context, finalText: String?, success: Boolean) {
            lastProgressVal = -1
            val intent = Intent(context, MuxForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TEXT, finalText)
                putExtra(EXTRA_SUCCESS, success)
            }
            try { ContextCompat.startForegroundService(context, intent) } catch (_: Exception) { }
        }
    }
}
