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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ScreenOcrService (FULLSCREEN TEXT → TD candidates)
 *
 * Cambio clave vs versiones anteriores:
 * - NO recorta al mapRect.
 * - NO depende de OpenCV para detectar forma.
 * - Hace 1 OCR (MLKit) sobre TODA la captura.
 * - De los bloques/lineas arma candidatos "TIEMPO + DISTANCIA" agrupando por cercanía.
 * - Elige 2 candidatos por distancia: min=pickup, max=total.
 *
 * Esto aguanta:
 * - botones abajo, paneles, rutas, etc. (porque filtramos por patrón TD).
 * - que "min" y "km" estén en líneas separadas.
 */
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

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

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

        // Delay pequeño para asegurar frame listo
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

                runMlKitOcrFullScreen(bmp)

            } catch (t: Throwable) {
                Log.e(TAG, "❌ Error capturando pantalla: ${t.message}", t)
                sendOcrError("Error capturando pantalla: ${t.message}")
            } finally {
                try { img?.close() } catch (_: Throwable) {}
                isCapturing.set(false)
            }
        }, 180)
    }

    // =============================
    // FULLSCREEN OCR → TD candidates
    // =============================

    private data class TimeDistance(val seconds: Int, val meters: Int)

    private data class TdCandidate(
        val rect: android.graphics.Rect,
        val td: TimeDistance,
        val text: String
    )

    private data class Token(
        val rect: android.graphics.Rect,
        val text: String,
        val seconds: Int?,
        val meters: Int?
    )

    private fun runMlKitOcrFullScreen(bitmap: Bitmap) {
        // Todo pesado en background
        Thread {
            val t0 = System.currentTimeMillis()
            try {
                val img = InputImage.fromBitmap(bitmap, 0)
                val res = Tasks.await(recognizer.process(img), 1400, TimeUnit.MILLISECONDS)

                val candidates = buildTdCandidatesFromText(res)

                val dt = System.currentTimeMillis() - t0

                if (candidates.isEmpty()) {
                    sendOcrResult(
                        "[OCR/CAPTURA]\n❌ No encontré candidatos TIEMPO+DISTANCIA en toda la pantalla.\n" +
                                "Tip: evita que el overlay tape las cajitas.\n" +
                                "(t=${dt}ms)"
                    )
                    return@Thread
                }

                val sorted = candidates.sortedBy { it.td.meters }
                if (sorted.size == 1) {
                    val c = sorted.first()
                    sendOcrResult(
                        buildString {
                            append("[OCR/CAPTURA]\n")
                            append("=== OCR (TEXTO→TD) ===\n")
                            append("Candidatos TD: 1\n")
                            append("BOX1: ${formatTimeDistance(c.td)}\n")
                            append("(Solo 1 box válida detectada)\n")
                            append("(t=${dt}ms)")
                        }
                    )
                    return@Thread
                }

                val pickup = sorted.first()
                val total = sorted.last()

                val debugList = sorted
                    .take(8)
                    .mapIndexed { i, c ->
                        val r = c.rect
                        "C${i + 1}: ${formatTimeDistance(c.td)}  rect=[${r.left},${r.top},${r.right},${r.bottom}]  txt='${c.text.take(60)}'"
                    }.joinToString("\n")

                val out = buildString {
                    append("[OCR/CAPTURA]\n")
                    append("=== OCR (TEXTO→TD) ===\n")
                    append("Candidatos TD: ${sorted.size}\n")
                    append("PICKUP (min): ${formatTimeDistance(pickup.td)}\n")
                    append("TOTAL  (max): ${formatTimeDistance(total.td)}\n")
                    append("\n-- debug candidatos (top 8) --\n")
                    append(debugList)
                    append("\n(t=${dt}ms)")
                }

                sendOcrResult(out)

            } catch (t: Throwable) {
                Log.e(TAG, "OCR failed: ${t.message}", t)
                sendOcrError("OCR falló: ${t.message}")
            }
        }.start()
    }

    private fun buildTdCandidatesFromText(text: Text): List<TdCandidate> {
        val tokens = ArrayList<Token>(64)

        // 1) Tomamos líneas (más estable que blocks completos) y sacamos:
        // - tokens con tiempo-only
        // - tokens con dist-only
        // - tokens con ambos
        for (b in text.textBlocks) {
            for (l in b.lines) {
                val bb = l.boundingBox ?: continue
                val raw = l.text ?: continue
                val norm = normalizeOcrText(raw)

                val sec = parseSeconds(norm)
                val m = parseMeters(norm)

                if (sec != null || m != null) {
                    tokens += Token(rect = bb, text = norm, seconds = sec, meters = m)
                }
            }
        }

        if (tokens.isEmpty()) return emptyList()

        val both = tokens.filter { it.seconds != null && it.meters != null }
            .map {
                TdCandidate(it.rect, TimeDistance(it.seconds!!, it.meters!!), it.text)
            }.toMutableList()

        val timeOnly = tokens.filter { it.seconds != null && it.meters == null }
        val distOnly = tokens.filter { it.meters != null && it.seconds == null }

        // 2) Emparejar timeOnly con distOnly cercanos para formar una "caja"
        val paired = ArrayList<TdCandidate>(16)
        val usedDist = BooleanArray(distOnly.size)

        fun overlapRatioX(a: android.graphics.Rect, b: android.graphics.Rect): Float {
            val inter = max(0, min(a.right, b.right) - max(a.left, b.left))
            val minW = max(1, min(a.width(), b.width()))
            return inter.toFloat() / minW.toFloat()
        }

        // Ventanas adaptativas: relativo a tamaño de línea
        fun isClose(t: Token, d: Token): Boolean {
            val cxT = (t.rect.left + t.rect.right) / 2
            val cxD = (d.rect.left + d.rect.right) / 2
            val cyT = (t.rect.top + t.rect.bottom) / 2
            val cyD = (d.rect.top + d.rect.bottom) / 2

            val dx = abs(cxT - cxD)
            val dy = abs(cyT - cyD)

            val maxLineW = max(t.rect.width(), d.rect.width())
            val maxLineH = max(t.rect.height(), d.rect.height())

            val okX = dx <= (maxLineW * 0.75f).toInt().coerceAtLeast(120)
            val okY = dy <= (maxLineH * 2.2f).toInt().coerceAtLeast(140)

            // Si están alineados en X, mejor
            val ov = overlapRatioX(t.rect, d.rect)
            return okX && okY && (ov >= 0.25f || dx <= 140)
        }

        for (t in timeOnly) {
            var bestJ = -1
            var bestScore = Int.MAX_VALUE

            for (j in distOnly.indices) {
                if (usedDist[j]) continue
                val d = distOnly[j]
                if (!isClose(t, d)) continue

                val cxT = (t.rect.left + t.rect.right) / 2
                val cxD = (d.rect.left + d.rect.right) / 2
                val cyT = (t.rect.top + t.rect.bottom) / 2
                val cyD = (d.rect.top + d.rect.bottom) / 2

                val score = abs(cxT - cxD) + 2 * abs(cyT - cyD) // preferimos cercanía vertical
                if (score < bestScore) {
                    bestScore = score
                    bestJ = j
                }
            }

            if (bestJ >= 0) {
                usedDist[bestJ] = true
                val d = distOnly[bestJ]
                val rect = unionRect(t.rect, d.rect).apply {
                    // pequeño pad para cubrir la cajita completa
                    inset(-12, -12)
                }.clampNonNegative()

                paired += TdCandidate(
                    rect = rect,
                    td = TimeDistance(seconds = t.seconds!!, meters = d.meters!!),
                    text = "${t.text} | ${d.text}"
                )
            }
        }

        // 3) Unimos todos (both + paired) y deduplicamos por td y cercanía
        val all = (both + paired)
            .filter { it.td.seconds > 0 && it.td.meters > 0 }
            .toMutableList()

        // quita falsos raros (distancia absurda por OCR)
        all.removeAll { it.td.meters > 600_000 } // >600km no aplica aquí

        return dedupeCandidates(all)
    }

    private fun dedupeCandidates(list: List<TdCandidate>): List<TdCandidate> {
        if (list.isEmpty()) return emptyList()

        // 1) dedupe por td exacto (seconds, meters) tomando el rect más pequeño (más probable cajita)
        val byKey = LinkedHashMap<Pair<Int, Int>, TdCandidate>()
        for (c in list) {
            val k = c.td.seconds to c.td.meters
            val prev = byKey[k]
            if (prev == null) {
                byKey[k] = c
            } else {
                val a1 = prev.rect.width() * prev.rect.height()
                val a2 = c.rect.width() * c.rect.height()
                if (a2 < a1) byKey[k] = c
            }
        }

        val uniq = byKey.values.toMutableList()

        // 2) dedupe por rect muy cercano (misma caja leída dos veces)
        val out = ArrayList<TdCandidate>(uniq.size)
        for (c in uniq) {
            val keep = out.none { o -> rectNear(o.rect, c.rect) && abs(o.td.meters - c.td.meters) <= 30 }
            if (keep) out += c
        }

        return out
    }

    private fun rectNear(a: android.graphics.Rect, b: android.graphics.Rect): Boolean {
        val cxA = (a.left + a.right) / 2
        val cxB = (b.left + b.right) / 2
        val cyA = (a.top + a.bottom) / 2
        val cyB = (b.top + b.bottom) / 2
        val dx = abs(cxA - cxB)
        val dy = abs(cyA - cyB)
        return dx <= 90 && dy <= 90
    }

    private fun unionRect(a: android.graphics.Rect, b: android.graphics.Rect): android.graphics.Rect {
        return android.graphics.Rect(
            min(a.left, b.left),
            min(a.top, b.top),
            max(a.right, b.right),
            max(a.bottom, b.bottom)
        )
    }

    private fun android.graphics.Rect.clampNonNegative(): android.graphics.Rect {
        if (left < 0) left = 0
        if (top < 0) top = 0
        if (right < 0) right = 0
        if (bottom < 0) bottom = 0
        return this
    }

    // -----------------------------
    // OCR text normalization + parsers (robustos)
    // -----------------------------

    private fun normalizeOcrText(text: String): String {
        var s = text.lowercase(Locale.ROOT)
        s = s.replace("\n", " ").replace("|", " ").replace(Regex("""\s+"""), " ").trim()

        // OCR típicos de "min"
        s = s.replace(Regex("""\bm\s*in\b"""), " min ")
        s = s.replace(Regex("""\bm1n\b"""), " min ")
        s = s.replace(Regex("""\bmn\b"""), " min ")
        s = s.replace(Regex("""\b1n\b"""), " min ")
        s = s.replace(Regex("""\brnin\b"""), " min ")

        // OCR típicos de "km"
        s = s.replace(Regex("""\bk\s*m\b"""), " km ")
        s = s.replace(Regex("""\bkn\b"""), " km ")
        s = s.replace(Regex("""\bkms\b"""), " km ")

        // decimal coma
        s = s.replace("，", ",")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s
    }

    // Tiempo: soporta h/hr/hrs/hora/horas, min/mins, s/seg/sec
    private fun parseSeconds(s: String): Int? {
        val t = s.lowercase(Locale.ROOT)

        // hh:mm (poco común aquí, pero por si acaso)
        Regex("""\b(\d{1,2})\s*:\s*(\d{2})\b""").find(t)?.let { m ->
            val hh = m.groupValues[1].toIntOrNull()
            val mm = m.groupValues[2].toIntOrNull()
            if (hh != null && mm != null) return hh * 3600 + mm * 60
        }

        Regex("""\b(\d{1,2})\s*(h|hr|hrs|hora|horas)\b""").find(t)?.let { m ->
            val v = m.groupValues[1].toIntOrNull() ?: return@let
            return v * 3600
        }

        Regex("""\b(\d{1,3})\s*(min|mins|min\.)\b""").find(t)?.let { m ->
            val v = m.groupValues[1].toIntOrNull() ?: return@let
            return v * 60
        }

        Regex("""\b(\d{1,3})\s*(s|seg|segs|sec|secs)\b""").find(t)?.let { m ->
            val v = m.groupValues[1].toIntOrNull() ?: return@let
            return v
        }

        return null
    }

    // Distancia: soporta km/k m/kn y metros (m/mt/mts/metro/metros) evitando confundir con "min"
    private fun parseMeters(s: String): Int? {
        val t = s.lowercase(Locale.ROOT)

        Regex("""\b(\d+(?:[.,]\d+)?)\s*(km)\b""").find(t)?.let { m ->
            val raw = m.groupValues[1].replace(',', '.')
            val v = raw.toDoubleOrNull() ?: return@let
            return (v * 1000.0).toInt()
        }

        // metros (NO aceptar "m" si es parte de min)
        Regex("""\b(\d+(?:[.,]\d+)?)\s*(mts|mt|metro|metros|m(?!\s*(?:in\b|1n\b|min\b)))\b""")
            .find(t)?.let { m ->
                val raw = m.groupValues[1].replace(',', '.')
                val v = raw.toDoubleOrNull() ?: return@let
                return v.toInt()
            }

        return null
    }

    private fun formatTimeDistance(td: TimeDistance): String {
        val timeStr = when {
            td.seconds >= 3600 && td.seconds % 3600 == 0 -> "${td.seconds / 3600} h"
            td.seconds >= 60 && td.seconds % 60 == 0 -> "${td.seconds / 60} min"
            td.seconds >= 60 -> "${td.seconds / 60} min ${td.seconds % 60} s"
            else -> "${td.seconds} s"
        }

        val distStr = if (td.meters >= 1000) {
            val km = td.meters / 1000.0
            String.format(Locale.US, "%.1f km", km)
        } else {
            "${td.meters} m"
        }

        return "$timeStr | $distStr"
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
    // Helpers / UI / Broadcast
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

    private fun sendOcrResult(text: String) {
        val out = Intent(DDContracts.ACTION_OCR_RESULT).apply {
            setPackage(packageName)
            putExtra(DDContracts.EXTRA_OCR_TEXT, text)
            putExtra(DDContracts.EXTRA_OCR_DEBUG, "")
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