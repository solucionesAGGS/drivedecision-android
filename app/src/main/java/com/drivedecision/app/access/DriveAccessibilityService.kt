package com.drivedecision.app.access

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DriveAccessibilityService : AccessibilityService() {

    private val TAG = "DD_ACC"

    companion object {
        const val ACTION_READ_REQUEST = "com.drivedecision.app.ACTION_READ_REQUEST"
        const val ACTION_READ_RESULT  = "com.drivedecision.app.ACTION_READ_RESULT"
        const val EXTRA_RESULT_TEXT   = "extra_result_text"

        const val INDRIVE_PACKAGE = "sinet.startup.inDriver"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_READ_REQUEST) {
                Log.d(TAG, "📥 READ_REQUEST recibido ✅")
                readNowAndSendWithRetry()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ onServiceConnected (Accessibility activo)")

        val filter = IntentFilter(ACTION_READ_REQUEST)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(requestReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(requestReceiver, filter)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(requestReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Lo dejamos “on demand” (solo cuando presionas LEER).
    }

    override fun onInterrupt() {}

    private fun readNowAndSendWithRetry() {
        val ok = readNowAndSendOnce()
        if (ok) return

        mainHandler.postDelayed({
            val ok2 = readNowAndSendOnce()
            if (ok2) return@postDelayed

            mainHandler.postDelayed({
                val ok3 = readNowAndSendOnce()
                if (!ok3) {
                    sendResult("❌ No pude leer el UI (rootInActiveWindow = null). Abre InDrive al frente y vuelve a intentar.")
                }
            }, 700)
        }, 300)
    }

    private fun readNowAndSendOnce(): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow = null (todavía)")
            return false
        }

        val rootPkg = root.packageName?.toString() ?: "null"
        val rootClass = root.className?.toString() ?: "null"
        Log.d(TAG, "🔎 root pkg=$rootPkg class=$rootClass")

        val texts = LinkedHashSet<String>()
        collectTexts(root, texts)

        val list = texts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(250)

        val header = buildString {
            appendLine("APP_AL_FRENTE: $rootPkg")
            appendLine("CLASS: $rootClass")
            appendLine("TOTAL_TEXTOS: ${list.size}")
            appendLine("-----")
        }

        sendResult(header + list.joinToString("\n"))
        return true
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out)
    }

    private fun sendResult(text: String) {
        Log.d(TAG, "📤 Enviando RESULT (chars=${text.length})")
        val i = Intent(ACTION_READ_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_RESULT_TEXT, text)
        }
        sendBroadcast(i)
    }
}
