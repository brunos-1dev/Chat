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
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONException
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    // Cámara / QR
    private val REQUEST_CAMERA_PERMISSION = 100
    private val REQUEST_IMAGE_CAPTURE = 101
    private val REQUEST_VIDEO_CAPTURE = 102
    private val REQUEST_QR_SCAN = 103

    // Ubicación
    private val REQUEST_LOCATION_PERMISSION = 200

    private lateinit var photoUri: Uri
    private lateinit var photoFile: File

    // Fused Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Firestore helpers
    private var lastLocation: Location? = null
    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    // Tracking foreground service
    private var isTracking = false
    private var currentSessionId: String? = null
    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", Context.MODE_PRIVATE)
    }

    // Acción a ejecutar cuando el usuario enciende la ubicación desde el diálogo
    private var onLocationEnabledAction: (() -> Unit)? = null

    // Launcher moderno para el IntentSender del diálogo de “activar ubicación”
    private val enableLocationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onLocationEnabledAction?.invoke()
                onLocationEnabledAction = null
            } else {
                Toast.makeText(this, "La ubicación sigue desactivada", Toast.LENGTH_SHORT).show()
            }
        }

    // --------------------- Firestore paths (resumen + historial) ---------------------

    private fun deviceDoc() =
        Firebase.firestore.collection("devices").document(deviceId)

    private fun locationHistoryCol() =
        deviceDoc().collection("location_history")

    private fun qrHistoryCol() =
        deviceDoc().collection("qr_history")

    private fun asignacionScansCol() =
        Firebase.firestore.collection("asignacion_scans")

    // -------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ✅ Sólo abrimos AuthActivity si hace falta (no logueado o perfil incompleto)
        val profileComplete = prefs.getBoolean("user_profile_complete", false)
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (!profileComplete || currentUser == null) {
            val authIntent = Intent(this, AuthActivity::class.java)
            startActivity(authIntent)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val buttonCamera = findViewById<Button>(R.id.buttonCamera)
        val buttonUbi = findViewById<Button>(R.id.buttonUbi)

        // Recuperar estado previo del tracking
        isTracking = prefs.getBoolean("tracking_active", false)
        updateUbiButtonText()

        // ====== CÁMARA / QR ======
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

        // ====== UBICACIÓN (iniciar / detener tracking) ======
        buttonUbi.setOnClickListener {
            if (!isTracking) {
                val fineGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarseGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
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
                    // Verificamos/encendemos ubicación del sistema y luego iniciamos el tracking
                    ensureLocationEnabled { startLocationTrackingService() }
                }
            } else {
                stopLocationTrackingService()
            }
        }

        // Long-press para consultar las últimas N ubicaciones
        buttonUbi.setOnLongClickListener {
            showLastLocationHistory(limit = 30)
            true
        }
    }

    // ====== Helpers de usuario (para nombre a guardar en Firestore) ======

    private fun getScannerDisplayName(): String {
        val firstName = prefs.getString("user_first_name", "") ?: ""
        val lastName = prefs.getString("user_last_name", "") ?: ""
        val email = prefs.getString("user_email", "") ?: ""

        val fullName = listOf(firstName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        return when {
            fullName.isNotEmpty() -> fullName
            email.isNotEmpty() -> email
            else -> deviceId
        }
    }

    // ====== Servicio de tracking: start/stop ======
    private fun startLocationTrackingService() {
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId

        val serviceIntent = Intent(this, LocationTrackingService::class.java).apply {
            putExtra(LocationTrackingService.EXTRA_SESSION_ID, sessionId)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        isTracking = true
        prefs.edit().putBoolean("tracking_active", true).apply()
        updateUbiButtonText()
    }

    private fun stopLocationTrackingService() {
        val serviceIntent = Intent(this, LocationTrackingService::class.java)
        stopService(serviceIntent)
        isTracking = false
        currentSessionId = null
        prefs.edit().putBoolean("tracking_active", false).apply()
        updateUbiButtonText()
    }

    private fun updateUbiButtonText() {
        val buttonUbi = findViewById<Button>(R.id.buttonUbi)
        buttonUbi.text = if (isTracking) "Detener ubicación" else "Iniciar ubicación"
    }

    // ====== Diálogo de cámara ======
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
        if (!storageDir.exists()) storageDir.mkdirs()
        return File(storageDir, imageFileName)
    }

    // ====== Runtime permissions ======
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
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

    // ====== Resultados de activities (QR / cámara) ======
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_QR_SCAN -> {
                    val qr = data?.getStringExtra("qr")
                    if (!qr.isNullOrEmpty()) {
                        saveQrToFirestore(qr, source = "live")

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

    // ====== Verificar/activar servicios de ubicación, luego ejecutar acción ======
    private fun ensureLocationEnabled(then: () -> Unit) {
        onLocationEnabledAction = then

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 10_000L
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
                        val request = IntentSenderRequest.Builder(ex.resolution.intentSender).build()
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

    // ====== Obtener una única ubicación (on-demand) ======
    private fun fetchCurrentLocation() {
        val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
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
                    saveLocationToFirestore(loc)
                    showLocationDialog(loc)
                } else {
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { last: Location? ->
                            if (last != null) {
                                lastLocation = last
                                saveLocationToFirestore(last)
                                showLocationDialog(last)
                            } else {
                                Toast.makeText(this, "No se pudo obtener la ubicación", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "No se pudo obtener la ubicación", Toast.LENGTH_SHORT).show()
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
            if (loc.hasAccuracy()) appendLine("Precisión: ${loc.accuracy} m")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasAltitude()) {
                appendLine("Altitud: ${loc.altitude} m")
            }
            if (loc.time > 0) {
                val ts = Date(loc.time)
                appendLine("Hora: $ts")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Ubicación actual")
            .setMessage(msg.trim())
            .setPositiveButton("OK", null)
            .show()
    }

    // ====== ML Kit: leer QR desde imagen ======
    private fun scanQrFromImage(uri: Uri) {
        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la imagen para leer el QR", Toast.LENGTH_SHORT).show()
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
                    saveQrToFirestore(value, source = "photo")

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
                Toast.makeText(this, "Error leyendo QR: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun formatQrMessage(qrText: String): String {
        return try {
            val json = JSONObject(qrText)

            // Solo lo “embellecemos” si es una asignación
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
            // Si no es JSON válido, mostramos el texto crudo
            qrText
        }
    }

    // ====== Firestore: guardar ubicación (resumen + historial) ======
    private fun saveLocationToFirestore(loc: Location) {
        // Resumen “vivo”
        val lastLocationMap = mapOf(
            "lat" to loc.latitude,
            "lon" to loc.longitude,
            "accuracy" to loc.accuracy,
            "time" to FieldValue.serverTimestamp()
        )
        val summary = mapOf(
            "deviceId" to deviceId,
            "androidVersion" to Build.VERSION.SDK_INT,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "sessionId" to (currentSessionId ?: ""),
            "tracking" to isTracking,
            "lastLocation" to lastLocationMap,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        deviceDoc()
            .set(summary, SetOptions.merge())
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error guardando resumen: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }

        // Historial (append-only)
        val history = mapOf(
            "lat" to loc.latitude,
            "lon" to loc.longitude,
            "accuracy" to loc.accuracy,
            "time" to FieldValue.serverTimestamp(),
            "sessionId" to (currentSessionId ?: "")
        )
        locationHistoryCol()
            .add(history)
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error guardando historial: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    // ====== Firestore: guardar QR (resumen + historial) ======
    private fun saveQrToFirestore(qrText: String, source: String) {
        val loc = lastLocation
        val lastQr = mutableMapOf(
            "text" to qrText,
            "source" to source,              // "live" o "photo"
            "scannedAt" to FieldValue.serverTimestamp(),
            "sessionId" to (currentSessionId ?: "")
        ).apply {
            if (loc != null) {
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                put("accuracy", loc.accuracy)
            }
        }

        // Resumen “vivo”
        val summary = mapOf(
            "deviceId" to deviceId,
            "lastQr" to lastQr,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        deviceDoc()
            .set(summary, SetOptions.merge())
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error guardando resumen QR: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }

        // Historial (append-only)
        qrHistoryCol()
            .add(lastQr)
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error guardando historial QR: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }

        // === NUEVO: si el QR es una asignación JSON, crear doc en 'asignacion_scans' ===
        try {
            val json = JSONObject(qrText)

            // Solo si viene con "tipo": "asignacion"
            if (json.optString("tipo") == "asignacion") {
                val asignacionData = mutableMapOf<String, Any?>(
                    "deviceId" to deviceId,
                    "androidId" to deviceId,       // por si querés usar ambos nombres
                    "scannedAt" to FieldValue.serverTimestamp()
                )

                // Datos del usuario que escanea
                val scannerName = getScannerDisplayName()
                val scannerEmail = prefs.getString("user_email", "") ?: ""
                val uid = FirebaseAuth.getInstance().currentUser?.uid

                asignacionData["scannerName"] = scannerName
                if (scannerEmail.isNotEmpty()) {
                    asignacionData["scannerEmail"] = scannerEmail
                }
                if (!uid.isNullOrEmpty()) {
                    asignacionData["scannerUid"] = uid
                }

                val keys = listOf(
                    "fecha",
                    "unidad",
                    "turno",
                    "cuadricula",
                    "movil",
                    "personal",
                    "localidad",
                    "asignacion"
                )

                for (key in keys) {
                    if (!json.isNull(key)) {
                        asignacionData[key] = json.optString(key)
                    }
                }

                asignacionData["rawQr"] = qrText

                asignacionScansCol()
                    .add(asignacionData)
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            this,
                            "Error guardando asignación: ${e.localizedMessage}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        } catch (e: JSONException) {
            // El QR no era JSON válido -> lo ignoramos para 'asignacion_scans'
            // (igual quedó guardado en qr_history y en lastQr)
        }
    }

    // ====== Historial: últimas N ubicaciones (AlertDialog simple) ======
    private fun showLastLocationHistory(limit: Int) {
        locationHistoryCol()
            .orderBy("time", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get()
            .addOnSuccessListener { qs ->
                if (qs.isEmpty) {
                    Toast.makeText(this, "Sin historial de ubicaciones", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val sb = StringBuilder()
                for (doc in qs.documents) {
                    val lat = doc.getDouble("lat") ?: 0.0
                    val lon = doc.getDouble("lon") ?: 0.0
                    val acc = doc.getDouble("accuracy")
                    val ts: Timestamp? = doc.getTimestamp("time")
                    val timeStr = ts?.toDate()?.let { sdf.format(it) } ?: "—"
                    val sid = doc.getString("sessionId")

                    sb.append("• ").append(timeStr).append(" — ")
                        .append(String.format(Locale.US, "%.6f, %.6f", lat, lon))
                    if (acc != null) sb.append(" (±").append(String.format(Locale.US, "%.1f", acc)).append(" m)")
                    if (!sid.isNullOrEmpty()) sb.append("  [").append(sid.take(8)).append("]")
                    sb.append('\n')
                }

                AlertDialog.Builder(this)
                    .setTitle("Últimas ${qs.size()} ubicaciones")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error leyendo historial", Toast.LENGTH_SHORT).show()
            }
    }

    // (Opcional) ejemplo para rango de fechas
    // startMillis / endMillis son epoch millis
    private fun fetchLocationHistoryBetween(startMillis: Long, endMillis: Long) {
        val start = Timestamp(Date(startMillis))
        val end = Timestamp(Date(endMillis))
        locationHistoryCol()
            .whereGreaterThanOrEqualTo("time", start)
            .whereLessThan("time", end)
            .orderBy("time", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { qs ->
                Toast.makeText(this, "Resultados: ${qs.size()}", Toast.LENGTH_SHORT).show()
                // TODO: mostrar en lista/Mapa según necesites
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error consultando rango", Toast.LENGTH_SHORT).show()
            }
    }
}
