// app/src/main/java/com/drivedecision/app/DDContracts.kt
package com.drivedecision.app

object DDContracts {

    // ===== Accessibility (leer UI texto) =====
    const val ACTION_READ_REQUEST = "com.drivedecision.app.ACTION_READ_REQUEST"
    const val ACTION_READ_RESULT  = "com.drivedecision.app.ACTION_READ_RESULT"
    const val EXTRA_RESULT_TEXT   = "extra_result_text"

    // ===== OCR / Captura (MediaProjection + MLKit) =====
    const val ACTION_START_PROJECTION = "com.drivedecision.app.ocr.ACTION_START_PROJECTION"
    const val ACTION_STOP_PROJECTION  = "com.drivedecision.app.ocr.ACTION_STOP_PROJECTION"
    const val ACTION_OCR_REQUEST      = "com.drivedecision.app.ocr.ACTION_OCR_REQUEST"

    const val ACTION_OCR_RESULT       = "com.drivedecision.app.ocr.ACTION_OCR_RESULT"
    const val ACTION_NEED_PROJECTION  = "com.drivedecision.app.ocr.ACTION_NEED_PROJECTION"

    const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
    const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
    const val EXTRA_OCR_TEXT    = "EXTRA_OCR_TEXT"
    const val EXTRA_OCR_DEBUG   = "EXTRA_OCR_DEBUG"
    const val EXTRA_ERROR       = "EXTRA_ERROR"
    const val EXTRA_TEXT       = "EXTRA_TEXT"

    // ===== Overlay =====
    const val ACTION_OVERLAY_START = "com.drivedecision.app.overlay.ACTION_OVERLAY_START"
    const val ACTION_OVERLAY_STOP  = "com.drivedecision.app.overlay.ACTION_OVERLAY_STOP"

    const val ACTION_OVERLAY_HIDE = "com.drivedecision.app.overlay.ACTION_HIDE"
    const val ACTION_OVERLAY_SHOW = "com.drivedecision.app.overlay.ACTION_SHOW"
    const val ACTION_OVERLAY_CLOSE = "com.drivedecision.app.ACTION_OVERLAY_CLOSE"
    // ===== Target =====
    const val INDRIVE_PACKAGE = "sinet.startup.inDriver"
}
