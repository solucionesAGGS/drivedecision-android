package com.drivedecision.app.access

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.drivedecision.app.DDContracts

/**
 * Lee texto accesible (principalmente de inDrive) y manda un dump por broadcast.
 * También extrae ofertas MXN de botones/textos.
 */
class DriveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DD_ACC"
        private const val TARGET_PACKAGE = "sinet.startup.inDriver"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                DDContracts.ACTION_READ_REQUEST -> {
                    // Forzar una lectura inmediata cuando overlay lo pida
                    try {
                        val dump = readCurrentWindowDump()
                        sendReadResult(dump)
                    } catch (t: Throwable) {
                        Log.e(TAG, "READ_REQUEST error: ${t.message}", t)
                        sendReadResult("❌ READ_REQUEST error: ${t.message}")
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected()")

        // 🔧 FIX: Android 13+ exige RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED al registrar receivers dinámicos
        try {
            val filter = IntentFilter(DDContracts.ACTION_READ_REQUEST)
            registerReceiverCompat(receiver, filter)
            Log.d(TAG, "Receiver registrado OK (${DDContracts.ACTION_READ_REQUEST})")
        } catch (t: Throwable) {
            Log.e(TAG, "No pude registrar receiver: ${t.message}", t)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Filtra por paquete (inDrive)
        val pkg = event.packageName?.toString() ?: return
        if (pkg != TARGET_PACKAGE) return

        // Leemos ventana actual (si está disponible)
        try {
            val dump = readCurrentWindowDump()
            if (dump.isNotBlank()) {
                sendReadResult(dump)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onAccessibilityEvent read error: ${t.message}", t)
        }
    }

    override fun onInterrupt() {}

    // -----------------------------
    // Compat receiver registration
    // -----------------------------
    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            // No expongas este receiver a otras apps
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    // -----------------------------
    // Read / dump current window
    // -----------------------------
    private fun readCurrentWindowDump(): String {
        val root = rootInActiveWindow ?: return ""

        // A veces root viene pero sin contenido; pequeño retry rápido
        if (root.childCount == 0) {
            SystemClock.sleep(30)
        }

        val sb = StringBuilder(4096)
        sb.append("=== ACCESSIBILITY DUMP ===\n")
        sb.append("pkg=").append(root.packageName).append("\n")
        sb.append("cls=").append(root.className).append("\n\n")

        // Dump completo (texto visible)
        dumpNodeText(root, sb)

        // Extra: ofertas detectadas
        val offers = extractOffersMxnFromText(sb.toString())
        if (offers.isNotEmpty()) {
            sb.append("\n=== OFFERS MXN ===\n")
            offers.distinct().sorted().forEach { sb.append("MXN").append(it).append("\n") }
        }

        return sb.toString()
    }

    private fun dumpNodeText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        try {
            val t = node.text?.toString()
            val cd = node.contentDescription?.toString()
            val vid = node.viewIdResourceName

            if (!t.isNullOrBlank()) sb.append(t).append("\n")
            if (!cd.isNullOrBlank()) sb.append(cd).append("\n")
            if (!vid.isNullOrBlank()) sb.append("id=").append(vid).append("\n")

            val n = node.childCount
            for (i in 0 until n) {
                val c = node.getChild(i) ?: continue
                dumpNodeText(c, sb)
            }
        } catch (_: Throwable) {
            // no-op
        }
    }

    // -----------------------------
    // Offers parsing
    // -----------------------------
    private fun extractOffersMxnFromText(allText: String): List<Int> {
        val s = allText.lowercase()

        // Match "mxn118", "mxn 118", "MXN 1,234" etc.
        val out = ArrayList<Int>(8)
        val re = Regex("""\bmxn\s*([0-9]{2,5})\b""")
        re.findAll(s).forEach { m ->
            val v = m.groupValues[1].toIntOrNull()
            if (v != null) out += v
        }
        return out
    }

    // -----------------------------
    // Broadcast result
    // -----------------------------
    private fun sendReadResult(text: String) {
        try {
            val out = Intent(DDContracts.ACTION_READ_RESULT).apply {
                setPackage(packageName)
                putExtra(DDContracts.EXTRA_READ_TEXT, text)
            }
            sendBroadcast(out)
        } catch (t: Throwable) {
            Log.e(TAG, "sendReadResult error: ${t.message}", t)
        }
    }
}
