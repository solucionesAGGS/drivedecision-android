package com.drivedecision.app.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class ScreenOcrService : Service() {

    private val TAG = "DD_OCR"

    companion object {
        const val ACTION_START_PROJECTION = "com.drivedecision.app.ACTION_START_PROJECTION"

        const val ACTION_OCR_REQUEST = "com.drivedecision.app.ACTION_OCR_REQUEST"
        const val ACTION_OCR_RESULT  = "com.drivedecision.app.ACTION_OCR_RESULT"
        const val EXTRA_OCR_TEXT     = "extra_ocr_text"

        const val EXTRA_RESULT_CODE  = "extra_result_code"
        const val EXTRA_DATA_INTENT  = "extra_data_intent"
    }

    private var mediaProjection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mpCallback = object : Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection onStop()")
            stopProjection()
        }
    }

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_OCR_REQUEST) {
                Log.d(TAG, "📥 OCR_REQUEST recibido ✅")
                // Aquí todavía no hacemos OCR real; solo confirmamos que “captura pipeline” está viva.
                sendResult("✅ OCR_REQUEST recibido. (Pipeline listo; OCR lo conectamos después)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()

        val filter = IntentFilter(ACTION_OCR_REQUEST)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(requestReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(requestReceiver, filter)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(requestReceiver) } catch (_: Exception) {}
        stopProjection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_PROJECTION) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)
            if (data != null) {
                startProjection(resultCode, data)
            } else {
                Log.e(TAG, "data intent null")
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotif() {
        val channelId = "dd_ocr"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "DriveDecision OCR", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notif = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
                .setContentTitle("DriveDecision")
                .setContentText("Captura activa")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("DriveDecision")
                .setContentText("Captura activa")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }

        startForeground(2002, notif)
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        stopProjection()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val mp = mediaProjection ?: return

        // ✅ IMPORTANTE: registrar callback ANTES de createVirtualDisplay (evita crash)
        mp.registerCallback(mpCallback, mainHandler)

        // pantalla
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi

        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        vd = mp.createVirtualDisplay(
            "DD_CAPTURE",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )

        Log.d(TAG, "✅ MediaProjection + VirtualDisplay OK (${width}x${height})")
        sendResult("✅ Captura iniciada OK (${width}x${height}). Ahora ya podemos hacer OCR del mapa.")
    }

    private fun stopProjection() {
        try { vd?.release() } catch (_: Exception) {}
        vd = null

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        try {
            mediaProjection?.unregisterCallback(mpCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}

        mediaProjection = null
    }

    private fun sendResult(text: String) {
        val i = Intent(ACTION_OCR_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_OCR_TEXT, text)
        }
        sendBroadcast(i)
    }
}
