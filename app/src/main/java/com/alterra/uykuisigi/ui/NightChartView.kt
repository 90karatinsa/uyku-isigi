package com.alterra.uykuisigi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.alterra.uykuisigi.logging.SessionStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Gece boyu melanopik EDI grafiği.
 *
 * Y ekseni logaritmik: uykuda anlamlı aralık 0.01 lüks ile 100 lüks arasında dört
 * büyüklük mertebesine yayılıyor, doğrusal eksende 0.5 ile 2 lüks arasındaki fark
 * (ki uyku açısından belirleyici olan tam da orada) görünmez hale gelirdi.
 *
 * 1 lüks (uyku önerisi) ve 10 lüks (akşam önerisi) çizgileri referans olarak çiziliyor.
 */
class NightChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val minLux = 0.02
    private val maxLuxFloor = 20.0

    private var samples: List<SessionStore.Sample> = emptyList()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A3341"); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7FB7FF"); strokeWidth = 3f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#227FB7FF"); style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#93A1B5"); textSize = 28f
    }

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)

    fun setSamples(list: List<SessionStore.Sample>) {
        samples = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padL = 96f
        val padR = 24f
        val padT = 20f
        val padB = 48f
        val w = width - padL - padR
        val h = height - padT - padB
        if (w <= 0 || h <= 0) return

        val peak = max(samples.maxOfOrNull { it.edi } ?: 1.0, maxLuxFloor)
        val yOf: (Double) -> Float = { v ->
            val clamped = v.coerceIn(minLux, peak)
            val t = (ln(clamped) - ln(minLux)) / (ln(peak) - ln(minLux))
            (padT + h * (1.0 - t)).toFloat()
        }

        // Onluk kılavuz çizgileri ve etiketleri
        var decade = minLux
        while (decade <= peak) {
            val y = yOf(decade)
            canvas.drawLine(padL, y, padL + w, y, gridPaint)
            val label = if (decade < 1) String.format(Locale.US, "%.2f", decade) else String.format(Locale.US, "%.0f", decade)
            canvas.drawText(label, 8f, y + 10f, textPaint)
            decade *= 10
        }

        // Uyku ve akşam eşikleri
        thresholdPaint.color = Color.parseColor("#804ADE80")
        canvas.drawLine(padL, yOf(1.0), padL + w, yOf(1.0), thresholdPaint)
        thresholdPaint.color = Color.parseColor("#80FBBF24")
        canvas.drawLine(padL, yOf(10.0), padL + w, yOf(10.0), thresholdPaint)

        if (samples.size < 2) {
            canvas.drawText("Grafik için en az iki ölçüm gerekiyor", padL, padT + h / 2, textPaint)
            return
        }

        val t0 = samples.first().timeMs
        val t1 = samples.last().timeMs
        val span = max(1L, t1 - t0)
        val xOf: (Long) -> Float = { t -> (padL + w * (t - t0).toDouble() / span).toFloat() }

        val path = Path()
        val fill = Path()
        samples.forEachIndexed { i, s ->
            val x = xOf(s.timeMs)
            val y = yOf(s.edi)
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, padT + h) ; fill.lineTo(x, y) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(xOf(samples.last().timeMs), padT + h)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)

        // Saat etiketleri
        canvas.drawText(clockFormat.format(Date(t0)), padL, height - 12f, textPaint)
        val endLabel = clockFormat.format(Date(t1))
        canvas.drawText(endLabel, padL + w - textPaint.measureText(endLabel), height - 12f, textPaint)
        val midLabel = clockFormat.format(Date(t0 + span / 2))
        canvas.drawText(midLabel, padL + w / 2 - textPaint.measureText(midLabel) / 2, height - 12f, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = min(MeasureSpec.getSize(heightMeasureSpec), (resources.displayMetrics.density * 220).toInt())
        setMeasuredDimension(w, if (h <= 0) (resources.displayMetrics.density * 220).toInt() else h)
    }
}
