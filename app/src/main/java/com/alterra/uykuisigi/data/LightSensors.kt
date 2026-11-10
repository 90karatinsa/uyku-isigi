package com.alterra.uykuisigi.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Telefonun dahili ışık sensörleri.
 *
 * Standart `TYPE_LIGHT` her zaman ön yüzdedir. Bazı cihazlarda (Pixel 9 Pro'da
 * VD6282) arka yüzde ayrı bir ışık sensörü daha bulunuyor; bu üretici tanımlı bir
 * tip olduğu için sabit bir sabitle değil, dize tipinden aranıyor.
 *
 * Neden önemli: kalibrasyon referansı ile ölçüm yapan kameranın **aynı yüzde**
 * olması gerekiyor. Arka kamerayı ön yüzdeki sensöre göre kalibre etmek, iki yüz
 * farklı ışık görüyorsa (ki masaya konmuş bir telefonda hep öyledir) kazancı kaydırır.
 */
class LightSensors(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Ön yüz: standart ortam ışığı sensörü. */
    val frontSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_LIGHT)

    /** Arka yüz: varsa üretici tanımlı ışık sensörü. */
    val rearSensor: Sensor? = findRearLightSensor()

    private fun findRearLightSensor(): Sensor? {
        val all = manager.getSensorList(Sensor.TYPE_ALL) ?: return null
        // Önce bilinen Google adı, sonra genel bir "arka + ışık" eşleşmesi.
        return all.firstOrNull { it.stringType == "com.google.sensor.rear_light" }
            ?: all.firstOrNull { s ->
                val t = s.stringType?.lowercase() ?: ""
                val n = s.name.lowercase()
                s.type != Sensor.TYPE_LIGHT &&
                    ("rear" in t || "back" in t || "rear" in n || "back" in n) &&
                    ("light" in t || "light" in n || "als" in n)
            }
    }

    @Volatile
    var frontLux: Float? = null
        private set

    @Volatile
    var rearLux: Float? = null
        private set

    /** Arka sensörün kaç kanal döndürdüğü; lüks vermiyorsa teşhis için gerekiyor. */
    @Volatile
    var rearChannels: Int = 0
        private set

    private var listener: (() -> Unit)? = null

    fun start(onChange: (() -> Unit)? = null) {
        listener = onChange
        frontSensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        rearSensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        manager.unregisterListener(this)
        listener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when {
            event.sensor.type == Sensor.TYPE_LIGHT -> frontLux = event.values[0]
            event.sensor == rearSensor -> {
                rearChannels = event.values.size
                rearLux = event.values[0]
            }
        }
        listener?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------- kalibrasyon

    data class Reference(
        val lux: Float,
        val label: String,
        /** Referans, ölçüm yapan kamerayla aynı yüzde mi? */
        val sameFace: Boolean
    )

    /**
     * Seçili kamera için en uygun kalibrasyon referansı.
     * Aynı yüzdeki sensör varsa o, yoksa diğer yüzdeki sensör "sameFace = false" ile
     * döndürülür; çağıran taraf bunu kullanıcıya uyarı olarak göstermeli.
     */
    fun reference(useFrontCamera: Boolean): Reference? {
        val front = frontLux?.takeIf { it > 0f }
        val rear = rearLux?.takeIf { it > 0f }
        return if (useFrontCamera) {
            front?.let { Reference(it, "ön ışık sensörü", true) }
                ?: rear?.let { Reference(it, "arka ışık sensörü", false) }
        } else {
            rear?.let { Reference(it, "arka ışık sensörü", true) }
                ?: front?.let { Reference(it, "ön ışık sensörü", false) }
        }
    }

    fun describe(): String = buildString {
        append("ön ışık sensörü: ")
        append(frontSensor?.name ?: "yok")
        append("\narka ışık sensörü: ")
        append(rearSensor?.let { "${it.name} (${it.stringType})" } ?: "yok")
        if (rearSensor != null && rearChannels > 0) append(" · $rearChannels kanal")
    }
}
