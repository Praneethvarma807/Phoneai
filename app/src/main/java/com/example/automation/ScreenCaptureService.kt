package com.example.automation

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.example.R
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

internal data class CaptureMetrics(
    val width: Int,
    val height: Int,
    val densityDpi: Int
)

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            completePendingAndClear(null)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundCompat()) {
            completePendingAndClear(null)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = extractProjectionData(intent)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            completePendingAndClear(null)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        captureSingleFrame(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(): Boolean {
        ensureNotificationChannel()
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun captureSingleFrame(resultCode: Int, data: Intent) {
        cleanupResources()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = runCatching { projectionManager?.getMediaProjection(resultCode, data) }.getOrNull()
        if (projection == null) {
            completePendingAndClear(null)
            stopForegroundCompat()
            stopSelf()
            return
        }
        mediaProjection = projection

        val handled = AtomicBoolean(false)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (handled.compareAndSet(false, true)) {
                    completePendingAndClear(null)
                }
                cleanupResources()
                stopForegroundCompat()
                stopSelf()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, mainHandler)

        val captureMetrics = resolveCaptureMetrics(this)
        val width = captureMetrics.width
        val height = captureMetrics.height
        val density = captureMetrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val createdDisplay = runCatching {
            projection.createVirtualDisplay(
                "CommanderScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )
        }.getOrNull()

        if (createdDisplay == null) {
            completePendingAndClear(null)
            cleanupResources()
            stopForegroundCompat()
            stopSelf()
            return
        }
        virtualDisplay = createdDisplay

        reader.setOnImageAvailableListener({ imgReader ->
            if (!handled.compareAndSet(false, true)) return@setOnImageAvailableListener
            val image = runCatching { imgReader.acquireLatestImage() }.getOrNull()
            if (image == null) {
                handled.set(false)
                return@setOnImageAvailableListener
            }
            val bitmap = try {
                val plane = image.planes[0]
                copyAndCropPlaneBitmap(
                    buffer = plane.buffer,
                    width = width,
                    height = height,
                    pixelStride = plane.pixelStride,
                    rowStride = plane.rowStride
                )
            } catch (_: Exception) {
                null
            } finally {
                runCatching { image.close() }
            }
            completePendingAndClear(bitmap)
            cleanupResources()
            stopForegroundCompat()
            stopSelf()
        }, mainHandler)

        val runnable = Runnable {
            if (handled.compareAndSet(false, true)) {
                completePendingAndClear(null)
                cleanupResources()
                stopForegroundCompat()
                stopSelf()
            }
        }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, CAPTURE_TIMEOUT_MS)
    }

    private fun completePendingAndClear(bitmap: Bitmap?) {
        val deferred = pendingCapture
        pendingCapture = null
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(bitmap)
        } else if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun cleanupResources() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null

        val proj = mediaProjection
        val cb = projectionCallback
        mediaProjection = null
        projectionCallback = null
        if (proj != null) {
            if (cb != null) {
                runCatching { proj.unregisterCallback(cb) }
            }
            runCatching { proj.stop() }
        }
    }

    override fun onDestroy() {
        completePendingAndClear(null)
        cleanupResources()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_capture_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.screen_capture_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_full_title))
            .setContentText("Analyzing screen locally (zero upload)")
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "commander_screen_capture"
        const val NOTIFICATION_ID = 4102
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CAPTURE_TIMEOUT_MS = 3000L

        @Volatile
        var pendingCapture: CompletableDeferred<Bitmap?>? = null

        internal fun extractProjectionData(intent: Intent?): Intent? =
            intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }

        internal fun resolveCaptureMetrics(context: Context): CaptureMetrics {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val fallbackDm = context.resources.displayMetrics
            var width = 0
            var height = 0
            var densityDpi = fallbackDm.densityDpi

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
                runCatching {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    width = bounds.width()
                    height = bounds.height()
                }
            } else if (windowManager != null) {
                val outMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                runCatching {
                    windowManager.defaultDisplay?.getRealMetrics(outMetrics)
                    width = outMetrics.widthPixels
                    height = outMetrics.heightPixels
                    if (outMetrics.densityDpi > 0) {
                        densityDpi = outMetrics.densityDpi
                    }
                }
            }

            if (width <= 0) width = fallbackDm.widthPixels
            if (height <= 0) height = fallbackDm.heightPixels

            return CaptureMetrics(
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                densityDpi = densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)
            )
        }

        internal fun copyAndCropPlaneBitmap(
            buffer: ByteBuffer,
            width: Int,
            height: Int,
            pixelStride: Int,
            rowStride: Int
        ): Bitmap? {
            if (width <= 0 || height <= 0 || pixelStride <= 0) return null
            val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)
            val paddedWidth = width + rowPadding / pixelStride
            val rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            return try {
                buffer.rewind()
                rawBitmap.copyPixelsFromBuffer(buffer)
                if (paddedWidth == width) {
                    rawBitmap
                } else {
                    val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                    rawBitmap.recycle()
                    cropped
                }
            } catch (_: Exception) {
                if (!rawBitmap.isRecycled) {
                    rawBitmap.recycle()
                }
                null
            }
        }
    }
}

