package com.example.shadowrec

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.app.KeyguardManager

class MainActivity : AppCompatActivity() {

    private val REQUEST_CAMERA_PERMISSION = 100
    private val REQUEST_IMAGE_CAPTURE = 101
    private val REQUEST_VIDEO_CAPTURE = 102
    private val REQUEST_QR_SCAN = 103
    private val REQUEST_LOCATION_PERMISSION = 200

    private lateinit var photoUri: Uri
    private lateinit var photoFile: File
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var lastLocation: Location? = null

    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private var isTracking = false
    private var currentSessionId: Int? = null

    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", Context.MODE_PRIVATE)
    }

    private val convoPrefs by lazy {
        getSharedPreferences("shadowrec_conversations", Context.MODE_PRIVATE)
    }

    private var onLocationEnabledAction: (() -> Unit)? = null

    private val stopTrackingCredentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(
                    this,
                    "Validación correcta",
                    Toast.LENGTH_SHORT
                ).show()

                stopLocationTrackingService()
            } else {
                Toast.makeText(
                    this,
                    "No se detuvo la ubicación",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val enableLocationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onLocationEnabledAction?.invoke()
                onLocationEnabledAction = null
            } else {
                Toast.makeText(this, "La ubicación sigue desactivada", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val profileComplete = prefs.getBoolean("user_profile_complete", false)
        val token = prefs.getString("auth_token", null)

        if (!profileComplete || token.isNullOrEmpty()) {
            val authIntent = Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(authIntent)
            finish()
            return
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val buttonCamera = findViewById<Button>(R.id.buttonCamera)
        val buttonUbi = findViewById<Button>(R.id.buttonUbi)
        val buttonChats = findViewById<Button>(R.id.buttonChats)
        val buttonLogout = findViewById<Button>(R.id.buttonLogout)

        buttonChats.setOnClickListener {
            startActivity(Intent(this, UsersActivity::class.java))
        }

        buttonLogout.setOnClickListener {
            if (isTracking) {
                stopLocationTrackingService()
            }

            prefs.edit().clear().apply()
            convoPrefs.edit().clear().apply()

            val intent = Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }

        isTracking = prefs.getBoolean("tracking_active", false)
        currentSessionId = prefs.getInt("current_session_id", -1).takeIf { it != -1 }
        updateUbiButtonText()

        buttonCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CAMERA_PERMISSION
                )
            } else {
                showCameraChoiceDialog()
            }
        }

        buttonUbi.setOnClickListener {
            if (!isTracking) {
                val fineGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val coarseGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!fineGranted && !coarseGranted) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        REQUEST_LOCATION_PERMISSION
                    )
                } else {
                    ensureLocationEnabled { startLocationTrackingService() }
                }
            } else {
                authenticateBeforeStoppingTracking()
            }
        }

        buttonUbi.setOnLongClickListener {
            Toast.makeText(
                this,
                "Historial de ubicación pendiente de migrar a backend",
                Toast.LENGTH_SHORT
            ).show()
            true
        }
    }

    private fun startLocationTrackingService() {
        val token = prefs.getString("auth_token", null)

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "No hay token de sesión", Toast.LENGTH_SHORT).show()
            return
        }

        val request = StartTrackingRequest(
            device_id = deviceId,
            marca = Build.BRAND,
            modelo = Build.MODEL,
            version_android = Build.VERSION.SDK_INT
        )

        ApiClient.authService.startTracking("Bearer $token", request)
            .enqueue(object : Callback<StartTrackingResponse> {
                override fun onResponse(
                    call: Call<StartTrackingResponse>,
                    response: Response<StartTrackingResponse>
                ) {
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@MainActivity,
                            "Error iniciando tracking",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    val body = response.body()

                    if (body == null || !body.ok) {
                        Toast.makeText(
                            this@MainActivity,
                            "No se pudo iniciar tracking",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    currentSessionId = body.session_id

                    val serviceIntent = Intent(
                        this@MainActivity,
                        LocationTrackingService::class.java
                    ).apply {
                        putExtra(
                            LocationTrackingService.EXTRA_SESSION_ID,
                            body.session_id.toString()
                        )
                    }

                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)

                    isTracking = true
                    prefs.edit()
                        .putBoolean("tracking_active", true)
                        .putInt("current_session_id", body.session_id)
                        .apply()

                    updateUbiButtonText()
                }

                override fun onFailure(call: Call<StartTrackingResponse>, t: Throwable) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error de conexión: ${t.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun stopLocationTrackingService() {
        val token = prefs.getString("auth_token", null)
        val sessionId = currentSessionId
            ?: prefs.getInt("current_session_id", -1).takeIf { it != -1 }

        stopService(Intent(this, LocationTrackingService::class.java))

        isTracking = false
        currentSessionId = null

        prefs.edit()
            .putBoolean("tracking_active", false)
            .remove("current_session_id")
            .apply()


        updateUbiButtonText()

        if (!token.isNullOrEmpty() && sessionId != null) {
            val request = StopTrackingRequest(
                session_id = sessionId,
                device_id = deviceId
            )

            ApiClient.authService.stopTracking("Bearer $token", request)
                .enqueue(object : Callback<GenericResponse> {
                    override fun onResponse(
                        call: Call<GenericResponse>,
                        response: Response<GenericResponse>
                    ) {
                        // Sin acción visual por ahora
                    }

                    override fun onFailure(call: Call<GenericResponse>, t: Throwable) {
                        // No bloqueamos la app si falla el stop remoto
                    }
                })
        }
    }

    private fun authenticateBeforeStoppingTracking() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            authenticateWithLegacyDeviceCredentialBeforeStop()
            return
        }

        val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        val canAuthenticate = BiometricManager.from(this).canAuthenticate(authenticators)

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(
                this,
                "No hay un método de seguridad disponible en este dispositivo",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirmar detención")
            .setSubtitle("Validá tu identidad para detener la ubicación")
            .setDescription("Esta acción detendrá el seguimiento de ubicación.")
            .setAllowedAuthenticators(authenticators)
            .build()

        val biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)

                    Toast.makeText(
                        this@MainActivity,
                        "Validación correcta",
                        Toast.LENGTH_SHORT
                    ).show()

                    stopLocationTrackingService()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errString)

                    Toast.makeText(
                        this@MainActivity,
                        "No se detuvo la ubicación",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()

                    Toast.makeText(
                        this@MainActivity,
                        "Validación no reconocida",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }

    private fun authenticateWithLegacyDeviceCredentialBeforeStop() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (!keyguardManager.isKeyguardSecure) {
            Toast.makeText(
                this,
                "Este dispositivo no tiene PIN, patrón o contraseña configurado",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = keyguardManager.createConfirmDeviceCredentialIntent(
            "Confirmar detención",
            "Validá tu identidad para detener la ubicación"
        )

        if (intent == null) {
            Toast.makeText(
                this,
                "No se pudo abrir la validación del dispositivo",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        stopTrackingCredentialLauncher.launch(intent)
    }

    private fun updateUbiButtonText() {
        val buttonUbi = findViewById<Button>(R.id.buttonUbi)
        buttonUbi.text = if (isTracking) "Detener ubicación" else "Iniciar ubicación"
    }

    private fun showCameraChoiceDialog() {
        val opciones = arrayOf("Escanear QR", "Tomar Foto", "Grabar Video")

        AlertDialog.Builder(this)
            .setTitle("Seleccionar una opción")
            .setItems(opciones) { _, which ->
                when (which) {
                    0 -> openQrScanner()
                    1 -> openCameraForPhoto()
                    2 -> openCameraForVideo()
                }
            }
            .show()
    }

    private fun openQrScanner() {
        val i = Intent(this, QrScannerActivity::class.java)
        startActivityForResult(i, REQUEST_QR_SCAN)
    }

    private fun openCameraForPhoto() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

        if (takePictureIntent.resolveActivity(packageManager) != null) {
            photoFile = createImageFile()

            photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                photoFile
            )

            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
        } else {
            Toast.makeText(this, "No se encontró app de cámara", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCameraForVideo() {
        val takeVideoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)

        if (takeVideoIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(takeVideoIntent, REQUEST_VIDEO_CAPTURE)
        } else {
            Toast.makeText(this, "No se encontró app de cámara", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_$timeStamp.jpg"

        val storageDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera"
        )

        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        return File(storageDir, imageFileName)
    }

    private fun saveQrToBackend(qrValue: String, source: String) {
        val token = prefs.getString("auth_token", null)

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "No hay token", Toast.LENGTH_SHORT).show()
            return
        }

        val request = QrScanRequest(
            device_id = deviceId,
            texto = qrValue,
            origen = source
        )

        ApiClient.authService.sendQrScan("Bearer $token", request)
            .enqueue(object : Callback<GenericResponse> {
                override fun onResponse(
                    call: Call<GenericResponse>,
                    response: Response<GenericResponse>
                ) {
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@MainActivity,
                            "Error guardando QR",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<GenericResponse>, t: Throwable) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error conexión QR",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showCameraChoiceDialog()
                } else {
                    Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
                }
            }

            REQUEST_LOCATION_PERMISSION -> {
                val anyGranted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }

                if (anyGranted) {
                    ensureLocationEnabled { startLocationTrackingService() }
                } else {
                    Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_QR_SCAN -> {
                    val qr = data?.getStringExtra("qr")

                    if (!qr.isNullOrEmpty()) {
                        saveQrToBackend(qr, source = "live")

                        val pretty = formatQrMessage(qr)

                        AlertDialog.Builder(this)
                            .setTitle("Asignación detectada")
                            .setMessage(pretty)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }

                REQUEST_IMAGE_CAPTURE -> {
                    Toast.makeText(this, "Foto tomada correctamente", Toast.LENGTH_SHORT).show()

                    MediaScannerConnection.scanFile(
                        this,
                        arrayOf(photoFile.absolutePath),
                        arrayOf("image/jpeg"),
                        null
                    )

                    scanQrFromImage(photoUri)
                }

                REQUEST_VIDEO_CAPTURE -> {
                    Toast.makeText(this, "Video grabado correctamente", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun ensureLocationEnabled(then: () -> Unit) {
        onLocationEnabledAction = then

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10_000L
        ).setMinUpdateIntervalMillis(5_000L).build()

        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        val client = LocationServices.getSettingsClient(this)

        client.checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                onLocationEnabledAction?.invoke()
                onLocationEnabledAction = null
            }
            .addOnFailureListener { ex ->
                if (ex is ResolvableApiException) {
                    try {
                        val request =
                            IntentSenderRequest.Builder(ex.resolution.intentSender).build()
                        enableLocationLauncher.launch(request)
                    } catch (_: Exception) {
                        openLocationSettingsFallback()
                    }
                } else {
                    openLocationSettingsFallback()
                }
            }
    }

    private fun openLocationSettingsFallback() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        Toast.makeText(this, "Activá la ubicación y volvé a la app", Toast.LENGTH_LONG).show()
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun fetchCurrentLocation() {
        val hasFine = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Toast.makeText(this, "Falta permiso de ubicación", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isLocationEnabled()) {
            Toast.makeText(this, "Por favor, activá la ubicación", Toast.LENGTH_SHORT).show()
            return
        }

        val cts = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    lastLocation = loc
                    showLocationDialog(loc)
                } else {
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { last: Location? ->
                            if (last != null) {
                                lastLocation = last
                                showLocationDialog(last)
                            } else {
                                Toast.makeText(
                                    this,
                                    "No se pudo obtener la ubicación",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(
                                this,
                                "No se pudo obtener la ubicación",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al obtener ubicación", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showLocationDialog(loc: Location) {
        val msg = buildString {
            appendLine("Lat: ${loc.latitude}")
            appendLine("Lon: ${loc.longitude}")

            if (loc.hasAccuracy()) {
                appendLine("Precisión: ${loc.accuracy} m")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasAltitude()) {
                appendLine("Altitud: ${loc.altitude} m")
            }

            if (loc.time > 0) {
                appendLine("Hora: ${Date(loc.time)}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Ubicación actual")
            .setMessage(msg.trim())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun scanQrFromImage(uri: Uri) {
        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la imagen para leer el QR", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = BarcodeScanning.getClient(options)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue

                if (!value.isNullOrEmpty()) {
                    saveQrToBackend(value, source = "photo")

                    val pretty = formatQrMessage(value)

                    AlertDialog.Builder(this)
                        .setTitle("Asignación detectada en la foto")
                        .setMessage(pretty)
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    Toast.makeText(this, "No se encontró QR en la foto", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error leyendo QR: ${e.localizedMessage}", Toast.LENGTH_SHORT)
                    .show()
            }
    }

    private fun formatQrMessage(qrText: String): String {
        return try {
            val json = JSONObject(qrText)

            if (json.optString("tipo") == "asignacion") {
                """
                Fecha: ${json.optString("fecha")}
                Unidad: ${json.optString("unidad")}
                Turno: ${json.optString("turno")}
                Cuadrícula: ${json.optString("cuadricula")}
                Móvil: ${json.optString("movil")}
                Personal: ${json.optString("personal")}
                Localidad: ${json.optString("localidad")}
                Asignación: ${json.optString("asignacion")}
                """.trimIndent()
            } else {
                qrText
            }
        } catch (e: JSONException) {
            qrText
        }
    }
}