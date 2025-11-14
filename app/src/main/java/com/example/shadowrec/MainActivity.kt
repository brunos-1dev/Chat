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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // Cámara / QR
    private val REQUEST_CAMERA_PERMISSION = 100
    private val REQUEST_IMAGE_CAPTURE = 101
    private val REQUEST_VIDEO_CAPTURE = 102
    private val REQUEST_QR_SCAN = 103

    // Ubicación
    private val REQUEST_LOCATION_PERMISSION = 200
    private val REQUEST_ENABLE_LOCATION = 201

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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val buttonCamera = findViewById<Button>(R.id.buttonCamera)
        val buttonUbi = findViewById<Button>(R.id.buttonUbi)

        // Recuperar si el tracking estaba activo antes (por si el service seguía corriendo)
        isTracking = prefs.getBoolean("tracking_active", false)

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
                val hasFine = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasFine) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        REQUEST_LOCATION_PERMISSION
                    )
                } else {
                    startLocationTrackingService()
                }
            } else {
                stopLocationTrackingService()
            }
        }

        // Texto inicial del botón según el estado
        updateUbiButtonText()
    }

    // ====== Servicio de tracking: start/stop ======

    private fun startLocationTrackingService() {
        // Generamos un nuevo sessionId cada vez que se inicia el tracking
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
        buttonUbi.text = if (isTracking) {
            "Detener ubicación"
        } else {
            "Iniciar ubicación"
        }
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
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startLocationTrackingService()
                } else {
                    Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ====== Resultados de activities ======
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_QR_SCAN -> {
                    val qr = data?.getStringExtra("qr")
                    if (!qr.isNullOrEmpty()) {
                        saveQrToFirestore(qr, source = "live") // 👈 guarda
                        AlertDialog.Builder(this)
                            .setTitle("QR detectado")
                            .setMessage(qr)
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
                    scanQrFromImage(photoUri) // Leer QR en la foto
                }
                REQUEST_VIDEO_CAPTURE -> {
                    Toast.makeText(this, "Video grabado correctamente", Toast.LENGTH_SHORT).show()
                }
                REQUEST_ENABLE_LOCATION -> {
                    if (isLocationEnabled()) {
                        fetchCurrentLocation()
                    } else {
                        Toast.makeText(this, "La ubicación sigue desactivada", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ====== Activar servicios de ubicación (SettingsClient) ======
    private fun ensureLocationEnabled() {
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
            .addOnSuccessListener { fetchCurrentLocation() }
            .addOnFailureListener { ex ->
                if (ex is ResolvableApiException) {
                    try { ex.startResolutionForResult(this, REQUEST_ENABLE_LOCATION) }
                    catch (_: Exception) { openLocationSettingsFallback() }
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

    // ====== Obtener una única ubicación ======
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
                    saveLocationToFirestore(loc)   // 👈 guarda
                    showLocationDialog(loc)
                } else {
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { last: Location? ->
                            if (last != null) {
                                lastLocation = last
                                saveLocationToFirestore(last) // 👈 guarda
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
                    saveQrToFirestore(value, source = "photo") // 👈 guarda
                    AlertDialog.Builder(this)
                        .setTitle("QR detectado en la foto")
                        .setMessage(value)
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

    // ====== Firestore: guardar ubicación ======
    private fun saveLocationToFirestore(loc: Location) {
        val data = hashMapOf(
            "deviceId" to deviceId,
            "lat" to loc.latitude,
            "lon" to loc.longitude,
            "accuracy" to loc.accuracy,
            "createdAt" to FieldValue.serverTimestamp(),
            "androidVersion" to Build.VERSION.SDK_INT,
            "brand" to Build.BRAND,
            "model" to Build.MODEL
        )
        Firebase.firestore.collection("locations")
            .add(data)
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error guardando ubicación: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    // ====== Firestore: guardar QR ======
    private fun saveQrToFirestore(qrText: String, source: String) {
        val loc = lastLocation
        val data = hashMapOf(
            "deviceId" to deviceId,
            "text" to qrText,
            "source" to source, // "live" o "photo"
            "createdAt" to FieldValue.serverTimestamp()
        ).apply {
            if (loc != null) {
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                put("accuracy", loc.accuracy)
            }
        }

        Firebase.firestore.collection("qr_scans")
            .add(data)
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error guardando QR: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }
}
