package com.drivedecision.app.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.drivedecision.app.DDContracts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class ScreenOcrService : Service() {

    companion object {
        private const val TAG = "DD_OCR"
        private const val NOTIF_CHANNEL_ID = "dd_ocr_channel"
        private const val NOTIF_ID = 2001
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var projectionCallbackRegistered = false

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0

    private val isCapturing = AtomicBoolean(false)

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("OCR listo"))
        readScreenMetrics()
        Log.d(TAG, "metrics: w=$screenW h=$screenH dpi=$screenDpi sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        stopProjection("service destroyed")
        try { recognizer.close() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        when (intent.action) {
            DDContracts.ACTION_START_PROJECTION -> {
                Log.d(TAG, "ACTION_START_PROJECTION")
                handleStartProjection(intent)
            }
            DDContracts.ACTION_OCR_REQUEST -> {
                Log.d(TAG, "ACTION_OCR_REQUEST")
                handleOcrRequest()
            }
            DDContracts.ACTION_STOP_PROJECTION -> {
                Log.d(TAG, "ACTION_STOP_PROJECTION")
                stopProjection("stop requested")
            }
            else -> Log.d(TAG, "onStartCommand: action=${intent.action} (keep alive)")
        }
        return START_STICKY
    }

    // -----------------------------
    // Projection start/stop
    // -----------------------------

    private fun handleStartProjection(i: Intent) {
        val resultCode = i.getIntExtra(DDContracts.EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = getParcelableIntentCompat(i, DDContracts.EXTRA_RESULT_DATA)

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            Log.e(TAG, "❌ Falta resultCode/resultData en START_PROJECTION")
            sendNeedProjection("Falta resultCode/resultData")
            return
        }

        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mgr.getMediaProjection(resultCode, resultData)

            if (mp == null) {
                Log.e(TAG, "❌ getMediaProjection devolvió null")
                sendNeedProjection("getMediaProjection devolvió null")
                return
            }

            // ✅ OBLIGATORIO antes de createVirtualDisplay en algunos devices
            registerProjectionCallbackIfNeeded(mp)

            mediaProjection = mp
            recreateCapturePipeline("startProjection")

            Log.d(TAG, "✅ MediaProjection OK (callback registrado=$projectionCallbackRegistered)")
            updateNotification("Captura activada")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Error iniciando projection: ${t.message}", t)
            sendOcrError("Error iniciando projection: ${t.message}")
            stopProjection("startProjection failed")
        }
    }

    private fun registerProjectionCallbackIfNeeded(mp: MediaProjection) {
        if (projectionCallbackRegistered) return

        mp.registerCallback(object : Callback() {
            override fun onStop() {
                Log.w(TAG, "📴 MediaProjection STOPPED by system")
                projectionCallbackRegistered = false
                stopProjection("projection stopped by system")
            }
        }, mainHandler)

        projectionCallbackRegistered = true
        Log.d(TAG, "✅ MediaProjection.registerCallback() OK")
    }

    private fun stopProjection(reason: String) {
        Log.w(TAG, "stopProjection(reason=$reason)")

        isCapturing.set(false)

        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null

        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null

        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null

        projectionCallbackRegistered = false
        updateNotification("Captura detenida")
    }

    // -----------------------------
    // OCR request flow
    // -----------------------------

    private fun handleOcrRequest() {
        val mp = mediaProjection
        if (mp == null) {
            Log.e(TAG, "❌ Captura no iniciada (mediaProjection=null)")
            sendNeedProjection("Captura no iniciada. Entra a Main y toca Empezar (acepta captura).")
            return
        }

        if (imageReader == null || virtualDisplay == null) {
            recreateCapturePipeline("ocrRequest")
        }

        captureOneFrameAndRunOcr()
    }

    private fun recreateCapturePipeline(from: String) {
        val mp = mediaProjection ?: return
        Log.d(TAG, "recreateCapturePipeline(from=$from)")

        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null

        val w = max(2, screenW)
        val h = max(2, screenH)

        // ✅ FORMATO CORRECTO: RGBA usa PixelFormat.RGBA_8888
        // ✅ FALLBACK: YUV_420_888 (ImageReader lo soporta bien)
        val formatsToTry = listOf(
            PixelFormat.RGBA_8888,
            android.graphics.ImageFormat.YUV_420_888
        )

        var lastError: Throwable? = null

        for (fmt in formatsToTry) {
            try {
                val reader = ImageReader.newInstance(w, h, fmt, 2)
                imageReader = reader

                virtualDisplay = mp.createVirtualDisplay(
                    "DD_VD",
                    w,
                    h,
                    screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    mainHandler
                )

                Log.d(TAG, "✅ createVirtualDisplay OK fmt=$fmt (w=$w h=$h dpi=$screenDpi)")
                return
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "❌ createVirtualDisplay failed fmt=$fmt: ${t.message}", t)
                try { imageReader?.close() } catch (_: Throwable) {}
                imageReader = null
                try { virtualDisplay?.release() } catch (_: Throwable) {}
                virtualDisplay = null
            }
        }

        sendOcrError("createVirtualDisplay falló: ${lastError?.message}")
        stopProjection("createVirtualDisplay failed")
    }

    private fun captureOneFrameAndRunOcr() {
        if (!isCapturing.compareAndSet(false, true)) {
            Log.w(TAG, "captureOneFrameAndRunOcr(): ya estaba capturando, ignoro")
            return
        }

        val reader = imageReader
        if (reader == null) {
            isCapturing.set(false)
            sendOcrError("ImageReader null (pipeline no listo)")
            return
        }

        Log.d(TAG, "📸 capturando 1 frame...")

        mainHandler.postDelayed({
            var img: Image? = null
            try {
                img = reader.acquireLatestImage()
                if (img == null) {
                    Log.w(TAG, "⚠️ acquireLatestImage() = null (sin frame aún)")
                    sendOcrError("No se obtuvo frame (null image). Intenta de nuevo.")
                    return@postDelayed
                }

                val bmp = imageToBitmapSafe(img)
                Log.d(TAG, "✅ bitmap capturado: ${bmp.width}x${bmp.height} cfg=${bmp.config}")

                runMlKitOcr(bmp)
            } catch (t: Throwable) {
                Log.e(TAG, "❌ Error capturando pantalla: ${t.message}", t)
                sendOcrError("Error capturando pantalla: ${t.message}")
            } finally {
                try { img?.close() } catch (_: Throwable) {}
                isCapturing.set(false)
            }
        }, 140)
    }


    private fun runMlKitOcr(bitmap: Bitmap) {
        Log.d(TAG, "🔎 OCR MLKit iniciando...")

        val fullImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(fullImage)
            .addOnSuccessListener { res ->
                val fullRaw = res.text ?: ""
                val debug = "blocks=${res.textBlocks.size}\nchars=${fullRaw.length}\n"

                // 1) Detectar cajitas por color (azul y verde)
                val boxes = detectRouteBoxes(bitmap)

                if (boxes == null) {
                    // Si no detectamos cajas, regresamos OCR completo como antes (NO rompemos nada)
                    Log.d(TAG, "⚠️ No se detectaron cajas de color, devolviendo OCR completo")
                    sendOcrResult(fullRaw, debug)
                    return@addOnSuccessListener
                }

                val blueCrop = safeCrop(bitmap, boxes.blueRect)
                val greenCrop = safeCrop(bitmap, boxes.greenRect)

                // 2) OCR sobre la caja AZUL
                ocrBitmapText(blueCrop)
                    .addOnSuccessListener { blueText ->
                        // 3) OCR sobre la caja VERDE
                        ocrBitmapText(greenCrop)
                            .addOnSuccessListener { greenText ->

                                val blueInfo = extractTimeKm(blueText)
                                val greenInfo = extractTimeKm(greenText)

                                val composed = buildString {
                                    appendLine("[OCR/CAPTURA]")
                                    appendLine("=== OCR DIRIGIDO (CAJAS) ===")
                                    appendLine("AZUL (pickup/tramo): ${blueInfo ?: "(no detectado)"}")
                                    appendLine("VERDE (ruta total):  ${greenInfo ?: "(no detectado)"}")
                                }

                                sendOcrResult(composed, debug)
                            }
                            .addOnFailureListener { e2 ->
                                Log.e(TAG, "❌ OCR verde falló: ${e2.message}", e2)
                                // No rompemos: devolvemos al menos el full OCR
                                sendOcrResult(fullRaw, debug + "\nverde_fail=${e2.message}")
                            }
                    }
                    .addOnFailureListener { e1 ->
                        Log.e(TAG, "❌ OCR azul falló: ${e1.message}", e1)
                        // No rompemos: devolvemos al menos el full OCR
                        sendOcrResult(fullRaw, debug + "\nazul_fail=${e1.message}")
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ OCR fail: ${e.message}", e)
                sendOcrError("OCR fail: ${e.message}")
            }
    }

    private data class RouteBoxes(
        val blueRect: android.graphics.Rect,
        val greenRect: android.graphics.Rect
    )

    private fun ocrBitmapText(bmp: Bitmap): com.google.android.gms.tasks.Task<String> {
        val img = InputImage.fromBitmap(bmp, 0)
        return recognizer.process(img).continueWith { task ->
            val res = task.result
            res?.text ?: ""
        }
    }

    /**
     * Extrae algo como: "10 min | 4.2 km" desde texto OCR
     */
    private fun extractTimeKm(text: String): String? {
        val t = text.replace("\n", " ").trim()

        // tiempo (ej: "10 min", "2 min.", "14min")
        val timeRegex = Regex("""(\d{1,3})\s*min\.?""", RegexOption.IGNORE_CASE)
        val time = timeRegex.find(t)?.groupValues?.getOrNull(1)

        // km (ej: "5,6 km", "10.8km")
        val kmRegex = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
        val kmRaw = kmRegex.find(t)?.groupValues?.getOrNull(1)
        val km = kmRaw?.replace(",", ".")

        if (time == null && km == null) return null

        return buildString {
            if (time != null) append("${time} min")
            if (time != null && km != null) append(" | ")
            if (km != null) append("${km} km")
        }
    }

    /**
     * Recorta seguro (evita crash si el rect se sale del bitmap)
     */
    private fun safeCrop(src: Bitmap, r: android.graphics.Rect): Bitmap {
        val left = r.left.coerceIn(0, src.width - 1)
        val top = r.top.coerceIn(0, src.height - 1)
        val right = r.right.coerceIn(left + 1, src.width)
        val bottom = r.bottom.coerceIn(top + 1, src.height)

        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    /**
     * Detecta 2 cajas sólidas por color:
     * - Azul: caja "A" (tiempo + km del pickup/tramo)
     * - Verde: caja "B" (tiempo + km total)
     *
     * Importante: NO nos vamos por "bbox de todo" porque la ruta verde del mapa te arruina.
     * Usamos componentes conectados y score por "relleno" (caja sólida gana vs línea delgada).
     */
    private fun detectRouteBoxes(bitmap: Bitmap): RouteBoxes? {
        // Downsample para no matar rendimiento
        val step = 4
        val w = bitmap.width / step
        val h = bitmap.height / step
        if (w <= 0 || h <= 0) return null

        fun isBlue(px: Int): Boolean {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(px, hsv)
            val hue = hsv[0]
            val sat = hsv[1]
            val v = hsv[2]
            // Azul UI típico (cajita)
            return hue in 185f..255f && sat > 0.25f && v > 0.20f
        }

        fun isGreen(px: Int): Boolean {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(px, hsv)
            val hue = hsv[0]
            val sat = hsv[1]
            val v = hsv[2]
            // Verde/amarillo-verde típico (cajita)
            return hue in 55f..150f && sat > 0.20f && v > 0.25f
        }

        // Máscara por color
        val blue = BooleanArray(w * h)
        val green = BooleanArray(w * h)

        for (yy in 0 until h) {
            val y = yy * step
            for (xx in 0 until w) {
                val x = xx * step
                val px = bitmap.getPixel(x, y)
                val idx = yy * w + xx
                blue[idx] = isBlue(px)
                green[idx] = isGreen(px)
            }
        }

        fun bestComponent(mask: BooleanArray): android.graphics.Rect? {
            val visited = BooleanArray(mask.size)
            var bestScore = 0.0
            var bestRect: android.graphics.Rect? = null

            val qx = IntArray(mask.size)
            val qy = IntArray(mask.size)

            fun push(ix: Int, iy: Int, tail: Int): Int {
                qx[tail] = ix
                qy[tail] = iy
                return tail + 1
            }

            for (iy in 0 until h) {
                for (ix in 0 until w) {
                    val p = iy * w + ix
                    if (!mask[p] || visited[p]) continue

                    // BFS
                    var head = 0
                    var tail = 0
                    tail = push(ix, iy, tail)
                    visited[p] = true

                    var minX = ix
                    var maxX = ix
                    var minY = iy
                    var maxY = iy
                    var count = 0

                    while (head < tail) {
                        val cx = qx[head]
                        val cy = qy[head]
                        head++

                        count++
                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        // 4 vecinos
                        fun tryAdd(nx: Int, ny: Int) {
                            if (nx !in 0 until w || ny !in 0 until h) return
                            val np = ny * w + nx
                            if (!mask[np] || visited[np]) return
                            visited[np] = true
                            tail = push(nx, ny, tail)
                        }

                        tryAdd(cx + 1, cy)
                        tryAdd(cx - 1, cy)
                        tryAdd(cx, cy + 1)
                        tryAdd(cx, cy - 1)
                    }

                    // Convertir a rect en coords originales
                    val rect = android.graphics.Rect(
                        minX * step,
                        minY * step,
                        (maxX + 1) * step,
                        (maxY + 1) * step
                    )

                    val rectW = rect.width().coerceAtLeast(1)
                    val rectH = rect.height().coerceAtLeast(1)
                    val area = (rectW * rectH).toDouble()

                    // score por "relleno": caja sólida (muchos pixeles dentro del bbox) gana
                    val fill = count / area
                    val score = (count.toDouble() * fill)

                    // filtros para evitar basura demasiado chica
                    if (area < 1200.0) continue

                    // filtros para evitar líneas larguísimas (ruta)
                    val aspect = rectW.toDouble() / rectH.toDouble()
                    if (aspect < 0.45 || aspect > 3.0) continue

                    if (score > bestScore) {
                        bestScore = score
                        bestRect = rect
                    }
                }
            }

            // Expandimos un poquito para incluir texto dentro (margen)
            bestRect?.let {
                val pad = 10
                it.inset(-pad, -pad)
                it.left = it.left.coerceAtLeast(0)
                it.top = it.top.coerceAtLeast(0)
                it.right = it.right.coerceAtMost(bitmap.width)
                it.bottom = it.bottom.coerceAtMost(bitmap.height)
            }

            return bestRect
        }

        val blueRect = bestComponent(blue)
        val greenRect = bestComponent(green)

        if (blueRect == null || greenRect == null) {
            Log.d(TAG, "detectRouteBoxes: blueRect=$blueRect greenRect=$greenRect")
            return null
        }

        return RouteBoxes(
            blueRect = blueRect,
            greenRect = greenRect
        )
    }
    // -----------------------------
    // Image -> Bitmap (RGBA o YUV)
    // -----------------------------

    private fun imageToBitmapSafe(image: Image): Bitmap {
        return when (image.format) {
            PixelFormat.RGBA_8888 -> imageToBitmapRgba(image)
            android.graphics.ImageFormat.YUV_420_888 -> imageToBitmapYuv420(image)
            else -> {
                Log.w(TAG, "Formato inesperado=${image.format}. Intentando RGBA-path.")
                imageToBitmapRgba(image)
            }
        }
    }

    private fun imageToBitmapRgba(image: Image): Bitmap {
        val width = image.width
        val height = image.height

        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun imageToBitmapYuv420(image: Image): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val out = IntArray(width * height)
        var outIndex = 0

        for (j in 0 until height) {
            val yRow = j * yRowStride
            val uvRow = (j / 2) * uvRowStride

            for (i in 0 until width) {
                val y = (yBuf.get(yRow + i).toInt() and 0xFF)

                val uvOffset = uvRow + (i / 2) * uvPixelStride
                val u = (uBuf.get(uvOffset).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvOffset).toInt() and 0xFF) - 128

                val yf = y - 16
                var r = (1.164f * yf + 1.596f * v).toInt()
                var g = (1.164f * yf - 0.813f * v - 0.391f * u).toInt()
                var b = (1.164f * yf + 2.018f * u).toInt()

                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                out[outIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, width, 0, 0, width, height)
        return bmp
    }

    // -----------------------------
    // Helpers
    // -----------------------------

    private fun readScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        screenDpi = dm.densityDpi
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("DriveDecision OCR")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_ID, "DriveDecision OCR", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun sendNeedProjection(msg: String) {
        val out = Intent(DDContracts.ACTION_NEED_PROJECTION).apply {
            setPackage(packageName)
            putExtra(DDContracts.EXTRA_ERROR, msg)
        }
        Log.w(TAG, "➡️ ACTION_NEED_PROJECTION: $msg")
        sendBroadcast(out)
    }

    private fun sendOcrResult(text: String, debug: String) {
        val out = Intent(DDContracts.ACTION_OCR_RESULT).apply {
            setPackage(packageName)
            putExtra(DDContracts.EXTRA_OCR_TEXT, text)
            putExtra(DDContracts.EXTRA_OCR_DEBUG, debug)
        }
        sendBroadcast(out)
    }

    private fun sendOcrError(msg: String) {
        val out = Intent(DDContracts.ACTION_OCR_RESULT).apply {
            setPackage(packageName)
            putExtra(DDContracts.EXTRA_OCR_TEXT, "❌ $msg")
            putExtra(DDContracts.EXTRA_OCR_DEBUG, "error=1")
        }
        sendBroadcast(out)
    }

    private fun getParcelableIntentCompat(i: Intent, key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(key) as? Intent
        }
    }
}