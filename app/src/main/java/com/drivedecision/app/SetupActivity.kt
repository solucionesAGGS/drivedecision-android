package com.drivedecision.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private fun EditText.readDoubleOr(default: Double): Double {
        val s = text?.toString()?.trim().orEmpty()
        return s.replace(',', '.').toDoubleOrNull() ?: default
    }

    private fun EditText.setDouble(v: Double) {
        setText(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val etMinNetPerHour = findViewById<EditText>(R.id.etMinNetPerHour)
        val etFuelPrice = findViewById<EditText>(R.id.etFuelPrice)
        val etCityKmPerL = findViewById<EditText>(R.id.etCityKmPerL)
        val etHwyKmPerL = findViewById<EditText>(R.id.etHwyKmPerL)
        val etOtherCostPerKm = findViewById<EditText>(R.id.etOtherCostPerKm)
        val etFeePct = findViewById<EditText>(R.id.etFeePct)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // Cargar defaults / valores actuales
        etMinNetPerHour.setDouble(DDSettings.getMinNetPerHour(this))
        etFuelPrice.setDouble(DDSettings.getFuelPrice(this))
        etCityKmPerL.setDouble(DDSettings.getCityKmPerL(this))
        etHwyKmPerL.setDouble(DDSettings.getHwyKmPerL(this))
        etOtherCostPerKm.setDouble(DDSettings.getOtherCostPerKm(this))
        etFeePct.setDouble(DDSettings.getFeePct(this))

        btnSave.setOnClickListener {
            val minNet = etMinNetPerHour.readDoubleOr(DDSettings.getMinNetPerHour(this)).coerceAtLeast(0.0)
            val fuel = etFuelPrice.readDoubleOr(DDSettings.getFuelPrice(this)).coerceAtLeast(0.0)
            val city = etCityKmPerL.readDoubleOr(DDSettings.getCityKmPerL(this)).coerceAtLeast(1.0)
            val hwy = etHwyKmPerL.readDoubleOr(DDSettings.getHwyKmPerL(this)).coerceAtLeast(1.0)
            val other = etOtherCostPerKm.readDoubleOr(DDSettings.getOtherCostPerKm(this)).coerceAtLeast(0.0)
            val feePct = etFeePct.readDoubleOr(DDSettings.getFeePct(this)).coerceIn(0.0, 100.0)

            DDSettings.setMinNetPerHour(this, minNet)
            DDSettings.setFuelPrice(this, fuel)
            DDSettings.setCityKmPerL(this, city)
            DDSettings.setHwyKmPerL(this, hwy)
            DDSettings.setOtherCostPerKm(this, other)
            DDSettings.setFeePct(this, feePct)

            Toast.makeText(this, "✅ Guardado", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
