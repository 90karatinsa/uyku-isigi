package com.alterra.uykuisigi.data

import android.content.Context
import com.alterra.uykuisigi.photometry.Photometry

/**
 * Kalıcı ayarlar. Kalibrasyon kamera başına saklanıyor: ön ve arka kameranın
 * diyaframı, sensörü ve önündeki kağıt farklı olduğu için kazanç da farklıdır.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("uyku_isik", Context.MODE_PRIVATE)

    var useFrontCamera: Boolean
        get() = prefs.getBoolean("front", true)
        set(v) = prefs.edit().putBoolean("front", v).apply()

    var preferRaw: Boolean
        get() = prefs.getBoolean("raw", true)
        set(v) = prefs.edit().putBoolean("raw", v).apply()

    var maxExposureSeconds: Float
        get() = prefs.getFloat("maxexp", 2.0f)
        set(v) = prefs.edit().putFloat("maxexp", v).apply()

    var averagingFrames: Int
        get() = prefs.getInt("avg", 4)
        set(v) = prefs.edit().putInt("avg", v).apply()

    var lightSource: Photometry.LightSource
        get() = runCatching {
            Photometry.LightSource.valueOf(prefs.getString("source", null) ?: "AUTO")
        }.getOrDefault(Photometry.LightSource.AUTO)
        set(v) = prefs.edit().putString("source", v.name).apply()

    var sessionIntervalMinutes: Int
        get() = prefs.getInt("interval", 5)
        set(v) = prefs.edit().putInt("interval", v).apply()

    fun calibrationGain(cameraId: String): Double =
        prefs.getFloat("gain_$cameraId", Photometry.DEFAULT_CALIBRATION_GAIN.toFloat()).toDouble()

    fun setCalibrationGain(cameraId: String, gain: Double) =
        prefs.edit().putFloat("gain_$cameraId", gain.toFloat()).apply()

    fun isCalibrated(cameraId: String): Boolean = prefs.contains("gain_$cameraId")

    fun darkOffset(cameraId: String): Double =
        prefs.getFloat("dark_$cameraId", 0f).toDouble()

    /**
     * Karanlık referansının alındığı pozlama çarpımı (enstantane x ISO).
     * Karanlık akımı pozlama süresi ve kazançla birlikte büyüdüğü için, ölçüm anındaki
     * çarpıma göre ölçeklenmesi gerekiyor; bu değer o ölçeklemenin paydası.
     */
    fun darkOffsetProduct(cameraId: String): Double =
        prefs.getFloat("darkp_$cameraId", 0f).toDouble()

    fun setDarkOffset(cameraId: String, offset: Double, product: Double) =
        prefs.edit()
            .putFloat("dark_$cameraId", offset.toFloat())
            .putFloat("darkp_$cameraId", product.toFloat())
            .apply()

    fun resetCalibration(cameraId: String) =
        prefs.edit()
            .remove("gain_$cameraId")
            .remove("dark_$cameraId")
            .remove("darkp_$cameraId")
            .apply()
}
