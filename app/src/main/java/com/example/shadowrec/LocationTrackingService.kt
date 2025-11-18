package com.example.shadowrec

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.*
import android.annotation.SuppressLint
import android.util.Log

class LocationTrackingService : Service() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"

        private const val CHANNEL_ID = "shadowrec_tracking"
        private const val CHANNEL_NAME = "Seguimiento de ubicación"
        private const val NOTIF_ID = 1010

        private const val ACTION_STOP = "com.example.shadowrec.ACTION_STOP"
        private const val ACTION_OPEN_GPS = "com.example.shadowrec.ACTION_OPEN_GPS"

        // Intervalos y umbrales (puedes ajustar)
        private const val INTERVAL_MS = 30_000L       // pedimos fix cada 30s
        private const val FASTEST_MS = 15_000L
        private const val WATCHDOG_TICK_MS = 30_000L  // chequeo del watchdog cada 30s
        private const val STALE_MS = INTERVAL_MS * 2  // 60s sin fix = stale
        private const val ALERT_MS = INTERVAL_MS * 3  // 90s sin fix = alert
        private const val OFFLINE_MS = INTERVAL_MS * 5// 150s sin fix = offline (estricto)
    }

    private val db by lazy { Firebase.firestore }
    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var request: LocationRequest

    private var sessionId: String? = null
    private var lastFixAt: Long? = null

    private var status: String = "starting" // ok | stale | gps_off | no_permission | offline | starting

    // Watchdog
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkWatchdog()
            watchdogHandler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    // Provider (GPS) ON/OFF
    private val providersReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Cambios de proveedores / modo
            val enabled = isLocationEnabled()
            if (!enabled) {
                onGpsTurnedOff()
            } else {
                onGpsTurnedOn()
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationAvailability(availability: LocationAvailability) {
            // Si el proveedor está sin disponibilidad, el watchdog terminará detectándolo.
            if (!availability.isLocationAvailable) {
                // Podés marcar temporalmente como "stale" si querés feedback más rápido:
                maybeUpdateStatus("stale")
                updateNotification()
            }
        }

        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastFixAt = System.currentTimeMillis()
            maybeUpdateStatus("ok")
            pushPoint(loc)
            upsertDeviceStatus(loc)
            updateNotification(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)

        // Crear canal de notificación
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }

        // Registrar receiver para cambios de GPS
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            // En algunos dispositivos:
            addAction("android.location.MODE_CHANGED")
        }
        registerReceiver(providersReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_OPEN_GPS -> {
                openLocationSettings()
            }
        }

        // Tomamos sessionId
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)

        // Verificar permisos antes de arrancar
        if (!hasLocationPermission()) {
            maybeUpdateStatus("no_permission")
            startForegroundWithNotification()
            // No pedimos permisos desde el service; la Activity se encarga.
            return START_STICKY
        }

        // Modo estricto: si GPS está apagado, avisar y no pedir updates
        if (!isLocationEnabled()) {
            onGpsTurnedOff()
            startForegroundWithNotification()
            return START_STICKY
        }

        // Configurar peticiones de ubicación
        request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_MS)
            .setWaitForAccurateLocation(true)
            .build()

        // Iniciar foreground y pedir updates
        startForegroundWithNotification()
        startLocationUpdates()

        // Arrancar watchdog
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_TICK_MS)

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        maybeUpdateStatus("ok")
        updateNotification()
    }

    private fun stopLocationUpdates() {
        fused.removeLocationUpdates(locationCallback)
    }

    private fun checkWatchdog() {
        val now = System.currentTimeMillis()

        // Sin permiso → ya marcado por onStartCommand, mantener notificación
        if (!hasLocationPermission()) {
            maybeUpdateStatus("no_permission")
            updateNotification()
            return
        }

        // GPS apagado → ya manejado por broadcast; redundancia por seguridad
        if (!isLocationEnabled()) {
            onGpsTurnedOff()
            return
        }

        // Si tenemos al menos un fix, evaluamos tiempos
        val last = lastFixAt
        if (last == null) {
            // Aún no llegó primer fix
            maybeUpdateStatus("stale")
            updateNotification()
            return
        }

        val elapsed = now - last

        when {
            elapsed >= OFFLINE_MS -> {
                // Estricto: consideramos OFFLINE (cortamos updates y pedimos reactivar)
                maybeUpdateStatus("offline")
                stopLocationUpdates()
                updateNotification()
            }
            elapsed >= ALERT_MS -> {
                maybeUpdateStatus("stale") // escalado fuerte
                updateNotification()
            }
            elapsed >= STALE_MS -> {
                maybeUpdateStatus("stale")
                updateNotification()
            }
            else -> {
                if (status != "ok") {
                    maybeUpdateStatus("ok")
                    updateNotification()
                }
            }
        }
    }

    private fun onGpsTurnedOff() {
        // Estricto: pausamos peticiones y notificamos
        stopLocationUpdates()
        maybeUpdateStatus("gps_off")
        updateNotification()
        upsertDeviceStatus(null) // para que el panel vea el estado
    }

    private fun onGpsTurnedOn() {
        // Si estábamos pausados por GPS off, reanudamos
        if (status == "gps_off" || status == "offline") {
            // Reanudar solo si hay permisos
            if (hasLocationPermission()) {
                startLocationUpdates()
                maybeUpdateStatus("ok")
                updateNotification()
            } else {
                maybeUpdateStatus("no_permission")
                updateNotification()
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notif = buildNotification(null)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notif,
            if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else 0
        )
    }

    private fun updateNotification(loc: Location? = null) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(loc))
    }

    private fun buildNotification(latest: Location?): Notification {
        val title = when (status) {
            "ok" -> "Reportando ubicación"
            "stale" -> "Sin fijes recientes…"
            "gps_off" -> "GPS apagado"
            "no_permission" -> "Sin permiso de ubicación"
            "offline" -> "Ubicación detenida (sin señal)"
            else -> "Iniciando seguimiento…"
        }

        val lastFixStr = lastFixAt?.let {
            val hh = android.text.format.DateFormat.format("HH:mm:ss", it)
            "Último fix: $hh"
        } ?: "Esperando primer fix…"

        val content = when (status) {
            "ok" -> {
                val acc = latest?.accuracy?.toInt()?.let { " | ±${it}m" } ?: ""
                "$lastFixStr$acc"
            }
            "gps_off" -> "Tocá “Activar GPS” para continuar"
            "no_permission" -> "Otorgá permisos para continuar"
            "offline" -> "Intentá reactivar el GPS para retomar"
            else -> lastFixStr
        }

        // Acción para abrir ajustes de GPS
        val openGpsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        val openGpsPI = PendingIntent.getActivity(
            this, 20, openGpsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )

        // Acción para detener el servicio
        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPI = PendingIntent.getService(
            this, 30, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name) // poné tu ícono
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(
                when (status) {
                    "gps_off", "offline" -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_LOW
                }
            )
            .addAction(0, "Activar GPS", openGpsPI)
            .addAction(0, "Detener", stopPI)

        // Tocar la notificación abre la app
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openAppPI = PendingIntent.getActivity(
            this, 10, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        builder.setContentIntent(openAppPI)

        return builder.build()
    }

    private fun pendingImmutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }


    private fun pushPoint(loc: Location) {
        val sid = sessionId ?: return

        // Datos de la ubicación
        val point = hashMapOf(
            "deviceId" to deviceId,
            "lat" to loc.latitude,
            "lon" to loc.longitude,
            "accuracy" to loc.accuracy,
            "createdAt" to FieldValue.serverTimestamp(),
            "provider" to (loc.provider ?: "fused"),
            "sessionId" to sid,
            "source" to "service"
        )

        // 1) Guardar igual que antes en la colección "locations"
        db.collection("locations")
            .add(point)
            .addOnFailureListener { e ->
                Log.e("LocationService", "Error guardando en locations", e)
            }

        // 2) Opcional: seguir intentando guardar también en "sessions"
        db.collection("sessions")
            .document(sid)
            .collection("points")
            .add(point)
            .addOnFailureListener { e ->
                Log.e("LocationService", "Error guardando en sessions", e)
            }
    }

    private fun upsertDeviceStatus(loc: Location?) {
        val payload = hashMapOf(
            "deviceId" to deviceId,
            "status" to status,
            "sessionId" to (sessionId ?: ""),
            "lastFixAt" to FieldValue.serverTimestamp(),
            "androidVersion" to Build.VERSION.SDK_INT,
            "brand" to Build.BRAND,
            "model" to Build.MODEL
        ).apply {
            if (loc != null) {
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                put("accuracy", loc.accuracy)
            }
        }
        db.collection("devices")
            .document(deviceId)
            .set(payload, SetOptions.merge())
            .addOnFailureListener { /* opcional: log */ }
    }

    private fun maybeUpdateStatus(new: String) {
        if (status != new) {
            status = new
            // Actualización mínima a devices si cambia estado (sin loc)
            if (new != "ok") {
                upsertDeviceStatus(null)
            }
        }
    }

    private fun openLocationSettings() {
        val i = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        unregisterReceiver(providersReceiver)
        stopLocationUpdates()
        maybeUpdateStatus("offline")
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
