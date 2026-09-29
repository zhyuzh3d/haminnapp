package life.airen.haminn.install

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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

    /**
     * The one corner radius Haminn clips an icon with, for a given side length.
     *
     * Every surface the host draws an icon onto asks this, so "Haminn's icon shape"
     * is a single rule instead of one number per call site. Nothing inspects the
     * artwork: whatever the image contains, it is clipped to this shape.
     */
    fun cornerRadius(side: Int): Float = side * CORNER_RATIO

    /**
     * Clips an icon to the rounded square Haminn hands to Android's own surfaces.
     *
     * Stored icon bytes stay exactly as they arrived, because the surfaces that clip
     * their own tiles - HaminnUI's app list, the website, the share card - already
     * round them and would otherwise show a sliver of their own background in the
     * corner. The surfaces that clip nothing are Android's: the "add to home screen"
     * dialog and the desktop tile it produces, and the recents card. They take a
     * bitmap and draw it as it is, so the shape has to be in the pixels.
     *
     * What that shape is, is our choice rather than the device's - see CORNER_RATIO.
     * A launcher may mask the result again; a radius kept below every mask means the
     * system's shape simply wins, and the only thing visible is the corner we
     * deliberately drew where nothing masks at all.
     *
     * It is idempotent: an already rounded artwork clips to itself.
     */
    fun rounded(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val square = if (source.width == side && source.height == side) source else {
            Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        }
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = cornerRadius(side)
        Canvas(output).drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), radius, radius, paint)
        if (square !== source) square.recycle()
        return output
    }

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

    /**
     * Corner radius of the icon shape Haminn draws, as a fraction of the side.
     *
     * This is our own fixed choice, deliberately not a property of any device. It is
     * kept below the radius a launcher is likely to mask with - the CMA-AN00 measures
     * 0.168, which is where the number used to be taken from - because the tile the
     * user ends up seeing is our shape intersected with the launcher's mask. While
     * ours is the looser of the two the mask decides the silhouette and the shortcut
     * matches the icons beside it; a radius above the mask would cut the corners
     * first and leave a second arc inside it.
     *
     * Haminn does not reproduce the icon language of every device and does not try
     * to. Below any mask the system's own shape shows, and on a launcher that applies
     * no mask the tile still reads as a rounded square rather than a raw rectangle.
     */
    private const val CORNER_RATIO = 0.12f
    private const val MAX_DECODE_DIMENSION = 1024
    private const val MAX_OUTPUT_BYTES = 512 * 1024
}
