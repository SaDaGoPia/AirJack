package com.ace4.airplayreceiver

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Approximates Android's AndroidX Palette "Vibrant" swatch without pulling
 * in the palette library (this project avoids AndroidX entirely): quantizes
 * a downsampled copy of the artwork into coarse RGB buckets, discards
 * near-black/near-white/low-saturation pixels (usually cover padding, not
 * the actual art), and picks the most frequent remaining bucket. The result
 * is clamped to a dark, saturated range so white notification text stays
 * legible regardless of the source image's own brightness.
 */
object ArtworkColor {

    fun extractAccent(bitmap: Bitmap, fallback: Int): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val buckets = HashMap<Int, Int>()
        val hsv = FloatArray(3)
        for (y in 0 until sample.height) {
            for (x in 0 until sample.width) {
                val pixel = sample.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                if (hsv[2] < 0.15f || hsv[2] > 0.95f || hsv[1] < 0.15f) continue
                val r = (Color.red(pixel) / 32) * 32
                val g = (Color.green(pixel) / 32) * 32
                val b = (Color.blue(pixel) / 32) * 32
                val key = Color.rgb(r, g, b)
                buckets[key] = (buckets[key] ?: 0) + 1
            }
        }
        sample.recycle()
        val winner = buckets.maxByOrNull { it.value }?.key ?: return fallback
        Color.colorToHSV(winner, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = hsv[2].coerceIn(0.28f, 0.55f)
        return Color.HSVToColor(hsv)
    }
}
