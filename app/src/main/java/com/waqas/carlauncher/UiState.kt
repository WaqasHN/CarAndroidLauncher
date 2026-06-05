package com.waqas.carlauncher

import android.content.Context

object UiState {
    private const val PREFS = "car_launcher_prefs"
    private const val KEY_NIGHT_MODE = "in_night_mode"
    private const val KEY_MUSIC_WAS_PLAYING_BEFORE_NIGHT = "music_was_playing_before_night"
    private const val KEY_FULL_SCREEN = "full_screen"
    private const val KEY_SPEEDOMETER_ENABLED = "speedometer_enabled"
    private const val KEY_SPEEDOMETER_DIGITAL = "speedometer_digital"
    private const val KEY_SPEEDOMETER_WHEN_MOVING = "speedometer_when_moving"
    private const val KEY_SPEEDOMETER_ON_HOME = "speedometer_on_home"

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

    /** Whether the speedometer is shown on the screensaver screen (default on). */
    fun isSpeedometerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEEDOMETER_ENABLED, true)

    fun setSpeedometerEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEEDOMETER_ENABLED, value).apply()
    }

    /** True = digital 7-segment readout, false = analog dial (default analog). */
    fun isSpeedometerDigital(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEEDOMETER_DIGITAL, false)

    fun setSpeedometerDigital(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEEDOMETER_DIGITAL, value).apply()
    }

    /** True = only reveal the gauge while the car is moving (default off, i.e. always). */
    fun isSpeedometerWhenMoving(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEEDOMETER_WHEN_MOVING, false)

    fun setSpeedometerWhenMoving(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEEDOMETER_WHEN_MOVING, value).apply()
    }

    /** Whether the speedometer is also shown on the home screen (default off). */
    fun isSpeedometerOnHome(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEEDOMETER_ON_HOME, false)

    fun setSpeedometerOnHome(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEEDOMETER_ON_HOME, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
