package com.waqas.carlauncher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.waqas.carlauncher.databinding.ActivityAudioLibraryBinding

class AudioLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioLibraryBinding
    private var tracks: List<Track> = emptyList()
    private var service: AudioPlayerService? = null

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadAndShow() else showEmpty(R.string.permission_required)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            this@AudioLibraryActivity.service =
                (service as AudioPlayerService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.trackList.layoutManager = LinearLayoutManager(this)

        if (ContextCompat.checkSelfPermission(this, audioPermission) ==
            PackageManager.PERMISSION_GRANTED) {
            loadAndShow()
        } else {
            permissionLauncher.launch(audioPermission)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AudioPlayerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(connection) } catch (_: Exception) {}
        service = null
    }

    private fun loadAndShow() {
        tracks = AudioRepo.loadAll(this)
        if (tracks.isEmpty()) {
            showEmpty(R.string.no_tracks)
            return
        }
        binding.emptyText.visibility = View.GONE
        binding.trackList.visibility = View.VISIBLE
        binding.trackList.adapter = AudioListAdapter(tracks) { index ->
            service?.playQueue(tracks, index)
        }
    }

    private fun showEmpty(textRes: Int) {
        binding.emptyText.setText(textRes)
        binding.emptyText.visibility = View.VISIBLE
        binding.trackList.visibility = View.GONE
    }
}
