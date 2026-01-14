package com.drivedecision.app.overlay

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.drivedecision.app.DDContracts
import com.drivedecision.app.R
import com.drivedecision.app.ocr.ScreenOcrService

class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "DD_FLOAT"
        private const val CH_ID = "dd_overlay_channel"
        private const val NOTIF_ID = 3001
    }

    private var wm: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var tvOutput: TextView? = null

    // Cache para pintar ambos resultados juntos
    private var lastAccText: String = "(pendiente)"
    private var lastOcrText: String = "(pendiente)"

    private val resultsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                DDContracts.ACTION_READ_RESULT -> {
                    val t = intent.getStringExtra(DDContracts.EXTRA_RESULT_TEXT) ?: ""
                    lastAccText = if (t.isBlank()) "(vacío)" else t
                    renderOutput()
                    Log.d(TAG, "⬅️ READ_RESULT len=${lastAccText.length}")
                    sendBroadcast(Intent(DDContracts.ACTION_OVERLAY_SHOW).apply { setPackage(packageName) })
                }

                DDContracts.ACTION_OCR_RESULT -> {
                    val t = intent.getStringExtra(DDContracts.EXTRA_OCR_TEXT) ?: ""
                    lastOcrText = if (t.isBlank()) "(vacío)" else t
                    renderOutput()

                    sendBroadcast(Intent(DDContracts.ACTION_OVERLAY_SHOW).apply { setPackage(packageName) })
                    Log.d(TAG, "⬅️ OCR_RESULT len=${lastOcrText.length}")
                }

                DDContracts.ACTION_NEED_PROJECTION -> {
                    val err = intent.getStringExtra(DDContracts.EXTRA_ERROR) ?: "Need projection"
                    lastOcrText = "❌ $err"
                    renderOutput()
                    sendBroadcast(Intent(DDContracts.ACTION_OVERLAY_SHOW).apply { setPackage(packageName) })
                    Log.w(TAG, "⬅️ NEED_PROJECTION: $err")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Receiver para resultados (ACCESSIBILITY + OCR)
        registerReceiverCompat()

        // Notificación (si ya la traes funcionando, déjala)
        createChannelIfNeeded()
        val notification = buildNotification("DriveDecision activo")

        try {
            // OJO: Si ya lo tienes jalando SIN FGS, puedes comentar esto.
            // Si lo usas, NO metas foregroundServiceType="systemAlertWindow" en manifest (te truena AAPT).
            startForeground(NOTIF_ID, notification)
            Log.d(TAG, "startForeground() OK")
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground() FAILED", t)
            // Si falla el FGS, aún intentamos overlay (depende tu device/ROM)
        }

        // Crea overlay UI
        attachBubble()
        Log.d(TAG, "✅ FloatingOverlayService iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DDContracts.ACTION_OVERLAY_CLOSE -> {
                Log.d(TAG, "ACTION_OVERLAY_CLOSE")
                stopSelf()
            }

            DDContracts.ACTION_OVERLAY_HIDE -> {
                Log.d(TAG, "ACTION_OVERLAY_HIDE")
                setOverlayVisible(false)
            }

            DDContracts.ACTION_OVERLAY_SHOW -> {
                Log.d(TAG, "ACTION_OVERLAY_SHOW")
                setOverlayVisible(true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        detachAll()
        unregisterReceiverSafe()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- UI ----------------

    private fun attachBubble() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null, false)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 300
        }

        wm?.addView(bubbleView, bubbleParams)
        Log.d(TAG, "bubble added")

        // Click real (tap) -> abre/cierra panel
        bubbleView?.setOnClickListener { togglePanel() }

        // Drag + click coexistentes
        makeDraggableTogether()
    }

    private fun togglePanel() {
        if (panelView == null) attachPanel() else detachPanel()
    }

    private fun attachPanel() {
        val inflater = LayoutInflater.from(this)
        panelView = inflater.inflate(R.layout.overlay_panel, null, false)

        tvOutput = panelView?.findViewById(R.id.tvOutput)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val b = bubbleParams
            x = (b?.x ?: 30) + 120
            y = (b?.y ?: 300)
        }

        wm?.addView(panelView, panelParams)
        Log.d(TAG, "panel added")

        panelView?.findViewById<View>(R.id.btnAnalyze)?.setOnClickListener {
            Log.d(TAG, "btnAnalyze click -> ACCESS + OCR (hide overlay)")

            // 0) Marca estado en UI
            lastAccText = "(leyendo...)"
            lastOcrText = "(capturando...)"
            renderOutput()

            // 1) Oculta overlay para que NO tape cajitas (solo el panel o todo, tú decides)
            sendBroadcast(Intent(DDContracts.ACTION_OVERLAY_HIDE).apply { setPackage(packageName) })

            // 2) ACCESSIBILITY -> broadcast request (esto sí es broadcast)
            sendBroadcast(Intent(DDContracts.ACTION_READ_REQUEST).apply { setPackage(packageName) })

            // 3) OCR/CAPTURA -> ARRANCA el service (esto NO es broadcast)
            requestOcrOnce()

            // 4) Failsafe opcional: si por lo que sea no llega respuesta, re-muestra overlay
            panelView?.postDelayed({
                sendBroadcast(Intent(DDContracts.ACTION_OVERLAY_SHOW).apply { setPackage(packageName) })
            }, 2000)
        }

        panelView?.findViewById<View>(R.id.btnClose)?.setOnClickListener {
            Log.d(TAG, "btnClose click -> close panel")
            detachPanel()
        }

        // Render inicial
        renderOutput()
    }

    private fun detachPanel() {
        try {
            panelView?.let { wm?.removeView(it) }
        } catch (_: Throwable) { }
        panelView = null
        panelParams = null
        tvOutput = null
        Log.d(TAG, "panel removed")
    }

    private fun detachAll() {
        try { detachPanel() } catch (_: Throwable) { }
        try {
            bubbleView?.let { wm?.removeView(it) }
        } catch (_: Throwable) { }
        bubbleView = null
        bubbleParams = null
    }

    /**
     * Drag burbuja + panel juntos, pero deja pasar el "tap" como click real.
     */
    private fun makeDraggableTogether() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        val clickSlop = 10 // px

        bubbleView?.isClickable = true
        bubbleView?.isFocusable = false

        bubbleView?.setOnTouchListener { v, ev ->
            val bp = bubbleParams ?: return@setOnTouchListener false

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bp.x
                    startY = bp.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()

                    if (kotlin.math.abs(dx) > clickSlop || kotlin.math.abs(dy) > clickSlop) {
                        moved = true
                    }

                    bp.x = startX + dx
                    bp.y = startY + dy
                    wm?.updateViewLayout(bubbleView, bp)

                    val pp = panelParams
                    if (panelView != null && pp != null) {
                        pp.x = bp.x + 120
                        pp.y = bp.y
                        wm?.updateViewLayout(panelView, pp)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick() // dispara setOnClickListener -> togglePanel()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun renderOutput() {
        val out =
            "=== RESULTADOS ===\n\n" +
                    "[ACCESSIBILITY]\n" +
                    lastAccText + "\n\n" +
                    "[OCR/CAPTURA]\n" +
                    lastOcrText

        tvOutput?.text = out
    }

    // ---------------- OCR trigger ----------------

    private fun requestOcrOnce() {
        val i = Intent(this, ScreenOcrService::class.java).apply {
            action = DDContracts.ACTION_OCR_REQUEST
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Log.d(TAG, "➡️ start(ScreenOcrService) ACTION_OCR_REQUEST")
        } catch (t: Throwable) {
            lastOcrText = "❌ No pude iniciar OCR: ${t.message}"
            renderOutput()
            Log.e(TAG, "start ScreenOcrService failed", t)
        }
    }

    // --------------- Notification ---------------

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CH_ID, "DriveDecision Overlay", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val closePi = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingOverlayService::class.java).apply {
                action = DDContracts.ACTION_OVERLAY_CLOSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("DriveDecision")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Cerrar", closePi)
            .build()
    }

    // --------------- Receiver register ---------------

    private fun registerReceiverCompat() {
        val filter = IntentFilter().apply {
            addAction(DDContracts.ACTION_READ_RESULT)
            addAction(DDContracts.ACTION_OCR_RESULT)
            addAction(DDContracts.ACTION_NEED_PROJECTION)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(resultsReceiver, filter)
        }
    }

    private fun unregisterReceiverSafe() {
        try { unregisterReceiver(resultsReceiver) } catch (_: Throwable) { }
    }

    private fun setOverlayVisible(visible: Boolean) {
        try {
            bubbleView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            panelView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        } catch (_: Throwable) {}
    }
}