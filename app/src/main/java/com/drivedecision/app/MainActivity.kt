// app/src/main/java/com/drivedecision/app/MainActivity.kt
package com.drivedecision.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.drivedecision.app.ocr.ScreenOcrService
import com.drivedecision.app.overlay.FloatingOverlayService

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "DD_MAIN" }

    // 1) MediaProjection launcher (pide permiso de captura)
    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                Log.d(TAG, "✅ MediaProjection OK -> enviando a ScreenOcrService")
                val i = Intent(this, ScreenOcrService::class.java).apply {
                    action = DDContracts.ACTION_START_PROJECTION
                    putExtra(DDContracts.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(DDContracts.EXTRA_RESULT_DATA, res.data)
                }
                startForegroundCompat(i)
                toast("Captura activada ✅")
            } else {
                Log.w(TAG, "❌ MediaProjection cancelado")
                toast("Captura cancelada")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStart).setOnClickListener { ensureNotificationPermissionThenStart() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAll() }
        findViewById<Button>(R.id.btnSetup).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun startFlow() {
        // 0) overlay permission
        if (!Settings.canDrawOverlays(this)) {
            toast("Activa 'Mostrar sobre otras apps'")
            openOverlayPermission()
            return
        }

        // 1) notifs permission (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 && !areNotificationsEnabled()) {
            toast("Activa notificaciones para el servicio")
            requestPostNotifications()
            return
        }

        // 2) accessibility -> si no está activo, abrir settings
        if (!isAccessibilityEnabled()) {
            toast("Activa Accesibilidad para DriveDecision")
            openAccessibilitySettings()
            return
        }

        // 3) arrancar overlay flotante
        startFloatingOverlay()

        // 4) pedir MediaProjection (captura)
        requestMediaProjection()
    }

    private fun stopAll() {
        // detener overlay
        stopService(Intent(this, FloatingOverlayService::class.java))

        // detener OCR projection/service
        startForegroundCompat(Intent(this, ScreenOcrService::class.java).apply {
            action = DDContracts.ACTION_STOP_PROJECTION
        })

        toast("Finalizado")
    }

    // --------------------
    // Overlay + Accessibility + Notifs
    // --------------------

    private fun openOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "openOverlayPermission error: ${t.message}", t)
            toast("No pude abrir settings overlay")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        // Simple y robusto: revisa si el servicio está habilitado por id
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val myServiceId = "$packageName/${packageName}.access.DriveAccessibilityService"
        return enabledServices.contains(myServiceId, ignoreCase = true)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            Log.e(TAG, "openAccessibilitySettings error: ${t.message}", t)
            toast("No pude abrir settings de accesibilidad")
        }
    }

    private fun areNotificationsEnabled(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun requestPostNotifications() {
        // Android 13+ usa runtime permission
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    // --------------------
    // Services
    // --------------------

    private fun startFloatingOverlay() {
        val i = Intent(this, FloatingOverlayService::class.java)
        startForegroundCompat(i)
        Log.d(TAG, "✅ FloatingOverlayService iniciado")
    }

    private fun requestMediaProjection() {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
        } catch (t: Throwable) {
            Log.e(TAG, "requestMediaProjection error: ${t.message}", t)
            toast("No pude pedir captura")
        }
    }

    private fun startForegroundCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private val REQ_NOTIF = 901

    private fun ensureNotificationPermissionThenStart() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            startFlow() // tu función actual
            return
        }

        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (granted) {
            startFlow()
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIF
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_NOTIF) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (ok) {
                startFlow()
            } else {
                android.widget.Toast.makeText(
                    this,
                    "Necesito permiso de notificaciones para iniciar el flotante",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}