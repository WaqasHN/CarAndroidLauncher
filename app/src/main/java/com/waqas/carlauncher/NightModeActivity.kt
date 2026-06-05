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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.waqas.carlauncher.databinding.ActivityNightModeBinding
import java.text.SimpleDateFormat
import java.util.Locale

class NightModeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESTORED = "restored_from_boot"

        // Hysteresis (km/h) for the "only while moving" option: reveal the gauge
        // once we're clearly moving, hide it once we're nearly stopped. The
        // dead-zone stops GPS jitter at a standstill from flickering it.
        private const val SPEED_SHOW_KMH = 3f
        private const val SPEED_HIDE_KMH = 1.5f
    }

    private lateinit var binding: ActivityNightModeBinding
    private val handler = Handler(Looper.getMainLooper())

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private var service: AudioPlayerService? = null
    private var isRestored: Boolean = false
    private var speedometerEnabled = true
    private var speedometerWhenMoving = false
    private var speedometerShown = false

    private val locationManager: LocationManager
        get() = getSystemService(LOCATION_SERVICE) as LocationManager

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val kmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            updateSpeed(kmh)
        }
        // Kept explicit (not SAM) so we don't hit AbstractMethodError on API 21,
        // where these three had no default implementations.
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLocationUpdates()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as AudioPlayerService.LocalBinder).getService()
            service = svc
            // Only capture the playing state on a fresh entry. On boot-time
            // auto-restore, the flag was already persisted before the reboot.
            if (!isRestored) {
                UiState.setMusicWasPlayingBeforeNight(this@NightModeActivity, svc.isPlaying)
            }
            svc.pause()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000L)
        }
    }

    private val timeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNightModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isRestored = intent.getBooleanExtra(EXTRA_RESTORED, false)
        UiState.setInNightMode(this, true)

        // Full brightness: this doubles as a daytime screensaver and must stay
        // readable in direct sunlight. At night the head unit drops to ~50% on
        // its own once the headlights come on, so we don't dim in software.
        val lp = window.attributes
        lp.screenBrightness = 1.0f
        window.attributes = lp

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SystemUi.apply(this, fullScreen = true)

        binding.root.setOnClickListener { dismiss() }

        // The gauge follows the user's settings: off, always on, or only while
        // moving, in the chosen analog/digital style.
        speedometerEnabled = UiState.isSpeedometerEnabled(this)
        speedometerWhenMoving = UiState.isSpeedometerWhenMoving(this)
        binding.speedometer.setMode(
            if (UiState.isSpeedometerDigital(this)) SpeedometerView.Mode.DIGITAL
            else SpeedometerView.Mode.ANALOG
        )
        // When gated on movement we start hidden and reveal it once we're moving.
        speedometerShown = speedometerEnabled && !speedometerWhenMoving
        showSpeedometer(speedometerShown)

        // We only need location (for the speed value) when the gauge is on. On a
        // fresh (non-boot) entry, ask for permission once if we don't have it;
        // the system dialog renders at normal brightness.
        if (speedometerEnabled && !hasLocationPermission() && !isRestored) {
            requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun updateSpeed(kmh: Float) {
        if (!speedometerEnabled) return
        if (speedometerWhenMoving) {
            // Reveal once clearly moving, hide again near a standstill.
            if (speedometerShown && kmh < SPEED_HIDE_KMH) {
                speedometerShown = false
                showSpeedometer(false)
            } else if (!speedometerShown && kmh >= SPEED_SHOW_KMH) {
                speedometerShown = true
                showSpeedometer(true)
            }
        }
        if (speedometerShown) binding.speedometer.setSpeed(kmh)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Toggle the gauge. When shown, the clock shrinks and shares a horizontal
     * chain with the gauge; when hidden, it grows back to the original size and
     * re-centres across the whole screen. Text sizes are in sp.
     */
    private fun showSpeedometer(show: Boolean) {
        binding.speedometer.visibility = if (show) View.VISIBLE else View.GONE
        binding.clockText.textSize = if (show) 96f else 160f
        binding.dateText.textSize = if (show) 32f else 48f
        val lp = binding.clockColumn.layoutParams as ConstraintLayout.LayoutParams
        if (show) {
            lp.endToStart = binding.speedometer.id
            lp.endToEnd = ConstraintLayout.LayoutParams.UNSET
        } else {
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToStart = ConstraintLayout.LayoutParams.UNSET
        }
        binding.clockColumn.layoutParams = lp
    }

    private fun startLocationUpdates() {
        if (!speedometerEnabled || !hasLocationPermission()) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun stopLocationUpdates() {
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    private fun dismiss() {
        val wasPlaying = UiState.musicWasPlayingBeforeNight(this)
        UiState.setMusicWasPlayingBeforeNight(this, false)
        UiState.setInNightMode(this, false)
        if (wasPlaying) service?.resume()
        finish()
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, AudioPlayerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(connection) } catch (_: Exception) {}
        service = null
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
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
        try { unregisterReceiver(timeChangedReceiver) } catch (_: Exception) {}
        stopLocationUpdates()
    }

    private fun updateClock() {
        val corrected = DateOffset.correctedNow(this)
        binding.clockText.text = timeFormat.format(corrected).lowercase(Locale.ROOT)
        binding.dateText.text = dateFormat.format(corrected)
    }
}
