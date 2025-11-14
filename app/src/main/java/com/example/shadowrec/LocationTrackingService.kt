package com.example.shadowrec

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import android.annotation.SuppressLint

class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var sessionId: String? = null
    private fun setTrackingActive(active: Boolean) {
        val prefs = getSharedPreferences("shadowrec_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tracking_active", active).apply()
    }

    // deviceId igual que en MainActivity, pero desde el contexto del servicio
    private val deviceId: String by lazy {
        Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Leer sessionId si viene en el Intent
        if (intent != null && intent.hasExtra(EXTRA_SESSION_ID)) {
            sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        }

        // Notificación de foreground service
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Iniciamos las actualizaciones de ubicación periódicas
        startLocationUpdates()
        setTrackingActive(true)   // 👈 marcamos activo

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        setTrackingActive(false)   // 👈 marcamos inactivo
    }


    override fun onBind(intent: Intent?): IBinder? {
        // No usamos binding en este servicio
        return null
    }

    // ====== Location cada ~20 seg ======

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        // Si ya tenemos un callback activo, no nos volvemos a suscribir
        if (locationCallback != null) {
            return
        }

        if (!hasLocationPermission()) {
            Toast.makeText(
                this,
                "Sin permisos de ubicación para iniciar el tracking",
                Toast.LENGTH_SHORT
            ).show()
            stopSelf()
            return
        }

        // Pedido de ubicación cada 20 segundos
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            20_000L // 20 segundos
        )
            .setMinUpdateIntervalMillis(20_000L)
            // Quitamos la distancia mínima para que reporte aunque no te muevas
            // .setMinUpdateDistanceMeters(5f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    if (loc != null) {
                        saveLocationToFirestore(loc)
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (se: SecurityException) {
            Toast.makeText(
                this,
                "Sin permisos para ubicación (SecurityException)",
                Toast.LENGTH_SHORT
            ).show()
            stopSelf()
        }
    }

    private fun stopLocationUpdates() {
        val callback = locationCallback ?: return
        fusedLocationClient.removeLocationUpdates(callback)
        locationCallback = null
    }

    private fun hasLocationPermission(): Boolean {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    // ====== Guardar en Firestore ======

    private fun saveLocationToFirestore(loc: Location) {
        val data = hashMapOf(
            "deviceId" to deviceId,
            "lat" to loc.latitude,
            "lon" to loc.longitude,
            "accuracy" to loc.accuracy,
            "createdAt" to FieldValue.serverTimestamp(),
            "androidVersion" to Build.VERSION.SDK_INT,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "source" to "service"      // para distinguir que viene del tracking
        ).apply {
            sessionId?.let { put("sessionId", it) }
        }

        Firebase.firestore.collection("locations")
            .add(data)
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error guardando ubicación: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }


    // ====== Notificación foreground ======

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ShadowRec tracking ubicación",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShadowRec")
            .setContentText("Tracking de ubicación en ejecución")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        private const val CHANNEL_ID = "SHADOWREC_LOCATION_CHANNEL"
        private const val NOTIFICATION_ID = 101
    }
}
