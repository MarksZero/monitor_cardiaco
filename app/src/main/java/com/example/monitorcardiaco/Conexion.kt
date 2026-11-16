package com.example.monitorcardiaco

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Sesion(val ip: String, val usuario: String, val clave: String, val rol: String)

// Nombre de cuenta y clave válidos (se usan aquí y en la pantalla)
val REGEX_NOMBRE = Regex("[a-zA-Z0-9_-]{1,24}")
val REGEX_CLAVE = Regex("[a-fA-F0-9]{64}")

// Solo se aceptan IPs de red privada, para no mandar la clave a Internet por error
fun ipLocalValida(ip: String): Boolean {
    val partes = ip.split(".").map { it.toIntOrNull() }
    if (partes.size != 4 || partes.any { it == null || it !in 0..255 }) return false
    val a = partes[0]!!
    val b = partes[1]!!
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

class Conexion(private val sesion: Sesion) {

    // Hace la petición al ESP8266. Con "cuerpo" es POST, sin "cuerpo" es GET.
    fun peticion(ruta: String, cuerpo: JSONObject? = null): JSONObject {
        require(ipLocalValida(sesion.ip)) { "Usa una IPv4 privada válida" }
        require(sesion.usuario.matches(REGEX_NOMBRE)) { "Usuario inválido" }
        require(sesion.clave.matches(REGEX_CLAVE)) { "La clave debe tener 64 caracteres hexadecimales" }

        val http = URL("http://${sesion.ip}$ruta").openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 3000
            http.readTimeout = 3000
            http.instanceFollowRedirects = false
            http.setRequestProperty("X-User", sesion.usuario)
            http.setRequestProperty("Authorization", "Bearer ${sesion.clave}")
            if (cuerpo != null) {
                http.requestMethod = "POST"
                http.doOutput = true
                http.setRequestProperty("Content-Type", "application/json")
                http.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
            }

            val codigo = http.responseCode
            if (codigo == 401) throw IOException("Acceso rechazado: revisa usuario y clave")
            if (codigo == 403) throw IOException("Tu cuenta no tiene permiso")
            if (codigo !in 200..299) throw IOException("El dispositivo rechazó la operación (HTTP $codigo)")

            val respuesta = http.inputStream.bufferedReader().use { it.readText() }
            try {
                return JSONObject(respuesta)
            } catch (e: JSONException) {
                throw IOException("Respuesta inválida del dispositivo")
            }
        } finally {
            http.disconnect()
        }
    }
}
