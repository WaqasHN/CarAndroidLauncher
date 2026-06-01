package com.waqas.carlauncher

import android.content.Context
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

object DateOffset {

    private const val PREFS = "car_launcher_prefs"
    private const val KEY_OFFSET_DAYS = "offset_days"

    fun getOffsetDays(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_OFFSET_DAYS, 0L)

    fun setOffsetDays(context: Context, days: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_OFFSET_DAYS, days)
            .apply()
    }

    fun correctedNow(context: Context): Date {
        val offset = getOffsetDays(context)
        return Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(offset))
    }

    fun computeOffsetDays(actualToday: Calendar): Long {
        val systemToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val target = (actualToday.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffMillis = target.timeInMillis - systemToday.timeInMillis
        return TimeUnit.MILLISECONDS.toDays(diffMillis)
    }
}
