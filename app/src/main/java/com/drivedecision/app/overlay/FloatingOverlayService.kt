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
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.SystemClock
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

    // ===== Auto-open (inDrive + "Solicitud de viaje") =====
    private var isOfferScreen: Boolean = false
    private var lastAutoOcrAt: Long = 0L
    private var lastSignature: String = ""
    // Estabilización de auto-OCR (evita capturar mientras el UI cambia)
    private var pendingSignature: String = ""
    private var pendingSince: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isOcrInFlight: Boolean = false
    private var ocrCooldownUntil: Long = 0L

    private val resultsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                DDContracts.ACTION_READ_RESULT -> {
                    val t = intent.getStringExtra(DDContracts.EXTRA_READ_TEXT) ?: ""
                    lastAccText = if (t.isBlank()) "(vacio)" else t
                    // 🔒 Si hay OCR en curso, NO muestres/ocultes panel aquí (podría tapar el mapa en la captura)
                    if (isOcrInFlight) {
                        // Solo actualiza el texto interno; la visibilidad se restaura en ACTION_OCR_RESULT
                        renderOutput()
                        return
                    }


                    // ✅ Auto-mostrar / ocultar panel SOLO en inDrive cuando detecta "Solicitud de viaje"
                    val norm = normalize(lastAccText)

                    // Señal explícita desde Accessibility cuando NO es inDrive
                    val forcedNotInDrive = norm.contains("not indrive")

                    // El dump normal trae "pkg=sinet.startup.inDriver"
                    val inDrive = norm.contains("pkg=${DDContracts.INDRIVE_PACKAGE.lowercase()}")

                    val nowOfferScreen = (!forcedNotInDrive) && inDrive && containsSolicitudDeViaje(lastAccText)

                    if (!nowOfferScreen) {
                        isOfferScreen = false
                        showPanelOnly(false) // deja la burbuja, quita el panel
                        renderOutput()
                        return
                    }

                    val wasOffer = isOfferScreen
                    isOfferScreen = true
                    showPanelOnly(true)

                    // Firma para detectar cambio de solicitud y auto-analizar
                    val signature = buildStableSignature(norm)

                    if (!wasOffer) {
                        lastSignature = "" // fuerza OCR al entrar a la pantalla objetivo
                    }
                    maybeAutoOcr(signature)

                    renderOutput()
                }
                DDContracts.ACTION_OCR_RESULT -> {
                    val t = intent.getStringExtra(DDContracts.EXTRA_OCR_TEXT) ?: ""
                    lastOcrText = if (t.isBlank()) "(vacio)" else t
                    // OCR listo -> vuelve a mostrar overlay
                    isOcrInFlight = false
                    ocrCooldownUntil = SystemClock.elapsedRealtime() + 900L
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

    // ---------------- Auto-open logic (inDrive + "Solicitud de viaje") ----------------

    private fun normalize(s: String): String {
        val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        return n.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").lowercase()
    }

    private fun containsSolicitudDeViaje(raw: String): Boolean {
        val s = normalize(raw)
        return s.contains("solicitud de viaje")
    }

    private fun showPanelOnly(show: Boolean) {
        if (isOcrInFlight) {
            // No cambies visibilidad mientras capturamos, para no tapar el mapa
            return
        }

        // La burbuja DD siempre visible
        bubbleView?.visibility = View.VISIBLE
        panelView?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun maybeAutoOcr(signature: String) {
        val now = SystemClock.elapsedRealtime()
        if (isOcrInFlight) return
        if (now < ocrCooldownUntil) return
        if (now - lastAutoOcrAt < 1200) return // debounce duro
        if (signature == lastSignature) return

        // --- Estabilización: requiere que la misma firma se mantenga ~280ms ---
        if (signature != pendingSignature) {
            pendingSignature = signature
            pendingSince = now
            // Revisa otra vez en 280ms si sigue igual
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({
                val now2 = SystemClock.elapsedRealtime()
                if (isOcrInFlight) return@postDelayed
                if (now2 < ocrCooldownUntil) return@postDelayed
                if (pendingSignature != signature) return@postDelayed
                if (now2 - pendingSince < 260) return@postDelayed

                // ok estable -> dispara OCR
                lastAutoOcrAt = now2
                lastSignature = signature
                requestOcrOnce()
            }, 280L)
            return
        }

        // ya era la misma firma; si ya pasó el tiempo suficiente, dispara sin esperar al callback
        if (now - pendingSince >= 280) {
            lastAutoOcrAt = now
            lastSignature = signature
            requestOcrOnce()
        }
    }



    // ---------------- OCR trigger ----------------

    private fun requestOcrOnce() {
        if (isOcrInFlight) return
        isOcrInFlight = true
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


    private data class TdInfo(
        val pickup: Td,
        val trip: Td,
        val totalMinutes: Int,
        val totalKm: Double
    )

    /**
     * Lee el resultado del OCR FULLSCREEN (ScreenOcrService) sin tocar su lógica.
     * Espera líneas:
     *  - "PICKUP (min): 17 min | 8.5 km"
     *  - "TOTAL  (max): 11 min | 9.7 km"   <-- aquí "TOTAL" es el RECORRIDO (max meters)
     *
     * Devuelve pickup+recorrido y calcula totales (sumas).
     */
    private fun parsePickupAndTripTd(ocrText: String): TdInfo? {
        if (ocrText.isBlank()) return null
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }

        fun parseAfterColon(line: String): Td? {
            val part = line.substringAfter(":", line)
            val km = parseDistanceKm(part) ?: return null
            val min = parseDurationMinutes(part) ?: return null
            return Td(min, km, "OCR")
        }

        val pickupLine = lines.firstOrNull { it.contains("PICKUP", ignoreCase = true) }

        // ✅ RECORRIDO es lo que queremos como "trip". TOTAL suele ser pickup+recorrido.
        val tripLine =
            lines.firstOrNull { it.contains("RECORRIDO", ignoreCase = true) }
                ?: lines.firstOrNull { it.contains("TOTAL", ignoreCase = true) && it.contains("max", ignoreCase = true) }
                ?: lines.lastOrNull { it.startsWith("TOTAL", ignoreCase = true) }

        val pickup = pickupLine?.let { parseAfterColon(it) }
        val trip = tripLine?.let { parseAfterColon(it) }

        if (pickup != null && trip != null) {
            return TdInfo(
                pickup = pickup,
                trip = trip,
                totalMinutes = pickup.minutes + trip.minutes,
                totalKm = pickup.km + trip.km
            )
        }

        // Fallback: si no vino en formato PICKUP/TOTAL, usamos lo existente (max km)
        val one = parseTotalTd(ocrText) ?: return null
        return TdInfo(
            pickup = Td(0, 0.0, "NONE"),
            trip = one,
            totalMinutes = one.minutes,
            totalKm = one.km
        )
    }

    private fun renderOutput() {
        val offers = parseOffers(lastAccText)
        val tdInfo = parsePickupAndTripTd(lastOcrText)

        // Si falta algo, mostramos SOLO lo mínimo
        if (offers.isEmpty() || tdInfo == null) {
            val out = StringBuilder()
            if (offers.isEmpty()) out.appendLine("⚠️ No detecté ofertas (MXN) todavía.")
            if (tdInfo == null) out.appendLine("⚠️ No detecté PICKUP + RECORRIDO (OCR).")
            txtOutput?.text = out.toString().trim()
            return
        }

        val s = DDSettings.load(this)

        val pickup = tdInfo.pickup
        val trip = tdInfo.trip

        val totalMin = tdInfo.totalMinutes
        val totalKm = tdInfo.totalKm

        val totalHours = totalMin.toDouble() / 60.0
        val speed = if (totalHours > 0) totalKm / totalHours else 0.0 // km/h
        val kmPerL = blendedKmPerL(speed, s.cityKmPerL, s.hwyKmPerL)

        // ✅ Costos variables con km totales (pickup + recorrido)
        val gasLiters = if (kmPerL > 0) totalKm / kmPerL else 0.0
        val gasCost = gasLiters * s.fuelPrice
        val wearCost = totalKm * s.otherCostPerKm
        val variableCost = gasCost + wearCost

        // Orden: pasajero primero, luego contraofertas
        val passengerOffer = offers.firstOrNull { it.label == "ACEPTAR" }
        val counterOffers = offers.filter { it.label != "ACEPTAR" }
            .distinctBy { it.amountMx }
            .sortedBy { it.amountMx }

        val displayOffers = buildList {
            passengerOffer?.let { add(it) }
            addAll(counterOffers.take(6))
        }

        data class OfferMetrics(
            val offer: Offer,
            val gross: Double,
            val fee: Double,
            val net: Double,
            val netPerHour: Double,
            val grossPerKmTrip: Double
        )

        fun metricsFor(o: Offer): OfferMetrics {
            val gross = o.amountMx
            val fee = gross * (s.feePct / 100.0)
            val net = gross - variableCost - fee
            val netPerHour = if (totalHours > 0) net / totalHours else 0.0

            // ✅ tarifa/km del pasajero = SOLO recorrido (trip), NO pickup
            val tripKm = trip.km.coerceAtLeast(0.001)
            val grossPerKmTrip = gross / tripKm

            return OfferMetrics(o, gross, fee, net, netPerHour, grossPerKmTrip)
        }

        val allMetrics = displayOffers.map { metricsFor(it) }

        // ✅ Recomendación correcta:
        // 1) si pasajero cumple → aceptar
        // 2) si no → la MÁS BARATA que cumpla
        // 3) si ninguna → sugerir mínimo requerido
        val minNetH = s.minNetPerHour
        val passengerOk = passengerOffer?.let { metricsFor(it).netPerHour >= minNetH } ?: false

        val recommendation = if (passengerOk && passengerOffer != null) {
            "✅ ACEPTAR: ${fmtMoney(passengerOffer.amountMx)} (cumple tu mínimo)"
        } else {
            val bestCheapest = counterOffers
                .map { it to metricsFor(it).netPerHour }
                .filter { it.second >= minNetH }
                .minByOrNull { it.first.amountMx }

            if (bestCheapest != null) {
                "✅ Recomienda: ${fmtMoney(bestCheapest.first.amountMx)} (la más barata que cumple)"
            } else {
                val requiredGross = variableCost + (minNetH * totalHours)
                "❌ Ninguna cumple. Sugerido mínimo: ${fmtMoney(roundUpToStep(requiredGross, 1.0))}"
            }
        }

        // --- OUTPUT MINIMAL ---
        val out = StringBuilder()
        out.appendLine("PICKUP: ${pickup.minutes} min | ${fmtKm(pickup.km)} km")
        out.appendLine("RECORRIDO: ${trip.minutes} min | ${fmtKm(trip.km)} km")
        out.appendLine("TOTAL: ${totalMin} min | ${fmtKm(totalKm)} km")
        out.appendLine("Costo var: ${fmtMoney(variableCost)} (gas ${fmtMoney(gasCost)} + desgaste ${fmtMoney(wearCost)})")
        out.appendLine("Cuota: ${fmt2(s.feePct)}% (se descuenta del pago)")
        out.appendLine("Mínimo objetivo: ${fmtMoney(minNetH)}/h")
        out.appendLine()

        out.appendLine("Ofertas:")
        allMetrics.forEach { m ->
            val ok = m.netPerHour >= minNetH
            val tag = if (ok) "✅" else "❌"
            val label = if (m.offer.label == "ACEPTAR") "PASAJERO" else m.offer.label

            // ✅ net primero, luego net/h, y tarifa/km solo recorrido
            out.appendLine("$tag $label ${fmtMoney(m.gross)} | cuota ${fmtMoney(m.fee)} | net ${fmtMoney(m.net)} | net/h ${fmtMoney(m.netPerHour)} | (tarifa/km rec ${fmtMoney(m.grossPerKmTrip)})")
        }

        out.appendLine()
        out.appendLine(recommendation)

        txtOutput?.text = out.toString().trim()
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
    private fun fmtMoney(v: Double): String = "$" + fmt2(v)

    private fun roundUpToStep(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        val k = kotlin.math.ceil(value / step)
        return k * step
    }

    // Firma "estable" para no disparar OCR por texto dinámico (ej: "53 seg", "hace 1 min")
    private fun buildStableSignature(norm: String): String {
        // norm ya debe venir en lowercase (tu normalize() lo hace)
        val lines = norm.split('\n')
        val keep = ArrayList<String>(64)

        for (raw in lines) {
            val line = raw.trim()
            if (line.isBlank()) continue

            // descarta cosas que cambian cada segundo y rompen la firma
            if (Regex("""\b\d+\s*seg\b""").containsMatchIn(line)) continue
            if (Regex("""\b\d+\s*segs\b""").containsMatchIn(line)) continue
            if (Regex("""\bhace\s*\d+\s*(?:min|mins|seg|segs)\b""").containsMatchIn(line)) continue
            if (Regex("""\b\d+\s*(?:min|mins)\b""").containsMatchIn(line) && line.length <= 12) continue

            // qué SI mantenemos (texto estable que define una solicitud)
            if (
                line.contains("solicitud de viaje") ||
                line.contains("mxn") ||
                line.contains("aceptar por") ||
                line.contains("ofrece tu tarifa") ||
                line.contains("pkg=") ||
                line.contains("not indrive") ||
                line.contains("pickup") ||
                line.contains("recorrido") ||
                line.contains("total")
            ) {
                keep.add(line)
            }
        }

        // Si por alguna razón quedara vacío, fallback seguro
        if (keep.isEmpty()) return norm.take(1200)

        // Junta y recorta para que sea estable y ligera
        return keep.joinToString("\n").take(1200)
    }

}