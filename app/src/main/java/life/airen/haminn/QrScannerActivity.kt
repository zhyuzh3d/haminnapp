package life.airen.haminn

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** CameraX preview with an APK-local ZXing decoder. It never calls a remote scanner service. */
class QrScannerActivity : ComponentActivity() {
    private val finished = AtomicBoolean(false)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var previewView: PreviewView
    private lateinit var torchButton: Button
    private var torchEnabled = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) previewView.post(::startCamera) else finishError(getString(R.string.qr_camera_permission_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            finishError(getString(R.string.qr_camera_unavailable))
            return
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = getString(R.string.qr_camera_preview)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(QrFinderOverlay(this), FrameLayout.LayoutParams(-1, -1))
        root.addView(TextView(this).apply {
            text = getString(R.string.qr_scan_prompt)
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(0x66000000)
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val close = Button(this).apply {
            text = getString(R.string.cancel)
            contentDescription = getString(R.string.cancel_qr_scan)
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(18), 0, 0, dp(22))
        })
        torchButton = Button(this).apply {
            text = getString(R.string.qr_turn_on_light)
            contentDescription = getString(R.string.qr_turn_on_light)
            visibility = View.INVISIBLE
        }
        root.addView(torchButton, FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(18), dp(22))
        })
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            previewView.post(::startCamera)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, QrAnalyzer(::finishSuccess)) }
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                torchButton.let { button ->
                    button.visibility = if (camera.cameraInfo.hasFlashUnit()) View.VISIBLE else View.INVISIBLE
                    button.setOnClickListener {
                        torchEnabled = !torchEnabled
                        camera.cameraControl.enableTorch(torchEnabled)
                        button.text = getString(if (torchEnabled) R.string.qr_turn_off_light else R.string.qr_turn_on_light)
                        button.contentDescription = button.text
                    }
                }
            } catch (error: Throwable) {
                finishError(getString(R.string.qr_camera_start_failed, error.message ?: getString(R.string.unknown_error)))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishSuccess(value: String) {
        if (!finished.compareAndSet(false, true)) return
        runOnUiThread {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, value))
            finish()
        }
    }

    private fun finishError(message: String) {
        if (!finished.compareAndSet(false, true)) return
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    override fun onDestroy() {
        cameraExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_RESULT = "life.airen.haminn.QR_RESULT"
        const val EXTRA_ERROR = "life.airen.haminn.QR_ERROR"
    }
}

private class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val lastAttempt = AtomicLong(0L)
    private val reader = MultiFormatReader().apply {
        setHints(EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            put(DecodeHintType.TRY_HARDER, true)
            put(DecodeHintType.ALSO_INVERTED, true)
            put(DecodeHintType.CHARACTER_SET, "UTF-8")
        })
    }

    override fun analyze(image: ImageProxy) {
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            val previous = lastAttempt.get()
            if (now - previous < 120 || !lastAttempt.compareAndSet(previous, now)) return
            val frame = image.luminanceFrame()?.rotate(image.imageInfo.rotationDegrees) ?: return
            val source = PlanarYUVLuminanceSource(frame.bytes, frame.width, frame.height, 0, 0, frame.width, frame.height, false)
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            if (result.barcodeFormat == BarcodeFormat.QR_CODE && result.text.isNotBlank()) onDecoded(result.text)
        } catch (_: ReaderException) {
            // A frame without a valid QR code is expected; keep analyzing the newest frame.
        } finally {
            reader.reset()
            image.close()
        }
    }

    private fun ImageProxy.luminanceFrame(): LuminanceFrame? {
        val plane = planes.firstOrNull() ?: return null
        val crop = cropRect
        val width = crop.width()
        val height = crop.height()
        if (width <= 0 || height <= 0) return null
        val buffer = plane.buffer
        val output = ByteArray(width * height)
        for (row in 0 until height) {
            val rowStart = (crop.top + row) * plane.rowStride + crop.left * plane.pixelStride
            for (column in 0 until width) {
                val source = rowStart + column * plane.pixelStride
                if (source !in 0 until buffer.limit()) return null
                output[row * width + column] = buffer.get(source)
            }
        }
        return LuminanceFrame(output, width, height)
    }
}

private data class LuminanceFrame(val bytes: ByteArray, val width: Int, val height: Int) {
    fun rotate(degrees: Int): LuminanceFrame = when ((degrees % 360 + 360) % 360) {
        90 -> {
            val rotated = ByteArray(bytes.size)
            for (y in 0 until height) for (x in 0 until width) rotated[x * height + (height - 1 - y)] = bytes[y * width + x]
            LuminanceFrame(rotated, height, width)
        }
        180 -> LuminanceFrame(ByteArray(bytes.size) { bytes[bytes.lastIndex - it] }, width, height)
        270 -> {
            val rotated = ByteArray(bytes.size)
            for (y in 0 until height) for (x in 0 until width) rotated[(width - 1 - x) * height + y] = bytes[y * width + x]
            LuminanceFrame(rotated, height, width)
        }
        else -> this
    }
}

private class QrFinderOverlay(context: android.content.Context) : View(context) {
    private val shade = Paint().apply { color = 0x78000000 }
    private val box = RectF()
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3
        strokeCap = Paint.Cap.SQUARE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height) * 0.68f
        box.set((width - size) / 2f, (height - size) / 2f, (width + size) / 2f, (height + size) / 2f)
        canvas.drawRect(0f, 0f, width.toFloat(), box.top, shade)
        canvas.drawRect(0f, box.top, box.left, box.bottom, shade)
        canvas.drawRect(box.right, box.top, width.toFloat(), box.bottom, shade)
        canvas.drawRect(0f, box.bottom, width.toFloat(), height.toFloat(), shade)
        val corner = size * 0.16f
        canvas.drawLine(box.left, box.top, box.left + corner, box.top, frame)
        canvas.drawLine(box.left, box.top, box.left, box.top + corner, frame)
        canvas.drawLine(box.right, box.top, box.right - corner, box.top, frame)
        canvas.drawLine(box.right, box.top, box.right, box.top + corner, frame)
        canvas.drawLine(box.left, box.bottom, box.left + corner, box.bottom, frame)
        canvas.drawLine(box.left, box.bottom, box.left, box.bottom - corner, frame)
        canvas.drawLine(box.right, box.bottom, box.right - corner, box.bottom, frame)
        canvas.drawLine(box.right, box.bottom, box.right, box.bottom - corner, frame)
    }
}
