package com.example.shadowrec

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LocationTrackingService : Service() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"

        private const val CHANNEL_ID = "shadowrec_tracking"
        private const val CHANNEL_NAME = "Seguimiento de ubicación"
        private const val NOTIF_ID = 1010

        private const val ACTION_STOP = "com.example.shadowrec.ACTION_STOP"
        private const val ACTION_OPEN_GPS = "com.example.shadowrec.ACTION_OPEN_GPS"

        private const val INTERVAL_MS = 30_000L
        private const val FASTEST_MS = 15_000L
        private const val WATCHDOG_TICK_MS = 30_000L
        private const val STALE_MS = INTERVAL_MS * 2
        private const val ALERT_MS = INTERVAL_MS * 3
        private const val OFFLINE_MS = INTERVAL_MS * 5
    }

    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var request: LocationRequest

    private var sessionId: Int? = null
    private var lastFixAt: Long? = null
    private var status: String = "starting"

    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    private val watchdogHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkWatchdog()
            watchdogHandler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    private val providersReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isLocationEnabled()) {
                onGpsTurnedOff()
            } else {
                onGpsTurnedOn()
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable) {
                Log.d("LocationService", "LocationAvailability: no disponible")
                maybeUpdateStatus("stale")
                updateNotification()
            }
        }

        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: run {
                Log.d("LocationService", "onLocationResult sin lastLocation")
                return
            }

            Log.d(
                "LocationService",
                "Llegó ubicación: ${loc.latitude}, ${loc.longitude} | acc=${loc.accuracy} | sessionId=$sessionId"
            )

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

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }

        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
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

        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.toIntOrNull()

        Log.d("LocationService", "Servicio iniciado con sessionId=$sessionId")

        if (sessionId == null) {
            Log.e("LocationService", "No llegó sessionId válido al servicio")
        }

        if (!hasLocationPermission()) {
            maybeUpdateStatus("no_permission")
            startForegroundWithNotification()
            return START_STICKY
        }

        if (!isLocationEnabled()) {
            onGpsTurnedOff()
            startForegroundWithNotification()
            return START_STICKY
        }

        request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_MS)
            .setWaitForAccurateLocation(true)
            .build()

        startForegroundWithNotification()
        startLocationUpdates()

        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_TICK_MS)

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        Log.d("LocationService", "Solicitando updates de ubicación")

        fused.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )

        maybeUpdateStatus("ok")
        updateNotification()
    }

    private fun stopLocationUpdates() {
        fused.removeLocationUpdates(locationCallback)
    }

    private fun checkWatchdog() {
        val now = System.currentTimeMillis()

        if (!hasLocationPermission()) {
            maybeUpdateStatus("no_permission")
            updateNotification()
            return
        }

        if (!isLocationEnabled()) {
            onGpsTurnedOff()
            return
        }

        val last = lastFixAt

        if (last == null) {
            Log.d("LocationService", "Watchdog: aún sin primer fix")
            maybeUpdateStatus("stale")
            updateNotification()
            return
        }

        val elapsed = now - last

        when {
            elapsed >= OFFLINE_MS -> {
                maybeUpdateStatus("offline")
                stopLocationUpdates()
                updateNotification()
            }

            elapsed >= ALERT_MS -> {
                maybeUpdateStatus("stale")
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
        stopLocationUpdates()
        maybeUpdateStatus("gps_off")
        updateNotification()
        upsertDeviceStatus(null)
    }

    private fun onGpsTurnedOn() {
        if (status == "gps_off" || status == "offline") {
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

        val openGpsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        val openGpsPI = PendingIntent.getActivity(
            this,
            20,
            openGpsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )

        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPI = PendingIntent.getService(
            this,
            30,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openAppPI = PendingIntent.getActivity(
            this,
            10,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
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
            .setContentIntent(openAppPI)
            .build()
    }

    private fun pendingImmutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE
        else
            0

    private fun hasLocationPermission(): Boolean {
        val fine =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val coarse =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun pushPoint(loc: Location) {
        val sid = sessionId ?: run {
            Log.e("LocationService", "No se guarda punto: sessionId null")
            return
        }

        val token = prefs.getString("auth_token", null)

        if (token.isNullOrEmpty()) {
            Log.e("LocationService", "No hay token para guardar punto")
            return
        }

        Log.d("LocationService", "Enviando punto sessionId=$sid")

        val request = SendPointRequest(
            session_id = sid,
            device_id = deviceId,
            lat = loc.latitude,
            lon = loc.longitude,
            accuracy = loc.accuracy.toDouble(),
            provider = loc.provider ?: "fused",
            origin = "service"
        )

        ApiClient.authService.sendPoint("Bearer $token", request)
            .enqueue(object : Callback<GenericResponse> {
                override fun onResponse(
                    call: Call<GenericResponse>,
                    response: Response<GenericResponse>
                ) {
                    if (response.isSuccessful) {
                        Log.d("LocationService", "Punto guardado OK")
                    } else {
                        Log.e(
                            "LocationService",
                            "Error guardando punto: ${response.code()} ${response.errorBody()?.string()}"
                        )
                    }
                }

                override fun onFailure(call: Call<GenericResponse>, t: Throwable) {
                    Log.e("LocationService", "Error conexión guardando punto", t)
                }
            })
    }

    private fun upsertDeviceStatus(loc: Location?) {
        val token = prefs.getString("auth_token", null)

        if (token.isNullOrEmpty()) {
            Log.e("LocationService", "No hay token para actualizar dispositivo")
            return
        }

        val request = DeviceStatusRequest(
            device_id = deviceId,
            status = status,
            lat = loc?.latitude,
            lon = loc?.longitude,
            accuracy = loc?.accuracy?.toDouble(),
            marca = Build.BRAND,
            modelo = Build.MODEL,
            version_android = Build.VERSION.SDK_INT
        )

        ApiClient.authService.updateDevice("Bearer $token", request)
            .enqueue(object : Callback<GenericResponse> {
                override fun onResponse(
                    call: Call<GenericResponse>,
                    response: Response<GenericResponse>
                ) {
                    if (response.isSuccessful) {
                        Log.d("LocationService", "Dispositivo actualizado OK status=$status")
                    } else {
                        Log.e(
                            "LocationService",
                            "Error actualizando dispositivo: ${response.code()} ${response.errorBody()?.string()}"
                        )
                    }
                }

                override fun onFailure(call: Call<GenericResponse>, t: Throwable) {
                    Log.e("LocationService", "Error conexión actualizando dispositivo", t)
                }
            })
    }

    private fun maybeUpdateStatus(new: String) {
        if (status != new) {
            status = new

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

        try {
            unregisterReceiver(providersReceiver)
        } catch (_: Exception) {
        }

        stopLocationUpdates()
        maybeUpdateStatus("offline")
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}