package com.alterra.uykuisigi.camera

import android.hardware.camera2.CameraMetadata
import android.media.Image
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Bir kareden çıkarılan doğrusal (linear) istatistikler.
 * Tüm değerler siyah seviyesi çıkarılmış ve 0..1 aralığına normalize edilmiş haldedir.
 */
data class FrameStats(
    val linearY: Double,
    val linearR: Double,
    val linearG: Double,
    val linearB: Double,
    val clippedFraction: Double,
    val sampleCount: Int
)

object FrameAnalyzer {

    /** sRGB elektro-optik transfer fonksiyonunun tersi; 8 bit için önceden hesaplanmış tablo. */
    private val SRGB_TO_LINEAR = DoubleArray(256) { i ->
        val c = i / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Merkez bölgenin karenin kenarına oranı. Kağıt tüm kadrajı kaplasa da kenar vinyetlemesini dışlıyoruz. */
    private const val CENTER = 0.6

    /** Her eksende bu kadar örnek alınır; kare boyutundan bağımsız sabit maliyet. */
    private const val SAMPLES_PER_AXIS = 120

    // ------------------------------------------------------------------ YUV

    /**
     * @param linearTonemap cihazda doğrusal tonemap eğrisi zorlanabildiyse true.
     *        Bu durumda Y zaten doğrusaldır ve sRGB ters dönüşümü uygulanmaz.
     */
    fun analyzeYuv(image: Image, linearTonemap: Boolean): FrameStats {
        val w = image.width
        val h = image.height
        val x0 = (w * (1 - CENTER) / 2).toInt()
        val x1 = (w * (1 + CENTER) / 2).toInt()
        val y0 = (h * (1 - CENTER) / 2).toInt()
        val y1 = (h * (1 + CENTER) / 2).toInt()

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride

        val stepX = max(1, (x1 - x0) / SAMPLES_PER_AXIS)
        val stepY = max(1, (y1 - y0) / SAMPLES_PER_AXIS)

        var sumLin = 0.0
        var sumY8 = 0.0
        var clipped = 0
        var n = 0

        var row = y0
        while (row < y1) {
            val base = row * yRow
            var col = x0
            while (col < x1) {
                val idx = base + col * yPix
                if (idx < yBuf.limit()) {
                    val v = yBuf.get(idx).toInt() and 0xFF
                    sumLin += if (linearTonemap) v / 255.0 else SRGB_TO_LINEAR[v]
                    sumY8 += v
                    if (v >= 250) clipped++
                    n++
                }
                col += stepX
            }
            row += stepY
        }
        if (n == 0) return FrameStats(0.0, 0.0, 0.0, 0.0, 0.0, 0)

        val meanLin = sumLin / n
        val meanY = sumY8 / n

        // Renk için U/V ortalamaları. Kağıt sayesinde alan tekdüze olduğundan
        // ortalama krominanstan RGB'ye dönüşüm yeterince doğru.
        val meanU = planeMean(image.planes[1], w / 2, h / 2)
        val meanV = planeMean(image.planes[2], w / 2, h / 2)

        val r8 = clamp8(meanY + 1.402 * (meanV - 128.0))
        val g8 = clamp8(meanY - 0.344136 * (meanU - 128.0) - 0.714136 * (meanV - 128.0))
        val b8 = clamp8(meanY + 1.772 * (meanU - 128.0))

        val lin: (Double) -> Double = { v ->
            if (linearTonemap) v / 255.0 else SRGB_TO_LINEAR[v.toInt().coerceIn(0, 255)]
        }

        return FrameStats(
            linearY = meanLin,
            linearR = lin(r8),
            linearG = lin(g8),
            linearB = lin(b8),
            clippedFraction = clipped.toDouble() / n,
            sampleCount = n
        )
    }

    private fun planeMean(plane: Image.Plane, w: Int, h: Int): Double {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val x0 = (w * (1 - CENTER) / 2).toInt()
        val x1 = (w * (1 + CENTER) / 2).toInt()
        val y0 = (h * (1 - CENTER) / 2).toInt()
        val y1 = (h * (1 + CENTER) / 2).toInt()
        val stepX = max(1, (x1 - x0) / (SAMPLES_PER_AXIS / 2))
        val stepY = max(1, (y1 - y0) / (SAMPLES_PER_AXIS / 2))
        var sum = 0.0
        var n = 0
        var row = y0
        while (row < y1) {
            val base = row * rowStride
            var col = x0
            while (col < x1) {
                val idx = base + col * pixStride
                if (idx < buf.limit()) {
                    sum += (buf.get(idx).toInt() and 0xFF)
                    n++
                }
                col += stepX
            }
            row += stepY
        }
        return if (n == 0) 128.0 else sum / n
    }

    private fun clamp8(v: Double) = min(255.0, max(0.0, v))

    // ------------------------------------------------------------------ RAW

    /**
     * RAW_SENSOR (Bayer, 16 bit) karesini çözer. Bu yol tercih edilir: veriler doğrusaldır,
     * ISP'nin ton eğrisi, gürültü azaltma ve gölge kaldırma müdahaleleri yoktur. Özellikle
     * 1 lüx altındaki ölçümlerde fark yaratan şey budur.
     *
     * @param blackLevel CFA sırasına göre 4 kanal siyah seviyesi
     * @param whiteLevel doygunluk seviyesi
     * @param cfa SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     */
    fun analyzeRaw(image: Image, blackLevel: FloatArray, whiteLevel: Int, cfa: Int): FrameStats {
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val pixStride = if (plane.pixelStride > 0) plane.pixelStride else 2

        val w = image.width
        val h = image.height
        // 2x2 blok hizası bozulmasın diye başlangıçları çift sayıya yuvarlıyoruz.
        val x0 = ((w * (1 - CENTER) / 2).toInt()) and 1.inv()
        val x1 = ((w * (1 + CENTER) / 2).toInt()) and 1.inv()
        val y0 = ((h * (1 - CENTER) / 2).toInt()) and 1.inv()
        val y1 = ((h * (1 + CENTER) / 2).toInt()) and 1.inv()

        val stepX = max(2, (((x1 - x0) / SAMPLES_PER_AXIS) and 1.inv()))
        val stepY = max(2, (((y1 - y0) / SAMPLES_PER_AXIS) and 1.inv()))

        // pos 0..3 = (satir%2)*2 + (sutun%2)
        val sums = DoubleArray(4)
        val counts = IntArray(4)
        var clipped = 0
        var total = 0

        var row = y0
        while (row < y1 - 1) {
            val base0 = row * rowStride
            val base1 = (row + 1) * rowStride
            var col = x0
            while (col < x1 - 1) {
                val v00 = read16(buf, base0 + col * pixStride)
                val v01 = read16(buf, base0 + (col + 1) * pixStride)
                val v10 = read16(buf, base1 + col * pixStride)
                val v11 = read16(buf, base1 + (col + 1) * pixStride)
                if (v00 >= 0 && v01 >= 0 && v10 >= 0 && v11 >= 0) {
                    sums[0] = sums[0] + v00.toDouble()
                    sums[1] = sums[1] + v01.toDouble()
                    sums[2] = sums[2] + v10.toDouble()
                    sums[3] = sums[3] + v11.toDouble()
                    for (k in 0 until 4) counts[k] = counts[k] + 1
                    val sat = whiteLevel * 0.98
                    if (v00 >= sat || v01 >= sat || v10 >= sat || v11 >= sat) clipped++
                    total++
                }
                col += stepX
            }
            row += stepY
        }
        if (total == 0) return FrameStats(0.0, 0.0, 0.0, 0.0, 0.0, 0)

        // Siyah seviyesi çıkar ve normalize et.
        val norm = DoubleArray(4)
        for (i in 0 until 4) {
            val bl = blackLevel.getOrElse(i) { blackLevel.getOrElse(0) { 64f } }.toDouble()
            val span = (whiteLevel - bl).coerceAtLeast(1.0)
            norm[i] = ((sums[i] / counts[i].coerceAtLeast(1)) - bl).coerceAtLeast(0.0) / span
        }

        // CFA düzenine göre pozisyonları kanallara eşle.
        // pos indeksleri: 0=(0,0) 1=(0,1) 2=(1,0) 3=(1,1)
        val (rIdx, grIdx, gbIdx, bIdx) = when (cfa) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> Quad(0, 1, 2, 3)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> Quad(1, 0, 3, 2)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> Quad(2, 3, 0, 1)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> Quad(3, 2, 1, 0)
            else -> Quad(0, 1, 2, 3)
        }

        val r = norm[rIdx]
        val g = (norm[grIdx] + norm[gbIdx]) / 2.0
        val b = norm[bIdx]

        // Yeşil kanal, bir Bayer sensöründe V(lambda) fotopik eğrisine en yakın tek kanaldır;
        // aydınlık hesabında onu kullanıyoruz. Spektral sapma kalibrasyon kazancına gömülüyor.
        return FrameStats(
            linearY = g,
            linearR = r,
            linearG = g,
            linearB = b,
            clippedFraction = clipped.toDouble() / total,
            sampleCount = total
        )
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)

    private fun read16(buf: java.nio.ByteBuffer, index: Int): Int {
        if (index < 0 || index + 1 >= buf.limit()) return -1
        return buf.getShort(index).toInt() and 0xFFFF
    }
}
