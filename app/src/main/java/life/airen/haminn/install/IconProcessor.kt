package life.airen.haminn.install

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import java.io.ByteArrayOutputStream
import java.io.InputStream

object IconProcessor {
    fun centeredPngBytes(open: () -> InputStream?): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "无法识别图标图片")
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_DIMENSION || bounds.outHeight / sample > MAX_DECODE_DIMENSION) sample *= 2
        val bitmap = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "无法读取图标图片")
        val side = minOf(bitmap.width, bitmap.height)
        val square = if (bitmap.width == side && bitmap.height == side) bitmap else {
            Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        }
        val scaled = if (square.width == OUTPUT_SIZE && square.height == OUTPUT_SIZE) square else {
            Bitmap.createScaledBitmap(square, OUTPUT_SIZE, OUTPUT_SIZE, true)
        }
        val bytes = try {
            ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "无法处理图标图片")
                }
                output.toByteArray()
            }
        } finally {
            if (scaled !== square) scaled.recycle()
            if (square !== bitmap) square.recycle()
            bitmap.recycle()
        }
        if (bytes.size > MAX_OUTPUT_BYTES) {
            throw HaminnException(ErrorCodes.QUOTA, "图标处理后仍然过大，请使用较简单的图片")
        }
        return bytes
    }

    fun centeredPngDataUrl(open: () -> InputStream?): String =
        DATA_URL_PREFIX + Base64.encodeToString(centeredPngBytes(open), Base64.NO_WRAP)

    fun decodePngDataUrl(value: String): ByteArray {
        if (!value.startsWith(DATA_URL_PREFIX)) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "图标格式无效")
        val bytes = runCatching { Base64.decode(value.removePrefix(DATA_URL_PREFIX), Base64.NO_WRAP) }
            .getOrElse { throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "图标格式无效") }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bytes.isEmpty() || bytes.size > MAX_OUTPUT_BYTES || bounds.outWidth != OUTPUT_SIZE || bounds.outHeight != OUTPUT_SIZE) {
            throw HaminnException(ErrorCodes.QUOTA, "图标文件无效或过大")
        }
        return bytes
    }

    const val DATA_URL_PREFIX = "data:image/png;base64,"
    const val OUTPUT_SIZE = 192
    private const val MAX_DECODE_DIMENSION = 1024
    private const val MAX_OUTPUT_BYTES = 512 * 1024
}
