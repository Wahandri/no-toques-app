package com.ultrasonica.alarma

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SonarService : Service() {

    companion object {
        private const val TAG = "SonarService"

        const val ACTION_START = "com.ultrasonica.alarma.action.START"
        const val ACTION_STOP = "com.ultrasonica.alarma.action.STOP"

        const val SAMPLE_RATE = 44100
        const val MAX_DISTANCE = 50f
        const val BLOCK_SIZE = 1024
        const val COUNTDOWN_SECONDS = 10

        private const val SMOOTHING_ALPHA = 0.15
        private const val NOISE_FLOOR_ALPHA = 0.002
        private const val CALIBRATION_FRAMES = 150
        private const val GRACE_PERIOD_MS = 4000L
        private const val ALARM_HYSTERESIS_CM = 8f

        private const val UI_UPDATE_INTERVAL_MS = 100L

        private const val CHANNEL_SERVICE_ID = "sonar_service_channel"
        private const val CHANNEL_ALARM_ID = "sonar_alarm_channel"
        private const val NOTIF_SERVICE_ID = 1001
        private const val NOTIF_ALARM_ID = 1002

        private const val ALARM_TIMEOUT_MS = 10_000L
        private const val ALARM_COOLDOWN_MS = 30_000L
    }

    // ==== ESTADOS ====

    enum class AlarmState {
        NONE, ACTIVE, COOLDOWN
    }

    // ==== BINDER ====

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): SonarService = this@SonarService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ==== CALLBACKS ====

    interface SonarCallback {
        fun onDistanceUpdate(distanceCm: Float, rawMagnitude: Double)
        fun onAlarmStateChange(state: AlarmState)
        fun onCountdown(seconds: Int) {}
        fun onDetectionStarted() {}
        fun onServiceError(message: String)
    }

    private var callback: SonarCallback? = null

    fun registerCallback(cb: SonarCallback) { callback = cb }
    fun unregisterCallback() { callback = null }

    // ==== PARÁMETROS CONFIGURABLES ====

    @Volatile var frequency: Float = 20000f
    @Volatile var thresholdDistance: Float = 30f
    @Volatile var alarmPitido: Boolean = true
    @Volatile var alarmVibracion: Boolean = true
    @Volatile var alarmNotificacion: Boolean = false

    // ==== ESTADO INTERNO ====

    @Volatile private var isRunning = false
    @Volatile private var stopping = false
    @Volatile private var alarmState = AlarmState.NONE
    @Volatile private var countdownActive = false
    @Volatile private var alarmEnabled = false
    @Volatile private var lastTriggerDistance: Float = -1f
    @Volatile private var currentDistance: Float = MAX_DISTANCE
    @Volatile private var currentMagnitude: Double = 0.0

    private var emitThread: Thread? = null
    private var captureThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var alarmAudioTrack: AudioTrack? = null
    private var alarmSoundThread: Thread? = null

    private lateinit var goertzel: GoertzelFilter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRunning && !stopping) {
                callback?.onDistanceUpdate(currentDistance, currentMagnitude)
                updateServiceNotification()
                mainHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS)
            }
        }
    }

    // ==== CICLO DE VIDA ====

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        goertzel = GoertzelFilter(frequency, SAMPLE_RATE)
        goertzel.initialize(BLOCK_SIZE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning && intent?.action != ACTION_STOP) return START_STICKY

        when (intent?.action) {
            ACTION_START -> startSonarInternal()
            ACTION_STOP -> stopSonarInternal()
            else -> startSonarInternal()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSonarInternal()
        mainHandler.removeCallbacks(uiUpdateRunnable)
        super.onDestroy()
    }

    // ==== CONTROL PÚBLICO ====

    fun startSonar() {
        if (isRunning) return
        startSonarInternal()
    }

    fun stopSonar() {
        stopSonarInternal()
        stopSelf()
    }

    fun dismissAlarm() {
        stopAlarmSound()
        stopVibration()
        lastTriggerDistance = currentDistance
        alarmState = AlarmState.NONE
        callback?.onAlarmStateChange(AlarmState.NONE)
    }

    fun setThreshold(cm: Float) {
        thresholdDistance = cm.coerceIn(10f, 50f)
    }

    // ==== GETTERS ====

    fun getCurrentDistance(): Float = currentDistance
    fun getCurrentMagnitude(): Double = currentMagnitude
    fun getAlarmState(): AlarmState = alarmState
    fun isServiceRunning(): Boolean = isRunning

    // ==== INICIO CON CUENTA ATRÁS ====

    private fun startSonarInternal() {
        if (isRunning) return
        stopping = false
        isRunning = true
        countdownActive = true
        alarmEnabled = false
        alarmState = AlarmState.NONE
        lastTriggerDistance = -1f
        currentDistance = MAX_DISTANCE
        currentMagnitude = 0.0

        startForeground(NOTIF_SERVICE_ID, buildServiceNotification("Preparándose ${COUNTDOWN_SECONDS}s..."))
        doCountdown(COUNTDOWN_SECONDS)
    }

    private fun doCountdown(seconds: Int) {
        if (!isRunning || stopping) return
        if (seconds <= 0) {
            countdownActive = false
            startAudioDetection()
            return
        }
        callback?.onCountdown(seconds)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_SERVICE_ID, buildServiceNotification("Activando en ${seconds}s..."))
        mainHandler.postDelayed({ doCountdown(seconds - 1) }, 1000L)
    }

    /**
     * Inicia los hilos de audio y programa la habilitación de la alarma
     * tras un periodo de gracia para que el noise floor se estabilice.
     */
    private fun startAudioDetection() {
        goertzel = GoertzelFilter(frequency, SAMPLE_RATE)
        goertzel.initialize(BLOCK_SIZE)

        nm().notify(NOTIF_SERVICE_ID, buildServiceNotification("Vigilante activo — calibrando..."))

        emitThread = Thread({ emitLoop() }, "emit-thread").also { it.start() }
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        captureThread = Thread({ captureLoop() }, "capture-thread").also { it.start() }

        mainHandler.post(uiUpdateRunnable)
        callback?.onDetectionStarted()

        // Periodo de gracia: la alarma no se activa hasta que pase este tiempo
        mainHandler.postDelayed({
            alarmEnabled = true
            nm().notify(NOTIF_SERVICE_ID, buildServiceNotification("Vigilante activo"))
        }, GRACE_PERIOD_MS)

        Log.d(TAG, "Sonar iniciado — frecuencia=${frequency}Hz umbral=${thresholdDistance}cm")
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ==== PARADA SEGURA ====

    private fun stopSonarInternal() {
        stopping = true
        isRunning = false
        alarmEnabled = false
        alarmState = AlarmState.NONE

        mainHandler.removeCallbacksAndMessages(null)
        stopAlarmSound()
        stopVibration()

        emitThread?.interrupt()
        captureThread?.interrupt()
        alarmSoundThread?.interrupt()
        emitThread = null
        captureThread = null
        alarmSoundThread = null

        audioTrack?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
            audioTrack = null
        }
        audioRecord?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
            audioRecord = null
        }

        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        Log.d(TAG, "Sonar detenido")
    }

    // ==== EMISIÓN ====

    private fun emitLoop() {
        val bufferSize = max(
            AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
            BLOCK_SIZE * 2
        )
        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error creando AudioTrack", e)
            callback?.onServiceError("Error al crear el emisor de audio")
            return
        }

        try { audioTrack?.play() } catch (_: Exception) { return }

        val buffer = ShortArray(BLOCK_SIZE)
        var phase = 0.0
        val phaseIncrement = 2.0 * Math.PI * frequency / SAMPLE_RATE

        while (isRunning && !stopping && !Thread.currentThread().isInterrupted) {
            for (i in buffer.indices) {
                buffer[i] = (sin(phase) * Short.MAX_VALUE * 0.8).toInt().toShort()
                phase += phaseIncrement
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
            }
            try {
                audioTrack?.write(buffer, 0, buffer.size)
            } catch (e: Exception) {
                Log.e(TAG, "Error escribiendo audio", e)
                break
            }
        }
    }

    // ==== CAPTURA Y DETECCIÓN ====

    /**
     * Hilo de captura con filtro Goertzel.
     *
     * Mejoras respecto a la versión anterior:
     * - Calibración más larga (150 frames ~3.5s) para estabilizar noise floor
     * - Noise floor más lento (alpha 0.002) para evitar falsos positivos
     * - Histéresis: una vez que salta la alarma, la distancia debe superar
     *   el umbral + ALARM_HYSTERESIS_CM para que se pueda volver a disparar
     * - Comprobación de stopping antes de triggerAlarm
     */
    private fun captureLoop() {
        val bufferSize = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            BLOCK_SIZE * 2
        )
        try {
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error creando AudioRecord", e)
            callback?.onServiceError("Error al acceder al micrófono")
            return
        }
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            callback?.onServiceError("No se pudo inicializar el micrófono")
            return
        }

        audioRecord?.startRecording()
        val buffer = ShortArray(BLOCK_SIZE)

        var smoothedMagnitude = 0.0
        var noiseFloor = Double.MAX_VALUE
        var framesSinceStart = 0

        while (isRunning && !stopping && !Thread.currentThread().isInterrupted) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (bytesRead <= 0) continue

            framesSinceStart++
            val magnitude = goertzel.process(buffer)
            currentMagnitude = magnitude

            if (smoothedMagnitude == 0.0) {
                smoothedMagnitude = magnitude
                noiseFloor = magnitude
            } else {
                smoothedMagnitude = SMOOTHING_ALPHA * magnitude + (1 - SMOOTHING_ALPHA) * smoothedMagnitude
            }

            /**
             * Calibración extendida: los primeros CALIBRATION_FRAMES (~3.5s)
             * se usan para establecer un noise floor estable.
             * El noise floor solo puede bajar durante calibración (nunca sube),
             * lo que evita que el ruido de la mesa dispare falsas alarmas.
             */
            if (framesSinceStart < CALIBRATION_FRAMES) {
                if (smoothedMagnitude < noiseFloor) {
                    noiseFloor = smoothedMagnitude
                }
            } else {
                if (smoothedMagnitude < noiseFloor) {
                    noiseFloor = smoothedMagnitude
                } else {
                    noiseFloor += NOISE_FLOOR_ALPHA * (smoothedMagnitude - noiseFloor)
                }
            }

            val distance = estimateDistance(smoothedMagnitude, noiseFloor)
            currentDistance = distance

            /**
             * Lógica de alarma con histéresis y periodo de gracia:
             *
             * alarmEnabled: false durante GRACE_PERIOD_MS tras iniciar detección
             * histéresis: si la alarma ya ha sonado, la distancia debe superar
             *   el umbral en ALARM_HYSTERESIS_CM para que se pueda rearmar
             */
            if (!alarmEnabled || stopping) continue

            val effectiveThreshold = if (lastTriggerDistance < 0f) {
                thresholdDistance
            } else {
                thresholdDistance + ALARM_HYSTERESIS_CM
            }

            if (distance < thresholdDistance && alarmState == AlarmState.NONE) {
                lastTriggerDistance = distance
                triggerAlarm()
            } else if (distance >= effectiveThreshold) {
                lastTriggerDistance = -1f
            }
        }
    }

    /**
     * Convierte magnitud del filtro Goertzel en distancia.
     * Usa relación señal/ruido (magnitude / noiseFloor) para estimar.
     */
    private fun estimateDistance(smoothedMagnitude: Double, noiseFloor: Double): Float {
        if (noiseFloor <= 0 || smoothedMagnitude <= noiseFloor * 1.01) {
            return MAX_DISTANCE
        }
        val excess = smoothedMagnitude / noiseFloor
        val excessRatio = minOf(excess - 1.0, 50.0)
        val sensitivityFactor = (50.0 / thresholdDistance).coerceIn(0.5, 3.0)
        val rawDistance = MAX_DISTANCE / (1.0 + excessRatio * sensitivityFactor)
        return rawDistance.toFloat().coerceIn(0f, MAX_DISTANCE)
    }

    // ==== SISTEMA DE ALARMA ====

    private fun triggerAlarm() {
        if (alarmState != AlarmState.NONE || stopping || !isRunning) return

        alarmState = AlarmState.ACTIVE
        callback?.onAlarmStateChange(AlarmState.ACTIVE)

        if (alarmPitido) playAlarmSound()
        if (alarmVibracion) vibrateAlarm()
        if (alarmNotificacion) sendAlarmNotification()

        mainHandler.postDelayed({
            if (alarmState == AlarmState.ACTIVE) {
                stopAlarmSound()
                stopVibration()
                alarmState = AlarmState.COOLDOWN
                callback?.onAlarmStateChange(AlarmState.COOLDOWN)

                mainHandler.postDelayed({
                    if (alarmState == AlarmState.COOLDOWN && isRunning && !stopping) {
                        alarmState = AlarmState.NONE
                        if (currentDistance < thresholdDistance) {
                            triggerAlarm()
                        }
                    }
                }, ALARM_COOLDOWN_MS)
            }
        }, ALARM_TIMEOUT_MS)
    }

    private fun playAlarmSound() {
        try {
            val sampleRate = 8000
            val bufSize = max(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                2048
            )
            alarmAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            alarmAudioTrack?.play()

            val phaseInc = 2.0 * Math.PI * 2000.0 / sampleRate
            var phase = 0.0
            val toneBuf = ShortArray(sampleRate / 8)
            val silenceBuf = ShortArray(sampleRate / 8)

            for (i in toneBuf.indices) {
                toneBuf[i] = (if (sin(phase) >= 0) Short.MAX_VALUE * 0.9 else -Short.MAX_VALUE * 0.9).toInt().toShort()
                phase += phaseInc
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
            }

            alarmSoundThread = Thread {
                while (alarmState == AlarmState.ACTIVE && isRunning && !stopping) {
                    try {
                        alarmAudioTrack?.write(toneBuf, 0, toneBuf.size)
                        alarmAudioTrack?.write(silenceBuf, 0, silenceBuf.size)
                    } catch (_: Exception) { break }
                }
            }.also { it.start() }

        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo alarma", e)
        }
    }

    private fun stopAlarmSound() {
        alarmSoundThread?.interrupt()
        alarmSoundThread = null
        alarmAudioTrack?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
            alarmAudioTrack = null
        }
    }

    private fun vibrateAlarm() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 500, 300)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 1)
        }
    }

    private fun stopVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.cancel()
    }

    private fun sendAlarmNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = Notification.Builder(this, CHANNEL_ALARM_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("¡Vigilante de Mesa!")
            .setContentText("Objeto detectado a ${currentDistance.toInt()} cm")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ALARM_ID, n)
    }

    // ==== NOTIFICACIONES ====

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val svcChannel = NotificationChannel(
            CHANNEL_SERVICE_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_channel_desc)
            setSound(null, null)
        }
        nm.createNotificationChannel(svcChannel)

        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.alarm_channel_desc)
        }
        nm.createNotificationChannel(alarmChannel)
    }

    private fun buildServiceNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.servicio_ejecutando))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateServiceNotification() {
        val text = if (alarmEnabled) {
            "Distancia: ${currentDistance.toInt()} cm | Umbral: ${thresholdDistance.toInt()} cm"
        } else {
            "Calibrando... ${currentDistance.toInt()} cm"
        }
        val notif = buildServiceNotification(text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_SERVICE_ID, notif)
    }
}

class GoertzelFilter(
    private val targetFreq: Float,
    private val sampleRate: Int = SonarService.SAMPLE_RATE
) {
    private var k = 0
    private var omega = 0.0
    private var sine = 0.0
    private var cosine = 0.0
    private var coeff = 0.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var blockSize = 0

    fun initialize(blockSize: Int) {
        this.blockSize = blockSize
        k = (0.5 + blockSize * targetFreq / sampleRate).toInt()
        omega = 2.0 * Math.PI * k / blockSize
        cosine = cos(omega)
        sine = sin(omega)
        coeff = 2.0 * cosine
        reset()
    }

    fun reset() {
        q1 = 0.0; q2 = 0.0
    }

    fun process(samples: ShortArray): Double {
        reset()
        for (s in samples) {
            val q0 = coeff * q1 - q2 + s
            q2 = q1; q1 = q0
        }
        val real = q1 - q2 * cosine
        val imag = q2 * sine
        return sqrt(real * real + imag * imag) / blockSize
    }
}
