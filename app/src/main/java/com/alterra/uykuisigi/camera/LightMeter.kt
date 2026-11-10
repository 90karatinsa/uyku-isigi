package com.alterra.uykuisigi.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.alterra.uykuisigi.photometry.Photometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kamerayı bir gelen ışık ölçere dönüştüren Camera2 motoru.
 *
 * Akış:
 *  1. Kamerayı aç, elle pozlama yeteneği var mı bak.
 *  2. Menzil arama: pozlamayı, ortalama doğrusal seviye orta griye yaklaşana kadar
 *     yinelemeli olarak ayarla. Önce enstantaneyi uzat (telefon sabit duracağı için
 *     hareket bulanıklığı sorun değil), yetmezse ISO yükselt. Böylece gürültü en az.
 *  3. Yakınsayınca birkaç kare daha çekip ortalamasını al (özellikle 1 lüx altında
 *     tek kare gürültüsü ölçümü bozar).
 *  4. Beyaz dengesi gün ışığına sabitlenmiş tek bir kare daha çekip renk sıcaklığını
 *     kestir; melanopik dönüşüm bunu kullanıyor.
 *
 * RAW_SENSOR destekleniyorsa aydınlık ölçümü RAW üzerinden yapılır: veri doğrusaldır,
 * ISP'nin ton eğrisi ve gürültü azaltması devrede değildir.
 */
class LightMeter(private val context: Context) {

    data class Options(
        val cameraId: String,
        val preferRaw: Boolean = true,
        val maxExposureSeconds: Double = 2.0,
        val averagingFrames: Int = 4,
        val calibrationGain: Double = Photometry.DEFAULT_CALIBRATION_GAIN,
        val darkOffsetLinear: Double = 0.0,
        val darkOffsetProduct: Double = 0.0
    )

    data class Reading(
        val lux: Double,
        val meanLinear: Double,
        val exposureSeconds: Double,
        val iso: Int,
        val aperture: Double,
        val cct: Double?,
        val usedRaw: Boolean,
        val manualControl: Boolean,
        val atSensitivityLimit: Boolean,
        val belowFloor: Boolean,
        val clipped: Boolean,
        val timestampMs: Long
    ) {
        val ev100: Double get() = Photometry.ev100(aperture, exposureSeconds, iso)
    }

    class MeterException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Cihazın gerçekten kabul ettiği istek anahtarları.
     *
     * Camera2'de desteklenmeyen bir mod değeri göndermek her HAL'de sessizce yok
     * sayılmıyor; bir kısmı çekimi tamamen reddediyor. Bu yüzden her modu ayarlamadan
     * önce cihazın listesinde olup olmadığına bakıyoruz.
     */
    private data class DeviceCaps(
        val manualPost: Boolean,
        val awbOff: Boolean,
        val awbDaylight: Boolean,
        val afOff: Boolean,
        val noiseOff: Boolean,
        val edgeOff: Boolean,
        val aberrationOff: Boolean,
        val contrastCurve: Boolean,
        val rawBoost: Boolean
    )

    private fun readCaps(chars: CameraCharacteristics, manualPost: Boolean): DeviceCaps {
        fun has(key: CameraCharacteristics.Key<IntArray>, value: Int): Boolean =
            chars.get(key)?.contains(value) ?: false
        return DeviceCaps(
            manualPost = manualPost,
            awbOff = manualPost && has(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES, CameraMetadata.CONTROL_AWB_MODE_OFF),
            awbDaylight = has(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            afOff = has(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, CameraMetadata.CONTROL_AF_MODE_OFF),
            noiseOff = has(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CameraMetadata.NOISE_REDUCTION_MODE_OFF),
            edgeOff = has(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_OFF),
            aberrationOff = has(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF),
            contrastCurve = manualPost && has(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE),
            rawBoost = chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE) != null
        )
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    companion object {
        private const val TARGET_MEAN = 0.18
        private const val ACCEPT_LOW = 0.05
        private const val ACCEPT_HIGH = 0.45
        private const val MAX_ITERATIONS = 8
        private const val NOISE_FLOOR = 0.0025

        /** Kare ortalamaya ayrılan azami süre; uzun pozlamada kare sayısını kısar. */
        private const val AVERAGING_BUDGET_SECONDS = 6.0

        /** Işık sensörüyle aynı yüzeyde olduğu için varsayılan ön kamera. */
        fun defaultCameraId(context: Context): String? = pickCamera(context, front = true)

        fun pickCamera(context: Context, front: Boolean): String? {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            return try {
                mgr.cameraIdList.firstOrNull {
                    mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
                } ?: mgr.cameraIdList.firstOrNull()
            } catch (e: Exception) {
                null
            }
        }

        fun supportsManualExposure(context: Context, cameraId: String): Boolean = try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val caps = mgr.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        } catch (e: Exception) {
            false
        }

        fun supportsRaw(context: Context, cameraId: String): Boolean = try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val caps = mgr.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ ölçüm

    @SuppressLint("MissingPermission")
    suspend fun measure(options: Options, onProgress: (String) -> Unit = {}): Reading =
        withContext(Dispatchers.IO) {
            val thread = HandlerThread("lightmeter").apply { start() }
            val handler = Handler(thread.looper)
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var yuvReader: ImageReader? = null
            var rawReader: ImageReader? = null
            try {
                val chars = manager.getCameraCharacteristics(options.cameraId)
                val hwCaps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val manual = hwCaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                val hasRaw = hwCaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                val manualPost = hwCaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
                val useRaw = hasRaw && options.preferRaw && manual

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw MeterException("Kamera akış yapılandırması okunamadı.")

                val yuvSize = pickSmallYuvSize(map.getOutputSizes(ImageFormat.YUV_420_888))
                yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2)
                val yuvQueue = LinkedBlockingQueue<Image>(2)
                yuvReader.setOnImageAvailableListener({ r ->
                    r.acquireNextImage()?.let { if (!yuvQueue.offer(it)) it.close() }
                }, handler)

                val rawQueue = LinkedBlockingQueue<Image>(2)
                if (useRaw) {
                    val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                    if (rawSizes != null && rawSizes.isNotEmpty()) {
                        val rs = rawSizes.maxByOrNull { it.width.toLong() * it.height }!!
                        rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2)
                        rawReader.setOnImageAvailableListener({ r ->
                            r.acquireNextImage()?.let { if (!rawQueue.offer(it)) it.close() }
                        }, handler)
                    }
                }
                onProgress("Kamera açılıyor")
                device = openCamera(options.cameraId, handler)

                val yuv = yuvReader
                val surfaces = listOfNotNull(yuv.surface, rawReader?.surface)
                val activeSession = try {
                    createSession(device, surfaces, handler)
                } catch (e: Exception) {
                    // RAW + YUV birlikte reddedilmiş olabilir; sadece YUV ile tekrar dene.
                    rawReader?.close()
                    rawReader = null
                    createSession(device, listOf(yuv.surface), handler)
                }
                session = activeSession
                val rawSurface = rawReader?.surface

                // --- sensör sınırları
                val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val aperture = apertures?.firstOrNull()?.toDouble() ?: 2.0
                val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                    ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
                val blackPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                val staticBlack = FloatArray(4) { i ->
                    blackPattern?.getOffsetForIndex(i % 2, i / 2)?.toFloat() ?: 64f
                }

                val isoMin = isoRange?.lower ?: 100
                val isoMax = isoRange?.upper ?: 800
                val expMinNs = expRange?.lower ?: 100_000L
                val expMaxNs = min(
                    expRange?.upper ?: 100_000_000L,
                    (options.maxExposureSeconds * 1e9).toLong()
                )
                val expMin = expMinNs / 1e9
                val expMax = expMaxNs / 1e9
                val maxFrameDurationNs = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: expMaxNs

                val caps = readCaps(chars, manualPost)
                // YUV yolunda Y'nin doğrusal sayılabilmesi için ton eğrisinin gerçekten
                // doğrusala sabitlenebilmesi gerekiyor; sabitlenemiyorsa sRGB tersi uygulanır.
                val linearTonemap = caps.contrastCurve

                // --- ölçüm
                val reading: Reading
                if (!manual) {
                    onProgress("Otomatik pozlama bekleniyor")
                    reading = measureAuto(
                        activeSession, yuvQueue, yuv.surface, handler, options, aperture, linearTonemap, caps
                    )
                } else {
                    onProgress("Işık seviyesi aranıyor")
                    reading = measureManual(
                        session = activeSession,
                        handler = handler,
                        options = options,
                        yuvQueue = yuvQueue,
                        rawQueue = rawQueue,
                        yuvSurface = yuv.surface,
                        rawSurface = rawSurface,
                        aperture = aperture,
                        isoMin = isoMin, isoMax = isoMax,
                        expMin = expMin, expMax = expMax,
                        maxFrameDurationNs = maxFrameDurationNs,
                        whiteLevel = whiteLevel,
                        cfa = cfa,
                        staticBlack = staticBlack,
                        linearTonemap = linearTonemap,
                        caps = caps,
                        onProgress = onProgress
                    )
                }
                reading
            } finally {
                try { session?.close() } catch (_: Exception) {}
                try { device?.close() } catch (_: Exception) {}
                try { yuvReader?.close() } catch (_: Exception) {}
                try { rawReader?.close() } catch (_: Exception) {}
                thread.quitSafely()
            }
        }

    // ------------------------------------------------------------- elle sürüş

    private fun measureManual(
        session: CameraCaptureSession,
        handler: Handler,
        options: Options,
        yuvQueue: LinkedBlockingQueue<Image>,
        rawQueue: LinkedBlockingQueue<Image>,
        yuvSurface: android.view.Surface,
        rawSurface: android.view.Surface?,
        aperture: Double,
        isoMin: Int, isoMax: Int,
        expMin: Double, expMax: Double,
        maxFrameDurationNs: Long,
        whiteLevel: Int,
        cfa: Int,
        staticBlack: FloatArray,
        linearTonemap: Boolean,
        caps: DeviceCaps,
        onProgress: (String) -> Unit
    ): Reading {
        var expSec = (1.0 / 60.0).coerceIn(expMin, expMax)
        var iso = isoMin.coerceAtLeast(100).coerceAtMost(isoMax)
        var mean = 0.0
        var clippedFrac = 0.0
        var atLimit = false
        var lastBlack = staticBlack

        for (iteration in 0 until MAX_ITERATIONS) {
            onProgress("Pozlama ayarlanıyor (${iteration + 1})")
            val (image, result) = capture(
                session, handler,
                target = rawSurface ?: yuvSurface,
                exposureNs = (expSec * 1e9).toLong(),
                iso = iso,
                frameDurationNs = min(max((expSec * 1e9).toLong(), 33_000_000L), maxFrameDurationNs),
                awbDaylight = false,
                caps = caps,
                queue = if (rawSurface != null) rawQueue else yuvQueue
            )
            try {
                val dyn = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                if (dyn != null && dyn.size >= 4) lastBlack = dyn
                val dynWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: whiteLevel
                val stats = if (rawSurface != null)
                    FrameAnalyzer.analyzeRaw(image, lastBlack, dynWhite, cfa)
                else
                    FrameAnalyzer.analyzeYuv(image, linearTonemap)
                mean = (stats.linearY - darkOffsetFor(options, expSec, iso)).coerceAtLeast(0.0)
                clippedFrac = stats.clippedFraction
            } finally {
                image.close()
            }

            val tooDark = mean < ACCEPT_LOW
            val tooBright = mean > ACCEPT_HIGH || clippedFrac > 0.01
            if (!tooDark && !tooBright) break

            val product = expSec * iso
            var factor = TARGET_MEAN / max(mean, NOISE_FLOOR / 2)
            factor = factor.coerceIn(1.0 / 64.0, 64.0)
            val wanted = product * factor
            val (newExp, newIso, limited) = allocate(wanted, expMin, expMax, isoMin, isoMax)
            atLimit = limited && tooDark
            if (newExp == expSec && newIso == iso) break
            expSec = newExp
            iso = newIso
            if (atLimit && iteration > 0) break
        }

        // Yakınsadı; gürültüyü düşürmek için birkaç kare daha alıp ortalamayı güncelle.
        // Uzun pozlamada kare sayısı bir zaman bütçesiyle sınırlanıyor: 16 saniyelik
        // pozlamada 4 kare ortalamak ölçümü bir dakikadan uzun sürdürürdü.
        val frameBudget = options.averagingFrames
            .coerceAtMost(max(1, (AVERAGING_BUDGET_SECONDS / max(expSec, 0.05)).toInt()))
        if (frameBudget > 1) {
            onProgress("Kareler ortalanıyor")
            var sum = mean
            var n = 1
            repeat(frameBudget - 1) {
                val (img, res) = capture(
                    session, handler,
                    target = rawSurface ?: yuvSurface,
                    exposureNs = (expSec * 1e9).toLong(),
                    iso = iso,
                    frameDurationNs = min(max((expSec * 1e9).toLong(), 33_000_000L), maxFrameDurationNs),
                    awbDaylight = false,
                    caps = caps,
                    queue = if (rawSurface != null) rawQueue else yuvQueue
                )
                try {
                    val dyn = res.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                    val black = if (dyn != null && dyn.size >= 4) dyn else lastBlack
                    val dynWhite = res.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: whiteLevel
                    val s = if (rawSurface != null)
                        FrameAnalyzer.analyzeRaw(img, black, dynWhite, cfa)
                    else
                        FrameAnalyzer.analyzeYuv(img, linearTonemap)
                    sum += (s.linearY - darkOffsetFor(options, expSec, iso)).coerceAtLeast(0.0)
                    n++
                } finally {
                    img.close()
                }
            }
            mean = sum / n
        }

        // Renk sıcaklığı için beyaz dengesi gün ışığına sabit tek kare.
        onProgress("Renk sıcaklığı ölçülüyor")
        var cct: Double? = null
        try {
            val (img, _) = capture(
                session, handler,
                target = yuvSurface,
                exposureNs = (expSec * 1e9).toLong(),
                iso = iso,
                frameDurationNs = min(max((expSec * 1e9).toLong(), 33_000_000L), maxFrameDurationNs),
                awbDaylight = true,
                caps = caps,
                queue = yuvQueue
            )
            try {
                val s = FrameAnalyzer.analyzeYuv(img, false)
                if (s.linearY > 0.004) {
                    cct = Photometry.cctFromLinearRgb(s.linearR, s.linearG, s.linearB)
                }
            } finally {
                img.close()
            }
        } catch (e: Exception) {
            cct = null
        }

        val belowFloor = mean < NOISE_FLOOR && atLimit
        // Taban altındaysak gerçek değeri bilemeyiz; gürültü tabanını kullanıp bunu
        // bir üst sınır olarak raporluyoruz.
        val effectiveMean = if (belowFloor) NOISE_FLOOR else mean
        val lux = Photometry.lux(aperture, expSec, iso, effectiveMean, options.calibrationGain)

        return Reading(
            lux = lux,
            meanLinear = mean,
            exposureSeconds = expSec,
            iso = iso,
            aperture = aperture,
            cct = cct,
            usedRaw = rawSurface != null,
            manualControl = true,
            atSensitivityLimit = atLimit,
            belowFloor = belowFloor,
            clipped = clippedFrac > 0.01,
            timestampMs = System.currentTimeMillis()
        )
    }

    /**
     * Karanlık referansını ölçüm anındaki pozlamaya ölçekler.
     *
     * Karanlık akımı hem pozlama süresiyle hem de sensör kazancıyla büyüdüğü için,
     * 2 saniye / ISO 3200'de ölçülen bir ofseti 1/60 s / ISO 100'lük bir kareden
     * olduğu gibi çıkarmak ölçümü yok ederdi. Oran, referansın alındığı t*ISO
     * çarpımına göre kuruluyor ve aşırı dışdeğerlemeyi önlemek için sınırlanıyor.
     */
    private fun darkOffsetFor(options: Options, expSec: Double, iso: Int): Double {
        if (options.darkOffsetLinear <= 0.0) return 0.0
        if (options.darkOffsetProduct <= 0.0) return options.darkOffsetLinear
        val ratio = (expSec * iso) / options.darkOffsetProduct
        return options.darkOffsetLinear * ratio.coerceIn(0.0, 1.5)
    }

    /**
     * Pozlama çarpımını (t * ISO) enstantane ve ISO'ya böler.
     * Telefon sabit durduğu için önce enstantaneyi uzatıyoruz: aynı pozlamayı düşük
     * ISO ile almak gürültüyü belirgin biçimde azaltır.
     */
    private fun allocate(
        product: Double, expMin: Double, expMax: Double, isoMin: Int, isoMax: Int
    ): Triple<Double, Int, Boolean> {
        var t = product / isoMin
        if (t <= expMax) {
            t = t.coerceAtLeast(expMin)
            return Triple(t, isoMin, false)
        }
        t = expMax
        val wantIso = (product / t).roundToInt()
        val iso = wantIso.coerceIn(isoMin, isoMax)
        return Triple(t, iso, wantIso > isoMax)
    }

    // --------------------------------------------------- otomatik pozlama yolu

    private fun measureAuto(
        session: CameraCaptureSession,
        yuvQueue: LinkedBlockingQueue<Image>,
        yuvSurface: android.view.Surface,
        handler: Handler,
        options: Options,
        fallbackAperture: Double,
        linearTonemap: Boolean,
        caps: DeviceCaps
    ): Reading {
        var rawMean = 0.0
        var exposure = 0.0
        var iso = 0
        var aperture = fallbackAperture
        var cct: Double? = null
        var clipped = false

        // Otomatik pozlamanın oturması için birkaç kare.
        repeat(6) { i ->
            val (img, res) = captureAuto(session, handler, yuvSurface, yuvQueue, caps)
            try {
                val s = FrameAnalyzer.analyzeYuv(img, linearTonemap)
                rawMean = s.linearY
                clipped = s.clippedFraction > 0.01
                if (i >= 3) cct = Photometry.cctFromLinearRgb(s.linearR, s.linearG, s.linearB)
            } finally {
                img.close()
            }
            exposure = (res.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1e9
            iso = res.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            aperture = res.get(CaptureResult.LENS_APERTURE)?.toDouble() ?: fallbackAperture
        }

        if (exposure <= 0.0 || iso <= 0) {
            throw MeterException(
                "Bu kamera pozlama bilgisini vermiyor. Ayarlardan arka kamerayı seç ya da " +
                    "telefonun kendi ışık sensörü modunu kullan."
            )
        }

        val mean = (rawMean - darkOffsetFor(options, exposure, iso)).coerceAtLeast(0.0)
        val lux = Photometry.lux(aperture, exposure, iso, mean, options.calibrationGain)
        return Reading(
            lux = lux,
            meanLinear = mean,
            exposureSeconds = exposure,
            iso = iso,
            aperture = aperture,
            cct = cct,
            usedRaw = false,
            manualControl = false,
            atSensitivityLimit = false,
            belowFloor = false,
            clipped = clipped,
            timestampMs = System.currentTimeMillis()
        )
    }

    // ------------------------------------------------------------ Camera2 yardımcıları

    private fun pickSmallYuvSize(sizes: Array<Size>?): Size {
        if (sizes == null || sizes.isEmpty()) return Size(640, 480)
        // Ortalama almak için büyük çözünürlüğe gerek yok; küçük kare hem hızlı hem az bellek.
        return sizes.filter { it.width >= 320 && it.height >= 240 }
            .minByOrNull { it.width.toLong() * it.height } ?: sizes.first()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(cameraId: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(MeterException("Kamera bağlantısı kesildi."))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val msg = when (error) {
                        ERROR_CAMERA_IN_USE -> "Kamera başka bir uygulama tarafından kullanılıyor."
                        ERROR_MAX_CAMERAS_IN_USE -> "Açık kamera sayısı sınırda."
                        ERROR_CAMERA_DISABLED -> "Kamera cihaz politikası ile kapatılmış."
                        else -> "Kamera açılamadı (kod $error)."
                    }
                    if (cont.isActive) cont.resumeWithException(MeterException(msg))
                }
            }, handler)
        }

    @Suppress("DEPRECATION")
    private suspend fun createSession(
        device: CameraDevice,
        surfaces: List<android.view.Surface>,
        handler: Handler
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resume(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(MeterException("Kamera oturumu kurulamadı."))
            }
        }, handler)
    }

    private fun capture(
        session: CameraCaptureSession,
        handler: Handler,
        target: android.view.Surface,
        exposureNs: Long,
        iso: Int,
        frameDurationNs: Long,
        awbDaylight: Boolean,
        caps: DeviceCaps,
        queue: LinkedBlockingQueue<Image>
    ): Pair<Image, TotalCaptureResult> {
        drain(queue)
        val b = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(target)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        if (caps.afOff) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        if (caps.noiseOff) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        if (caps.edgeOff) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        if (caps.aberrationOff) {
            b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
        }
        // Ham duyarlılık takviyesi, RAW ile YUV arasındaki kazanç ilişkisini bozar; kapatıyoruz.
        if (caps.rawBoost) b.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)

        if (awbDaylight) {
            // Renk kestirimi: beyaz dengesi bilinen bir noktaya sabitlenir.
            if (caps.awbDaylight) b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
        } else {
            // AWB_MODE_OFF yalnızca elle son işleme destekleyen cihazlarda garanti;
            // yoksa gün ışığı ön ayarı da yeterince belirlenimci bir sabittir.
            if (caps.awbOff) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            } else if (caps.awbDaylight) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
            }
            if (caps.manualPost && caps.awbOff) {
                // Nötr kazanç + birim matris: ISP'nin renk müdahalesi devre dışı.
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(1f, 1f, 1f, 1f))
                b.set(
                    CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    ColorSpaceTransform(
                        intArrayOf(
                            1, 1, 0, 1, 0, 1,
                            0, 1, 1, 1, 0, 1,
                            0, 1, 0, 1, 1, 1
                        )
                    )
                )
                // Doğrusal ton eğrisi: YUV yolunda Y doğrudan doğrusal hale gelir.
                if (caps.contrastCurve) {
                    b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    val linear = floatArrayOf(0f, 0f, 1f, 1f)
                    b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(linear, linear, linear))
                }
            }
        }

        return submit(session, b.build(), handler, queue, exposureNs)
    }

    private fun captureAuto(
        session: CameraCaptureSession,
        handler: Handler,
        target: android.view.Surface,
        queue: LinkedBlockingQueue<Image>,
        caps: DeviceCaps
    ): Pair<Image, TotalCaptureResult> {
        drain(queue)
        val b = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(target)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        if (caps.awbDaylight) b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
        return submit(session, b.build(), handler, queue, 200_000_000L)
    }

    private fun submit(
        session: CameraCaptureSession,
        request: CaptureRequest,
        handler: Handler,
        queue: LinkedBlockingQueue<Image>,
        exposureNs: Long
    ): Pair<Image, TotalCaptureResult> {
        val results = LinkedBlockingQueue<Any>(1)
        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult
            ) {
                results.offer(result)
            }

            override fun onCaptureFailed(
                s: CameraCaptureSession, r: CaptureRequest, failure: android.hardware.camera2.CaptureFailure
            ) {
                results.offer(MeterException("Kare alınamadı (sebep ${failure.reason})."))
            }
        }, handler)

        val timeoutMs = (exposureNs / 1_000_000L) + 8_000L
        val res = results.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw MeterException("Kamera yanıt vermedi.")
        if (res is MeterException) throw res
        val image = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw MeterException("Görüntü verisi alınamadı.")
        return Pair(image, res as TotalCaptureResult)
    }

    private fun drain(queue: LinkedBlockingQueue<Image>) {
        while (true) {
            val img = queue.poll() ?: break
            img.close()
        }
    }
}
