package com.alterra.uykuisigi

import com.alterra.uykuisigi.logging.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gece raporundaki "eşik üstünde geçen süre" sayıları.
 *
 * Bu hesap sessizce yanlış sonuç üretebilen türden: hata fırlatmaz, sadece rapordaki
 * dakikalar kayar. Önceki sürüm sabit bir ölçüm aralığı varsayıyordu ve kullanıcı
 * ayarı sonradan değiştirdiğinde süreleri olduğu gibi ölçekliyordu.
 */
class SessionStatsTest {

    private fun samples(startMs: Long, gapMinutes: Int, values: List<Double>) =
        values.mapIndexed { i, edi ->
            SessionStore.Sample(
                timeMs = startMs + i.toLong() * gapMinutes * 60_000L,
                lux = edi, edi = edi, cct = null, iso = 100,
                exposureSeconds = 0.1, belowFloor = false
            )
        }

    @Test
    fun uniformSamplesGiveExactMinutes() {
        // 5 dakikalık aralık, 12 örnek = 1 saat.
        // Eşikler kesin karşılaştırma (>) kullandığı için test verisi sınıra oturmuyor.
        val s = samples(0L, 5, listOf(0.2, 0.3, 2.0, 4.0, 0.4, 0.2, 12.0, 0.1, 0.3, 0.2, 0.2, 3.5))
        val st = SessionStore.stats(s)
        assertEquals(12, st.count)
        assertEquals(60, st.durationMinutes)
        assertEquals(20, st.minutesAbove1)
        assertEquals(15, st.minutesAbove3)
        assertEquals(5, st.minutesAbove10)
    }

    /**
     * Asıl düzeltilen hata: süreler kayıt sırasındaki gerçek aralıktan gelmeli.
     * Aynı değer dizisi 15 dakikalık aralıkla kaydedilmişse süreler üç katı olmalı,
     * ve bu, ayarlarda o an ne yazdığından bağımsız olmalı.
     */
    @Test
    fun intervalComesFromTimestampsNotSettings() {
        val values = listOf(0.2, 2.0, 4.0, 0.1)
        val fiveMin = SessionStore.stats(samples(0L, 5, values))
        val fifteenMin = SessionStore.stats(samples(0L, 15, values))
        assertEquals(10, fiveMin.minutesAbove1)
        assertEquals(30, fifteenMin.minutesAbove1)
    }

    /**
     * Ölçüm başarısız olup boşluk oluşursa o boşluk süreye yansımalı.
     * Sabit aralık varsayan eski hesap bu 10 dakikayı 5 sayardı.
     */
    @Test
    fun gapsAreCounted() {
        val s = listOf(
            0L to 5.0,
            5L * 60_000 to 5.0,   // burada kamera iki ölçüm kaçırmış: 10 dakikalık boşluk
            15L * 60_000 to 0.1,
            20L * 60_000 to 0.1
        ).map { (t, edi) ->
            SessionStore.Sample(t, edi, edi, null, 100, 0.1, false)
        }
        val st = SessionStore.stats(s)
        // Ortanca aralık 5 dk, tavan 15 dk; 10 dakikalık boşluk tavanın altında olduğu için
        // olduğu gibi sayılıyor: 5 + 10 = 15 dakika eşik üstü.
        assertEquals(15, st.minutesAbove1)
    }

    /** Çok uzun bir boşluk tek örneğe sınırsız yüklenmemeli. */
    @Test
    fun hugeGapIsCapped() {
        val s = listOf(
            0L to 5.0,
            5L * 60_000 to 5.0,
            185L * 60_000 to 0.1,   // 3 saatlik kopukluk
            190L * 60_000 to 0.1
        ).map { (t, edi) -> SessionStore.Sample(t, edi, edi, null, 100, 0.1, false) }
        val st = SessionStore.stats(s)
        // Ortanca aralık 5 dk, tavan 15 dk: kameranın 3 saat sustuğu boşluk tek bir
        // örneğe olduğu gibi yüklenmiyor, 15 dakikayla sınırlanıyor. Bilmediğimiz bir
        // süreyi bildiğimiz son değerle doldurmak raporu uydurma veriyle şişirirdi.
        assertEquals(5 + 15, st.minutesAbove1)
    }

    @Test
    fun singleSampleHasNoDuration() {
        val st = SessionStore.stats(samples(0L, 5, listOf(4.0)))
        assertEquals(1, st.count)
        assertEquals(0, st.durationMinutes)
        assertEquals(0, st.minutesAbove1)
    }

    @Test
    fun emptySessionIsZero() {
        val st = SessionStore.stats(emptyList())
        assertEquals(0, st.count)
        assertEquals(0.0, st.median, 1e-9)
    }

    @Test
    fun quantilesAndPeak() {
        val st = SessionStore.stats(samples(0L, 5, listOf(0.1, 0.2, 0.3, 0.4, 50.0)))
        assertEquals(0.3, st.median, 1e-9)
        assertEquals(50.0, st.max, 1e-9)
    }
}
