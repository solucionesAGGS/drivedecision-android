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
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.drivedecision.app.R
import com.drivedecision.app.access.DriveAccessibilityService

class OverlayService : Service() {

    private val TAG = "DD_OVL"
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null

    private var tvOutput: TextView? = null

    // ✅ Recibe el texto y lo muestra
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DriveAccessibilityService.ACTION_READ_RESULT) {
                val text = intent.getStringExtra(DriveAccessibilityService.EXTRA_RESULT_TEXT) ?: "(vacío)"
                Log.d(TAG, "📩 READ_RESULT recibido (chars=${text.length})")

                mainHandler.post {
                    ensurePanel()
                    showPanel()
                    tvOutput?.text = text
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // ✅ Registrar receiver del RESULT
        val filter = IntentFilter().apply {
            addAction(DriveAccessibilityService.ACTION_READ_RESULT)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(resultReceiver, filter)
        }

        showBubble()
        Log.d(TAG, "OverlayService creado ✅")
    }

    override fun onDestroy() {
        try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
        removeBubble()
        removePanel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ---------------- UI: Bubble ----------------

    private fun showBubble() {
        if (bubbleView != null) return

        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 280
        }

        bubbleView!!.setOnClickListener {
            ensurePanel()
            showPanel()
        }

        wm.addView(bubbleView, params)
    }

    private fun removeBubble() {
        try { bubbleView?.let { wm.removeView(it) } } catch (_: Exception) {}
        bubbleView = null
    }

    // ---------------- UI: Panel ----------------

    private fun ensurePanel() {
        if (panelView != null) return

        val inflater = LayoutInflater.from(this)
        panelView = inflater.inflate(R.layout.overlay_panel, null)

        tvOutput = panelView!!.findViewById(R.id.tvOutput)

        val btnLeer = panelView!!.findViewById<Button>(R.id.btnLeer)
        val btnCerrar = panelView!!.findViewById<Button>(R.id.btnCerrar)
        val btnOcr = panelView!!.findViewById<Button>(R.id.btnOcr)

        btnLeer.setOnClickListener {
            Log.d(TAG, "📌 CLICK LEER (sendBroadcast READ_REQUEST)")
            val i = Intent(DriveAccessibilityService.ACTION_READ_REQUEST).apply {
                setPackage(packageName)
            }
            sendBroadcast(i)

            // opcional: feedback inmediato
            tvOutput?.text = "⏳ Leyendo... abre InDrive al frente"
        }

        // OCR por ahora lo dejamos sin romper nada
        btnOcr.setOnClickListener {
            Log.d(TAG, "📌 CLICK OCR (todavía sin captura estable)")
            tvOutput?.text = "⚠️ OCR aún en ajuste (primero dejemos LEER estable)"
        }

        btnCerrar.setOnClickListener {
            hidePanel()
        }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        wm.addView(panelView, params)
        // empieza oculto
        panelView!!.visibility = View.GONE
    }

    private fun showPanel() {
        panelView?.visibility = View.VISIBLE
    }

    private fun hidePanel() {
        panelView?.visibility = View.GONE
    }

    private fun removePanel() {
        try { panelView?.let { wm.removeView(it) } } catch (_: Exception) {}
        panelView = null
        tvOutput = null
    }

    // ---------------- Foreground notification ----------------

    private fun startForegroundNotif() {
        val channelId = "dd_overlay_channel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "DriveDecision Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DriveDecision")
            .setContentText("Overlay activo")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        startForeground(1001, notif)
    }
}
