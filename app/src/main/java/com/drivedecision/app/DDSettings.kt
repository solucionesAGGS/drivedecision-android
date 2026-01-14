package com.drivedecision.app

import android.content.Context
import android.content.SharedPreferences

object DDSettings {
    private const val PREFS = "dd_settings"

    const val K_CONSUMO = "k_consumo"
    const val K_GASOLINA = "k_gasolina"
    const val K_DESGASTE = "k_desgaste"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}