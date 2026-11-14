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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.R
import kotlinx.coroutines.CompletableDeferred

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            pendingCapture?.complete(null)
            pendingCapture = null
            stopSelf()
            return START_NOT_STICKY
        }

        captureSingleFrame(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun captureSingleFrame(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            pendingCapture?.complete(null)
            pendingCapture = null
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanupResources()
            }
        }, mainHandler)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = windowManager.currentWindowMetrics
        val width = metrics.bounds.width().coerceAtLeast(720)
        val height = metrics.bounds.height().coerceAtLeast(1280)
        val density = resources.displayMetrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "CommanderScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )

        var handled = false
        reader.setOnImageAvailableListener({ imgReader ->
            if (handled) return@setOnImageAvailableListener
            val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            handled = true
            val bitmap = try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val rawBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                rawBitmap.copyPixelsFromBuffer(buffer)
                Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
            } catch (_: Exception) {
                null
            } finally {
                image.close()
            }
            pendingCapture?.complete(bitmap)
            pendingCapture = null
            cleanupResources()
            stopSelf()
        }, mainHandler)

        // Safety timeout if OS doesn't deliver a frame within 3s
        mainHandler.postDelayed({
            if (!handled) {
                handled = true
                pendingCapture?.complete(null)
                pendingCapture = null
                cleanupResources()
                stopSelf()
            }
        }, 3000L)
    }

    private fun cleanupResources() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    override fun onDestroy() {
        cleanupResources()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
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

        @Volatile
        var pendingCapture: CompletableDeferred<Bitmap?>? = null
    }
}
