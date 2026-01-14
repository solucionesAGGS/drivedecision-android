package com.drivedecision.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private lateinit var etConsumo: EditText
    private lateinit var etGasolina: EditText
    private lateinit var etDesgaste: EditText
    private lateinit var btnGuardar: Button
    private lateinit var btnCancelar: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        etConsumo = findViewById(R.id.etConsumo)
        etGasolina = findViewById(R.id.etGasolina)
        etDesgaste = findViewById(R.id.etDesgaste)
        btnGuardar = findViewById(R.id.btnGuardar)
        btnCancelar = findViewById(R.id.btnCancelar)

        val p = DDSettings.prefs(this)
        etConsumo.setText(p.getString(DDSettings.K_CONSUMO, "") ?: "")
        etGasolina.setText(p.getString(DDSettings.K_GASOLINA, "") ?: "")
        etDesgaste.setText(p.getString(DDSettings.K_DESGASTE, "") ?: "")

        btnGuardar.setOnClickListener {
            DDSettings.prefs(this).edit()
                .putString(DDSettings.K_CONSUMO, etConsumo.text?.toString()?.trim() ?: "")
                .putString(DDSettings.K_GASOLINA, etGasolina.text?.toString()?.trim() ?: "")
                .putString(DDSettings.K_DESGASTE, etDesgaste.text?.toString()?.trim() ?: "")
                .apply()
            finish()
        }

        btnCancelar.setOnClickListener { finish() }
    }
}