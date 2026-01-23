package com.drivedecision.app.access

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.drivedecision.app.DDContracts

class DriveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DD_ACC"

        // SharedPreferences donde guardamos el rect del mapa
        private const val PREFS = "dd_prefs"
        private const val K_L = "map_left"
        private const val K_T = "map_top"
        private const val K_R = "map_right"
        private const val K_B = "map_bottom"
        private const val K_TS = "map_ts"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val readReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DDContracts.ACTION_READ_REQUEST) {
                readNowAndSendOnce()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        val filter = IntentFilter().apply {
            addAction(DDContracts.ACTION_READ_REQUEST)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(readReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(readReceiver, filter)
        }

        Log.d(TAG, "✅ Accessibility conectado y receiver registrado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No usamos el stream de eventos para no generar “sopa” / overhead.
        // Solo leemos cuando nos piden ACTION_READ_REQUEST.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(readReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun readNowAndSendOnce() {
        val root = rootInActiveWindow
        if (root == null) {
            sendResult("APP_AL_FRENTE: (sin rootInActiveWindow)")
            return
        }

        // 1) Guarda bounds del mapa (si los encontramos)
        updateMapBoundsIfPossible(root)

        // 2) Saca texto de accesibilidad (lo que ya te sirve hoy)
        val lines = mutableListOf<String>()

        val pkg = root.packageName?.toString() ?: "(null)"
        val cls = root.className?.toString() ?: "(null)"
        lines += "APP_AL_FRENTE: $pkg"
        lines += "CLASS: $cls"

        val collected = mutableListOf<String>()
        collectTexts(root, collected)

        lines += "TOTAL_TEXTOS: ${collected.size}"
        lines += "-----"
        lines += collected.joinToString("\n")

        sendResult(lines.joinToString("\n"))
    }

    private fun sendResult(text: String) {
        sendBroadcast(
            Intent(DDContracts.ACTION_READ_RESULT).apply {
                setPackage(packageName)
                putExtra(DDContracts.EXTRA_RESULT_TEXT, text)
            }
        )
        Log.d(TAG, "➡️ READ_RESULT enviado len=${text.length}")
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank()) out.add(t)

        val cd = node.contentDescription?.toString()?.trim()
        if (!cd.isNullOrBlank() && cd != t) out.add(cd)

        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectTexts(c, out)
        }
    }

    /**
     * Busca un nodo que represente el mapa (“Mapa de Google”) y guarda sus boundsInScreen.
     * Esto nos permite recortar la captura SOLO al área del mapa.
     */
    private fun updateMapBoundsIfPossible(root: AccessibilityNodeInfo) {
        val mapNode = findNodeByTextOrDesc(root, "Mapa de Google")
        if (mapNode != null) {
            val r = Rect()
            mapNode.getBoundsInScreen(r)
            if (r.width() > 50 && r.height() > 50) {
                prefs.edit()
                    .putInt(K_L, r.left)
                    .putInt(K_T, r.top)
                    .putInt(K_R, r.right)
                    .putInt(K_B, r.bottom)
                    .putLong(K_TS, System.currentTimeMillis())
                    .apply()
                Log.d(TAG, "🗺️ Map bounds guardados: $r")
            }
        }
    }

    private fun findNodeByTextOrDesc(node: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        val t = node.text?.toString()
        val cd = node.contentDescription?.toString()

        if (t?.contains(needle, ignoreCase = true) == true) return node
        if (cd?.contains(needle, ignoreCase = true) == true) return node

        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = findNodeByTextOrDesc(c, needle)
            if (found != null) return found
        }
        return null
    }
}
