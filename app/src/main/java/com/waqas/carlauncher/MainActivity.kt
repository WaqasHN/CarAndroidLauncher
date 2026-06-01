package com.waqas.carlauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.waqas.carlauncher.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity(), AudioPlayerService.Listener {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private val timeFormat = SimpleDateFormat("hh:mm", Locale.getDefault())
    private val ampmFormat = SimpleDateFormat("a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private var player: AudioPlayerService? = null

    private val pickWallpaper = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            Wallpaper.saveFromUri(this, uri)
            applyWallpaper()
            Toast.makeText(this, R.string.wallpaper_set, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateClock()
            val msUntilNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            handler.postDelayed(this, msUntilNextMinute)
        }
    }

    private val timeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = syncVolumeUi()
    }

    private val audioManager: AudioManager
        get() = getSystemService(AUDIO_SERVICE) as AudioManager

    private var muteRestoreVolume = -1

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as AudioPlayerService.LocalBinder).getService()
            player = svc
            svc.setListener(this@MainActivity)
            svc.resumeIfPending()
            refreshMedia()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            player = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null && UiState.isInNightMode(this)) {
            startActivity(
                Intent(this, NightModeActivity::class.java)
                    .putExtra(NightModeActivity.EXTRA_RESTORED, true)
            )
        }

        binding.appDrawerButton.setOnClickListener {
            startActivity(Intent(this, AppDrawerActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.nightModeButton.setOnClickListener {
            startActivity(Intent(this, NightModeActivity::class.java))
        }
        binding.wallpaperButton.setOnClickListener {
            pickWallpaper.launch("image/*")
        }
        binding.wallpaperButton.setOnLongClickListener {
            if (Wallpaper.isSet(this)) {
                Wallpaper.clear(this)
                applyWallpaper()
                Toast.makeText(this, R.string.wallpaper_cleared, Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.mediaCard.setOnClickListener {
            startActivity(Intent(this, AudioLibraryActivity::class.java))
        }
        binding.mediaTitle.setOnClickListener {
            startActivity(Intent(this, AudioLibraryActivity::class.java))
        }
        binding.mediaPrev.setOnClickListener { player?.previous() }
        binding.mediaPlay.setOnClickListener { player?.playPause() }
        binding.mediaNext.setOnClickListener { player?.next() }

        binding.volumeSlider.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSlider.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    updateVolumeIcon(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.volumeButton.setOnClickListener { toggleMute() }
        updateVolumeIcon(binding.volumeSlider.progress)
    }

    private fun syncVolumeUi() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSlider.progress = current
        updateVolumeIcon(current)
    }

    private fun updateVolumeIcon(volume: Int) {
        binding.volumeButton.setImageResource(
            if (volume <= 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun toggleMute() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current > 0) {
            muteRestoreVolume = current
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            binding.volumeSlider.progress = 0
            updateVolumeIcon(0)
        } else {
            val target = if (muteRestoreVolume > 0) muteRestoreVolume
                         else audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            binding.volumeSlider.progress = target
            updateVolumeIcon(target)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AudioPlayerService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        player?.setListener(null)
        try { unbindService(connection) } catch (_: Exception) {}
        player = null
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(timeChangedReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        handler.post(tickRunnable)
        updateClock()
        refreshMedia()
        applyWallpaper()
        SystemUi.apply(this, UiState.isFullScreen(this))
        syncVolumeUi()
        try {
            ContextCompat.registerReceiver(
                this,
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {}
    }

    private fun applyWallpaper() {
        val bitmap = Wallpaper.loadBitmap(this)
        if (bitmap != null) {
            binding.wallpaper.setImageBitmap(bitmap)
            binding.wallpaper.visibility = View.VISIBLE
        } else {
            binding.wallpaper.setImageDrawable(null)
            binding.wallpaper.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
        try { unregisterReceiver(timeChangedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
    }

    override fun onPlayerStateChanged() {
        runOnUiThread { refreshMedia() }
    }

    private fun updateClock() {
        val corrected = DateOffset.correctedNow(this)
        binding.clockText.text = timeFormat.format(corrected)
        binding.amPmText.text = ampmFormat.format(corrected).lowercase(Locale.ROOT)
        binding.dateText.text = dateFormat.format(corrected)
    }

    private fun refreshMedia() {
        val svc = player
        val track = svc?.currentTrack
        if (track == null) {
            binding.mediaTitle.setText(R.string.media_idle)
            binding.mediaPlay.setImageResource(android.R.drawable.ic_media_play)
        } else {
            val artist = track.artist?.takeIf { it.isNotBlank() }
            binding.mediaTitle.text = if (artist != null) "${track.title} — $artist" else track.title
            binding.mediaPlay.setImageResource(
                if (svc.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }
    }
}
