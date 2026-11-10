package com.alterra.uykuisigi

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.alterra.uykuisigi.data.Settings
import com.alterra.uykuisigi.databinding.ActivitySessionBinding
import com.alterra.uykuisigi.logging.SessionStore
import com.alterra.uykuisigi.photometry.SleepAssessment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bir gece kaydının grafiği, sayıları ve uyku yorumu. */
class SessionActivity : AppCompatActivity() {

    private lateinit var b: ActivitySessionBinding
    private lateinit var store: SessionStore
    private lateinit var settings: Settings
    private var current: File? = null

    private val dayFormat = SimpleDateFormat("d MMMM, HH:mm", Locale("tr", "TR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = SessionStore(this)
        settings = Settings(this)

        b.otherSessionsButton.setOnClickListener { pickSession() }
        b.shareButton.setOnClickListener { share() }

        load(store.latest())
    }

    private fun load(file: File?) {
        current = file
        if (file == null) {
            b.sessionRange.text = "Henüz kayıt yok."
            b.statsText.text = ""
            b.verdictCard.visibility = View.GONE
            b.shareButton.isEnabled = false
            return
        }

        val session = store.read(file)
        b.chart.setSamples(session.samples)

        if (session.samples.isEmpty()) {
            b.sessionRange.text = "${file.name} · ölçüm yok"
            b.statsText.text = "Bu kayıtta hiç ölçüm yok. Kayıt çok kısa sürmüş ya da her denemede hata alınmış olabilir."
            b.verdictCard.visibility = View.GONE
            return
        }

        val s = store.stats(session)
        b.sessionRange.text = "${dayFormat.format(Date(session.startMs))} – ${
            SimpleDateFormat("HH:mm", Locale.US).format(Date(session.endMs))
        }  ·  ${formatDuration(s.durationMinutes)}"

        b.statsText.text = buildString {
            append("ölçüm sayısı   ").append(s.count).append('\n')
            append("ortanca        ").append(fmt(s.median)).append(" lx mEDI\n")
            append("%90 dilim      ").append(fmt(s.p90)).append(" lx mEDI\n")
            append("tepe           ").append(fmt(s.max)).append(" lx mEDI\n")
            append("1 lx üstü      ").append(formatDuration(s.minutesAbove1)).append('\n')
            append("3 lx üstü      ").append(formatDuration(s.minutesAbove3)).append('\n')
            append("10 lx üstü     ").append(formatDuration(s.minutesAbove10))
        }

        val v = SleepAssessment.sessionVerdict(
            minutesAbove1 = s.minutesAbove1,
            minutesAbove10 = s.minutesAbove10,
            totalMinutes = s.durationMinutes.coerceAtLeast(1),
            median = s.median,
            max = s.max
        )
        b.verdictCard.visibility = View.VISIBLE
        b.verdictHeadline.text = v.headline
        b.verdictHeadline.setTextColor(ContextCompat.getColor(this, v.grade.colorRes))
        b.verdictDetail.text = v.detail
        b.verdictAdvice.text = v.advice
        b.shareButton.isEnabled = true
    }

    private fun pickSession() {
        val files = store.listSessions()
        if (files.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Kayıtlı gece yok.").setPositiveButton("Tamam", null).show()
            return
        }
        val labels = files.map { f ->
            "${f.name}  (${f.length() / 1024} KB)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kayıtlar")
            .setItems(labels) { _, which -> load(files[which]) }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun share() {
        val f = current ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.dosyalar", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Kaydı paylaş"))
    }

    private fun formatDuration(minutes: Int): String = when {
        minutes <= 0 -> "yok"
        minutes < 60 -> "$minutes dk"
        else -> "${minutes / 60} sa ${minutes % 60} dk"
    }

    private fun fmt(v: Double): String = when {
        v >= 100 -> String.format(Locale.US, "%.0f", v)
        v >= 10 -> String.format(Locale.US, "%.1f", v)
        v >= 1 -> String.format(Locale.US, "%.2f", v)
        else -> String.format(Locale.US, "%.3f", v)
    }
}
