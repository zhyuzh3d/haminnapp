package life.airen.haminn.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import life.airen.haminn.MainActivity
import life.airen.haminn.R
import life.airen.haminn.data.HostImageStore
import life.airen.haminn.model.WebAppInstance
import java.io.File
import java.util.Locale

object HappTaskHost {
    fun intent(context: Context, instance: WebAppInstance): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = taskUri(instance.appId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            putExtra(MainActivity.EXTRA_APP_ID, instance.appId)
            instance.happId?.let { putExtra(MainActivity.EXTRA_HAPP_ID, it) }
            instance.publisherKeyId?.let { putExtra(MainActivity.EXTRA_PUBLISHER_KEY_ID, it) }
        }

    fun taskUri(appId: String): Uri = Uri.Builder()
        .scheme("haminn")
        .authority("happ")
        .appendPath(appId)
        .build()

    @Suppress("DEPRECATION")
    fun applyDescription(activity: Activity, instance: WebAppInstance?) {
        val icon = if (instance == null) {
            BitmapFactory.decodeResource(activity.resources, R.drawable.haminn_icon)
        } else {
            instance.effectiveIconUrl
                ?.let { HostImageStore(activity).open(it)?.file }
                ?.let(::decodeTaskIcon)
                ?: defaultTaskIcon(instance)
        }
        activity.setTaskDescription(
            ActivityManager.TaskDescription(instance?.name ?: activity.getString(R.string.app_name), icon, 0)
        )
    }

    private fun decodeTaskIcon(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_TASK_ICON_SIZE || bounds.outHeight / sampleSize > MAX_TASK_ICON_SIZE) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }

    private fun defaultTaskIcon(instance: WebAppInstance): Bitmap {
        val bitmap = Bitmap.createBitmap(MAX_TASK_ICON_SIZE, MAX_TASK_ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = intArrayOf(
            Color.rgb(24, 121, 201),
            Color.rgb(181, 53, 93),
            Color.rgb(35, 139, 89),
            Color.rgb(185, 94, 32),
        )
        canvas.drawColor(palette[Math.floorMod(instance.appId.hashCode(), palette.size)])
        val mark = instance.name.trim().codePoints().findFirst().orElse('H'.code)
        val text = String(Character.toChars(mark)).uppercase(Locale.ROOT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = MAX_TASK_ICON_SIZE * 0.58f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = MAX_TASK_ICON_SIZE / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, MAX_TASK_ICON_SIZE / 2f, baseline, paint)
        return bitmap
    }

    private const val MAX_TASK_ICON_SIZE = 256
}
