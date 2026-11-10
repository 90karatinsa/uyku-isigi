package com.alterra.uykuisigi

import com.alterra.uykuisigi.photometry.Photometry
import com.alterra.uykuisigi.photometry.SleepAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotometryTest {

    /**
     * ISO 2720 referans noktası: f/2.0, 1/60 s, ISO 100 ve orta gri pozlamada
     * gelen ışık ölçeri 600 lüks göstermelidir. E = N^2 * C / (t * S).
     */
    @Test
    fun midGrayMatchesIso2720() {
        val lux = Photometry.lux(
            aperture = 2.0,
            exposureSeconds = 1.0 / 60.0,
            iso = 100,
            meanLinear = Photometry.MID_GRAY_LINEAR,
            calibrationGain = 1.0
        )
        assertEquals(600.0, lux, 0.5)
    }

    /** Aynı nokta EV100 üzerinden de tutmalı: E = 2.5 * 2^EV100. */
    @Test
    fun evAgreesWithLux() {
        val ev = Photometry.ev100(2.0, 1.0 / 60.0, 100)
        assertEquals(7.907, ev, 0.01)
        val viaEv = 2.5 * Math.pow(2.0, ev)
        val viaLux = Photometry.lux(2.0, 1.0 / 60.0, 100, Photometry.MID_GRAY_LINEAR, 1.0)
        assertEquals(viaEv, viaLux, 1.0)
    }

    /** Sinyal yarıya inerse aydınlık da yarıya inmeli; hesap doğrusal olmalı. */
    @Test
    fun linearInSignal() {
        val full = Photometry.lux(2.0, 0.01, 400, 0.20, 2.5)
        val half = Photometry.lux(2.0, 0.01, 400, 0.10, 2.5)
        assertEquals(full / 2.0, half, 1e-9)
    }

    /** Pozlamayı iki katına çıkarmak aynı ışıkta sinyali iki katına çıkarır: lüks sabit kalmalı. */
    @Test
    fun invariantUnderExposureChange() {
        val a = Photometry.lux(2.0, 0.01, 100, 0.18, 2.5)
        val b = Photometry.lux(2.0, 0.02, 100, 0.36, 2.5)
        assertEquals(a, b, 1e-9)
        val c = Photometry.lux(2.0, 0.01, 200, 0.36, 2.5)
        assertEquals(a, c, 1e-9)
    }

    /** Karanlık bir yatak odası: 1.3 s, ISO 3200, f/1.8, düşük sinyal. */
    @Test
    fun darkBedroomIsSubLux() {
        val lux = Photometry.lux(1.8, 1.3, 3200, 0.18, 2.5)
        assertTrue("beklenen 0.3-1.5 lüks aralığı, bulunan $lux", lux in 0.3..1.5)
    }

    /** Nötr sinyal sRGB tanımı gereği D65'e, yani ~6500 K'ye düşmeli. */
    @Test
    fun neutralIsD65() {
        val cct = Photometry.cctFromLinearRgb(0.5, 0.5, 0.5)
        assertTrue("renk sıcaklığı okunamadı", cct != null)
        assertEquals(6500.0, cct!!, 60.0)
    }

    @Test
    fun warmSignalIsLowerCct() {
        val warm = Photometry.cctFromLinearRgb(0.60, 0.45, 0.22)!!
        val cool = Photometry.cctFromLinearRgb(0.42, 0.48, 0.62)!!
        assertTrue("sıcak kaynak daha düşük K vermeli: $warm vs $cool", warm < cool)
        assertTrue(warm < 4500.0)
        assertTrue(cool > 6500.0)
    }

    @Test
    fun melanopicRatioTable() {
        assertEquals(1.00, Photometry.melanopicDer(6500.0), 0.001)
        assertEquals(0.76, Photometry.melanopicDer(4000.0), 0.001)
        assertEquals(0.48, Photometry.melanopicDer(2700.0), 0.001)
        // Ara nokta doğrusal ara değerlemeye uymalı
        assertEquals((0.55 + 0.66) / 2, Photometry.melanopicDer(3250.0), 0.005)
        // Aralık dışı kırpılmalı
        assertEquals(0.25, Photometry.melanopicDer(500.0), 0.001)
        assertEquals(1.16, Photometry.melanopicDer(30000.0), 0.001)
        // Renk okunamadıysa nötr beyaz varsayımı
        assertEquals(0.75, Photometry.melanopicDer(null), 0.001)
    }

    /**
     * Aynı fotopik lüks, kaynağa göre çok farklı melanopik yük demek.
     * Uygulamanın var oluş sebebi bu: 5 lüks mum ışığı ile 5 lüks beyaz LED aynı şey değil.
     */
    @Test
    fun sameLuxDifferentMelanopicLoad() {
        val candle = Photometry.melanopicEdi(5.0, null, Photometry.LightSource.CANDLE)
        val coolLed = Photometry.melanopicEdi(5.0, null, Photometry.LightSource.COOL_LED)
        assertEquals(1.0, candle, 1e-9)
        assertEquals(5.0, coolLed, 1e-9)
        assertTrue(coolLed > candle * 4)
    }

    @Test
    fun sleepThresholdsFollowConsensus() {
        val ctx = SleepAssessment.Context.SLEEP
        assertEquals(SleepAssessment.Grade.GOOD, SleepAssessment.evaluate(ctx, 0.4).grade)
        assertEquals(SleepAssessment.Grade.OK, SleepAssessment.evaluate(ctx, 1.5).grade)
        assertEquals(SleepAssessment.Grade.WARN, SleepAssessment.evaluate(ctx, 6.0).grade)
        assertEquals(SleepAssessment.Grade.BAD, SleepAssessment.evaluate(ctx, 20.0).grade)
        assertEquals(SleepAssessment.Grade.SEVERE, SleepAssessment.evaluate(ctx, 120.0).grade)
    }

    @Test
    fun eveningAndDayThresholds() {
        assertEquals(SleepAssessment.Grade.GOOD, SleepAssessment.evaluate(SleepAssessment.Context.EVENING, 8.0).grade)
        assertEquals(SleepAssessment.Grade.WARN, SleepAssessment.evaluate(SleepAssessment.Context.EVENING, 50.0).grade)
        assertEquals(SleepAssessment.Grade.GOOD, SleepAssessment.evaluate(SleepAssessment.Context.DAY, 400.0).grade)
        assertEquals(SleepAssessment.Grade.WARN, SleepAssessment.evaluate(SleepAssessment.Context.DAY, 50.0).grade)
    }
}
