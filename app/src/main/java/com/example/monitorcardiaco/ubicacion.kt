package com.example.monitorcardiaco

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Espera hasta 8 segundos; nunca bloquea el SOS si no hay ubicación. */
suspend fun Context.obtenerUbicacion(): Location? = suspendCancellableCoroutine { continuacion ->
    val precisa = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val aproximada = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!precisa && !aproximada) { continuacion.resume(null); return@suspendCancellableCoroutine }
    val administrador = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val manejador = Handler(Looper.getMainLooper())
    var terminado = false
    lateinit var escucha: LocationListener
    lateinit var tiempoAgotado: Runnable
    fun terminar(resultado: Location?) {
        if (terminado) return
        terminado = true
        administrador.removeUpdates(escucha)
        manejador.removeCallbacks(tiempoAgotado)
        if (continuacion.isActive) continuacion.resume(resultado)
    }
    escucha = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Solo se acepta una ubicación de los últimos 30 segundos
            if ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000 in 0..30000)
                terminar(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Compatibilidad API 26")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    tiempoAgotado = Runnable { terminar(null) }
    continuacion.invokeOnCancellation { manejador.post { terminar(null) } }
    manejador.postDelayed(tiempoAgotado, 8000)
    try {
        val proveedores = if (precisa) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER)
        var registrado = false
        for (proveedor in proveedores) {
            if (administrador.isProviderEnabled(proveedor)) {
                administrador.requestLocationUpdates(proveedor, 0L, 0f, escucha, Looper.getMainLooper())
                registrado = true
            }
        }
        if (!registrado) terminar(null)
    } catch (_: SecurityException) { terminar(null)
    } catch (_: IllegalArgumentException) { terminar(null) }
}
