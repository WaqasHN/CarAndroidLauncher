package com.waqas.carlauncher

import android.content.Context

object UiState {
    private const val PREFS = "car_launcher_prefs"
    private const val KEY_NIGHT_MODE = "in_night_mode"
    private const val KEY_MUSIC_WAS_PLAYING_BEFORE_NIGHT = "music_was_playing_before_night"
    private const val KEY_FULL_SCREEN = "full_screen"

    fun isInNightMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NIGHT_MODE, false)

    fun setInNightMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NIGHT_MODE, value).apply()
    }

    fun musicWasPlayingBeforeNight(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MUSIC_WAS_PLAYING_BEFORE_NIGHT, false)

    fun setMusicWasPlayingBeforeNight(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUSIC_WAS_PLAYING_BEFORE_NIGHT, value).apply()
    }

    fun isFullScreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULL_SCREEN, false)

    fun setFullScreen(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULL_SCREEN, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
