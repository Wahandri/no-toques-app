package com.ultrasonica.alarma

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ultrasonica.alarma.SonarService.AlarmState
import com.ultrasonica.alarma.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ==== CONTRASEÑA ====
    private val PREFS_NAME = "vigilante_prefs"
    private val KEY_PASSWORD = "alarm_password"
    private val DEFAULT_PASSWORD = "5522"

    private var sonarService: SonarService? = null
    private var isBound = false

    // Estado del teclado de alarma
    private val passwordInput = StringBuilder()
    private val pwDots = arrayOfNulls<TextView>(4)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SonarService.LocalBinder
            sonarService = binder.getService()
            isBound = true
            sonarService?.registerCallback(sonarCallback)
            syncUIConfigToService()
            updateAllUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sonarService?.unregisterCallback()
            sonarService = null
            isBound = false
        }
    }

    private val sonarCallback = object : SonarService.SonarCallback {
        override fun onDistanceUpdate(distanceCm: Float, rawMagnitude: Double) {
            updateDistanceUI(distanceCm, rawMagnitude)
            if (sonarService?.getAlarmState() == AlarmState.ACTIVE) {
                binding.tvAlarmDistance.text = "Distancia: ${distanceCm.toInt()} cm"
            }
        }

        override fun onAlarmStateChange(state: AlarmState) {
            runOnUiThread { handleAlarmState(state) }
        }

        override fun onCountdown(seconds: Int) {
            runOnUiThread {
                binding.tvServiceStatus.text = "Coloca el móvil — $seconds s"
                binding.tvServiceStatus.setTextColor(
                    ContextCompat.getColor(this@MainActivity, R.color.secondary)
                )
            }
        }

        override fun onDetectionStarted() {
            runOnUiThread {
                binding.tvServiceStatus.text = getString(R.string.servicio_ejecutando)
                binding.tvServiceStatus.setTextColor(
                    ContextCompat.getColor(this@MainActivity, R.color.active_green)
                )
            }
        }

        override fun onServiceError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                stopService()
            }
        }
    }

    // ==== PERMISOS ====

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startService()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Permisos necesarios")
                .setMessage("La app necesita acceso al micrófono y notificaciones.")
                .setPositiveButton("Reintentar") { _, _ -> checkAndRequestPermissions() }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    // ==== CICLO DE VIDA ====

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }

        setupUI()
        restoreUIState()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, SonarService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            sonarService?.unregisterCallback()
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // ==== UI SETUP ====

    private fun setupUI() {
        pwDots[0] = binding.pwDot1
        pwDots[1] = binding.pwDot2
        pwDots[2] = binding.pwDot3
        pwDots[3] = binding.pwDot4

        // Botón de ajustes (contraseña)
        binding.btnSettings.setOnClickListener { showPasswordDialog() }

        // Slider sensibilidad
        binding.sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                val cm = progress + 10
                binding.tvSensitivityValue.text = getString(R.string.label_sensibilidad, cm)
                sonarService?.setThreshold(cm.toFloat())
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        // Frecuencia
        binding.frequencyGroup.setOnCheckedChangeListener { _, checkedId ->
            val freq = when (checkedId) {
                R.id.rb18kHz -> 18000f
                R.id.rb22kHz -> 22000f
                else -> 20000f
            }
            sonarService?.frequency = freq
        }

        // Checkboxes
        binding.chkPitido.setOnCheckedChangeListener { _, checked ->
            sonarService?.alarmPitido = checked
        }
        binding.chkVibracion.setOnCheckedChangeListener { _, checked ->
            sonarService?.alarmVibracion = checked
        }
        binding.chkNotificacion.setOnCheckedChangeListener { _, checked ->
            sonarService?.alarmNotificacion = checked
        }

        // === TECLADO NUMÉRICO ===
        val keypadIds = listOf(
            R.id.kb0, R.id.kb1, R.id.kb2, R.id.kb3, R.id.kb4,
            R.id.kb5, R.id.kb6, R.id.kb7, R.id.kb8, R.id.kb9
        )
        keypadIds.forEach { id ->
            findViewById<Button>(id).setOnClickListener {
                val btn = it as Button
                onKeypadDigit(btn.text.toString())
            }
        }
        binding.kbDelete.setOnClickListener { onKeypadDelete() }
        binding.kbClear.setOnClickListener { onKeypadClear() }

        // Botón de emergencia dentro del overlay: detiene el servicio completo
        binding.btnEmergencyStop.setOnClickListener {
            stopService()
        }
    }

    private fun restoreUIState() {
        setServiceInactiveUI()
        binding.alarmOverlay.visibility = LinearLayout.GONE
        binding.tvDistance.text = getString(R.string.label_distancia, 0)
        binding.distanceBar.progress = 0
        binding.tvSensitivityValue.text = getString(R.string.label_sensibilidad, 30)
        binding.sensitivitySeek.progress = 20
    }

    // ==== CONTRASEÑA ====

    private fun getSavedPassword(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
    }

    private fun savePassword(newPw: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PASSWORD, newPw)
            .apply()
    }

    private fun showPasswordDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD

        val input = EditText(this).apply {
            setText(current)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSelection(length())
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.password_title)
            .setMessage("Código actual: $current")
            .setView(input)
            .setPositiveButton(R.string.password_guardar) { _, _ ->
                val newPw = input.text.toString()
                if (newPw.length == 4 && newPw.all { it.isDigit() }) {
                    savePassword(newPw)
                    Toast.makeText(this, "Código actualizado: $newPw", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Debe ser un número de 4 dígitos", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.password_cancelar, null)
            .show()
    }

    // ==== TECLADO ====

    private fun onKeypadDigit(digit: String) {
        if (passwordInput.length >= 4) return
        passwordInput.append(digit)
        updatePasswordDots()
        if (passwordInput.length == 4) {
            checkPassword()
        }
    }

    private fun onKeypadDelete() {
        if (passwordInput.isEmpty()) return
        passwordInput.deleteCharAt(passwordInput.length - 1)
        updatePasswordDots()
        binding.tvPwError.visibility = LinearLayout.GONE
    }

    private fun onKeypadClear() {
        passwordInput.clear()
        updatePasswordDots()
        binding.tvPwError.visibility = LinearLayout.GONE
    }

    private fun updatePasswordDots() {
        for (i in 0..3) {
            pwDots[i]?.text = if (i < passwordInput.length) "●" else "○"
        }
    }

    private fun checkPassword() {
        val entered = passwordInput.toString()
        val correct = getSavedPassword()
        if (entered == correct) {
            passwordInput.clear()
            updatePasswordDots()
            binding.tvPwError.visibility = LinearLayout.GONE
            sonarService?.dismissAlarm()
        } else {
            binding.tvPwError.text = getString(R.string.codigo_incorrecto)
            binding.tvPwError.visibility = LinearLayout.VISIBLE
            passwordInput.clear()
            updatePasswordDots()
        }
    }

    // ==== PERMISOS ====

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isEmpty()) {
            startService()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // ==== CONTROL DEL SERVICIO ====

    private fun syncUIConfigToService() {
        val svc = sonarService ?: return
        svc.frequency = when (binding.frequencyGroup.checkedRadioButtonId) {
            R.id.rb18kHz -> 18000f
            R.id.rb22kHz -> 22000f
            else -> 20000f
        }
        svc.setThreshold((binding.sensitivitySeek.progress + 10).toFloat())
        svc.alarmPitido = binding.chkPitido.isChecked
        svc.alarmVibracion = binding.chkVibracion.isChecked
        svc.alarmNotificacion = binding.chkNotificacion.isChecked
    }

    private fun startService() {
        val intent = Intent(this, SonarService::class.java).apply {
            action = SonarService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        if (!isBound) {
            bindService(Intent(this, SonarService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        }
        setServiceActiveUI()
    }

    private fun stopService() {
        if (isBound) {
            sonarService?.unregisterCallback()
            unbindService(serviceConnection)
            isBound = false
            sonarService = null
        }
        stopService(Intent(this, SonarService::class.java))
        setServiceInactiveUI()
        binding.alarmOverlay.visibility = LinearLayout.GONE
    }

    // ==== CLICS ====

    fun onToggleClick(view: android.view.View) {
        val running = sonarService?.isServiceRunning() == true
        if (running) {
            AlertDialog.Builder(this)
                .setTitle(R.string.confirmar_detener)
                .setMessage(R.string.confirmar_detener_msg)
                .setPositiveButton("Sí, detener") { _, _ -> stopService() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            checkAndRequestPermissions()
        }
    }

    // ==== UI UPDATES ====

    private fun updateAllUI() {
        val service = sonarService ?: return
        if (service.isServiceRunning()) {
            setServiceActiveUI()
            updateDistanceUI(service.getCurrentDistance(), service.getCurrentMagnitude())
            when (service.getAlarmState()) {
                AlarmState.ACTIVE -> showAlarmOverlay()
                else -> binding.alarmOverlay.visibility = LinearLayout.GONE
            }
        } else {
            setServiceInactiveUI()
        }
    }

    private fun updateDistanceUI(distanceCm: Float, rawMagnitude: Double) {
        val cm = distanceCm.toInt().coerceIn(0, 50)
        binding.tvDistance.text = getString(R.string.label_distancia, cm)
        val barProgress = ((1f - distanceCm / SonarService.MAX_DISTANCE) * 100).toInt()
        binding.distanceBar.progress = barProgress.coerceIn(0, 100)
    }

    private fun handleAlarmState(state: AlarmState) {
        when (state) {
            AlarmState.ACTIVE -> showAlarmOverlay()
            AlarmState.COOLDOWN -> {
                binding.alarmOverlay.visibility = LinearLayout.GONE
                Toast.makeText(this, "Esperando 30s para re-armar...", Toast.LENGTH_SHORT).show()
            }
            AlarmState.NONE -> {
                binding.alarmOverlay.visibility = LinearLayout.GONE
                onKeypadClear()
            }
        }
    }

    // ==== CAMBIOS VISUALES ====

    private fun setServiceActiveUI() {
        binding.btnToggle.text = getString(R.string.btn_detener)
        binding.btnToggle.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.alarm_red)
        )
        binding.tvServiceStatus.text = getString(R.string.servicio_ejecutando)
        binding.tvServiceStatus.setTextColor(
            ContextCompat.getColor(this, R.color.active_green)
        )
        setConfigControlsEnabled(false)
    }

    private fun setServiceInactiveUI() {
        binding.btnToggle.text = getString(R.string.btn_activar)
        binding.btnToggle.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.inactive_gray)
        )
        binding.tvServiceStatus.text = ""
        setConfigControlsEnabled(true)
        binding.distanceBar.progress = 0
        binding.tvDistance.text = getString(R.string.label_distancia, 0)
    }

    private fun showAlarmOverlay() {
        onKeypadClear()
        binding.alarmOverlay.visibility = LinearLayout.VISIBLE
        binding.tvAlarmDistance.text = "Distancia: ${sonarService?.getCurrentDistance()?.toInt() ?: 0} cm"
    }

    private fun setConfigControlsEnabled(enabled: Boolean) {
        binding.sensitivitySeek.isEnabled = enabled
        binding.frequencyGroup.isEnabled = enabled
        binding.chkPitido.isEnabled = enabled
        binding.chkVibracion.isEnabled = enabled
        binding.chkNotificacion.isEnabled = enabled
        for (i in 0 until binding.frequencyGroup.childCount) {
            binding.frequencyGroup.getChildAt(i).isEnabled = enabled
        }
    }
}
