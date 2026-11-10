# Uyku Işığı

> **English:** Uyku Işığı ("sleep light") turns an Android phone camera, with a small piece of paper as a
> diffuser, into an incident light meter. It reports melanopic EDI, not just lux, against the Brown et al.
> 2022 consensus thresholds (≤ 1 lx while asleep, ≤ 10 lx in the 3 hours before bed, ≥ 250 lx in daytime),
> and can log a whole night to CSV with a morning chart. Kotlin, Camera2 (RAW when available), no network
> permission: nothing leaves the phone. **Not a medical device.**

Android telefonun kamerasını, önüne konan bir kağıt difüzörle **gelen ışık ölçere**
(incident light meter) dönüştürür; ölçtüğü aydınlığı uyku açısından yorumlar.

## Neden sadece "lüks" değil

Uykuyu bozan şey aydınlık değil, ışığın gözdeki melanopsin reseptörlerini uyarma
gücüdür. Aynı 5 lükslük iki ortamdan biri mum ışığıysa diğeri beyaz LED'se,
melatonin üzerindeki etkileri arasında 5 kat fark olur.

Bu yüzden uygulama iki değer üretir:

- **fotopik lüks** — klasik aydınlık,
- **melanopik EDI** — melanopik gün ışığı eşdeğeri aydınlığı.

Uyku önerileri melanopik EDI cinsinden tanımlı olduğu için asıl gösterilen odur.
Renk sıcaklığı kameradan kestirilir, istenirse ışık kaynağı elle de seçilebilir.

## Kullandığı eşikler

Brown ve ark. 2022 (PLOS Biology) uzman konsensüsü, göz hizasında dikey düzlemde
melanopik EDI cinsinden:

| Bağlam | Öneri |
|---|---|
| Uyku ortamı | ≤ 1 lüks |
| Yatmadan önceki 3 saat | ≤ 10 lüks |
| Gündüz | ≥ 250 lüks |

Üst bantlardaki uyarılar destekleyici bulgulara dayanıyor: uykuda ~100 lüks
maruziyetin gece kalp hızını yükseltip ertesi sabah insülin direncini artırması
(Mason ve ark. 2022, PNAS) ve yatak odasında 5 lüks üzerinin depresif belirti ve
kilo artışıyla ilişkilendirilmesi (Obayashi ve ark.).

## Ölçüm nasıl yapılıyor

Kağıt, kamerayı kosinüs alıcısına çeviriyor. ISO 2720'ye göre bir gelen ışık
ölçerinde doğru pozlama şartı:

```
E = N² · C / (t · S)
```

`E` aydınlık (lüks), `N` diyafram, `t` enstantane, `S` ISO, `C = 250` (düz alıcı).

Pozlama elle sürüldüğü için kare "doğru pozlanmış" olmak zorunda değil; ölçülen
ortalama doğrusal seviye `M`, orta gri referansı `0.184`'ten ne kadar saparsa
aydınlık da o oranda sapıyor:

```
E = N² · C / (t · S) · (M / 0.184) · g
```

`g` kullanıcı kalibrasyon kazancı: kağıdın geçirgenliğini, sensörün V(λ)'dan sapan
spektral duyarlılığını ve vinyetlemeyi birlikte yutuyor.

### Ölçüm zinciri

1. **Menzil arama.** Pozlama, ortalama seviye orta griye yaklaşana kadar yinelemeli
   ayarlanır. Telefon sabit duracağı için önce enstantane uzatılır, ancak sonra ISO
   yükseltilir — aynı pozlamayı düşük ISO ile almak gürültüyü belirgin azaltır.
2. **RAW tercih edilir.** RAW_SENSOR varsa aydınlık ondan hesaplanır: veri doğrusaldır,
   ISP'nin ton eğrisi ve gürültü azaltması devrede değildir. 1 lüks altında farkı
   yaratan tam olarak budur. RAW yoksa YUV kullanılır ve mümkünse ton eğrisi doğrusala
   sabitlenir, o da olmazsa sRGB tersi uygulanır.
3. **Kare ortalama.** Yakınsadıktan sonra birkaç kare daha alınıp ortalanır.
4. **Renk geçişi.** Beyaz dengesi gün ışığına sabitlenmiş tek kareden renk sıcaklığı
   kestirilir (McCamy), oradan melanopik oran bulunur.
5. **Elle pozlama yoksa** otomatik pozlamanın seçtiği t/ISO/diyafram değerleri
   sonuçtan okunup aynı formülde kullanılır. Hassasiyet düşer ama çalışır.

## Kalibrasyon — atlanmaması gereken adım

Kalibre edilmeden mutlak lüks değerleri yaklaşıktır (varsayılan kazanç, kağıdın
%40 geçirgen olduğu tahminine dayanır). Karşılaştırmalar yine anlamlıdır, mutlak
değerler değil.

**Kazanç kalibrasyonu.** Aydınlık ve tekdüze bir ortamda (beyaz tavana bakan telefon
iyi bir seçenek, en az 20 lüks) **Kalibre et**'e bas. Uygulama, seçili kamerayla **aynı
yüzdeki** ışık sensörünü referans alır. Telefonda ışık sensörü yoksa bilinen bir değer
elle girilir.

> **Kağıt yalnızca objektifi kapatmalı, ışık sensörünü değil.** Bazı cihazlarda kamerayla
> aynı yüzde bir ışık sensörü bulunur (Pixel 9 Pro'da arka yüzdeki VD6282, kamera çubuğunun
> içinde). Kağıt ikisini birden örterse kamera da referans sensör de aynı oranda kararır,
> oran sadeleşir ve kazanç 1'e yakın çıkar. Kalibrasyon kendini iptal etmiş olur — üstelik
> hata vermeden. Uygulama bu durumu iki eşikle yakalar:
>
> | kazanç | anlamı | davranış |
> |---|---|---|
> | < 0.8 | kağıt hiç takılı değil (yansıyan ışık ölçülmüş) | kaydedilmez |
> | 0.8 – 1.5 | kağıt sensörü de örtüyor | kaydedilir, kalıcı uyarı |
> | 2 – 4 | tek kat fotokopi kağıdı için beklenen aralık | normal |
> | > 500 | gölge veya fazla kat | kaydedilmez |

**Karanlık referansı.** 1 lüks altını ölçecekseniz şart. Kamerayı tamamen kapatıp
**Karanlık referansı**'na basın; sensörün karanlık akımı kaydedilir ve sonraki
ölçümlerden — o ölçümün `t × ISO` çarpımına ölçeklenerek — çıkarılır. Bu yapılmazsa
çok karanlık odalar olduğundan aydınlık ölçülür.

Kalibrasyon kamera başına saklanır. Kağıt değişirse yeniden kalibre edin.

## Gece boyu kayıt

Tek bir anlık ölçüm "bu odada uyunur mu" sorusuna eksik cevap verir: sokak lambası
gece yarısı yanar, gün ışığı sabaha karşı sızar. Gece kaydı, seçilen aralıkla
(varsayılan 5 dk) ölçüm alır, CSV'ye yazar; sabah logaritmik grafik, ortanca/tepe
değerler ve "1 / 3 / 10 lüks üzerinde geçen süre" ile rapor çıkarır.

Kamera arka planda ancak `camera` tipinde bir ön plan servisiyle açılabildiği için
bildirim zorunlu. Ekran kapalıyken ölçüm sürsün diye kısmi wake lock tutuluyor —
telefonu şarja takın.

## Doğruluk sınırları

**Tıbbi cihaz değildir;** tanı ya da tedavi için kullanılmaz. Dürüst olmak gerekirse bir laboratuvar
fotometresi de değil:

- Telefon sensörünün spektral duyarlılığı V(λ) eğrisi değil. Kalibrasyondan **sonra
  bile**, kalibrasyonun yapıldığından farklı tipte bir kaynakta %20–40 sapma olabilir.
- Kağıt gerçek bir kosinüs düzelticisi değil; yandan gelen ışığı olması gerekenden
  farklı ağırlıklandırır.
- Renk sıcaklığı kestirimi kabadır. Dar bantlı kaynaklarda (kehribar gece lambası)
  tablo yanıltır — o durumda ışık kaynağını elle seçin.
- ~0.05 lüks altı çoğu telefonda ölçülemez; uygulama bunu "taban altında" diye
  işaretler ve üst sınır gösterir.
- Telefonun kendi ışık sensörü mutlak doğrulukta daha iyidir ama tipik olarak
  1–5 lüks altını ayırt edemez; uyku için kritik aralık tam da orasıdır. Kamera
  yolunun varlık sebebi bu.

Güvenilir olduğu yer: **karşılaştırma** ("perdeyi değiştirince ne oldu") ve
**eşik testi** ("1 lüks çizgisinin altında mıyım"). Kalibrasyondan sonra ikisi de
sağlam çalışır.

## Kurulum

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ya da APK'yı telefona kopyalayıp dosya yöneticisinden açın (bilinmeyen kaynaklara
izin vermeniz gerekir).

## Derleme

JDK 17 ve Android SDK (platform 34) gerekir. SDK yolu `local.properties` içindeki `sdk.dir` ile verilir
(Android Studio bunu kendisi yazar).

```
./gradlew assembleDebug
```

Testler:

```
./gradlew testDebugUnitTest
```

Kotlin 1.9.22 · AGP 8.2.2 · Gradle 8.4 · minSdk 28 · targetSdk 34

## Dosya haritası

| Dosya | İş |
|---|---|
| `photometry/Photometry.kt` | Lüks formülü, CCT kestirimi, melanopik oran tablosu |
| `photometry/SleepAssessment.kt` | Eşikler ve yorum metinleri |
| `camera/LightMeter.kt` | Camera2 motoru, menzil arama, RAW/YUV yolu |
| `camera/FrameAnalyzer.kt` | Kareden doğrusal istatistik (Bayer CFA çözümü dahil) |
| `data/Settings.kt` | Kamera başına kalibrasyon ve tercihler |
| `data/LightSensors.kt` | TYPE_LIGHT, kalibrasyon referansı |
| `logging/NightSessionService.kt` | Gece boyu ön plan servisi |
| `logging/SessionStore.kt` | CSV kayıt ve istatistik |
| `ui/NightChartView.kt` | Logaritmik gece grafiği |

## Lisans

MIT, bkz. [LICENSE](LICENSE).
