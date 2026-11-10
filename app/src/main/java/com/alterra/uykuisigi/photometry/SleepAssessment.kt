package com.alterra.uykuisigi.photometry

/**
 * Ölçülen melanopik EDI değerini uyku açısından yorumlar.
 *
 * Eşikler, Brown ve ark. 2022 (PLOS Biology) uzman konsensüs önerilerine dayanıyor.
 * Öneriler göz hizasında, dikey düzlemde, melanopik EDI cinsinden tanımlı:
 *   - uyku ortamı  : <= 1 lx
 *   - yatmadan onceki 3 saat : <= 10 lx
 *   - gunduz       : >= 250 lx
 *
 * Üst bantlardaki ifadeler destekleyici bulgulara dayanıyor: uyku sırasında ~100 lx
 * maruziyetin gece kalp hızını yükseltip ertesi sabah insülin direncini artırdığı
 * (Mason ve ark. 2022, PNAS) ve yatak odasında 5 lx üzerinin depresif belirti ve
 * kilo artışıyla ilişkilendirildiği (Obayashi ve ark.) çalışmaları.
 */
object SleepAssessment {

    enum class Context(val label: String) {
        SLEEP("Uyku"),
        EVENING("Akşam"),
        DAY("Gündüz")
    }

    enum class Grade(val colorRes: Int) {
        GOOD(com.alterra.uykuisigi.R.color.good),
        OK(com.alterra.uykuisigi.R.color.ok),
        WARN(com.alterra.uykuisigi.R.color.warn),
        BAD(com.alterra.uykuisigi.R.color.bad),
        SEVERE(com.alterra.uykuisigi.R.color.severe)
    }

    data class Verdict(
        val grade: Grade,
        val headline: String,
        val detail: String,
        val advice: String
    )

    fun evaluate(context: Context, melanopicEdi: Double): Verdict = when (context) {
        Context.SLEEP -> sleep(melanopicEdi)
        Context.EVENING -> evening(melanopicEdi)
        Context.DAY -> day(melanopicEdi)
    }

    private fun sleep(m: Double): Verdict = when {
        m < 1.0 -> Verdict(
            Grade.GOOD,
            "Uykuyu bozmaz",
            "1 lüx melanopik EDI'nin altındasın. Uyku ortamı için önerilen aralığın içinde. " +
                "Bu seviyede melatonin baskılanması ve uyku bölünmesi beklenmiyor.",
            "Bir şey değiştirmene gerek yok. Aynı odada gece kalkıp ışık yakıyorsan, o anları da ayrıca ölçmek mantıklı."
        )
        m < 3.0 -> Verdict(
            Grade.OK,
            "İyi, ama sıfırlanabilir",
            "Önerilen 1 lüx sınırının biraz üstündesin. Çoğu insan için belirgin bir etki beklenmez, " +
                "ancak ışığa duyarlı kişilerde uyku derinliğini bir miktar azaltabilir.",
            "Genellikle tek bir kaynak yetiyor: cihazların bekleme LED'leri, şarj aleti, klima paneli. " +
                "Tek tek kapatıp yeniden ölçerek suçluyu bul."
        )
        m < 10.0 -> Verdict(
            Grade.WARN,
            "Sınırda, iyileştir",
            "Bu aralık uyku ortamı için yüksek. Yatak odasında 5 lüx üzeri seviyeler, uyku bölünmesi ve " +
                "sabah dinlenmemiş uyanma ile ilişkilendirilmiş durumda.",
            "Perde/karartma kalitesini kontrol et, sokak lambası veya komşu ışığı geliyor mu bak. " +
                "Ekranları odadan çıkar ya da yüzü aşağı çevir."
        )
        m < 40.0 -> Verdict(
            Grade.BAD,
            "Uyku kalitesini düşürür",
            "Bu seviyedeki ışık uyku sırasında yüzeysel uyku evrelerini artırıyor, derin uyku ve REM'i " +
                "kısaltıyor. Melatonin salınımı gece boyunca baskı altında kalır.",
            "Ana kaynağı bul ve kapat: açık kalan bir lamba, koridor ışığı, perdesiz pencere. " +
                "Kısa vadeli çözüm olarak uyku bandı (göz maskesi) ölçülen değerin neredeyse tamamını keser."
        )
        else -> Verdict(
            Grade.SEVERE,
            "Yüksek risk",
            "Uyku sırasında bu seviyede ışık, tek gecede bile gece kalp hızının yükselmesine, kalp hızı " +
                "değişkenliğinin düşmesine ve ertesi sabah insülin duyarlılığının azalmasına yol açtığı " +
                "gösterilen aralıkta (~100 lüx civarı çalışmalar).",
            "Bu bir 'ince ayar' meselesi değil; odada açık bir ışık kaynağı var. Kapat, sonra yeniden ölç."
        )
    }

    private fun evening(m: Double): Verdict = when {
        m < 10.0 -> Verdict(
            Grade.GOOD,
            "Yatmaya uygun",
            "Yatmadan önceki 3 saat için önerilen 10 lüx melanopik EDI sınırının altındasın. " +
                "Melatonin salınımının normal saatinde başlamasına engel değil.",
            "Bu ortamı koru. Bu saatte ekran kullanıyorsan ekranı ayrıca ölçmek anlamlı olur."
        )
        m < 30.0 -> Verdict(
            Grade.OK,
            "Biraz parlak",
            "Önerilen sınırın üzerindesin ama uçurumda değilsin. Melatonin başlangıcı bir miktar gecikebilir.",
            "Tavan aydınlatması yerine göz hizasının altında, sıcak renkli tek bir lamba kullan. " +
                "Kaynağın renk sıcaklığını düşürmek, parlaklığı düşürmek kadar etkili."
        )
        m < 100.0 -> Verdict(
            Grade.WARN,
            "Melatonin baskılanır",
            "Bu aralıkta akşam maruziyeti melatonin salınımını ölçülebilir biçimde geciktiriyor. " +
                "Uykuya dalma süresinin uzaması beklenir.",
            "Yatmadan 2-3 saat önce tavan aydınlatmasını kapat, ışığı 2700 K ve altına indir, " +
                "kısılabilir armatürlerde en düşük kademeye geç."
        )
        else -> Verdict(
            Grade.BAD,
            "Uyku saatini geciktirir",
            "Gündüz seviyesinde bir ışıktasın. Akşam saatlerinde bu, sirkadiyen ritmi geriye kaydırır: " +
                "uyku geç gelir, sabah kalkmak zorlaşır.",
            "Akşam için ayrı bir aydınlatma senaryosu kur. Aynı odada gündüz ve gece aynı ampulü " +
                "kullanmak bu tablonun en yaygın sebebi."
        )
    }

    private fun day(m: Double): Verdict = when {
        m >= 250.0 -> Verdict(
            Grade.GOOD,
            "Gündüz için yeterli",
            "Önerilen 250 lüx melanopik EDI eşiğinin üzerindesin. Bu, sirkadiyen ritmi sabitleyen ve " +
                "akşam ışığına karşı direnci artıran seviye.",
            "İyi. Gün içindeki bu maruziyet, gece aynı ışıktan daha az etkilenmeni de sağlıyor."
        )
        m >= 100.0 -> Verdict(
            Grade.OK,
            "Yetersiz sayılır",
            "Tipik iç mekan aydınlatmasındasın. Ritmi bozacak kadar düşük değil ama pekiştirecek kadar da yüksek değil.",
            "Sabah ilk saatlerde 20-30 dakika dışarı çık ya da pencere kenarına geç; " +
                "bulutlu bir günde bile dışarısı iç mekanın 10-50 katıdır."
        )
        else -> Verdict(
            Grade.WARN,
            "Gündüz çok karanlık",
            "Gündüz maruziyetin bu kadar düşükse sirkadiyen sinyal zayıflar. Bunun pratik sonucu, " +
                "akşam ışığına daha duyarlı hale gelmen ve uyku saatinin kaymasıdır.",
            "Öncelik gece karartmak değil, gündüzü aydınlatmak. Sabah dışarıda geçen süre en güçlü kaldıraç."
        )
    }

    /** Gece boyu kayıt için özet yorum. */
    fun sessionVerdict(minutesAbove1: Int, minutesAbove10: Int, totalMinutes: Int, median: Double, max: Double): Verdict {
        val pct1 = if (totalMinutes > 0) 100.0 * minutesAbove1 / totalMinutes else 0.0
        return when {
            max < 1.0 -> Verdict(
                Grade.GOOD, "Gece boyunca karanlık",
                "Kayıt boyunca hiçbir noktada 1 lüx melanopik EDI aşılmadı. Ortanca değer ${fmt(median)} lüx.",
                "Uyku ortamı açısından yapılacak bir şey yok."
            )
            minutesAbove10 > 20 -> Verdict(
                Grade.BAD, "Gecede belirgin ışık var",
                "Toplam $minutesAbove10 dakika boyunca 10 lüx melanopik EDI üzerinde kaldın, tepe değer ${fmt(max)} lüx. " +
                    "Bu süreler uyku yapısını bozmaya yeter.",
                "Grafikte yükselen saatlere bak: sabaha karşı artıyorsa gün ışığı/sokak lambası, " +
                    "ani sıçramalar varsa odada açılan bir ışık ya da bildirimle uyanan bir ekran."
            )
            pct1 > 30.0 -> Verdict(
                Grade.WARN, "Sürekli hafif ışık",
                "Gecenin %${pct1.toInt()}'inde 1 lüx üzerindeydin, ortanca ${fmt(median)} lüx, tepe ${fmt(max)} lüx. " +
                    "Ani bir kaynak değil, sabit bir sızıntı var.",
                "Sabit sızıntı genelde perde kenarı veya bekleme LED'idir. Odayı gece gözün karanlığa " +
                    "alıştıktan sonra kontrol et; 1 lüx çıplak gözle kolayca görülür."
            )
            else -> Verdict(
                Grade.OK, "Büyük ölçüde uygun",
                "Ortanca ${fmt(median)} lüx, tepe ${fmt(max)} lüx. Kısa süreli yükselmeler dışında ortam karanlık.",
                "Tepe değerin oluştuğu saate bak; tek seferlik bir olaysa (tuvalete kalkma gibi) sorun değil."
            )
        }
    }

    private fun fmt(v: Double) = if (v < 10) String.format("%.2f", v) else String.format("%.0f", v)
}
