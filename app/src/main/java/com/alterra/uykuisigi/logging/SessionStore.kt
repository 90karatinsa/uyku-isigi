package com.alterra.uykuisigi.logging

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gece boyu kayıtları düz CSV olarak saklar. Amaç dışa aktarılabilir ham veri:
 * dosya doğrudan paylaşılabilir, tabloya açılabilir.
 */
class SessionStore(context: Context) {

    private val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "kayitlar")
        .apply { mkdirs() }

    data class Sample(
        val timeMs: Long,
        val lux: Double,
        val edi: Double,
        val cct: Double?,
        val iso: Int,
        val exposureSeconds: Double,
        val belowFloor: Boolean
    )

    data class Session(val file: File, val samples: List<Sample>) {
        val startMs: Long get() = samples.firstOrNull()?.timeMs ?: file.lastModified()
        val endMs: Long get() = samples.lastOrNull()?.timeMs ?: file.lastModified()
        val durationMinutes: Int get() = ((endMs - startMs) / 60000L).toInt()
    }

    fun createSession(): File {
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val f = File(dir, "gece_$name.csv")
        if (!f.exists()) {
            f.writeText("zaman_ms;yerel_saat;fotopik_lux;melanopik_edi;cct_k;iso;enstantane_s;taban_altinda\n")
        }
        return f
    }

    fun append(file: File, s: Sample) {
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(s.timeMs))
        val line = buildString {
            append(s.timeMs); append(';')
            append(clock); append(';')
            append(String.format(Locale.US, "%.4f", s.lux)); append(';')
            append(String.format(Locale.US, "%.4f", s.edi)); append(';')
            append(s.cct?.let { String.format(Locale.US, "%.0f", it) } ?: ""); append(';')
            append(s.iso); append(';')
            append(String.format(Locale.US, "%.5f", s.exposureSeconds)); append(';')
            append(if (s.belowFloor) 1 else 0)
            append('\n')
        }
        file.appendText(line)
    }

    fun listSessions(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun latest(): File? = listSessions().firstOrNull()

    fun read(file: File): Session {
        val samples = mutableListOf<Sample>()
        if (file.exists()) {
            file.useLines { lines ->
                lines.drop(1).forEach { line ->
                    val p = line.split(';')
                    if (p.size >= 8) {
                        runCatching {
                            samples += Sample(
                                timeMs = p[0].toLong(),
                                lux = p[2].toDouble(),
                                edi = p[3].toDouble(),
                                cct = p[4].toDoubleOrNull(),
                                iso = p[5].toIntOrNull() ?: 0,
                                exposureSeconds = p[6].toDoubleOrNull() ?: 0.0,
                                belowFloor = p[7].trim() == "1"
                            )
                        }
                    }
                }
            }
        }
        return Session(file, samples)
    }

    fun delete(file: File) { runCatching { file.delete() } }


    companion object {
        /** Kayıt özeti: uyku yorumunu bu değerler besliyor. */
        data class Stats(
            val count: Int,
            val durationMinutes: Int,
            val median: Double,
            val p90: Double,
            val max: Double,
            val minutesAbove1: Int,
            val minutesAbove3: Int,
            val minutesAbove10: Int
        )

        /**
         * Kayıt özeti.
         *
         * Eşik üstünde geçen süreler, sabit bir ölçüm aralığı varsayılarak değil, örneklerin
         * **gerçek zaman damgalarından** hesaplanıyor. İki sebebi var: kullanıcı ayarlardaki
         * aralığı kayıttan sonra değiştirebilir (o zaman sabit varsayım süreleri olduğu gibi
         * ölçekler), ve arada bir ölçüm başarısız olursa oluşan boşluk sabit varsayımda
         * görünmez. Her örnek, kendisiyle bir sonraki örnek arasındaki süreyi temsil ediyor.
         *
         * Aşırı uzun boşluklar (kamera bir saat boyunca hata verdiyse) tek bir örneğe
         * yüklenmesin diye, ortanca aralığın üç katıyla sınırlanıyor.
         */
        fun stats(s: List<Sample>): Stats {
            if (s.isEmpty()) return Stats(0, 0, 0.0, 0.0, 0.0, 0, 0, 0)

            val weightsMs = DoubleArray(s.size)
            if (s.size == 1) {
                // Tek örnekten süre çıkarılamaz; sayı raporlanır, süreler sıfır kalır.
            } else {
                val gaps = (1 until s.size).map { (s[it].timeMs - s[it - 1].timeMs).toDouble() }
                val medianGap = gaps.sorted()[gaps.size / 2]
                val cap = medianGap * 3
                for (i in 0 until s.size - 1) {
                    weightsMs[i] = (s[i + 1].timeMs - s[i].timeMs).toDouble().coerceIn(0.0, cap)
                }
                // Son örnek bir ortanca aralık kadar sürdü sayılıyor.
                weightsMs[s.size - 1] = medianGap
            }

            fun minutesAbove(threshold: Double): Int {
                var ms = 0.0
                s.forEachIndexed { i, sample -> if (sample.edi > threshold) ms += weightsMs[i] }
                return (ms / 60000.0).toInt()
            }

            val v = s.map { it.edi }.sorted()
            fun q(p: Double): Double {
                val idx = ((v.size - 1) * p).toInt().coerceIn(0, v.size - 1)
                return v[idx]
            }

            return Stats(
                count = v.size,
                durationMinutes = (weightsMs.sum() / 60000.0).toInt(),
                median = q(0.5),
                p90 = q(0.9),
                max = v.last(),
                minutesAbove1 = minutesAbove(1.0),
                minutesAbove3 = minutesAbove(3.0),
                minutesAbove10 = minutesAbove(10.0)
            )
        }
    }

    /** Oturum nesnesi üzerinden kısayol. */
    fun stats(session: Session): Stats = Companion.stats(session.samples)
}
