package com.example.frolovsistems.core.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Готовит выбранный снимок к отправке: ужимает до разумного размера и
 * перекодирует в JPEG. Сервер принимает не больше 8 МБ, а снимок с камеры
 * телефона легко занимает больше — и трафик экономить тоже стоит.
 */
object PhotoBytes {

    private const val MAX_SIDE = 1920
    private const val QUALITY = 85
    private const val MAX_BYTES = 7 * 1024 * 1024

    suspend fun fromUri(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // Первый проход — только размеры, без разворачивания картинки в память.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@withContext null

        val scaled = scaleDown(decoded)
        if (scaled !== decoded) decoded.recycle()

        var quality = QUALITY
        var bytes = compress(scaled, quality)
        // Если после сжатия всё ещё слишком много — понижаем качество.
        while (bytes.size > MAX_BYTES && quality > 45) {
            quality -= 15
            bytes = compress(scaled, quality)
        }
        scaled.recycle()

        if (bytes.size > MAX_BYTES) null else bytes
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Степень двойки: BitmapFactory умеет прореживать только так. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= MAX_SIDE && h / 2 >= MAX_SIDE) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_SIDE) return source
        val ratio = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
