package com.waqas.carlauncher

import android.content.Context

object PlayerState {
    private const val PREFS = "car_launcher_prefs"
    private const val KEY_TRACK_ID = "last_track_id"
    private const val KEY_POSITION = "last_position_ms"
    private const val KEY_WAS_PLAYING = "last_was_playing"

    fun save(context: Context, trackId: Long, positionMs: Int, wasPlaying: Boolean) {
        prefs(context).edit()
            .putLong(KEY_TRACK_ID, trackId)
            .putInt(KEY_POSITION, positionMs)
            .putBoolean(KEY_WAS_PLAYING, wasPlaying)
            .apply()
    }

    fun savedTrackId(context: Context): Long = prefs(context).getLong(KEY_TRACK_ID, -1L)
    fun savedPosition(context: Context): Int = prefs(context).getInt(KEY_POSITION, 0)
    fun savedWasPlaying(context: Context): Boolean = prefs(context).getBoolean(KEY_WAS_PLAYING, false)

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_TRACK_ID)
            .remove(KEY_POSITION)
            .remove(KEY_WAS_PLAYING)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
