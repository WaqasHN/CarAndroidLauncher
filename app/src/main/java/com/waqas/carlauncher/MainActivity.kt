package com.waqas.carlauncher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.waqas.carlauncher.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity(), AudioPlayerService.Listener {

    companion object {
        private const val TAG = "MainActivity"

        // Hysteresis (km/h) for the home speedometer's "only while moving" option,
        // matching the screensaver: reveal when clearly moving, hide near a stop.
        private const val SPEED_SHOW_KMH = 3f
        private const val SPEED_HIDE_KMH = 1.5f
    }

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private var speedoEnabled = false
    private var speedoWhenMoving = false
    private var speedoShown = false

    private val locationManager: LocationManager
        get() = getSystemService(LOCATION_SERVICE) as LocationManager

    private val speedoLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val kmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            updateSpeedo(kmh)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val requestSpeedoLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSpeedoUpdates()
    }

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

    // True while the user is dragging the volume slider, so the system's volume
    // broadcast can't write progress back and fight the drag.
    private var draggingVolume = false

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
                    applyMusicVolume(progress)
                    updateVolumeIcon(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { draggingVolume = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                draggingVolume = false
                syncVolumeUi()
            }
        })
        // Keep the clickable media card from stealing the slider's drag gesture.
        binding.volumeSlider.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        binding.volumeButton.setOnClickListener { toggleMute() }
        updateVolumeIcon(binding.volumeSlider.progress)

        // If the device reports a fixed media volume (common on head units that
        // route audio through an external amp), STREAM_MUSIC changes won't be
        // audible no matter what we do — log it so it's clear during testing.
        if (audioManager.isVolumeFixed) {
            Log.w(TAG, "Device reports fixed media volume; the slider cannot change output level.")
        }
    }

    /**
     * Set the Android system media volume. FLAG_SHOW_UI surfaces the native
     * volume panel as confirmation, and the try/catch keeps us from crashing if
     * a device policy rejects the change.
     */
    private fun applyMusicVolume(volume: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                volume.coerceIn(0, max),
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "System rejected the volume change", e)
        }
    }

    private fun syncVolumeUi() {
        if (draggingVolume) return
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
            applyMusicVolume(0)
            binding.volumeSlider.progress = 0
            updateVolumeIcon(0)
        } else {
            val target = if (muteRestoreVolume > 0) muteRestoreVolume
                         else audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
            applyMusicVolume(target)
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
        setupHomeSpeedometer()
    }

    /** Show and drive the home-screen speedometer per the shared settings. */
    private fun setupHomeSpeedometer() {
        speedoEnabled = UiState.isSpeedometerOnHome(this)
        if (!speedoEnabled) {
            setSpeedoShown(false)
            return
        }
        speedoWhenMoving = UiState.isSpeedometerWhenMoving(this)
        val digital = UiState.isSpeedometerDigital(this)
        binding.homeSpeedometer.setMode(
            if (digital) SpeedometerView.Mode.DIGITAL else SpeedometerView.Mode.ANALOG
        )
        // Digital = wide rectangular plate (fits a 3-digit "140 km/h" row);
        // analog = round metallic disc.
        binding.homeSpeedometer.setBackgroundResource(
            if (digital) R.drawable.bg_speedo_metal_rect else R.drawable.bg_speedo_metal
        )
        binding.homeSpeedometer.layoutParams = binding.homeSpeedometer.layoutParams.apply {
            width = dp(if (digital) 280 else 240)
            height = dp(if (digital) 132 else 240)
        }
        // When gated on movement we start hidden and reveal it once we're moving.
        setSpeedoShown(!speedoWhenMoving)

        if (!hasLocationPermission()) {
            requestSpeedoLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            startSpeedoUpdates()
        }
    }

    /**
     * Toggle the gauge's visibility and shift the media card in step, so the
     * music card re-centres whenever the gauge is hidden (e.g. when stopped
     * under the "only while moving" option).
     */
    private fun setSpeedoShown(shown: Boolean) {
        speedoShown = shown
        binding.homeSpeedometer.visibility = if (shown) View.VISIBLE else View.GONE
        applyHomeSpeedometerLayout(shown)
    }

    /**
     * When the gauge is shown, the media card moves into the left portion
     * (ending at the guideline) so the gauge has the right side; otherwise it
     * re-centres. The date/time stays centred across the screen either way.
     */
    private fun applyHomeSpeedometerLayout(speedoVisible: Boolean) {
        (binding.mediaCard.layoutParams as ConstraintLayout.LayoutParams).let {
            it.endToEnd =
                if (speedoVisible) R.id.speedoGuideline else ConstraintLayout.LayoutParams.PARENT_ID
            it.matchConstraintPercentWidth = if (speedoVisible) 0.45f else 0.6f
            binding.mediaCard.layoutParams = it
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateSpeedo(kmh: Float) {
        if (!speedoEnabled) return
        if (speedoWhenMoving) {
            if (speedoShown && kmh < SPEED_HIDE_KMH) {
                setSpeedoShown(false)
            } else if (!speedoShown && kmh >= SPEED_SHOW_KMH) {
                setSpeedoShown(true)
            }
        }
        if (speedoShown) binding.homeSpeedometer.setSpeed(kmh)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startSpeedoUpdates() {
        if (!speedoEnabled || !hasLocationPermission()) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, speedoLocationListener, Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun stopSpeedoUpdates() {
        try { locationManager.removeUpdates(speedoLocationListener) } catch (_: Exception) {}
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
        stopSpeedoUpdates()
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
