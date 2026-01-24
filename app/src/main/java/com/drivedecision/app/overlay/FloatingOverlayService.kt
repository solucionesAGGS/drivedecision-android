package com.drivedecision.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.drivedecision.app.DDSettings
import com.drivedecision.app.DDContracts
import com.drivedecision.app.R
import com.drivedecision.app.ocr.ScreenOcrService
import kotlin.math.abs
import kotlin.math.max

/**
 * Overlay flotante (burbuja + panel).
 *
 * - Recibe texto del Accessibility (ACTION_READ_RESULT)
 * - Dispara OCR de pantalla (ACTION_OCR_REQUEST) y recibe resultado (ACTION_OCR_RESULT)
 * - Con ambos (ofertas + tiempo/distancia) calcula si conviene por hora / por km
 *
 * IMPORTANTE: No cambia nada del AccessibilityService. Solo consume su broadcast.
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "DD_OVL"
        private const val CHANNEL_ID = "dd_overlay_channel"
        private const val NOTIF_ID = 1001
    }

    private lateinit var wm: WindowManager

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var txtOutput: TextView? = null
    private var btnAnalyze: Button? = null
    private var btnClosePanel: Button? = null

    private var lastAccText: String = ""
    private var lastOcrText: String = ""

    private val resultsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                DDContracts.ACTION_READ_RESULT -> {
                    val t = intent.getStringExtra(DDContracts.EXTRA_READ_TEXT) ?: ""
                    lastAccText = if (t.isBlank()) "(vacio)" else t
                    renderOutput()
                }
                DDContracts.ACTION_OCR_RESULT -> {
                    val t = intent.getStringExtra(DDContracts.EXTRA_OCR_TEXT) ?: ""
                    lastOcrText = if (t.isBlank()) "(vacio)" else t
                    // OCR listo -> vuelve a mostrar overlay
                    setOverlayVisible(true)
                    renderOutput()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        startForeground(NOTIF_ID, buildForegroundNotification())
        createChannelIfNeeded()

        buildViews()
        registerReceiverCompat()

        Log.d(TAG, "Overlay creado")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(resultsReceiver)
        } catch (_: Throwable) {
        }

        try {
            bubbleView?.let { wm.removeView(it) }
        } catch (_: Throwable) {
        }
        try {
            panelView?.let { wm.removeView(it) }
        } catch (_: Throwable) {
        }
        bubbleView = null
        panelView = null

        Log.d(TAG, "Overlay destruido")
    }

    // ---------------- UI ----------------

    private fun buildViews() {
        val inflater = LayoutInflater.from(this)

        bubbleView = inflater.inflate(R.layout.overlay_bubble, null, false)
        panelView = inflater.inflate(R.layout.overlay_panel, null, false)

        // En overlay_panel.xml el TextView se llama tvOutput
        txtOutput = panelView?.findViewById(R.id.tvOutput)
        btnAnalyze = panelView?.findViewById(R.id.btnAnalyze)
        btnClosePanel = panelView?.findViewById(R.id.btnClose)

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 40
            y = 400
        }

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            x = 0
            y = 260
        }

        wm.addView(bubbleView, bubbleParams)
        wm.addView(panelView, panelParams)

        // start with panel hidden (solo burbuja)
        panelView?.visibility = View.GONE

        setupBubbleDragAndClick()
        setupButtons()
    }

    private fun setupBubbleDragAndClick() {
        val b = bubbleView ?: return
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        b.setOnTouchListener { _, ev ->
            val lp = bubbleParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    try {
                        wm.updateViewLayout(bubbleView, lp)
                    } catch (_: Throwable) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        btnClosePanel?.setOnClickListener {
            panelView?.visibility = View.GONE
        }
        btnAnalyze?.setOnClickListener {
            requestOcrOnce()
        }
    }

    private fun togglePanel() {
        panelView?.let { p ->
            p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        try {
            bubbleView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            panelView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        } catch (_: Throwable) {
        }
    }

    // ---------------- OCR trigger ----------------

    private fun requestOcrOnce() {
        // ocultar overlay para que no estorbe en la captura
        setOverlayVisible(false)

        Log.d(TAG, "📌 CLICK LEER (startService OCR_REQUEST)")

        val i = Intent(this, ScreenOcrService::class.java).apply {
            action = DDContracts.ACTION_OCR_REQUEST
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    // ---------------- Render / lógica negocio ----------------

    private data class Offer(val amountMx: Double, val label: String)
    private data class Td(val minutes: Int, val km: Double, val src: String)

    private fun renderOutput() {
        val out = StringBuilder()

        // 1) Mostrar info base (para debug)
        if (lastAccText.isNotBlank()) {
            out.appendLine("=== ACCESS ===")
            out.appendLine(lastAccText.trim())
            out.appendLine()
        }
        if (lastOcrText.isNotBlank()) {
            out.appendLine("=== OCR ===")
            out.appendLine(lastOcrText.trim())
            out.appendLine()
        }

        // 2) Intentar parsear ofertas y TD
        val offers = parseOffers(lastAccText)
        val td = parseTotalTd(lastOcrText)

        if (offers.isEmpty()) {
            out.appendLine("⚠️ No detecté ofertas (MXN) en accessibility.")
            txtOutput?.text = out.toString()
            return
        }
        if (td == null) {
            out.appendLine("⚠️ No detecté TIEMPO+DISTANCIA en OCR.")
            txtOutput?.text = out.toString()
            return
        }

        // 3) Calcular métricas por cada oferta
        val s = DDSettings.load(this)

        val totalHours = td.minutes.toDouble() / 60.0
        val speed = if (totalHours > 0) td.km / totalHours else 0.0 // km/h
        val kmPerL = blendedKmPerL(speed, s.cityKmPerL, s.hwyKmPerL)

        val gasLiters = if (kmPerL > 0) td.km / kmPerL else 0.0
        val gasCost = gasLiters * s.fuelPrice
        val otherCost = td.km * s.otherCostPerKm
        val variableCost = gasCost + otherCost

        out.appendLine("=== ANÁLISIS (TD total) ===")
        out.appendLine("TD: ${td.minutes} min | ${fmtKm(td.km)} km  (src=${td.src})")
        out.appendLine("Vel prom: ${fmt1(speed)} km/h  | Rend est: ${fmt1(kmPerL)} km/L")
        out.appendLine("Costo var aprox: $${fmt2(variableCost)}  (gas=$${fmt2(gasCost)} + otros=$${fmt2(otherCost)})")
        out.appendLine("Mínimo deseado (neto/h): $${fmt2(s.minNetPerHour)}")
        out.appendLine()

        val sorted = offers.distinctBy { it.amountMx }.sortedByDescending { it.amountMx }.take(10)

        out.appendLine("=== OFERTAS (top) ===")
        for (o in sorted) {
            val gross = o.amountMx
            val net = gross - variableCost
            val payPerHour = if (totalHours > 0) net / totalHours else 0.0
            val payPerKmGross = if (td.km > 0) gross / td.km else 0.0
            val ok = payPerHour >= s.minNetPerHour

            out.appendLine("${if (ok) "✅" else "❌"} ${o.label}: $${fmt0(gross)} | net/h=$${fmt0(payPerHour)} | $/km(gross)=$${fmt2(payPerKmGross)}")
        }

        val bestOk = sorted
            .map { o ->
                val net = o.amountMx - variableCost
                val payPerHour = if (totalHours > 0) net / totalHours else 0.0
                o to payPerHour
            }
            .filter { it.second >= s.minNetPerHour }
            .maxByOrNull { it.second }

        out.appendLine()
        if (bestOk != null) {
            out.appendLine("⭐ Recomendación: ${bestOk.first.label} (net/h≈$${fmt0(bestOk.second)})")
        } else {
            out.appendLine("⭐ Recomendación: Ninguna oferta cumple tu mínimo neto/h.")
        }

        txtOutput?.text = out.toString()
    }

    private fun parseOffers(text: String): List<Offer> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Offer>()

        // Prioridad: "Aceptar por MXN70"
        Regex("""aceptar\s+por\s+mxn\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull()
                if (v != null) out += Offer(v, "ACEPTAR")
            }

        // Luego cualquier MXNxx (incluye listas)
        Regex("""mxn\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { m ->
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@forEach
                out += Offer(v, "MXN${fmt0(v)}")
            }

        // También $xx por si cambia formato
        Regex("""\$\s*([0-9]+(?:[.,][0-9]+)?)""")
            .findAll(text)
            .forEach { m ->
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@forEach
                out += Offer(v, "$${fmt0(v)}")
            }

        // dedup preservando orden
        val seen = HashSet<Double>()
        return out.filter { seen.add(it.amountMx) }
    }

    private fun parseTotalTd(ocrText: String): Td? {
        if (ocrText.isBlank()) return null
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }

        fun parseLine(line: String): Td? {
            val km = parseDistanceKm(line) ?: return null
            val min = parseDurationMinutes(line) ?: return null
            return Td(min, km, line.take(24))
        }

        // 1) si hay línea con "TOTAL"
        lines.firstOrNull { it.contains("total", ignoreCase = true) }?.let { l ->
            parseLine(l)?.let { return it.copy(src = "TOTAL") }
        }

        // 2) candidatos por línea
        val cands = lines.mapNotNull { parseLine(it) }
        if (cands.isNotEmpty()) {
            // escoger el que tenga más km (normalmente es el total)
            return cands.maxByOrNull { it.km }!!.copy(src = "MAX_KM")
        }

        // 3) fallback en todo el texto
        val kmAll = parseDistanceKm(ocrText)
        val minAll = parseDurationMinutes(ocrText)
        if (kmAll != null && minAll != null) return Td(minAll, kmAll, "ALL")
        return null
    }

    private fun parseDistanceKm(s0: String): Double? {
        val s = s0.lowercase()

        // km tolerante OCR
        Regex("""(\d+(?:[.,]\d+)?)\s*(?:k\s*m|km|kms|kn)\b""")
            .find(s)?.let { m ->
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull()
                if (v != null) return v
            }

        // metros -> km
        Regex("""(\d+(?:[.,]\d+)?)\s*(?:mts|mt|metro|metros|m)\b""")
            .find(s)?.let { m ->
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull()
                if (v != null) return v / 1000.0
            }

        return null
    }

    private fun parseDurationMinutes(s0: String): Int? {
        val s = s0.lowercase()

        // hh:mm
        Regex("""\b(\d{1,2})\s*:\s*(\d{1,2})\b""").find(s)?.let { m ->
            val hh = m.groupValues[1].toIntOrNull()
            val mm = m.groupValues[2].toIntOrNull()
            if (hh != null && mm != null) return (hh * 60 + mm)
        }

        // X h Y min
        Regex("""\b(\d+)\s*(?:h|hr|hrs|hora|horas)\s*(\d+)\s*(?:min|mins|minuto|minutos|m)\b""")
            .find(s)?.let { m ->
                val h = m.groupValues[1].toIntOrNull()
                val min = m.groupValues[2].toIntOrNull()
                if (h != null && min != null) return h * 60 + min
            }

        // solo horas
        Regex("""\b(\d+)\s*(?:h|hr|hrs|hora|horas)\b""")
            .find(s)?.let { m ->
                val h = m.groupValues[1].toIntOrNull()
                if (h != null) return h * 60
            }

        // minutos
        Regex("""\b(\d+)\s*(?:min|mins|minuto|minutos)\b""")
            .find(s)?.let { m ->
                val min = m.groupValues[1].toIntOrNull()
                if (min != null) return min
            }

        // segundos -> redondeo hacia arriba a 1 min mínimo
        Regex("""\b(\d+)\s*(?:s|seg|segs|segundo|segundos)\b""")
            .find(s)?.let { m ->
                val sec = m.groupValues[1].toIntOrNull()
                if (sec != null) return kotlin.math.max(1, (sec + 59) / 60)
            }

        // fallback: " 10 m " puede confundir con metros, por eso solo si NO hay km
        if (!s.contains("km") && !s.contains("k m")) {
            Regex("""\b(\d+)\s*m\b""").find(s)?.let { m ->
                val min = m.groupValues[1].toIntOrNull()
                if (min != null) return min
            }
        }

        return null
    }

    private fun blendedKmPerL(speedKmh: Double, city: Double, highway: Double): Double {
        // Interpola suavemente: <25 = city, >60 = highway
        val a = when {
            speedKmh <= 25.0 -> 0.0
            speedKmh >= 60.0 -> 1.0
            else -> (speedKmh - 25.0) / (60.0 - 25.0)
        }
        return city * (1.0 - a) + highway * a
    }

    // ---------------- Foreground / receiver helpers ----------------

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("DriveDecision")
            .setContentText("Overlay activo")
            .setOngoing(true)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "DriveDecision Overlay", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun registerReceiverCompat() {
        val f = IntentFilter().apply {
            addAction(DDContracts.ACTION_READ_RESULT)
            addAction(DDContracts.ACTION_OCR_RESULT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultsReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(resultsReceiver, f)
        }
    }

    // ---------------- small formatters ----------------

    private fun fmt0(v: Double): String = v.toInt().toString()
    private fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
    private fun fmtKm(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
