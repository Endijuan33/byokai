package com.byokai.data.local

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.repository.AttachmentStorage
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a user-picked image [Uri] (gallery pick or camera capture) into
 * a stored [ImageAttachment] (Phase 9B).
 *
 * Pipeline (all on [Dispatchers.IO]):
 * 1. Decode bounds only, pick an `inSampleSize` so the longest side fits
 *    [MAX_DIMENSION_PX], then decode and scale precisely.
 * 2. Apply the EXIF orientation (phone cameras routinely store portrait
 *    shots rotated) so the stored pixels are upright — what the user
 *    saw is what the provider receives.
 * 3. Compress to JPEG ([JPEG_QUALITY]) into [AttachmentStorage] under a
 *    fresh `<uuid>.jpg` name. Alpha is dropped (documented, not silent:
 *    PNG transparency becomes black) — vision endpoints accept JPEG
 *    universally and it keeps the 15 MB adapter cap reachable.
 *
 * Throws [IOException] (or [IllegalArgumentException] for a non-image)
 * on any failure; the caller ([com.byokai.ui.chat.ChatViewModel])
 * surfaces a localized error and never stages a half-written file —
 * the attachment row is only returned after the bytes are on disk.
 */
class ImageProcessor(
    private val contentResolver: ContentResolver,
    private val attachmentStorage: AttachmentStorage,
) {

    companion object {
        /** Longest side of the stored image, in pixels. */
        const val MAX_DIMENSION_PX = 1568

        /** JPEG quality for the stored copy (0-100). */
        const val JPEG_QUALITY = 85
    }

    suspend fun process(uri: Uri): ImageAttachment =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // NOTE: decodeStream RETURNS NULL when inJustDecodeBounds is
            // set — the dimensions land in bounds.outWidth/outHeight, not
            // in the return value. A null stream (not a null return)
            // means the image could not be opened.
            val input = open(uri) ?: throw IOException("Cannot open image: $uri")
            input.use { BitmapFactory.decodeStream(it, null, bounds) }
            val (srcW, srcH) = bounds.outWidth to bounds.outHeight
            require(srcW > 0 && srcH > 0) { "Not a decodable image: $uri" }

            val sample = sampleSize(srcW, srcH)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = open(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: throw IOException("Cannot decode image: $uri")

            try {
                val oriented = applyExifOrientation(uri, decoded)
                try {
                    val (dstW, dstH) = scaledDimensions(oriented.width, oriented.height)
                    val scaled =
                        if (dstW != oriented.width || dstH != oriented.height) {
                            Bitmap.createScaledBitmap(oriented, dstW, dstH, true)
                        } else {
                            oriented
                        }
                    try {
                        return@withContext store(scaled, dstW, dstH)
                    } finally {
                        if (scaled !== oriented) scaled.recycle()
                    }
                } finally {
                    if (oriented !== decoded) oriented.recycle()
                }
            } finally {
                decoded.recycle()
            }
        }

    private fun open(uri: Uri) = contentResolver.openInputStream(uri)

    private fun sampleSize(srcW: Int, srcH: Int): Int {
        var sample = 1
        val longest = maxOf(srcW, srcH)
        // inSampleSize is most efficient as a power of two.
        while (longest / (sample * 2) >= MAX_DIMENSION_PX) sample *= 2
        return sample
    }

    private fun scaledDimensions(w: Int, h: Int): Pair<Int, Int> {
        val longest = maxOf(w, h)
        if (longest <= MAX_DIMENSION_PX) return w to h
        val scale = MAX_DIMENSION_PX.toDouble() / longest
        return (w * scale).toInt() to (h * scale).toInt()
    }

    /**
     * Reads the EXIF orientation tag and returns an upright bitmap
     * (a rotated copy when needed, the original otherwise). A missing
     * or unreadable EXIF block means "no rotation" — never a failure.
     */
    private fun applyExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            open(uri)?.use { ExifInterface(it) }
                ?.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
        )
    }

    private fun store(bitmap: Bitmap, width: Int, height: Int): ImageAttachment {
        val id = UUID.randomUUID().toString()
        val fileName = "$id.jpg"
        val file = attachmentStorage.fileFor(fileName)
            .also { it.parentFile?.mkdirs() }
        try {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw IOException("JPEG encoding failed")
                }
            }
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw e
        }
        return ImageAttachment(
            id = id,
            fileName = fileName,
            mimeType = "image/jpeg",
            width = width,
            height = height,
        )
    }
}
