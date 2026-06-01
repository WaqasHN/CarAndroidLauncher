package com.waqas.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object Wallpaper {
    private const val FILE_NAME = "wallpaper.img"
    private const val MAX_DIM = 1280
    private const val MAX_OOM_RETRIES = 5

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun isSet(context: Context): Boolean = file(context).exists()

    fun saveFromUri(context: Context, sourceUri: Uri) {
        val dest = file(context)
        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Cannot open input stream for $sourceUri" }
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * Decodes the saved wallpaper, downsampling so neither dimension exceeds
     * MAX_DIM. Uses RGB_565 (half the memory of ARGB_8888 — fine for a background)
     * and retries with progressively larger inSampleSize if the device throws
     * OutOfMemoryError, so large images on RAM-constrained head units still load.
     */
    fun loadBitmap(context: Context): Bitmap? {
        val f = file(context)
        if (!f.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(f.absolutePath, bounds)
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = computeSampleSize(bounds.outWidth, bounds.outHeight)
        repeat(MAX_OOM_RETRIES) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
                if (bmp != null) return bmp
            } catch (oom: OutOfMemoryError) {
                // fall through to next attempt
            } catch (e: Exception) {
                return null
            }
            sample *= 2
        }
        return null
    }

    private fun computeSampleSize(srcW: Int, srcH: Int): Int {
        var sample = 1
        while (srcW / sample > MAX_DIM || srcH / sample > MAX_DIM) {
            sample *= 2
        }
        return sample
    }
}
