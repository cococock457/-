package ua.trevogaalarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator

class RingService : Service() {
    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        Notif.channels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, Notif.CH_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Підйом!")
            .setContentText("Будильник дзвенить")
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Вимкнути", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
        startSound()
        return START_NOT_STICKY
    }

    private fun startSound() {
        try {
            player?.release()
            player = null
            val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val saved = Store.str(this, "tone", "")
            // Обрана користувачем мелодія; якщо її файл недоступний, граємо стандартну
            player = (if (saved.isNotEmpty()) tryPlay(Uri.parse(saved)) else null) ?: tryPlay(default)
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 600), 0))
        } catch (e: Exception) {
            // если звук не удался, остаётся вибрация и уведомление
        }
    }

    private fun tryPlay(uri: Uri?): MediaPlayer? {
        if (uri == null) return null
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            try { mp.release() } catch (e2: Exception) {}
            null
        }
    }

    override fun onDestroy() {
        try { player?.stop() } catch (e: Exception) {}
        player?.release()
        player = null
        try { (getSystemService(VIBRATOR_SERVICE) as Vibrator).cancel() } catch (e: Exception) {}
        super.onDestroy()
    }
}
