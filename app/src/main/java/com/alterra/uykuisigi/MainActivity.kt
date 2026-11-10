package com.alterra.uykuisigi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alterra.uykuisigi.camera.LightMeter
import com.alterra.uykuisigi.data.LightSensors
import com.alterra.uykuisigi.data.Settings
import com.alterra.uykuisigi.databinding.ActivityMainBinding
import com.alterra.uykuisigi.logging.NightSessionService
import com.alterra.uykuisigi.logging.SessionStore
import com.alterra.uykuisigi.photometry.Photometry
import com.alterra.uykuisigi.photometry.SleepAssessment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var sensors: LightSensors
    private lateinit var store: SessionStore

    private var mode = SleepAssessment.Context.SLEEP
    private var busy = false

    private val intervalOptions = intArrayOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)
    private val maxExposureOptions = floatArrayOf(0.5f, 1f, 2f, 4f, 8f, 16f)

    /**
     * Bu değerin altındaki kalibrasyon kazançları güvenilmez. Tek kat fotokopi kağıdı
     * ışığın yarıdan fazlasını keser, yani gerçek kazanç 2× ile 4× arasında olmalı.
     */
    private val SUSPICIOUS_GAIN = 1.5

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) refreshCapabilities()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        settings = Settings(this)
        sensors = LightSensors(this)
        store = SessionStore(this)

        setupContextToggle()
        setupSettings()
        setupMaxExposure()
        setupButtons()
        updateHowTo()
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        sensors.start { runOnUiThread { showSensors() } }
        refreshCapabilities()
        refreshCalibrationStatus()
        b.lastSessionButton.visibility = if (store.latest() != null) View.VISIBLE else View.GONE
        observeSession()
    }

    override fun onPause() {
        super.onPause()
        sensors.stop()
    }

    // ------------------------------------------------------------------ kurulum

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun setupContextToggle() {
        // Saate göre makul bir başlangıç seç; kullanıcı yine de değiştirebilir.
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        mode = when (hour) {
            in 22..23, in 0..6 -> SleepAssessment.Context.SLEEP
            in 18..21 -> SleepAssessment.Context.EVENING
            else -> SleepAssessment.Context.DAY
        }
        b.contextGroup.check(
            when (mode) {
                SleepAssessment.Context.SLEEP -> R.id.btnSleep
                SleepAssessment.Context.EVENING -> R.id.btnEvening
                SleepAssessment.Context.DAY -> R.id.btnDay
            }
        )
        b.contextGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.btnSleep -> SleepAssessment.Context.SLEEP
                R.id.btnEvening -> SleepAssessment.Context.EVENING
                else -> SleepAssessment.Context.DAY
            }
            updateHowTo()
            lastReadingLux?.let { showVerdictFor(it, lastReadingCct) }
        }
    }

    private var lastReadingLux: Double? = null
    private var lastReadingCct: Double? = null

    private fun setupSettings() {
        b.frontSwitch.isChecked = settings.useFrontCamera
        b.frontSwitch.setOnCheckedChangeListener { _, checked ->
            settings.useFrontCamera = checked
            refreshCapabilities()
            refreshCalibrationStatus()
            showSensors()
            updateHowTo()
        }

        val sources = Photometry.LightSource.values()
        b.sourceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, sources.map { it.label }
        )
        b.sourceSpinner.setSelection(sources.indexOf(settings.lightSource).coerceAtLeast(0))
        b.sourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                settings.lightSource = sources[pos]
                lastReadingLux?.let { showVerdictFor(it, lastReadingCct) }
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }

        val idx = intervalOptions.indexOf(settings.sessionIntervalMinutes).coerceAtLeast(0)
        b.intervalSeek.progress = idx
        showInterval(intervalOptions[idx])
        b.intervalSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val m = intervalOptions[progress.coerceIn(0, intervalOptions.size - 1)]
                showInterval(m)
                if (fromUser) settings.sessionIntervalMinutes = m
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun setupMaxExposure() {
        val idx = maxExposureOptions.indexOfFirst { it >= settings.maxExposureSeconds }
            .let { if (it < 0) maxExposureOptions.size - 1 else it }
        b.maxExpSeek.progress = idx
        showMaxExposure(maxExposureOptions[idx])
        b.maxExpSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = maxExposureOptions[progress.coerceIn(0, maxExposureOptions.size - 1)]
                showMaxExposure(v)
                if (fromUser) settings.maxExposureSeconds = v
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun showMaxExposure(seconds: Float) {
        val text = if (seconds < 1f) "1/${(1f / seconds).toInt()} s" else "${seconds.toInt()} s"
        b.maxExpLabel.text = "Azami pozlama süresi: $text"
    }

    private fun showInterval(minutes: Int) {
        b.intervalLabel.text = "Gece kaydı ölçüm aralığı: $minutes dakika"
    }

    private fun setupButtons() {
        b.measureButton.setOnClickListener { measure() }
        b.calibrateButton.setOnClickListener { calibrate() }
        b.darkRefButton.setOnClickListener { darkReference() }
        b.lastSessionButton.setOnClickListener {
            startActivity(Intent(this, SessionActivity::class.java))
        }
        b.sessionButton.setOnClickListener {
            if (NightSessionService.state.value.running) {
                NightSessionService.stop(this)
            } else {
                startNightSession()
            }
        }
    }

    // ------------------------------------------------------------------ ölçüm

    private fun cameraId(): String? = LightMeter.pickCamera(this, settings.useFrontCamera)

    private fun options(id: String, gainOverride: Double? = null) = LightMeter.Options(
        cameraId = id,
        preferRaw = settings.preferRaw,
        maxExposureSeconds = settings.maxExposureSeconds.toDouble(),
        averagingFrames = settings.averagingFrames,
        calibrationGain = gainOverride ?: settings.calibrationGain(id),
        darkOffsetLinear = settings.darkOffset(id),
        darkOffsetProduct = settings.darkOffsetProduct(id)
    )

    private fun measure() {
        if (busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(); return
        }
        val id = cameraId() ?: run { toast("Kamera bulunamadı."); return }

        busy = true
        b.measureButton.isEnabled = false
        b.warningText.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val reading = LightMeter(this@MainActivity).measure(options(id)) { msg ->
                    runOnUiThread { b.measureButton.text = msg }
                }
                showReading(reading)
            } catch (e: Exception) {
                b.luxValue.text = "Ölçüm başarısız"
                b.exposureInfo.text = e.message ?: "Bilinmeyen hata"
                b.verdictCard.visibility = View.GONE
            } finally {
                busy = false
                b.measureButton.isEnabled = true
                b.measureButton.text = getString(R.string.measure)
            }
        }
    }

    private fun showReading(r: LightMeter.Reading) {
        lastReadingLux = r.lux
        lastReadingCct = r.cct

        val edi = Photometry.melanopicEdi(r.lux, r.cct, settings.lightSource)
        b.ediValue.text = fmt(edi)
        b.luxValue.text = buildString {
            append("fotopik ${fmt(r.lux)} lüks")
            r.cct?.let { append("  ·  ~${it.toInt()} K") }
            val der = settings.lightSource.der ?: Photometry.melanopicDer(r.cct)
            append("  ·  mDER ${String.format(Locale.US, "%.2f", der)}")
        }
        b.exposureInfo.text = String.format(
            Locale.US,
            "f/%.1f · %s · ISO %d · EV100 %.1f · %s",
            r.aperture, exposureText(r.exposureSeconds), r.iso, r.ev100,
            if (r.usedRaw) "RAW" else "YUV"
        )

        val warnings = mutableListOf<String>()
        if (r.belowFloor) warnings += "Ölçüm tabanının altında: gerçek değer bundan düşük olabilir, üst sınır gösteriliyor."
        if (r.clipped) warnings += "Kare doygun: ışık ölçüm aralığının üstünde ya da kağıt yeterince kapatmıyor."
        if (r.atSensitivityLimit && !r.belowFloor) warnings += "Sensör sınırında ölçüldü; hassasiyet düşük."
        if (!r.manualControl) warnings += "Bu kamera elle pozlamayı desteklemiyor, otomatik pozlama verisi kullanıldı."
        val camId = cameraId() ?: ""
        if (!settings.isCalibrated(camId)) {
            warnings += "Henüz kalibre edilmedi. Mutlak değerler yaklaşık."
        } else if (settings.calibrationGain(camId) < SUSPICIOUS_GAIN) {
            warnings += "Kalibrasyon kazancı şüpheli (${String.format(Locale.US, "%.2f", settings.calibrationGain(camId))}×). " +
                "Kağıt muhtemelen ışık sensörünü de örtüyordu; bu değer ölçümü olduğundan düşük gösterir."
        }
        if (warnings.isEmpty()) {
            b.warningText.visibility = View.GONE
        } else {
            b.warningText.visibility = View.VISIBLE
            b.warningText.text = warnings.joinToString("\n")
        }

        showVerdictFor(r.lux, r.cct)
    }

    private fun showVerdictFor(lux: Double, cct: Double?) {
        val edi = Photometry.melanopicEdi(lux, cct, settings.lightSource)
        b.ediValue.text = fmt(edi)
        val v = SleepAssessment.evaluate(mode, edi)
        b.verdictCard.visibility = View.VISIBLE
        b.verdictHeadline.text = v.headline
        b.verdictHeadline.setTextColor(ContextCompat.getColor(this, v.grade.colorRes))
        b.verdictDetail.text = v.detail
        b.verdictAdvice.text = v.advice
    }

    // -------------------------------------------------------------- kalibrasyon

    private fun calibrate() {
        if (busy) return
        val id = cameraId() ?: run { toast("Kamera bulunamadı."); return }
        val ref = sensors.reference(settings.useFrontCamera)

        if (ref == null || ref.lux < 20f) {
            askReferenceLux(id)
            return
        }
        if (!ref.sameFace) {
            // Referans sensör kameranın öbür yüzünde: iki yüz farklı ışık görüyorsa
            // kazanç kayar, bu yüzden kullanıcı bilerek onaylamalı.
            AlertDialog.Builder(this)
                .setTitle("Sensör diğer yüzde")
                .setMessage(
                    "Seçili kamera ile kullanılabilir ışık sensörü telefonun farklı yüzlerinde " +
                        "(${ref.label}). Kalibrasyonun doğru olması için iki yüzün de aynı ışığı " +
                        "görmesi gerekir.\n\nTelefonu odanın ortasında, iki yüzü de benzer " +
                        "aydınlıkta olacak şekilde tutuyorsan devam edebilirsin."
                )
                .setPositiveButton("Yine de kalibre et") { _, _ ->
                    runCalibration(id, ref.lux.toDouble(), ref.label)
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        } else {
            runCalibration(id, ref.lux.toDouble(), ref.label)
        }
    }

    private fun askReferenceLux(id: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "örn. 300"
        }
        val hasAny = sensors.frontSensor != null || sensors.rearSensor != null
        val reason = if (!hasAny)
            "Bu telefonda kullanılabilir ışık sensörü yok."
        else
            "Işık sensörü şu an güvenilir bir değer vermiyor (en az 20 lüks gerekli)."

        AlertDialog.Builder(this)
            .setTitle("Referans aydınlık")
            .setMessage(
                "$reason\n\nBilinen bir aydınlık değeri gir; kalibrasyon buna göre yapılsın. " +
                    "Ölçümü aydınlık ve tekdüze bir ortamda yap (beyaz tavana bakan telefon iyi bir seçenek)."
            )
            .setView(input)
            .setPositiveButton("Kalibre et") { _, _ ->
                val v = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (v == null || v <= 0) toast("Geçerli bir sayı gir.")
                else runCalibration(id, v, "elle girilen referans")
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun runCalibration(id: String, referenceLux: Double, sourceLabel: String) {
        busy = true
        b.calibrateButton.isEnabled = false
        b.calibrationStatus.text = "Kalibrasyon ölçümü alınıyor…"

        lifecycleScope.launch {
            try {
                // Kazanç 1.0 ile ölç; referansa oran doğrudan aranan kazancı verir.
                val r = LightMeter(this@MainActivity).measure(options(id, gainOverride = 1.0))
                if (r.lux <= 0.0 || r.belowFloor) {
                    b.calibrationStatus.text = "Ortam kalibrasyon için fazla karanlık. Işığı aç ve tekrar dene."
                } else {
                    val gain = referenceLux / r.lux
                    if (gain < 0.8) {
                        // Difüzör ışığı yalnızca azaltabilir, dolayısıyla kazanç 1'in altına
                        // inemez. İndiyse kamera kağıtsız ölçmüş demektir: kağıtsız kamera
                        // yansıyan ışığı ölçer ve beyaz bir yüzeyi %18 gri sanarak yaklaşık
                        // 4 kat fazla okur. Bu kalibrasyonu kaydetmek ölçümü kalıcı bozar.
                        b.calibrationStatus.text =
                            "Kalibrasyon kaydedilmedi: kazanç ${String.format(Locale.US, "%.2f", gain)}× çıktı. " +
                                "Kağıt ışığı azaltır, yani kazanç 1'in altında olamaz. Büyük ihtimalle kağıt " +
                                "kameranın üstünde değil ya da objektifi tam kapatmıyor. Kağıdı bantlayıp tekrar dene."
                    } else if (gain > 500.0) {
                        b.calibrationStatus.text =
                            "Kalibrasyon kaydedilmedi: kazanç ${String.format(Locale.US, "%.0f", gain)}× çıktı, " +
                                "bu kadar kalın bir difüzör beklenmiyor. Kağıdın üstüne gölge düşüyor ya da " +
                                "kat sayısı fazla olabilir."
                    } else {
                        settings.setCalibrationGain(id, gain)
                        val sameFaceSensor =
                            if (settings.useFrontCamera) sensors.frontSensor else sensors.rearSensor
                        if (gain < SUSPICIOUS_GAIN && sameFaceSensor != null) {
                            // Kağıt hem lensi hem referans sensörü örtüyorsa ikisi de aynı oranda
                            // kararır ve oran sadeleşir: kazanç 1'e yakın çıkar. Bu, hata vermeyen
                            // ama ölçümü tamamen geçersiz kılan durum, o yüzden ısrarla söylüyoruz.
                            b.calibrationStatus.text =
                                "Kaydedildi ama ŞÜPHELİ: kazanç ${String.format(Locale.US, "%.2f", gain)}×. " +
                                    "Tek kat fotokopi kağıdı ışığın yarıdan fazlasını keser, yani 2× ile 4× " +
                                    "arası beklenir. 1'e yakın bir değer, kağıdın referans sensörü " +
                                    "(${sameFaceSensor.name}) de örttüğü anlamına gelir: ikisi birlikte " +
                                    "karardığı için oran sadeleşir. Kağıdı küçültüp sensörü açıkta bırak " +
                                    "ve yeniden kalibre et."
                        } else {
                            toast("Kalibre edildi: ${String.format(Locale.US, "%.2f", gain)}× ($sourceLabel)")
                            refreshCalibrationStatus()
                        }
                    }
                }
            } catch (e: Exception) {
                b.calibrationStatus.text = "Kalibrasyon başarısız: ${e.message}"
            } finally {
                busy = false
                b.calibrateButton.isEnabled = true
            }
        }
    }

    private fun darkReference() {
        if (busy) return
        val id = cameraId() ?: run { toast("Kamera bulunamadı."); return }
        AlertDialog.Builder(this)
            .setTitle("Karanlık referansı")
            .setMessage(
                "Kamerayı tamamen kapat: kağıdın üstünü koyu bir bezle ört ya da telefonu yüzü " +
                    "aşağı, kalın bir kumaşın altına koy. Işık sızmasın.\n\n" +
                    "Bu ölçüm sensörün kendi karanlık akımını kaydeder ve 1 lüks altındaki " +
                    "okumalardan çıkarılır. Bunu yapmadan çok karanlık odalar olduğundan " +
                    "aydınlık ölçülür."
            )
            .setPositiveButton("Hazırım, ölç") { _, _ -> runDarkReference(id) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun runDarkReference(id: String) {
        busy = true
        b.darkRefButton.isEnabled = false
        b.calibrationStatus.text = "Karanlık referansı alınıyor…"
        lifecycleScope.launch {
            try {
                val opts = options(id).copy(darkOffsetLinear = 0.0, darkOffsetProduct = 0.0, averagingFrames = 6)
                val r = LightMeter(this@MainActivity).measure(opts)
                settings.setDarkOffset(id, r.meanLinear, r.exposureSeconds * r.iso)
                toast("Karanlık referansı kaydedildi.")
                refreshCalibrationStatus()
            } catch (e: Exception) {
                b.calibrationStatus.text = "Karanlık referansı alınamadı: ${e.message}"
            } finally {
                busy = false
                b.darkRefButton.isEnabled = true
            }
        }
    }

    private fun refreshCalibrationStatus() {
        val id = cameraId()
        if (id == null) {
            b.calibrationStatus.text = "Kamera bulunamadı."
            return
        }
        val gain = settings.calibrationGain(id)
        val calibrated = settings.isCalibrated(id)
        val dark = settings.darkOffset(id)
        b.calibrationStatus.text = buildString {
            if (calibrated && gain in 0.8..SUSPICIOUS_GAIN) {
                append("Kazanç ${String.format(Locale.US, "%.2f", gain)}× — şüpheli. ")
                append("Kağıt tek başına ışığın yarıdan fazlasını keser, 2-4× beklenir. ")
                append("1'e yakın değer kağıdın ışık sensörünü de örttüğünü gösterir. ")
                append("Kağıdı küçültüp yeniden kalibre et.")
            } else if (calibrated && gain < 0.8) {
                append("Kazanç ${String.format(Locale.US, "%.2f", gain)}× — bu değer geçersiz. ")
                append("Kağıt takılıyken kazanç 1'in altına inemez; bu kalibrasyon kağıtsız yapılmış. ")
                append("Kağıdı bantlayıp yeniden kalibre et.")
            } else if (calibrated) {
                append("Kazanç ${String.format(Locale.US, "%.2f", gain)}× (kalibre edildi).")
            } else {
                append("Kazanç ${String.format(Locale.US, "%.2f", gain)}× (varsayılan tahmin). ")
                append("Mutlak lüks değerleri için bir kez kalibre et.")
            }
            if (dark > 0) append("\nKaranlık referansı: ${String.format(Locale.US, "%.4f", dark)}")
        }
    }

    private fun showSensors() {
        val ref = sensors.reference(settings.useFrontCamera)
        b.sensorReading.text = buildString {
            append("Ön ışık sensörü: ")
            append(sensors.frontLux?.let { String.format(Locale.US, "%.1f lüks", it) }
                ?: if (sensors.frontSensor != null) "bekleniyor" else "yok")
            append("\nArka ışık sensörü: ")
            append(sensors.rearLux?.let { String.format(Locale.US, "%.1f lüks", it) }
                ?: if (sensors.rearSensor != null) "bekleniyor" else "yok")
            if (ref != null) {
                append("\nKalibrasyon referansı: ").append(ref.label)
                if (!ref.sameFace) append(" (kameranın diğer yüzünde)")
            }
        }
    }

    // ------------------------------------------------------------- gece kaydı

    private fun startNightSession() {
        val minutes = settings.sessionIntervalMinutes
        AlertDialog.Builder(this)
            .setTitle("Gece boyu kayıt")
            .setMessage(
                "Telefon $minutes dakikada bir ölçüm alacak ve sonuçları CSV olarak kaydedecek.\n\n" +
                    "• Telefonu yatağın yanına, ${if (settings.useFrontCamera) "ekranı yukarı bakacak" else "ekranı aşağı bakacak"} " +
                    "şekilde koy; kağıtlı kamera tavanı görsün.\n" +
                    "• Üstünü örtme, yastıkla kapatma.\n" +
                    "• Şarja tak: gece boyunca kamera açılıp kapandığı için pil tüketimi belirgindir.\n\n" +
                    "Sabah aynı düğmeyle durdur, rapor çıkar."
            )
            .setPositiveButton("Başlat") { _, _ ->
                NightSessionService.start(this)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun observeSession() {
        lifecycleScope.launch {
            NightSessionService.state.collectLatest { st ->
                if (st.running) {
                    b.sessionButton.text = getString(R.string.stop_session)
                    val last = st.lastEdi?.let { " · son ${fmt(it)} lx" } ?: ""
                    b.subtitle.text = "Gece kaydı sürüyor: ${st.sampleCount} ölçüm$last"
                } else {
                    b.sessionButton.text = getString(R.string.night_session)
                    b.subtitle.text = "Kamera + kağıt difüzör ile gelen ışık ölçümü"
                    b.lastSessionButton.visibility = if (store.latest() != null) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // ------------------------------------------------------------------ bilgi

    private fun refreshCapabilities() {
        val id = cameraId()
        if (id == null) {
            b.capabilityInfo.text = "Kamera listelenemedi."
            return
        }
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val c = mgr.getCameraCharacteristics(id)
            val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
            val manual = LightMeter.supportsManualExposure(this, id)
            val raw = LightMeter.supportsRaw(this, id)
            b.capabilityInfo.text = buildString {
                append("kamera $id · ")
                append(if (settings.useFrontCamera) "ön" else "arka")
                append("\nelle pozlama: ").append(if (manual) "var" else "yok")
                append(" · RAW: ").append(if (raw) "var" else "yok")
                ap?.let { append("\ndiyafram: f/").append(String.format(Locale.US, "%.1f", it)) }
                iso?.let { append("\nISO: ").append(it.lower).append("–").append(it.upper) }
                exp?.let {
                    append("\nenstantane: ")
                    append(exposureText(it.lower / 1e9)).append(" – ").append(exposureText(it.upper / 1e9))
                }
                if (!manual) append("\n\nElle pozlama olmadan ölçüm otomatik pozlama verisinden yapılır; hassasiyet düşer.")
                append("\n\n").append(sensors.describe())
            }
        } catch (e: Exception) {
            b.capabilityInfo.text = "Kamera özellikleri okunamadı: ${e.message}"
        }
    }

    private fun updateHowTo() {
        val sameFaceSensor = if (settings.useFrontCamera) sensors.frontSensor else sensors.rearSensor
        val sensorWarning = if (sameFaceSensor != null) {
            "\n• ÖNEMLİ: Kağıt yalnızca lensi kapatsın, ışık sensörünü kapatmasın. " +
                "Bu telefonda kameranın olduğu yüzde bir ışık sensörü var (${sameFaceSensor.name}) ve " +
                "kalibrasyon referansı o. Kağıt ikisini birden örterse kamera da sensör de aynı oranda " +
                "kararır, kazanç 1'e yakın çıkar ve kalibrasyon kendini iptal eder — hata vermeden " +
                "yanlış sonuç üretir. Kağıdı küçük kes, sadece objektifin üstüne bantla."
        } else ""

        val common = "• Beyaz fotokopi kağıdından tek kat, küçük bir parça kes ve objektifin üstüne " +
            "bantla. Kağıt lense yapışık olmalı ve objektifi tamamen kapatmalı — ama sadece objektifi.\n" +
            "• Kalibrasyonu hangi kağıtla yaptıysan ölçümü de onunla yap; kağıt değişirse yeniden kalibre et.\n" +
            "• Kağıdın üstüne elinle gölge düşürme." + sensorWarning
        val specific = when (mode) {
            SleepAssessment.Context.SLEEP ->
                "\n• Uyku ölçümü: telefonu yastığın yanına, kamera tavana bakacak şekilde yatır. " +
                    "Işıkları uyurken nasılsa öyle bırak, odadan çık ve kapıyı kapat.\n" +
                    "• Not: göz kapağı ışığın büyük kısmını keser, yani retinaya ulaşan doz oda " +
                    "değerinden düşüktür. Öneri eşikleri de zaten oda seviyesi için tanımlı."
            SleepAssessment.Context.EVENING ->
                "\n• Akşam ölçümü: telefonu göz hizasında, normalde baktığın yöne doğru dik tut. " +
                    "Öneri eşikleri göz hizasında, dikey düzlemde tanımlı."
            SleepAssessment.Context.DAY ->
                "\n• Gündüz ölçümü: telefonu göz hizasında, baktığın yöne doğru dik tut. " +
                    "Pencereye dönükken ve odanın içine dönükken ayrı ayrı ölç; fark genelde 10 katın üzerindedir."
        }
        b.howToText.text = common + specific
    }

    // ---------------------------------------------------------------- yardımcı

    private fun fmt(v: Double): String = when {
        v >= 100 -> String.format(Locale.US, "%.0f", v)
        v >= 10 -> String.format(Locale.US, "%.1f", v)
        v >= 1 -> String.format(Locale.US, "%.2f", v)
        else -> String.format(Locale.US, "%.3f", v)
    }

    private fun exposureText(seconds: Double): String =
        if (seconds >= 1.0) String.format(Locale.US, "%.2f s", seconds)
        else "1/" + String.format(Locale.US, "%.0f", 1.0 / seconds) + " s"

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
