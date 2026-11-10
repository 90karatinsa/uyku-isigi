package com.alterra.uykuisigi.logging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.alterra.uykuisigi.MainActivity
import com.alterra.uykuisigi.R
import com.alterra.uykuisigi.camera.LightMeter
import com.alterra.uykuisigi.data.Settings
import com.alterra.uykuisigi.photometry.Photometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Gece boyunca belirli aralıklarla ölçüm alan ön plan servisi.
 *
 * Tek bir anlık ölçüm "bu odada uyunur mu" sorusuna eksik cevap verir: sokak lambası
 * gece yarısı yanar, gün ışığı sabaha karşı sızar, biri koridor ışığını açık unutur.
 * Gerçek soru gecenin kaçta kaçının karanlık geçtiğidir; bu servis onu ölçer.
 *
 * Kamera arka planda ancak "camera" tipinde bir ön plan servisiyle açılabilir, bu yüzden
 * servis bildirimi zorunlu. Ekran kapalıyken de ölçüm sürsün diye kısmi wake lock tutuluyor;
 * bunun bedeli pil, o yüzden kullanıcıya şarjda bırakması söyleniyor.
 */
class NightSessionService : Service() {

    companion object {
        const val ACTION_START = "com.alterra.uykuisigi.START_SESSION"
        const val ACTION_STOP = "com.alterra.uykuisigi.STOP_SESSION"
        private const val CHANNEL_ID = "gece_kaydi"
        private const val NOTIF_ID = 42

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        data class State(
            val running: Boolean = false,
            val sampleCount: Int = 0,
            val lastEdi: Double? = null,
            val lastError: String? = null,
            val filePath: String? = null,
            val startedAtMs: Long = 0L
        )

        fun start(context: Context) {
            val i = Intent(context, NightSessionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NightSessionService::class.java).setAction(ACTION_STOP))
        }
    }

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var store: SessionStore
    private lateinit var settings: Settings
    private var file: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        settings = Settings(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            else -> startSession()
        }
        return START_STICKY
    }

    private fun startSession() {
        if (job?.isActive == true) return

        val f = store.createSession()
        file = f
        _state.value = State(running = true, filePath = f.absolutePath, startedAtMs = System.currentTimeMillis())

        val notification = buildNotification("Kayıt başladı", "İlk ölçüm alınıyor…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uykuisigi:gece").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }

        val cameraId = LightMeter.pickCamera(this, settings.useFrontCamera)
        if (cameraId == null) {
            _state.value = _state.value.copy(running = false, lastError = "Kullanılabilir kamera bulunamadı.")
            stopSelf()
            return
        }

        val meter = LightMeter(this)
        val options = LightMeter.Options(
            cameraId = cameraId,
            preferRaw = settings.preferRaw,
            maxExposureSeconds = settings.maxExposureSeconds.toDouble(),
            averagingFrames = settings.averagingFrames,
            calibrationGain = settings.calibrationGain(cameraId),
            darkOffsetLinear = settings.darkOffset(cameraId),
            darkOffsetProduct = settings.darkOffsetProduct(cameraId)
        )
        val intervalMs = settings.sessionIntervalMinutes.coerceAtLeast(1) * 60_000L
        val source = settings.lightSource

        val s = CoroutineScope(SupervisorJob())
        scope = s
        job = s.launch {
            var count = 0
            while (isActive) {
                try {
                    val r = meter.measure(options)
                    val edi = Photometry.melanopicEdi(r.lux, r.cct, source)
                    store.append(
                        f,
                        SessionStore.Sample(
                            timeMs = r.timestampMs,
                            lux = r.lux,
                            edi = edi,
                            cct = r.cct,
                            iso = r.iso,
                            exposureSeconds = r.exposureSeconds,
                            belowFloor = r.belowFloor
                        )
                    )
                    count++
                    _state.value = _state.value.copy(sampleCount = count, lastEdi = edi, lastError = null)
                    updateNotification(count, edi)
                } catch (e: Exception) {
                    _state.value = _state.value.copy(lastError = e.message)
                    updateNotification(count, null, e.message)
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopSession() {
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        _state.value = _state.value.copy(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        job?.cancel()
        scope?.cancel()
        _state.value = _state.value.copy(running = false)
        super.onDestroy()
    }

    // ------------------------------------------------------------- bildirim

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Gece kaydı", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gece boyunca ışık ölçümü sürerken görünen bildirim"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NightSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_light)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .build()
    }

    private fun updateNotification(count: Int, edi: Double?, error: String? = null) {
        val text = when {
            error != null -> "Son ölçüm alınamadı: $error"
            edi == null -> "$count ölçüm alındı"
            else -> String.format(Locale.US, "%d ölçüm · son değer %.2f lx mEDI", count, edi)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification("Gece kaydı sürüyor", text))
    }
}
