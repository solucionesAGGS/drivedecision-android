package com.drivedecision.app

import android.content.Context

/**
 * Settings persistidos (SharedPreferences).
 *
 * Importante:
 * - Estos valores son usados para estimar costo de gasolina y "pago neto por hora".
 * - Si no llenas algo en Setup, se usan defaults razonables.
 */
object DDSettings {

    private const val PREFS = "dd_settings"

    // Precio gasolina (MXN/L)
    private const val K_FUEL_PRICE = "fuel_price"

    // Rendimiento (km/L) estimado
    private const val K_KM_PER_L_CITY = "km_per_l_city"
    private const val K_KM_PER_L_HWY = "km_per_l_hwy"

    // Meta mínima (MXN netos por hora)
    private const val K_MIN_NET_PER_HOUR = "min_net_per_hour"

    // Costo variable extra por km (llantas, aceite, mantenimiento) MXN/km
    private const val K_OTHER_COST_PER_KM = "other_cost_per_km"

    // Cuota (%) sobre el pago (se descuenta del bruto)
    private const val K_FEE_PCT = "fee_pct"

    // Defaults (ajústalos si quieres)
    private const val DEF_FUEL_PRICE = 24.0
    private const val DEF_CITY_KM_PER_L = 10.0
    private const val DEF_HWY_KM_PER_L = 14.0
    private const val DEF_MIN_NET_PER_HOUR = 90.0
    private const val DEF_OTHER_COST_PER_KM = 0.0
    private const val DEF_FEE_PCT = 0.0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getFuelPrice(ctx: Context): Double =
        prefs(ctx).getFloat(K_FUEL_PRICE, DEF_FUEL_PRICE.toFloat()).toDouble()

    fun setFuelPrice(ctx: Context, v: Double) {
        prefs(ctx).edit().putFloat(K_FUEL_PRICE, v.toFloat()).apply()
    }

    fun getCityKmPerL(ctx: Context): Double =
        prefs(ctx).getFloat(K_KM_PER_L_CITY, DEF_CITY_KM_PER_L.toFloat()).toDouble()

    fun setCityKmPerL(ctx: Context, v: Double) {
        prefs(ctx).edit().putFloat(K_KM_PER_L_CITY, v.toFloat()).apply()
    }

    fun getHwyKmPerL(ctx: Context): Double =
        prefs(ctx).getFloat(K_KM_PER_L_HWY, DEF_HWY_KM_PER_L.toFloat()).toDouble()

    fun setHwyKmPerL(ctx: Context, v: Double) {
        prefs(ctx).edit().putFloat(K_KM_PER_L_HWY, v.toFloat()).apply()
    }

    fun getMinNetPerHour(ctx: Context): Double =
        prefs(ctx).getFloat(K_MIN_NET_PER_HOUR, DEF_MIN_NET_PER_HOUR.toFloat()).toDouble()

    fun setMinNetPerHour(ctx: Context, v: Double) {
        prefs(ctx).edit().putFloat(K_MIN_NET_PER_HOUR, v.toFloat()).apply()
    }

    fun getOtherCostPerKm(ctx: Context): Double =
        prefs(ctx).getFloat(K_OTHER_COST_PER_KM, DEF_OTHER_COST_PER_KM.toFloat()).toDouble()

    fun setOtherCostPerKm(ctx: Context, v: Double) {
        prefs(ctx).edit().putFloat(K_OTHER_COST_PER_KM, v.toFloat()).apply()
    }


    fun getFeePct(ctx: Context): Double =
        prefs(ctx).getFloat(K_FEE_PCT, DEF_FEE_PCT.toFloat()).toDouble()

    fun setFeePct(ctx: Context, v: Double) {
        prefs(ctx).edit().putFloat(K_FEE_PCT, v.toFloat()).apply()
    }

    /**
     * Snapshot de settings para leerlos una sola vez.
     * (La overlay lo usa en cada análisis, evita accesos repetidos a prefs.)
     */
    data class Snapshot(
        val fuelPrice: Double,
        val cityKmPerL: Double,
        val hwyKmPerL: Double,
        val minNetPerHour: Double,
        val otherCostPerKm: Double,
        val feePct: Double
    )

    fun load(ctx: Context): Snapshot = Snapshot(
        fuelPrice = getFuelPrice(ctx),
        cityKmPerL = getCityKmPerL(ctx),
        hwyKmPerL = getHwyKmPerL(ctx),
        minNetPerHour = getMinNetPerHour(ctx),
        otherCostPerKm = getOtherCostPerKm(ctx),
        feePct = getFeePct(ctx)
    )


    /**
     * Estima km/L según velocidad promedio del viaje.
     * - <= 25 km/h -> ciudad
     * - >= 55 km/h -> carretera
     * - entre 25..55 -> interpolación lineal
     */
    fun estimateKmPerL(ctx: Context, avgSpeedKmh: Double): Double {
        val city = getCityKmPerL(ctx).coerceAtLeast(1.0)
        val hwy = getHwyKmPerL(ctx).coerceAtLeast(1.0)
        return when {
            avgSpeedKmh.isNaN() || avgSpeedKmh <= 0.0 -> city
            avgSpeedKmh <= 25.0 -> city
            avgSpeedKmh >= 55.0 -> hwy
            else -> {
                val t = (avgSpeedKmh - 25.0) / (55.0 - 25.0)
                city + (hwy - city) * t
            }
        }
    }
}
