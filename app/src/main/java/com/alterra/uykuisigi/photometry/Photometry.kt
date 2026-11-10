package com.alterra.uykuisigi.photometry

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Kameranın pozlama verisinden aydınlık (lüx) ve melanopik EDI hesabı.
 *
 * Yöntem: kameranın önündeki kağıt bir difüzör (kosinüs alıcısı) görevi görüyor, yani
 * cihaz bir "gelen ışık" (incident) ışıkölçere dönüşüyor. ISO 2720'ye göre, bir gelen
 * ışık ölçerinde doğru pozlama şartı:
 *
 *     E = (N^2 * C) / (t * S)
 *
 * E: aydınlık (lüx), N: diyafram sayısı, t: enstantane (s), S: ISO duyarlılığı,
 * C: gelen ışık kalibrasyon sabiti (düz alıcı için 250).
 *
 * Biz pozlamayı elle sürüyoruz, dolayısıyla kare "doğru pozlanmış" olmak zorunda değil.
 * Ölçülen ortalama doğrusal seviye M, orta gri referansı 0.184'ten ne kadar saparsa
 * aydınlık da o oranda sapar:
 *
 *     E = (N^2 * C) / (t * S) * (M / 0.184) * g
 *
 * g: kullanıcı kalibrasyon kazancı. Kağıdın ışık geçirgenliğini (tipik olarak %30-50),
 * sensörün V(lambda)'dan sapan spektral duyarlılığını ve lens vinyetlemesini birlikte
 * yutar. Bu yüzden g'yi telefonun kendi ışık sensörüne karşı bir kez kalibre etmek şart.
 */
object Photometry {

    /** ISO 2720 gelen ışık sabiti, düz alıcı. Yarım küre kubbe için 320 kullanılır. */
    const val INCIDENT_CONSTANT = 250.0

    /** %18 orta grinin doğrusal karşılığı. */
    const val MID_GRAY_LINEAR = 0.184

    /** Kağıt geçirgenliği bilinmeden önceki makul başlangıç (yaklaşık %40 geçirgenlik). */
    const val DEFAULT_CALIBRATION_GAIN = 2.5

    /**
     * @param aperture diyafram sayısı (f/N)
     * @param exposureSeconds enstantane, saniye
     * @param iso sensör duyarlılığı
     * @param meanLinear kare ortalaması, doğrusal, siyah seviyesi çıkarılmış, 0..1
     * @param calibrationGain kullanıcı kalibrasyon kazancı
     */
    fun lux(
        aperture: Double,
        exposureSeconds: Double,
        iso: Int,
        meanLinear: Double,
        calibrationGain: Double
    ): Double {
        if (exposureSeconds <= 0.0 || iso <= 0) return 0.0
        val atMidGray = INCIDENT_CONSTANT * aperture * aperture / (exposureSeconds * iso)
        return calibrationGain * atMidGray * (meanLinear / MID_GRAY_LINEAR)
    }

    /** ISO 100'e normalize pozlama değeri; sadece bilgilendirme amaçlı gösteriliyor. */
    fun ev100(aperture: Double, exposureSeconds: Double, iso: Int): Double {
        if (exposureSeconds <= 0.0 || iso <= 0) return 0.0
        return log2(aperture * aperture / exposureSeconds) - log2(iso / 100.0)
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    // ---------------------------------------------------------------- renk

    /**
     * Doğrusal sRGB'den korele renk sıcaklığı (McCamy yaklaşımı).
     * Kare, beyaz dengesi gün ışığına sabitlenmiş halde çekilir; sonuç yaklaşıktır.
     */
    fun cctFromLinearRgb(r: Double, g: Double, b: Double): Double? {
        if (r <= 0.0 && g <= 0.0 && b <= 0.0) return null
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        val sum = x + y + z
        if (sum <= 1e-9) return null
        val cx = x / sum
        val cy = y / sum
        val denom = 0.1858 - cy
        if (abs(denom) < 1e-6) return null
        val n = (cx - 0.3320) / denom
        val cct = 437.0 * n.pow(3) + 3601.0 * n.pow(2) + 6861.0 * n + 5517.0
        return if (cct.isFinite() && cct in 1200.0..20000.0) cct else null
    }

    // ------------------------------------------------------------ melanopik

    /**
     * Melanopik gün ışığı eşdeğeri oranı (melanopic DER): melanopik EDI / fotopik lüx.
     * D65 için tanım gereği 1.0. Aşağıdaki tablo fosfor dönüşümlü LED ve kara cisim
     * benzeri kaynaklar için tipik değerlerdir; dar bantlı kaynaklarda (kehribar LED,
     * bazı gece lambaları) gerçek değer bunun epey altında olabilir.
     */
    private val CCT_POINTS = doubleArrayOf(1800.0, 2200.0, 2700.0, 3000.0, 3500.0, 4000.0, 4500.0, 5000.0, 5700.0, 6500.0, 8000.0, 10000.0)
    private val DER_POINTS = doubleArrayOf(0.25, 0.36, 0.48, 0.55, 0.66, 0.76, 0.82, 0.87, 0.94, 1.00, 1.10, 1.16)

    fun melanopicDer(cct: Double?): Double {
        if (cct == null) return 0.75 // renk okunamadıysa nötr beyaz LED varsayımı
        if (cct <= CCT_POINTS.first()) return DER_POINTS.first()
        if (cct >= CCT_POINTS.last()) return DER_POINTS.last()
        for (i in 0 until CCT_POINTS.size - 1) {
            if (cct <= CCT_POINTS[i + 1]) {
                val f = (cct - CCT_POINTS[i]) / (CCT_POINTS[i + 1] - CCT_POINTS[i])
                return DER_POINTS[i] + f * (DER_POINTS[i + 1] - DER_POINTS[i])
            }
        }
        return DER_POINTS.last()
    }

    /** Kullanıcının elle seçebileceği kaynak tipleri; renk tahmini güvenilmezse kullanılır. */
    enum class LightSource(val label: String, val der: Double?) {
        AUTO("Otomatik (renkten tahmin)", null),
        CANDLE("Mum / kehribar gece lambası", 0.20),
        INCANDESCENT("Akkor ampul, 2700 K", 0.45),
        WARM_LED("Sıcak beyaz LED, 3000 K", 0.55),
        NEUTRAL_LED("Nötr beyaz LED, 4000 K", 0.76),
        COOL_LED("Beyaz LED, 6500 K", 1.00),
        SCREEN("Ekran (telefon/TV)", 0.68),
        DAYLIGHT("Gün ışığı", 1.00);

        override fun toString() = label
    }

    fun melanopicEdi(photopicLux: Double, cct: Double?, source: LightSource): Double {
        val der = source.der ?: melanopicDer(cct)
        return photopicLux * der
    }
}
