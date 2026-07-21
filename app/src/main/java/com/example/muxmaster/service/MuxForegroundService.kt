package com.example.muxmaster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.muxmaster.MainActivity
import com.example.muxmaster.R

/**
 * Mux işlemi sırasında (uygulama arka plana alınsa/ekran kapansa bile) yüzde
 * ilerlemeli GERÇEK bir Android bildirimi gösteren foreground service.
 *
 * KÖK NEDEN (arka planda ilerleme görünmüyordu): önceden ilerleme SADECE bir
 * Compose state'iydi (muxProgress); uygulama arka plandayken/ekran kapalıyken
 * kullanıcının işlemi takip edebileceği hiçbir gösterge yoktu, üstelik normal
 * bir Service (foreground olmayan) uzun süre arka planda kaldığında sistem
 * tarafından herhangi bir an sonlandırılabilirdi. ÇÖZÜM: mux başladığında GERÇEK
 * bir foreground service başlatılıyor (bildirim + `dataSync` servis tipi ile
 * süreç canlı tutuluyor), her ilerleme adımında bildirim güncelleniyor,
 * işlem bitince (başarı/hata/iptal fark etmeksizin) servis durduruluyor.
 */
class MuxForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotification(0, getString(R.string.notif_muxing_progress), ongoing = true))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val text = intent.getStringExtra(EXTRA_TEXT) ?: getString(R.string.notif_muxing_progress)
                notify(buildNotification(progress, text, ongoing = true))
            }
            ACTION_STOP -> {
                val finalText = intent.getStringExtra(EXTRA_TEXT)
                val success = intent.getBooleanExtra(EXTRA_SUCCESS, true)
                if (!finalText.isNullOrBlank()) {
                    notify(buildNotification(100, finalText, ongoing = false, success = success))
                }
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    // KOK NEDEN (arka planda islem cok yavasliyordu): foreground service tek
    // basina sureci canli tutar ama CPU'nun uyku moduna (idle/suspend) gecmesini
    // ENGELLEMEZ. Ekran kapanip cihaz uykuya girince ffmpeg is parcaciklari
    // saniyede birkac kez calisip duruyor, bu da 1.5-2 saatlik bir sesi
    // 10-15+ dakikaya cikariyordu. PARTIAL_WAKE_LOCK, ekran kapali olsa bile
    // CPU'yu tam hizda calisir tutar (ekran/parlaklik etkilenmez, sadece CPU).
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MuxMaster:ConvertWakeLock"
            ).apply {
                setReferenceCounted(false)
                // Guvenlik agi: is bitince releaseWakeLock() zaten cagriliyor,
                // bu sadece servis beklenmedik sekilde oldurulursen kalici
                // wake lock kalmasin diye ust sinir.
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

    private fun notify(notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try { nm.notify(NOTIF_ID, notification) } catch (_: SecurityException) { /* bildirim izni yok, foreground service yine de çalışmaya devam eder */ }
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
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        if (ongoing) {
            builder.setContentText(getString(R.string.notif_progress_text, progress))
            builder.setProgress(100, progress.coerceIn(0, 100), false)
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
        private const val ACTION_STOP = "com.example.muxmaster.action.STOP"
        private const val EXTRA_PROGRESS = "extra_progress"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_SUCCESS = "extra_success"

        fun start(context: Context) {
            val intent = Intent(context, MuxForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, progress: Int, text: String) {
            val intent = Intent(context, MuxForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_TEXT, text)
            }
            try { ContextCompat.startForegroundService(context, intent) } catch (_: Exception) { }
        }

        fun stop(context: Context, finalText: String?, success: Boolean) {
            val intent = Intent(context, MuxForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TEXT, finalText)
                putExtra(EXTRA_SUCCESS, success)
            }
            try { ContextCompat.startForegroundService(context, intent) } catch (_: Exception) { }
        }
    }
}
