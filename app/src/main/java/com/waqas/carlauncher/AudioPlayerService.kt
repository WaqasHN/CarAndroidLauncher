package com.waqas.carlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AudioPlayerService : Service() {

    companion object {
        private const val CHANNEL_ID = "audio_playback"
        private const val NOTIF_ID = 1001
        private const val PERIODIC_SAVE_MS = 5_000L
    }

    interface Listener {
        fun onPlayerStateChanged()
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlayerService = this@AudioPlayerService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val queue = mutableListOf<Track>()
    private var currentIndex = -1
    private var listener: Listener? = null
    private var isForeground = false
    private var pendingResume = false
    private var pendingPositionMs = 0

    private val periodicSave = object : Runnable {
        override fun run() {
            saveState()
            handler.postDelayed(this, PERIODIC_SAVE_MS)
        }
    }

    val currentTrack: Track? get() = queue.getOrNull(currentIndex)
    val isPlaying: Boolean get() = player?.isPlaying == true
    val hasPendingResume: Boolean get() = pendingResume

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tryRestore()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicSave)
        saveState()
        player?.release()
        player = null
    }

    fun setListener(l: Listener?) { listener = l }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        queue.clear()
        queue.addAll(tracks)
        currentIndex = startIndex.coerceIn(0, queue.lastIndex)
        pendingResume = false
        pendingPositionMs = 0
        playCurrent()
    }

    fun playPause() {
        val p = player
        if (p == null) {
            if (queue.isNotEmpty()) {
                val seek = pendingPositionMs
                pendingPositionMs = 0
                pendingResume = false
                playCurrent(seek)
            }
            return
        }
        if (p.isPlaying) {
            p.pause()
            handler.removeCallbacks(periodicSave)
        } else {
            p.start()
            handler.post(periodicSave)
        }
        updateNotification()
        saveState()
        listener?.onPlayerStateChanged()
    }

    fun pause() {
        val p = player ?: return
        if (!p.isPlaying) return
        p.pause()
        handler.removeCallbacks(periodicSave)
        updateNotification()
        saveState()
        listener?.onPlayerStateChanged()
    }

    fun resume() {
        val p = player
        if (p == null) {
            if (queue.isNotEmpty()) {
                val seek = pendingPositionMs
                pendingPositionMs = 0
                pendingResume = false
                playCurrent(seek)
            }
            return
        }
        if (p.isPlaying) return
        p.start()
        handler.post(periodicSave)
        updateNotification()
        saveState()
        listener?.onPlayerStateChanged()
    }

    fun next() {
        if (queue.isEmpty()) return
        currentIndex = (currentIndex + 1) % queue.size
        playCurrent()
    }

    fun previous() {
        if (queue.isEmpty()) return
        currentIndex = if (currentIndex <= 0) queue.lastIndex else currentIndex - 1
        playCurrent()
    }

    fun resumeIfPending() {
        if (!pendingResume || currentTrack == null) return
        pendingResume = false
        val seek = pendingPositionMs
        pendingPositionMs = 0
        playCurrent(seek)
    }

    private fun playCurrent(seekMs: Int = 0) {
        val track = currentTrack ?: return
        handler.removeCallbacks(periodicSave)
        player?.release()
        player = null

        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        try {
            mp.setDataSource(this, track.uri)
            mp.prepare()
            if (seekMs > 0) mp.seekTo(seekMs)
            mp.start()
        } catch (e: Exception) {
            mp.release()
            listener?.onPlayerStateChanged()
            return
        }
        mp.setOnCompletionListener { next() }
        player = mp

        goForeground()
        updateNotification()
        handler.post(periodicSave)
        saveState()
        listener?.onPlayerStateChanged()
    }

    /**
     * Atomically marks the service as started AND foreground. Bundling these two
     * calls together avoids the 5-second-deadline crash that happens if you call
     * startForegroundService and then fail to reach startForeground in time.
     */
    private fun goForeground() {
        if (isForeground) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, AudioPlayerService::class.java)
        )
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
        isForeground = true
    }

    private fun updateNotification() {
        if (!isForeground) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val track = currentTrack
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: getString(R.string.app_name))
            .setContentText(track?.artist ?: getString(R.string.media_idle))
            .setOngoing(isPlaying)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_audio),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun tryRestore() {
        val savedId = PlayerState.savedTrackId(this)
        if (savedId <= 0) return
        val all = try { AudioRepo.loadAll(this) } catch (e: Exception) { return }
        val index = all.indexOfFirst { it.id == savedId }
        if (index < 0) return
        queue.clear()
        queue.addAll(all)
        currentIndex = index
        pendingPositionMs = PlayerState.savedPosition(this)
        pendingResume = PlayerState.savedWasPlaying(this)
    }

    private fun saveState() {
        val track = currentTrack ?: return
        val pos = try { player?.currentPosition ?: pendingPositionMs } catch (e: Exception) { 0 }
        PlayerState.save(this, track.id, pos, isPlaying)
    }
}
