package com.waqas.carlauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.waqas.carlauncher.databinding.ActivityNightModeBinding
import java.text.SimpleDateFormat
import java.util.Locale

class NightModeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESTORED = "restored_from_boot"
    }

    private lateinit var binding: ActivityNightModeBinding
    private val handler = Handler(Looper.getMainLooper())

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private var service: AudioPlayerService? = null
    private var isRestored: Boolean = false

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

        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SystemUi.apply(this, fullScreen = true)

        binding.root.setOnClickListener { dismiss() }
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
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
        try { unregisterReceiver(timeChangedReceiver) } catch (_: Exception) {}
    }

    private fun updateClock() {
        val corrected = DateOffset.correctedNow(this)
        binding.clockText.text = timeFormat.format(corrected).lowercase(Locale.ROOT)
        binding.dateText.text = dateFormat.format(corrected)
    }
}
